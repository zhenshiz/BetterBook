package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.zhenshiz.betterbook.compat.BookJeiPlugin;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.gui.input.InputType;
import mezz.jei.gui.input.UserInput;

/** JEI 原生配方布局的 LDLib2 宿主，转发坐标、悬停提示和分类自带交互。 */
final class BookJeiView extends UIElement {
    private final IRecipeLayoutDrawable<?> recipe;
    private String failure = "";

    BookJeiView(String id, boolean editable) {
        recipe = BookJeiPlugin.layout(id);
        recipe.setPosition(0, 0);
        addClass("book-jei-layout");
        if (!editable) {
            addEventListener(UIEvents.MOUSE_DOWN, e -> click(e, InputType.SIMULATE));
            addEventListener(UIEvents.MOUSE_UP, e -> click(e, InputType.EXECUTE));
            addEventListener(
                    UIEvents.DRAG_SOURCE_UPDATE,
                    e -> {
                        var p = local(e);
                        var delta = getLocalMouseNormal(e.deltaX, e.deltaY);
                        recipe.getInputHandler()
                                .handleMouseDragged(
                                        p[0],
                                        p[1],
                                        com.mojang.blaze3d.platform.InputConstants.Type.MOUSE
                                                .getOrCreate(0),
                                        delta.x / scale(),
                                        delta.y / scale());
                        e.hasHandler = true;
                    });
            addEventListener(
                    UIEvents.MOUSE_MOVE,
                    e -> {
                        var p = local(e);
                        recipe.getInputHandler().handleMouseMoved(p[0], p[1]);
                    });
            addEventListener(
                    UIEvents.MOUSE_WHEEL,
                    e -> {
                        var p = local(e);
                        if (recipe.getInputHandler()
                                .handleMouseScrolled(p[0], p[1], e.deltaX, e.deltaY)) {
                            e.hasHandler = true;
                            e.stopPropagation();
                        }
                    });
            addEventListener(
                    UIEvents.KEY_DOWN,
                    e -> {
                        var p = local(e);
                        if (recipe.getInputHandler()
                                .handleInput(
                                        p[0],
                                        p[1],
                                        UserInput.fromVanilla(
                                                e.keyCode,
                                                e.scanCode,
                                                e.modifiers,
                                                InputType.IMMEDIATE))) {
                            e.hasHandler = true;
                            e.stopPropagation();
                        }
                    });
        }
    }

    private float scale() {
        return Math.max(
                .01f,
                Math.min(
                        (getSizeWidth() - 8) / Math.max(1, recipe.getRect().getWidth()),
                        (getSizeHeight() - 8) / Math.max(1, recipe.getRect().getHeight())));
    }

    private float x() {
        return getPositionX() + (getSizeWidth() - recipe.getRect().getWidth() * scale()) / 2;
    }

    private float y() {
        return getPositionY() + (getSizeHeight() - recipe.getRect().getHeight() * scale()) / 2;
    }

    private float[] local(UIEvent e) {
        if (e.type.equals(UIEvents.KEY_DOWN) && getModularUI() != null) {
            var ui = getModularUI();
            var p = getLocalMouse(ui.getLastMouseX(), ui.getLastMouseY());
            return new float[] {(p.x - x()) / scale(), (p.y - y()) / scale()};
        }
        var p = getLocalMouse(e.x, e.y);
        return new float[] {(p.x - x()) / scale(), (p.y - y()) / scale()};
    }

    private void click(UIEvent e, InputType type) {
        var p = local(e);
        UserInput.fromVanilla(p[0], p[1], e.button, type)
                .ifPresent(
                        input -> {
                            if (recipe.getInputHandler().handleInput(p[0], p[1], input)) {
                                e.hasHandler = true;
                                e.stopPropagation();
                                if (type == InputType.SIMULATE && e.button == 0)
                                    startDrag(this, null);
                            }
                        });
        if (e.type.equals(UIEvents.MOUSE_DOWN)) focus();
    }

    @Override
    public void screenTick() {
        super.screenTick();
        if (failure.isEmpty())
            try {
                recipe.tick();
            } catch (RuntimeException e) {
                fail(e);
            }
    }

    private void fail(RuntimeException error) {
        failure =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        com.zhenshiz.betterbook.BetterBook.LOGGER.warn("JEI book recipe failed", error);
    }

    @Override
    public void drawBackgroundAdditional(GUIContext c) {
        if (!failure.isEmpty()) {
            c.graphics.drawWordWrap(
                    c.mc.font,
                    net.minecraft.network.chat.Component.translatable(
                            "gui.betterbook.recipe_error", failure),
                    (int) getPositionX() + 4,
                    (int) getPositionY() + 4,
                    Math.max(1, (int) getSizeWidth() - 8),
                    0xff914b38);
            return;
        }
        float scale = scale(), x = x(), y = y();
        int mx = Math.round((c.localMouseX - x) / scale),
                my = Math.round((c.localMouseY - y) / scale);
        // JEI 分类混合即时纹理绘制与 GuiGraphics 缓冲绘制，先提交书页背景。
        c.graphics.flush();
        c.pose.pushPose();
        c.pose.translate(x, y, 0);
        c.pose.scale(scale, scale, 1);
        try {
            recipe.drawRecipe(c.graphics, mx, my);
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            c.graphics.flush();
            c.pose.popPose();
        }
        if (isSelfOrChildHover())
            c.postRendering(
                    context -> {
                        context.pose.pushPose();
                        context.pose.translate(x, y, 0);
                        context.pose.scale(scale, scale, 1);
                        try {
                            recipe.drawOverlays(context.graphics, mx, my);
                        } catch (RuntimeException e) {
                            fail(e);
                        } finally {
                            context.pose.popPose();
                        }
                    });
    }
}
