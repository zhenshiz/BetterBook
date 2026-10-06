package com.zhenshiz.betterbook.client;

import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.core.StageNames;
import com.zhenshiz.betterbook.data.PlayerStages;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.Set;
import java.util.stream.Collectors;

/** 当前连接中服务端确认的阶段缓存，供客户端主线程上的阅读 UI 查询。 */
@EventBusSubscriber(modid = BetterBook.ID, value = Dist.CLIENT)
public final class ClientBookStages {
    private static Set<String> stages = Set.of();
    private static long revision;

    private ClientBookStages() {}

    /**
     * 查询最新快照是否包含指定阶段。
     *
     * @param stage 阶段名称字符串
     * @return 拥有阶段时为 <code>true</code>，尚无快照时为 <code>false</code>
     * @throws IllegalArgumentException 阶段名称无效
     */
    public static boolean has(String stage) {
        return stages.contains(StageNames.normalize(stage));
    }

    /**
     * 获取缓存版本，集合变化或断开连接时递增，相同快照不递增。
     *
     * @return 本地缓存的版本号
     */
    public static long revision() {
        return revision;
    }

    /**
     * 在客户端主线程替换完整集合，过期连接的待处理快照被丢弃。
     *
     * @param snapshot 服务端发送的完整阶段快照
     */
    public static void receive(PlayerStages.Stages snapshot) {
        var mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        mc.execute(
                () -> {
                    if (connection == null || mc.getConnection() != connection) return;
                    var next =
                            snapshot.stages.stream()
                                    .map(StageNames::normalize)
                                    .collect(Collectors.toUnmodifiableSet());
                    if (stages.equals(next)) return;
                    stages = next;
                    revision++;
                });
    }

    /**
     * 断开连接时清空阶段，避免下一世界沿用旧权限。
     *
     * @param event 客户端断开连接事件
     */
    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        stages = Set.of();
        revision++;
    }
}
