package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.platform.NativeImage;
import com.zhenshiz.betterbook.core.LatexNode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.jsoup.nodes.Element;

import java.util.function.Consumer;

/** 编辑和阅读共用公式视图；双击打开编辑器，移除时释放纹理。 */
final class BookLatexView extends UIElement {
    private Element element;
    private LatexNode node;
    private LatexRenderer.Result rendered;
    private ResourceLocation texture;
    private final boolean editable;
    private float inset = 6;

    void inset(float inset) {
        this.inset = inset;
    }

    BookLatexView(Element element, boolean editable, Consumer<Element> update) {
        this.editable = editable;
        setFormula(element);
        if (editable)
            addEventListener(
                    UIEvents.DOUBLE_CLICK,
                    event -> {
                        if (event.button != 0) return;
                        BookLatexEditor.open(
                                this,
                                node,
                                replacement -> {
                                    var changed = this.element.clone();
                                    replacement.writeTo(changed);
                                    update.accept(changed);
                                },
                                "apply");
                        event.stopPropagation();
                    });
    }

    boolean matches(Element candidate) {
        return element.outerHtml().equals(candidate.outerHtml());
    }

    void setFormula(Element element) {
        release();
        this.element = element.clone();
        node = LatexNode.read(element);
        rendered = LatexRenderer.get(node);
        if (editable) getStyle().tooltips(Component.translatable("gui.betterbook.latex_edit_hint"));
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        float x = getPositionX(),
                y = getPositionY(),
                width = getSizeWidth(),
                height = getSizeHeight();
        if (!rendered.valid()) {
            context.graphics.drawString(
                    context.mc.font,
                    Component.translatable("gui.betterbook.latex_invalid"),
                    (int) x + 6,
                    (int) y + 8,
                    0xffaf463d,
                    false);
            return;
        }
        if (texture == null) {
            var bitmap = rendered.image();
            var image = new NativeImage(bitmap.getWidth(), bitmap.getHeight(), false);
            for (int py = 0; py < bitmap.getHeight(); py++)
                for (int px = 0; px < bitmap.getWidth(); px++) {
                    int argb = bitmap.getRGB(px, py);
                    image.setPixelRGBA(
                            px,
                            py,
                            (argb & 0xff00ff00) | ((argb >> 16) & 255) | ((argb & 255) << 16));
                }
            texture =
                    context.mc
                            .getTextureManager()
                            .register("betterbook_latex", new DynamicTexture(image));
        }
        float scale =
                Math.min(
                        1,
                        Math.min(
                                Math.max(1, width - 2 * inset) / rendered.width(),
                                Math.max(1, height - 2 * inset) / rendered.height()));
        float w = rendered.width() * scale, h = rendered.height() * scale;
        float dx =
                node.align().equals("left")
                        ? inset
                        : node.align().equals("right") ? width - w - inset : (width - w) / 2;
        // 背景使用批量绘制，先提交它，避免后续提交时遮住公式纹理。
        context.graphics.flush();
        context.pose.pushPose();
        context.pose.translate(x + dx, y + (height - h) / 2, 0);
        context.pose.scale(scale / LatexRenderer.SCALE, scale / LatexRenderer.SCALE, 1);
        context.graphics.blit(
                texture,
                0,
                0,
                0,
                0,
                rendered.icon().getIconWidth(),
                rendered.icon().getIconHeight(),
                rendered.icon().getIconWidth(),
                rendered.icon().getIconHeight());
        context.pose.popPose();
    }

    private void release() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(texture);
        texture = null;
    }

    @Override
    protected void onRemoved() {
        release();
        super.onRemoved();
    }
}
