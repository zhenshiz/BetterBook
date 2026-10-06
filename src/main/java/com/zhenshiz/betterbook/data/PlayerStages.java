package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacket;
import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;
import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.client.ClientBookStages;
import com.zhenshiz.betterbook.core.StageNames;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.*;

/** 主世界保存的 UUID 阶段集合，跨死亡、重登和维度保留；仅向本人发送快照。 */
@EventBusSubscriber(modid = BetterBook.ID)
public final class PlayerStages extends SavedData implements IPersistedSerializable {
    public static final String SYNC = "betterbook:player_stages";
    private static final String DATA_ID = "betterbook_player_stages";
    private static final Factory<PlayerStages> FACTORY =
            new Factory<>(PlayerStages::new, PlayerStages::load);

    @Persisted private final Map<UUID, Stages> players = new HashMap<>();

    /** 持久化和 RPC 共用的完整阶段模型；发送前复制以隔离世界存储。 */
    public static final class Stages implements IPersistedSerializable {
        @Persisted public final Set<String> stages = new LinkedHashSet<>();
    }

    /**
     * 从玩家服务器的主世界取得存储。
     *
     * @param player 服务端玩家
     * @return 当前世界共用的阶段存储
     * @throws IllegalStateException 不在服务端主线程
     */
    public static PlayerStages get(ServerPlayer player) {
        if (!player.server.isSameThread())
            throw new IllegalStateException("Player stages require the server thread");
        return player.server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    private static PlayerStages load(CompoundTag tag, HolderLookup.Provider provider) {
        var data = new PlayerStages();
        data.deserializeNBT(provider, tag);
        return data;
    }

    /**
     * 使用 LDLib2 持久化访问器保存阶段集合。
     *
     * @param tag 目标 NBT
     * @param provider 注册表上下文
     * @return 包含阶段集合的 NBT
     */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        return tag.merge(serializeNBT(provider));
    }

    /**
     * 查询 UUID 是否拥有规范化后的阶段。
     *
     * @param player 玩家 UUID
     * @param stage 阶段名称字符串
     * @return 拥有阶段时为 <code>true</code>
     * @throws IllegalArgumentException 阶段名称无效
     */
    public boolean has(UUID player, String stage) {
        String normalized = StageNames.normalize(stage);
        var entry = players.get(player);
        return entry != null && entry.stages.contains(normalized);
    }

    /**
     * 新增阶段，重复授予不修改存储。
     *
     * @param player 玩家 UUID
     * @param stage 阶段名称字符串
     * @return 集合变化时为 <code>true</code>
     * @throws IllegalArgumentException 阶段名称无效
     */
    public boolean add(UUID player, String stage) {
        String normalized = StageNames.normalize(stage);
        if (!players.computeIfAbsent(player, id -> new Stages()).stages.add(normalized))
            return false;
        setDirty();
        return true;
    }

    /**
     * 移除阶段，空集合不保留 UUID 条目。
     *
     * @param player 玩家 UUID
     * @param stage 阶段名称字符串
     * @return 集合变化时为 <code>true</code>
     * @throws IllegalArgumentException 阶段名称无效
     */
    public boolean remove(UUID player, String stage) {
        String normalized = StageNames.normalize(stage);
        var entry = players.get(player);
        if (entry == null || !entry.stages.remove(normalized)) return false;
        if (entry.stages.isEmpty()) players.remove(player);
        setDirty();
        return true;
    }

    /**
     * 取得阶段名称的排序快照。
     *
     * @param player 玩家 UUID
     * @return 不可变的规范阶段名称列表
     */
    public List<String> list(UUID player) {
        var entry = players.get(player);
        return entry == null ? List.of() : entry.stages.stream().sorted().toList();
    }

    /**
     * 清除 UUID 的全部阶段。
     *
     * @param player 玩家 UUID
     * @return 实际移除的阶段数量
     */
    public int clear(UUID player) {
        var entry = players.remove(player);
        if (entry == null) return 0;
        setDirty();
        return entry.stages.size();
    }

    /**
     * 将完整快照发送给该玩家，不包含其他玩家的状态。
     *
     * @param player 快照接收者
     * @throws IllegalStateException 不在服务端主线程
     */
    public static void sync(ServerPlayer player) {
        var snapshot = new Stages();
        snapshot.stages.addAll(get(player).list(player.getUUID()));
        RPCPacketDistributor.rpcToPlayer(player, SYNC, snapshot);
    }

    /**
     * 接收服务端快照；客户端发送的同名 RPC 不修改任何状态。
     *
     * @param sender RPC 发送方
     * @param snapshot 完整阶段快照
     */
    @RPCPacket(SYNC)
    public static void receive(RPCSender sender, Stages snapshot) {
        if (sender.isServer()) ClientBookStages.receive(snapshot);
    }

    /**
     * 登录后同步世界保存的阶段。
     *
     * @param event 玩家登录事件
     */
    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    /**
     * 向重生后的玩家实例同步阶段。
     *
     * @param event 玩家重生事件
     */
    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    /**
     * 在维度切换完成后同步阶段。
     *
     * @param event 玩家维度切换事件
     */
    @SubscribeEvent
    public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }
}
