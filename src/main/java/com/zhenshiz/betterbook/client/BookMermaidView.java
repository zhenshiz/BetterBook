package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.RectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.network.chat.Component;

import org.jsoup.nodes.Element;

import java.util.function.Consumer;

/** 图表在上、可折叠源码在下；阅读模式完全不创建编辑控件。 */
final class BookMermaidView extends UIElement {
    private Element element;
    private MermaidNode node;
    private final boolean editable;
    private final Consumer<Element> update;
    private final UIElement diagram =
            new UIElement() {
                @Override
                public void drawBackgroundAdditional(GUIContext context) {
                    MermaidRenderer.draw(
                            context.graphics,
                            MermaidRenderer.get(node.source()),
                            getPositionX(),
                            getPositionY(),
                            getSizeWidth(),
                            getSizeHeight());
                }
            };
    private TextArea input;
    private Button toggle;

    BookMermaidView(Element element, boolean editable, Consumer<Element> update) {
        this.editable = editable;
        this.update = update;
        this.element = element.clone();
        node = MermaidNode.read(element);
        setId("mermaid-block");
        getLayout().gapAll(0).paddingAll(1);
        getStyle().backgroundTexture(paperTexture(MermaidRenderer.BACKGROUND));
        diagram.setId("mermaid-diagram");
        diagram.getStyle().backgroundTexture(new ColorRectTexture(MermaidRenderer.BACKGROUND));
        diagram.getLayout().widthPercent(100).flex(1).minHeight(40);
        diagram.getStyle().tooltips(Component.translatable("gui.betterbook.mermaid_expand"));
        diagram.addEventListener(
                UIEvents.DOUBLE_CLICK,
                event -> {
                    if (event.button != 0) return;
                    expanded();
                    event.stopPropagation();
                });
        addChild(diagram);
        if (editable) {
            toggle =
                    new Button()
                            .setOnClick(
                                    event ->
                                            commit(
                                                    new MermaidNode(
                                                            node.source(), !node.sourceVisible())));
            toggle.setId("mermaid-source-toggle");
            toggle.getLayout().widthPercent(100).height(24).flexShrink(0);
            toggle.buttonStyle(
                    style ->
                            style.baseTexture(paperTexture(MermaidRenderer.HEADER))
                                    .hoverTexture(paperTexture(MermaidRenderer.HEADER_HOVER))
                                    .pressedTexture(paperTexture(MermaidRenderer.HEADER_PRESSED)));
            toggle.text.textStyle(
                    style -> style.fontSize(8).textShadow(false).textColor(MermaidRenderer.TEXT));
            input = new TextArea();
            input.setId("mermaid-source");
            input.getLayout().widthPercent(100).height(124).flexShrink(0);
            input.getStyle()
                    .backgroundTexture(new ColorRectTexture(MermaidRenderer.SOURCE_BACKGROUND));
            input.contentView
                    .getStyle()
                    .backgroundTexture(new ColorRectTexture(MermaidRenderer.SOURCE_BACKGROUND));
            input.textAreaStyle(
                    style ->
                            style.fontSize(8)
                                    .textShadow(false)
                                    .textColor(MermaidRenderer.TEXT)
                                    .cursorColor(MermaidRenderer.TEXT)
                                    .errorColor(MermaidRenderer.ERROR)
                                    .focusOverlay(
                                            RectTexture.of(0x00000000)
                                                    .setStroke(1)
                                                    .setBorderColor(MermaidRenderer.BORDER)));
            input.setValue(node.source().split("\n", -1), false);
            input.setLinesResponder(
                    lines -> {
                        String source = String.join("\n", lines);
                        if (source.length() > MermaidNode.MAX_SOURCE) {
                            input.setValue(node.source().split("\n", -1), false);
                            return;
                        }
                        commit(new MermaidNode(source, node.sourceVisible()));
                    });
            // 文字事件由源码编辑器消费，避免祖先富文本处理同一次输入。
            input.addEventListener(UIEvents.CHAR_TYPED, UIEvent::stopPropagation);
            for (String type :
                    new String[] {
                        UIEvents.MOUSE_DOWN,
                        UIEvents.DOUBLE_CLICK,
                        UIEvents.MOUSE_WHEEL,
                        UIEvents.VALIDATE_COMMAND,
                        UIEvents.EXECUTE_COMMAND
                    }) input.addEventListener(type, UIEvent::stopPropagation);
            toggle.addEventListener(UIEvents.MOUSE_DOWN, UIEvent::stopPropagation);
            var header = Widgets.row();
            header.getLayout().gapAll(0);
            toggle.getLayout().flex(1).minWidth(0).widthAuto();
            var templates =
                    new Button()
                            .setText("gui.betterbook.mermaid_templates")
                            .setOnClick(event -> templates());
            templates.setId("mermaid-templates");
            templates.getLayout().height(24).flexShrink(0);
            templates.buttonStyle(
                    style ->
                            style.baseTexture(paperTexture(MermaidRenderer.HEADER))
                                    .hoverTexture(paperTexture(MermaidRenderer.HEADER_HOVER))
                                    .pressedTexture(paperTexture(MermaidRenderer.HEADER_PRESSED)));
            templates.text.textStyle(
                    style -> style.fontSize(8).textShadow(false).textColor(MermaidRenderer.TEXT));
            templates.addEventListener(UIEvents.MOUSE_DOWN, UIEvent::stopPropagation);
            header.addChildren(toggle, templates);
            addChildren(header, input);
            refreshControls();
        }
    }

    private void expanded() {
        var dialog = new Dialog().setTitle("gui.betterbook.mermaid");
        dialog.setId("mermaid-expanded");
        dialog.overlay.getLayout().width(Math.min(680, getModularUI().getScreenWidth() - 24));
        var canvas =
                new UIElement() {
                    @Override
                    public void drawBackgroundAdditional(GUIContext context) {
                        MermaidRenderer.draw(
                                context.graphics,
                                MermaidRenderer.get(node.source()),
                                getPositionX(),
                                getPositionY(),
                                getSizeWidth(),
                                getSizeHeight());
                    }
                };
        canvas.setId("mermaid-expanded-diagram");
        canvas.getLayout()
                .widthPercent(100)
                .height(Math.max(100, Math.min(410, getModularUI().getScreenHeight() - 80)));
        canvas.getStyle().backgroundTexture(paperTexture(MermaidRenderer.BACKGROUND));
        dialog.addContent(canvas);
        dialog.addButton(Widgets.button("close", "mermaid-expanded-close", dialog::close));
        dialog.show(getModularUI());
    }

    private void templates() {
        var dialog = new Dialog().setTitle("gui.betterbook.mermaid_templates");
        dialog.setId("mermaid-template-dialog");
        for (var template : MermaidTemplates.ALL) {
            var button =
                    Widgets.button(
                            "mermaid_type_" + template.id(),
                            "mermaid-template-" + template.id(),
                            () -> {
                                dialog.close();
                                input.setValue(template.source().split("\n", -1), false);
                                commit(new MermaidNode(template.source(), true));
                                input.focus();
                            });
            button.getLayout().widthPercent(100).height(22);
            dialog.addContent(button);
        }
        dialog.addButton(Widgets.button("cancel", "mermaid-template-cancel", dialog::close));
        dialog.show(getModularUI());
    }

    private static RectTexture paperTexture(int color) {
        return RectTexture.of(color).setStroke(1).setBorderColor(MermaidRenderer.FRAME);
    }

    void sync(Element replacement) {
        if (element.outerHtml().equals(replacement.outerHtml())) return;
        element = replacement.clone();
        node = MermaidNode.read(element);
        if (editable) {
            if (!String.join("\n", input.getValue()).equals(node.source()))
                input.setValue(node.source().split("\n", -1), false);
            refreshControls();
        }
    }

    private void commit(MermaidNode replacement) {
        if (replacement.equals(node)) return;
        node = replacement;
        node.writeTo(element);
        refreshControls();
        update.accept(element.clone());
    }

    private void refreshControls() {
        if (!editable) return;
        input.setDisplay(node.sourceVisible());
        toggle.setText(
                Component.literal(node.sourceVisible() ? "▾ " : "▸ ")
                        .append(Component.translatable("gui.betterbook.mermaid_source")));
    }
}
