package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.data.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** 选区鼠标交互和服务端确认的世界线框。 */
@EventBusSubscriber(modid = BetterBook.ID, value = Dist.CLIENT)
public final class StructureSelectionClient {
    private static BlockPos first, second;
    private static String dimension = "";

    private StructureSelectionClient() {}

    /**
     * @return 当前完整选区的包围盒，未完成选择时返回空值
     */
    public static AABB bounds() {
        return first == null || second == null
                ? null
                : new AABB(
                        Math.min(first.getX(), second.getX()),
                        Math.min(first.getY(), second.getY()),
                        Math.min(first.getZ(), second.getZ()),
                        Math.max(first.getX(), second.getX()) + 1,
                        Math.max(first.getY(), second.getY()) + 1,
                        Math.max(first.getZ(), second.getZ()) + 1);
    }

    /**
     * @param dim 维度
     * @param a 第一角点
     * @param b 第二角点
     */
    public static void selection(String dim, BlockPos a, BlockPos b) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            dimension = dim;
                            first = a;
                            second = b;
                        });
    }

    /**
     * @param event 左键方块事件
     */
    @SubscribeEvent
    public static void left(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getItemStack().is(BookItems.STRUCTURE_WAND)) return;
        event.setCanceled(true);
        if (event.getLevel().isClientSide
                && event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START)
            RPCPacketDistributor.rpcToServer(StructureSelection.SELECT, event.getPos(), true);
    }

    /**
     * @param event 右键方块事件
     */
    @SubscribeEvent
    public static void right(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || !event.getItemStack().is(BookItems.STRUCTURE_WAND)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide)
            RPCPacketDistributor.rpcToServer(StructureSelection.SELECT, event.getPos(), false);
    }

    /**
     * @param event 世界渲染事件
     */
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || mc.level == null
                || mc.player == null
                || !mc.player.getMainHandItem().is(BookItems.STRUCTURE_WAND)
                || !dimension.equals(mc.level.dimension().location().toString())) return;
        var pose = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        if (first != null)
            LevelRenderer.renderLineBox(
                    pose, lines, new AABB(first).inflate(.005), .3f, 1f, .45f, 1);
        if (second != null)
            LevelRenderer.renderLineBox(
                    pose, lines, new AABB(second).inflate(.005), 1f, .65f, .2f, 1);
        var bounds = bounds();
        if (bounds != null)
            LevelRenderer.renderLineBox(pose, lines, bounds.inflate(.01), 1f, .85f, .35f, 1);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    /**
     * @param event 断开服务器事件
     */
    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        first = second = null;
        dimension = "";
        StructureFiles.clear();
    }
}
