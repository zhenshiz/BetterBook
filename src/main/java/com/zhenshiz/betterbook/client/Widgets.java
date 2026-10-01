package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;

import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;

import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

final class Widgets {
    private Widgets() {}

    static UIElement row() {
        return new UIElement()
                .layout(
                        l ->
                                l.flexDirection(FlexDirection.ROW)
                                        .gapAll(3)
                                        .widthPercent(100)
                                        .flexShrink(0));
    }

    static Button button(String key, String id, Runnable run) {
        var b = new Button().setText("gui.betterbook." + key).setOnClick(e -> run.run());
        b.setId(id);
        b.getLayout().height(18).minWidth(22).flexShrink(0);
        return b;
    }

    static Button literal(String text, Runnable run) {
        var b = new Button().setText(text, false).setOnClick(e -> run.run());
        b.getLayout().height(18).minWidth(20).flexShrink(0);
        return b;
    }

    static Button tool(String icon, String key, String id, Runnable action) {
        return tool(
                SpriteTexture.of("betterbook:textures/gui/editor/" + icon + ".png"),
                key,
                id,
                action);
    }

    static Button tool(IGuiTexture icon, String key, String id, Runnable action) {
        var b = new Button().noText().setOnClick(e -> action.run());
        b.setId(id);
        b.addClass("book-tool");
        b.getLayout()
                .width(24)
                .height(26)
                .paddingAll(4)
                .flexShrink(0)
                .alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER);
        var image = new UIElement();
        image.getLayout().width(16).height(16).flexShrink(0);
        image.getStyle().backgroundTexture(icon);
        b.addChild(image);
        b.getStyle().tooltips(Component.translatable("gui.betterbook." + key));
        return b;
    }

    static void prompt(UIElement parent, String key, String value, Consumer<String> apply) {
        Dialog.stringEditorDialog("gui.betterbook." + key, value, null, apply)
                .show(parent.getModularUI());
    }
}
