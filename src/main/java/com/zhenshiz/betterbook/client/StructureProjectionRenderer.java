package com.zhenshiz.betterbook.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhenshiz.betterbook.BetterBook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;

/** 按真实方块模型绘制半透明缺块，红框标出被错误方块占用的位置。 */
@EventBusSubscriber(modid = BetterBook.ID, value = Dist.CLIENT)
public final class StructureProjectionRenderer {
    private StructureProjectionRenderer() {}

    /**
     * @param event 世界半透明层渲染事件
     */
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        var projection = StructureProjectionClient.active;
        var mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || projection == null
                || projection.anchor == null
                || mc.level == null) return;
        var camera = event.getCamera().getPosition();
        var visible = new ArrayList<StructureProjection.Block>();
        for (var block : projection.blocks) {
            if (block.status == StructureProjection.Status.MATCHED
                    || block.status == StructureProjection.Status.UNLOADED) continue;
            var pos = projection.anchor.offset(block.offset);
            if (pos.distToCenterSqr(camera) <= 48 * 48
                    && event.getFrustum().isVisible(new AABB(pos))) visible.add(block);
        }
        if (visible.isEmpty()) return;
        var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        try {
            pose.translate(-camera.x, -camera.y, -camera.z);
            for (var block : visible) {
                if (block.status == StructureProjection.Status.WRONG
                        || block.state.getRenderShape()
                                != net.minecraft.world.level.block.RenderShape.MODEL) continue;
                var pos = projection.anchor.offset(block.offset);
                pose.pushPose();
                try {
                    pose.translate(pos.getX(), pos.getY(), pos.getZ());
                    mc.getBlockRenderer()
                            .renderSingleBlock(
                                    block.state,
                                    pose,
                                    type ->
                                            new GhostVertex(
                                                    buffers.getBuffer(
                                                            RenderType.entityTranslucentCull(
                                                                    TextureAtlas.LOCATION_BLOCKS))),
                                    LightTexture.FULL_BRIGHT,
                                    OverlayTexture.NO_OVERLAY);
                } finally {
                    pose.popPose();
                }
            }
            buffers.endBatch();
            var lines = buffers.getBuffer(RenderType.lines());
            boolean fits = projection.fits(mc.level);
            for (var block : visible) {
                boolean wrong = block.status == StructureProjection.Status.WRONG || !fits;
                LevelRenderer.renderLineBox(
                        pose,
                        lines,
                        new AABB(projection.anchor.offset(block.offset)).inflate(.003),
                        wrong ? 1f : .45f,
                        wrong ? .25f : .85f,
                        wrong ? .2f : 1f,
                        wrong ? .9f : .4f);
            }
            buffers.endBatch(RenderType.lines());
        } finally {
            pose.popPose();
        }
    }

    private record GhostVertex(VertexConsumer delegate) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            delegate.setColor(r * 3 / 4, g * 9 / 10, b, a * 2 / 5);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
