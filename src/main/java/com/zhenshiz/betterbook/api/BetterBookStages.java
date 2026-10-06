package com.zhenshiz.betterbook.api;

import com.lowdragmc.lowdraglib2.integration.kjs.KJSBindings;
import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.data.PlayerStages;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** 服务端主线程使用的玩家阶段 API；名称统一规范化，不发送聊天或命令反馈。 */
@KJSBindings(value = "BetterBookStages", modId = BetterBook.ID)
public final class BetterBookStages {
    private BetterBookStages() {}

    /**
     * 查询玩家是否拥有指定阶段。
     *
     * @param player 服务端玩家
     * @param stage 阶段名称字符串
     * @return 拥有阶段时为 <code>true</code>
     * @throws IllegalArgumentException 阶段名称无效
     * @throws IllegalStateException 不在服务端主线程
     */
    public static boolean has(ServerPlayer player, String stage) {
        return PlayerStages.get(player).has(player.getUUID(), stage);
    }

    /**
     * 授予阶段并在发生变化时向该玩家同步完整快照。
     *
     * @param player 服务端玩家
     * @param stage 阶段名称字符串
     * @return 新增阶段时为 <code>true</code>，已拥有时为 <code>false</code>
     * @throws IllegalArgumentException 阶段名称无效
     * @throws IllegalStateException 不在服务端主线程
     */
    public static boolean add(ServerPlayer player, String stage) {
        boolean changed = PlayerStages.get(player).add(player.getUUID(), stage);
        if (changed) PlayerStages.sync(player);
        return changed;
    }

    /**
     * 撤销阶段并在发生变化时向该玩家同步完整快照。
     *
     * @param player 服务端玩家
     * @param stage 阶段名称字符串
     * @return 移除阶段时为 <code>true</code>，原本没有时为 <code>false</code>
     * @throws IllegalArgumentException 阶段名称无效
     * @throws IllegalStateException 不在服务端主线程
     */
    public static boolean remove(ServerPlayer player, String stage) {
        boolean changed = PlayerStages.get(player).remove(player.getUUID(), stage);
        if (changed) PlayerStages.sync(player);
        return changed;
    }

    /**
     * 获取按名称排序的阶段快照，不暴露可修改的存储集合。
     *
     * @param player 服务端玩家
     * @return 不可变的规范阶段名称列表
     * @throws IllegalStateException 不在服务端主线程
     */
    public static List<String> list(ServerPlayer player) {
        return PlayerStages.get(player).list(player.getUUID());
    }

    /**
     * 清空阶段并在发生变化时向该玩家同步空快照。
     *
     * @param player 服务端玩家
     * @return 实际移除的阶段数量
     * @throws IllegalStateException 不在服务端主线程
     */
    public static int clear(ServerPlayer player) {
        int removed = PlayerStages.get(player).clear(player.getUUID());
        if (removed > 0) PlayerStages.sync(player);
        return removed;
    }
}
