package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacket;
import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.zhenshiz.betterbook.BetterBook;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.*;

/** 服务端选区和导出命令；客户端只能选择手中工具可触及的方块。 */
@EventBusSubscriber(modid = BetterBook.ID)
public final class StructureSelection {
    public static final String SELECT = "betterbook:structure_select",
            SYNC = "betterbook:structure_selection";
    private static final Map<ServerPlayer, Selection> SELECTIONS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Integer> LAST_EXPORT = new WeakHashMap<>();

    private record Selection(ResourceLocation dimension, BlockPos first, BlockPos second) {}

    private StructureSelection() {}

    /**
     * @param event 服务端挖掘事件，选区工具不破坏方块
     */
    @SubscribeEvent
    public static void preventMining(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide && event.getItemStack().is(BookItems.STRUCTURE_WAND))
            event.setCanceled(true);
    }

    /**
     * @param event 服务端方块交互事件，选区工具不打开容器
     */
    @SubscribeEvent
    public static void preventUse(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide
                && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && event.getItemStack().is(BookItems.STRUCTURE_WAND)) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }

    /**
     * 校验选点并向操作者回传已确认的选区。
     *
     * @param sender 选点玩家
     * @param pos 方块坐标
     * @param first 是否第一个角点
     */
    @RPCPacket(SELECT)
    public static void select(RPCSender sender, BlockPos pos, boolean first) {
        var player = sender.asPlayer();
        if (sender.isServer() || player == null || pos == null) return;
        player.server.execute(
                () -> {
                    if (!player.getMainHandItem().is(BookItems.STRUCTURE_WAND)
                            || !player.canInteractWithBlock(pos, 1)) return;
                    var dimension = player.level().dimension().location();
                    var previous = SELECTIONS.get(player);
                    if (previous != null && !previous.dimension.equals(dimension)) previous = null;
                    var selected =
                            new Selection(
                                    dimension,
                                    first ? pos : previous == null ? null : previous.first,
                                    first ? previous == null ? null : previous.second : pos);
                    SELECTIONS.put(player, selected);
                    RPCPacketDistributor.rpcToPlayer(
                            player,
                            SYNC,
                            dimension.toString(),
                            selected.first != null,
                            selected.first == null ? BlockPos.ZERO : selected.first,
                            selected.second != null,
                            selected.second == null ? BlockPos.ZERO : selected.second);
                    player.displayClientMessage(
                            Component.translatable(
                                    first
                                            ? "gui.betterbook.selection_first"
                                            : "gui.betterbook.selection_second",
                                    pos.getX(),
                                    pos.getY(),
                                    pos.getZ()),
                            true);
                });
    }

    /**
     * 接收服务器确认的选区，不接受其他玩家发送的数据。
     *
     * @param sender RPC 发送端
     * @param dimension 维度标识
     * @param hasFirst 是否已有第一点
     * @param first 第一点坐标
     * @param hasSecond 是否已有第二点
     * @param second 第二点坐标
     */
    @RPCPacket(SYNC)
    public static void receiveSelection(
            RPCSender sender,
            String dimension,
            boolean hasFirst,
            BlockPos first,
            boolean hasSecond,
            BlockPos second) {
        if (sender.isServer())
            com.zhenshiz.betterbook.client.StructureSelectionClient.selection(
                    dimension, hasFirst ? first : null, hasSecond ? second : null);
    }

    /**
     * @param event 服务端指令注册事件
     */
    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("betterbook")
                                .then(
                                        Commands.literal("structure")
                                                .requires(s -> s.hasPermission(2))
                                                .then(
                                                        Commands.literal("clear")
                                                                .executes(
                                                                        c -> {
                                                                            var p =
                                                                                    c.getSource()
                                                                                            .getPlayerOrException();
                                                                            SELECTIONS.remove(p);
                                                                            RPCPacketDistributor
                                                                                    .rpcToPlayer(
                                                                                            p,
                                                                                            SYNC,
                                                                                            p.level()
                                                                                                    .dimension()
                                                                                                    .location()
                                                                                                    .toString(),
                                                                                            false,
                                                                                            BlockPos
                                                                                                    .ZERO,
                                                                                            false,
                                                                                            BlockPos
                                                                                                    .ZERO);
                                                                            return 1;
                                                                        }))
                                                .then(
                                                        Commands.literal("export")
                                                                .then(
                                                                        Commands.argument(
                                                                                        "name",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .executes(
                                                                                        c -> {
                                                                                            var p =
                                                                                                    c.getSource()
                                                                                                            .getPlayerOrException();
                                                                                            String
                                                                                                    name =
                                                                                                            StringArgumentType
                                                                                                                    .getString(
                                                                                                                            c,
                                                                                                                            "name");
                                                                                            var
                                                                                                    selected =
                                                                                                            SELECTIONS
                                                                                                                    .get(
                                                                                                                            p);
                                                                                            if (!name
                                                                                                    .matches(
                                                                                                            "[a-zA-Z0-9_\\-]{1,64}")) {
                                                                                                c.getSource()
                                                                                                        .sendFailure(
                                                                                                                Component
                                                                                                                        .translatable(
                                                                                                                                "gui.betterbook.structure_bad_name"));
                                                                                                return 0;
                                                                                            }
                                                                                            if (selected
                                                                                                            == null
                                                                                                    || selected.first
                                                                                                            == null
                                                                                                    || selected.second
                                                                                                            == null
                                                                                                    || !selected
                                                                                                            .dimension
                                                                                                            .equals(
                                                                                                                    p.level()
                                                                                                                            .dimension()
                                                                                                                            .location())) {
                                                                                                c.getSource()
                                                                                                        .sendFailure(
                                                                                                                Component
                                                                                                                        .translatable(
                                                                                                                                "gui.betterbook.selection_missing"));
                                                                                                return 0;
                                                                                            }
                                                                                            int
                                                                                                    tick =
                                                                                                            p
                                                                                                                    .server
                                                                                                                    .getTickCount();
                                                                                            var
                                                                                                    last =
                                                                                                            LAST_EXPORT
                                                                                                                    .get(
                                                                                                                            p);
                                                                                            if (last
                                                                                                            != null
                                                                                                    && tick
                                                                                                                    - last
                                                                                                            < 40)
                                                                                                return 0;
                                                                                            LAST_EXPORT
                                                                                                    .put(
                                                                                                            p,
                                                                                                            tick);
                                                                                            try {
                                                                                                var
                                                                                                        tag =
                                                                                                                BookSchematic
                                                                                                                        .capture(
                                                                                                                                p
                                                                                                                                        .serverLevel(),
                                                                                                                                selected.first,
                                                                                                                                selected.second,
                                                                                                                                name,
                                                                                                                                p.getGameProfile()
                                                                                                                                        .getName());
                                                                                                byte
                                                                                                                []
                                                                                                        bytes =
                                                                                                                BookSchematic
                                                                                                                        .compress(
                                                                                                                                tag);
                                                                                                var
                                                                                                        path =
                                                                                                                ServerStructureFiles
                                                                                                                        .save(
                                                                                                                                name,
                                                                                                                                bytes);
                                                                                                c.getSource()
                                                                                                        .sendSuccess(
                                                                                                                () ->
                                                                                                                        Component
                                                                                                                                .translatable(
                                                                                                                                        "gui.betterbook.structure_saved",
                                                                                                                                        path
                                                                                                                                                .toString()),
                                                                                                                false);
                                                                                                return 1;
                                                                                            } catch (
                                                                                                    Exception
                                                                                                            e) {
                                                                                                c.getSource()
                                                                                                        .sendFailure(
                                                                                                                Component
                                                                                                                        .translatable(
                                                                                                                                "gui.betterbook.structure_failed",
                                                                                                                                e
                                                                                                                                        .getMessage()));
                                                                                                return 0;
                                                                                            }
                                                                                        })))));
    }
}
