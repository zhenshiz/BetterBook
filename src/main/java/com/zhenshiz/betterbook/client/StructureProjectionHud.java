package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.hud.ModularHudLayer;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;

import dev.vfyjxf.taffy.style.TaffyPosition;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** LDLib2 HUD：固定在屏幕左上角，缓存 UI 并按 tick 刷新进度。 */
final class StructureProjectionHud implements ModularHudLayer {
    private ModularUI ui;

    /**
     * @return 当前投影的缓存 HUD，没有投影或正在显示界面时返回空值
     */
    @Override
    public ModularUI getModularUI() {
        var mc = Minecraft.getInstance();
        if (StructureProjectionClient.active == null || mc.screen != null || mc.options.hideGui)
            return null;
        if (ui == null) ui = build();
        return ui;
    }

    private ModularUI build() {
        var heading = label();
        var progress = label();
        var controls = label();
        var hint = label();
        var panel =
                new UIElement() {
                    @Override
                    public void screenTick() {
                        super.screenTick();
                        var p = StructureProjectionClient.active;
                        if (p == null) return;
                        heading.setText(
                                StructureProjectionClient.text(
                                        p.selecting ? "selecting" : "building"));
                        progress.setText(
                                StructureProjectionClient.text(
                                        "progress",
                                        p.matched,
                                        p.blocks.size(),
                                        p.wrong,
                                        p.unloaded));
                        var cancel = StructureProjectionClient.CANCEL.getTranslatedKeyMessage();
                        controls.setText(
                                p.editable
                                        ? StructureProjectionClient.text(
                                                "controls",
                                                StructureProjectionClient.ROTATE
                                                        .getTranslatedKeyMessage(),
                                                StructureProjectionClient.MOVE
                                                        .getTranslatedKeyMessage(),
                                                cancel)
                                        : StructureProjectionClient.text("locked", cancel));
                        hint.setText(
                                p.selecting
                                        ? StructureProjectionClient.text(
                                                p.anchor == null
                                                        ? "aim"
                                                        : p.fits(Minecraft.getInstance().level)
                                                                ? "confirm"
                                                                : "outside")
                                        : StructureProjectionClient.text("legend"));
                    }
                };
        panel.setId("structure-projection-hud");
        panel.getLayout()
                .positionType(TaffyPosition.ABSOLUTE)
                .left(6)
                .top(6)
                .width(260)
                .maxWidthPercent(80)
                .paddingAll(6)
                .gapAll(3);
        panel.getStyle().backgroundTexture(new ColorRectTexture(0xdd493324));
        panel.addChildren(heading, progress, controls, hint);
        var root = new UIElement();
        root.getLayout().widthPercent(100).heightPercent(100);
        root.addChild(panel);
        return ModularUI.of(UI.of(root));
    }

    private static Label label() {
        var label = new Label();
        label.setText(Component.empty());
        label.getLayout().widthPercent(100).minWidth(0);
        label.textStyle(
                style ->
                        style.textColor(0xffefe4c7)
                                .textShadow(false)
                                .textWrap(TextWrap.WRAP)
                                .adaptiveHeight(true));
        return label;
    }
}
