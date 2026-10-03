package com.zhenshiz.betterbook.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.data.BookSchematic;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import org.lwjgl.glfw.GLFW;

/** 从书页启动的本地投影会话，不发送放置方块的网络请求。 */
@EventBusSubscriber(modid = BetterBook.ID, value = Dist.CLIENT)
public final class StructureProjectionClient {
    static final KeyMapping ROTATE = key("rotate", GLFW.GLFW_KEY_J);
    static final KeyMapping MOVE = key("move", GLFW.GLFW_KEY_G);
    static final KeyMapping CANCEL = key("cancel", GLFW.GLFW_KEY_H);
    static StructureProjection active;
    private static ClientLevel world;
    private static int ticks;
    // 确认选点后一直吞掉此次右键长按，防止副手或下一 tick 意外使用物品。
    private static boolean suppressUse;

    private StructureProjectionClient() {}

    private static KeyMapping key(String action, int code) {
        return new KeyMapping(
                "key.betterbook.projection_" + action,
                net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                code,
                "key.categories.betterbook");
    }

    static Component begin(BookSchematic.Snapshot snapshot, boolean editable) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return text("no_world");
        long count = snapshot.blocks().values().stream().filter(state -> !state.isAir()).count();
        if (count == 0) return text("empty");
        if (count > StructureProjection.MAX_BLOCKS)
            return text("too_large", StructureProjection.MAX_BLOCKS);
        active = new StructureProjection(snapshot, editable);
        world = mc.level;
        ticks = 0;
        suppressUse = true;
        // closeContainer 同时通知服务端结束可能仍在后台的编辑菜单。
        if (mc.screen != null) mc.screen.onClose();
        mc.player.closeContainer();
        mc.setScreen(null);
        return null;
    }

    static Component text(String suffix, Object... args) {
        return Component.translatable("gui.betterbook.projection_" + suffix, args);
    }

    static void clear() {
        active = null;
        world = null;
    }

    private static BlockPos target() {
        var mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            var pos = hit.getBlockPos();
            return mc.level.getBlockState(pos).canBeReplaced()
                    ? pos
                    : pos.relative(hit.getDirection());
        }
        return null;
    }

    /**
     * 更新选点、快捷键和世界方块完成进度。
     *
     * @param event 客户端事件
     */
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!mc.options.keyUse.isDown()) suppressUse = false;
        if (active != null && (mc.level != world || mc.player == null || !mc.player.isAlive()))
            clear();
        while (CANCEL.consumeClick()) if (mc.screen == null) clear();
        while (ROTATE.consumeClick()) {
            if (active != null && mc.screen == null) {
                active.rotate();
                active.refresh(world);
            }
        }
        while (MOVE.consumeClick()) {
            if (active != null && active.editable && mc.screen == null) active.selecting = true;
        }
        if (active == null || mc.isPaused()) return;
        if (active.selecting && mc.screen == null) {
            var next = target();
            if (!java.util.Objects.equals(next, active.anchor)) {
                active.anchor = next;
                active.refresh(world);
            }
        }
        if (++ticks % 5 == 0) {
            active.refresh(world);
            if (!active.selecting && active.matched == active.blocks.size()) {
                mc.player.displayClientMessage(text("complete", active.matched), false);
                clear();
            }
        }
    }

    /**
     * 选点时拦截普通交互，确认后等待此次右键松开。
     *
     * @param event 客户端事件
     */
    @SubscribeEvent
    public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (event.isUseItem() && suppressUse) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (active == null || !active.selecting) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (event.isUseItem() && event.getHand() == InteractionHand.MAIN_HAND) {
            active.anchor = target();
            if (!active.fits(world)) {
                mc.player.displayClientMessage(
                        text(active.anchor == null ? "aim" : "outside"), true);
                return;
            }
            active.selecting = false;
            active.refresh(world);
            suppressUse = true;
        }
    }

    /**
     * 断线时释放当前世界投影。
     *
     * @param event 客户端事件
     */
    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        suppressUse = false;
    }

    @EventBusSubscriber(
            modid = BetterBook.ID,
            value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    /** 客户端按键和 HUD 的模组总线注册入口。 */
    public static final class Registration {
        /**
         * 注册玩家可自定义的投影快捷键。
         *
         * @param event 客户端事件
         */
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(ROTATE);
            event.register(MOVE);
            event.register(CANCEL);
        }

        /**
         * 注册随投影会话显示的 LDLib2 HUD。
         *
         * @param event 客户端事件
         */
        @SubscribeEvent
        public static void hud(RegisterGuiLayersEvent event) {
            event.registerAboveAll(
                    ResourceLocation.fromNamespaceAndPath(BetterBook.ID, "projection"),
                    new StructureProjectionHud());
        }
    }
}
