package com.zhenshiz.betterbook.client;

import static com.zhenshiz.betterbook.client.Widgets.*;

import com.lowdragmc.lowdraglib2.gui.texture.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Cursor;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.OreSprites;
import com.zhenshiz.betterbook.core.*;

import dev.vfyjxf.taffy.style.*;

import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

/** 分类插入面板与局部 LaTeX 格式菜单；草稿仅在确认时提交。 */
final class BookLatexEditor {
    private static final int TEXT = 0xff222222;
    private final Dialog dialog = new FormulaDialog().setTitle("gui.betterbook.latex_editor");
    private final UIElement visualFields = new UIElement();
    private final SourceInput sourceInput = new SourceInput();
    private final Label error = new Label(), hint = new Label();
    private final BookLatexView preview;
    private final Button confirm, visualButton, sourceButton;
    private final Consumer<LatexNode> apply;
    private final List<SlotInput> slots = new ArrayList<>();
    private final LatexNode original;
    private LatexTemplates.Template template;
    private SlotInput lastSlot;
    private String source, align;
    private boolean visual = true;
    private UIElement popup, popupAnchor;
    private int customColor = 0xff393024;

    private final class FormulaDialog extends Dialog {
        @Override
        protected void keyDown(UIEvent event) {
            if (event.keyCode == GLFW.GLFW_KEY_ESCAPE && popup != null) {
                closePopup();
                event.stopPropagation();
                return;
            }
            super.keyDown(event);
        }
    }

    /** 工具栏获得焦点时保留选区，以便将样式包裹在原选中文本周围。 */
    private static final class SlotInput extends TextField {
        @Override
        protected void onBlur(UIEvent event) {}

        void format(LatexFormatting.Format format) {
            int from = Math.min(getSelectionStart(), getSelectionEnd());
            int to = Math.max(getSelectionStart(), getSelectionEnd());
            if (from == to) {
                from = 0;
                to = getValue().length();
            }
            String selected = getValue().substring(from, to);
            setSelection(from, to);
            insertText(format.wrap(selected));
            setCursor(from + format.prefix().length() + selected.length());
            setSelection(
                    from + format.prefix().length(),
                    from + format.prefix().length() + selected.length());
            focus();
        }
    }

    private static final class SourceInput extends TextArea {
        @Override
        protected void onBlur(UIEvent event) {}

        void insertFormula(String value) {
            insertText(value);
            focus();
        }

        void format(LatexFormatting.Format format) {
            var lines = getValue();
            int from = offset(lines, getSelStartLine(), getSelStartCol());
            int to = offset(lines, getSelEndLine(), getSelEndCol());
            String selected =
                    String.join("\n", lines).substring(Math.min(from, to), Math.max(from, to));
            int start = Math.min(from, to) + format.prefix().length();
            insertText(format.wrap(selected));
            var a = position(getValue(), start);
            var b = position(getValue(), start + selected.length());
            setCursor(b.line(), b.col());
            setSelection(a, b);
            focus();
        }

        private static int offset(String[] lines, int line, int col) {
            int offset = 0;
            for (int i = 0; i < line; i++) offset += lines[i].length() + 1;
            return offset + col;
        }

        private static Cursor position(String[] lines, int offset) {
            for (int i = 0; i < lines.length; i++) {
                if (offset <= lines[i].length()) return new Cursor(i, offset);
                offset -= lines[i].length() + 1;
            }
            return new Cursor(lines.length - 1, lines[lines.length - 1].length());
        }
    }

    static void insertOrEdit(UIElement host, BookSession session) {
        if (session.hasDraft()) return;
        var selection = session.selection();
        long revision = session.revision();
        var element = session.document().blocks().get(selection.head().block()).element();
        boolean editing = element.is(LatexNode.SELECTOR);
        var initial =
                editing ? LatexNode.read(element) : new LatexNode("x^2", 16, "#393024", "center");
        open(
                host,
                initial,
                node -> {
                    if (session.revision() != revision)
                        throw new IllegalStateException("Book changed while editing formula");
                    session.select(selection);
                    if (editing) session.editElement(node::writeTo);
                    else session.insertHtml(node.element().outerHtml());
                },
                editing ? "apply" : "insert");
    }

    static void open(UIElement host, LatexNode initial, Consumer<LatexNode> apply, String action) {
        new BookLatexEditor(host, initial, apply, action);
    }

    private BookLatexEditor(
            UIElement host, LatexNode initial, Consumer<LatexNode> apply, String action) {
        this.apply = apply;
        original = initial;
        source = initial.source();
        align = initial.align();
        dialog.setId("latex-editor");
        dialog.setClickOutsideClose(false);
        var ui = host.getModularUI();
        dialog.overlay.getLayout().width(Math.max(180, Math.min(650, ui.getScreenWidth() - 24)));
        var scroll = new ScrollerView();
        scroll.setId("latex-content-scroll");
        scroll.viewPort.getLayout().paddingAll(0);
        scroll.viewPort.getStyle().backgroundTexture(IGuiTexture.EMPTY);
        scroll.getLayout()
                .widthPercent(100)
                .height(Math.max(100, Math.min(390, ui.getScreenHeight() - 74)));
        var content = new UIElement();
        content.getLayout().widthPercent(100).minWidth(0).gapAll(7).paddingAll(5);
        scroll.addScrollViewChild(content);
        dialog.addContent(scroll);

        content.addChild(label("latex_templates"));
        var categories = row();
        categories.setId("latex-categories");
        categories.getLayout().flexWrap(FlexWrap.WRAP).gapAll(4);
        for (var category : LatexPalette.CATEGORIES) {
            var button = new Button().noText();
            button.setId("latex-category-" + category.id());
            button.setOnClick(event -> categoryPopup(button, category));
            button.getLayout()
                    .flexDirection(FlexDirection.COLUMN)
                    .width(58)
                    .height(64)
                    .paddingAll(3)
                    .gapAll(1)
                    .flexShrink(0);
            themeButton(button);
            var thumbnail = formulaView(category.icon(), 17);
            thumbnail.getLayout().widthPercent(100).height(39).flexShrink(0);
            var name = label("latex_category_" + category.id());
            name.textStyle(
                    style ->
                            style.fontSize(7)
                                    .textColor(TEXT)
                                    .textAlignHorizontal(
                                            com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal
                                                    .CENTER));
            name.getLayout().height(10);
            var arrow = new Label();
            arrow.setText("▾", false);
            arrow.textStyle(
                    style ->
                            style.textColor(TEXT)
                                    .fontSize(6)
                                    .textAlignHorizontal(
                                            com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal
                                                    .CENTER));
            arrow.getLayout().height(7);
            button.addChildren(thumbnail, name, arrow);
            categories.addChild(button);
        }
        content.addChild(categories);
        content.addChild(divider());

        var controls = row();
        controls.setId("latex-format-controls");
        controls.getLayout().flexWrap(FlexWrap.WRAP).alignItems(AlignItems.CENTER).gapAll(4);
        controls.addChild(
                menuButton("latex_color", "latex-color-menu", button -> colorPopup(button)));
        controls.addChild(menuButton("latex_size", "latex-size-menu", this::sizePopup));
        var clear =
                button(
                        "latex_clear",
                        "latex-clear",
                        () -> {
                            closePopup();
                            source = "";
                            template = null;
                            rebuildFields();
                            syncSource();
                            refresh();
                        });
        themeButton(clear);
        clear.addClass("__reject-button__");
        controls.addChild(clear);
        var spacer = new UIElement();
        spacer.getLayout().flex(1).minWidth(0);
        controls.addChild(spacer);
        sourceButton = button("latex_source", "latex-mode-source", () -> mode(false));
        visualButton = button("latex_visual", "latex-mode-visual", () -> mode(true));
        themeButton(sourceButton);
        themeButton(visualButton);
        controls.addChildren(sourceButton, visualButton);
        content.addChild(controls);
        hint.setId("latex-mode-hint");
        hint.getLayout().widthPercent(100);
        hint.textStyle(
                style ->
                        style.adaptiveWidth(false)
                                .adaptiveHeight(true)
                                .textWrap(TextWrap.WRAP)
                                .fontSize(7)
                                .textColor(0xffb6c2cd));
        content.addChild(hint);
        visualFields.setId("latex-fields");
        visualFields.getLayout().widthPercent(100).gapAll(4);
        content.addChild(visualFields);
        sourceInput.setId("latex-source");
        sourceInput.getLayout().widthPercent(100).height(116).flexShrink(0);
        sourceInput.textAreaStyle(style -> style.textShadow(false));
        sourceInput.setLinesResponder(
                lines -> {
                    source = String.join("\n", lines);
                    template = null;
                    refresh();
                });
        syncSource();
        content.addChild(sourceInput);
        var output = row();
        output.getLayout().alignItems(AlignItems.CENTER);
        var outputTitle = label("latex_preview");
        outputTitle.getLayout().flex(1);
        output.addChild(outputTitle);
        for (String alignment : List.of("left", "center", "right")) {
            var button =
                    button(
                            alignment,
                            "latex-align-" + alignment,
                            () -> {
                                align = alignment;
                                refresh();
                            });
            themeButton(button);
            output.addChild(button);
        }
        content.addChild(output);
        preview = new BookLatexView(initial.element(), false, e -> {});
        preview.setId("latex-preview");
        preview.getLayout().widthPercent(100).height(100).flexShrink(0);
        preview.getStyle().backgroundTexture(OreSprites.SLOT_LIGHT);
        content.addChild(preview);
        error.setId("latex-error");
        error.getLayout().widthPercent(100);
        error.textStyle(
                style ->
                        style.adaptiveWidth(false)
                                .adaptiveHeight(true)
                                .textWrap(TextWrap.WRAP)
                                .fontSize(7)
                                .textColor(0xfff58a80));
        content.addChild(error);
        confirm = button(action, "latex-confirm", this::commit);
        dialog.addButton(confirm);
        dialog.addButton(button("cancel", "latex-cancel", dialog::close));
        dialog.addEventListener(
                UIEvents.MOUSE_DOWN,
                event -> {
                    if (popup != null
                            && !popup.isAncestorOf(event.target)
                            && popup != event.target
                            && !popupAnchor.isAncestorOf(event.target)
                            && popupAnchor != event.target) closePopup();
                },
                true);
        rebuildFields();
        mode(true);
        dialog.show(ui);
    }

    private static Label label(String key) {
        var label = new Label();
        label.setText("gui.betterbook." + key);
        label.textStyle(style -> style.textShadow(false));
        return label;
    }

    private static void themeButton(Button button) {
        button.text.textStyle(style -> style.fontSize(8).textShadow(false));
    }

    private static UIElement divider() {
        var line = new UIElement();
        line.getLayout().widthPercent(100).height(1).flexShrink(0);
        line.getStyle().backgroundTexture(new ColorRectTexture(0xff8b8d90));
        return line;
    }

    private static BookLatexView formulaView(String source, int size) {
        var view =
                new BookLatexView(
                        new LatexNode(source, size, "#222222", "center").element(), false, e -> {});
        view.inset(2);
        return view;
    }

    private Button menuButton(String key, String id, Consumer<Button> open) {
        var button = new Button();
        button.setText(Component.translatable("gui.betterbook." + key).append(" ▾"));
        button.setId(id);
        button.getLayout().height(23).paddingHorizontal(7).flexShrink(0);
        themeButton(button);
        button.setOnClick(event -> open.accept(button));
        return button;
    }

    private void categoryPopup(Button anchor, LatexPalette.Category category) {
        var body = popupBody(anchor, "latex-palette-" + category.id(), 410, 290);
        if (body == null) return;
        for (var section : category.sections()) {
            String sectionName = section.id().substring(category.id().length() + 1);
            var heading = label("latex_section_" + sectionName);
            heading.textStyle(style -> style.fontSize(8));
            heading.getLayout().widthPercent(100).marginTop(5);
            body.addChildren(heading, divider());
            var grid = row();
            grid.getLayout().flexWrap(FlexWrap.WRAP).gapAll(4);
            for (var entry : section.entries()) {
                var result =
                        LatexRenderer.get(new LatexNode(entry.source(), 14, "#222222", "center"));
                float width = Math.clamp(result.width() + 16, 28, 130);
                float height = Math.clamp(result.height() + 12, 25, 58);
                var button =
                        new Button()
                                .noText()
                                .setOnClick(
                                        event -> {
                                            closePopup();
                                            choose(category, entry);
                                        });
                button.setId("latex-entry-" + entry.id());
                button.getLayout().width(width).height(height).paddingAll(0).flexShrink(0);
                themeButton(button);
                button.getStyle().tooltips(Component.literal(entry.source()));
                var image = formulaView(entry.source(), 14);
                image.getLayout().widthPercent(100).heightPercent(100);
                button.addChild(image);
                grid.addChild(button);
            }
            body.addChild(grid);
        }
    }

    private void sizePopup(Button anchor) {
        var body = popupBody(anchor, "latex-size-popup", 225, 290);
        if (body == null) return;
        for (var format : LatexFormatting.SIZES) {
            var button =
                    new Button()
                            .noText()
                            .setOnClick(
                                    event -> {
                                        closePopup();
                                        format(format);
                                    });
            button.setId("latex-size-" + format.id());
            button.getLayout().widthPercent(100).height(29).paddingAll(2).gapAll(4);
            themeButton(button);
            button.getStyle().tooltips(Component.literal(format.wrap("")));
            var sample = formulaView(format.wrap(format.sample()), 9);
            sample.getLayout().width(70).heightPercent(100).flexShrink(0);
            var name = label("latex_size_" + format.id());
            name.getLayout().flex(1).minWidth(0).heightPercent(100);
            name.textStyle(
                    style ->
                            style.textColor(TEXT)
                                    .textAlignVertical(
                                            com.lowdragmc.lowdraglib2.gui.ui.data.Vertical.CENTER));
            button.addChildren(sample, name);
            body.addChild(button);
        }
    }

    private void colorPopup(Button anchor) {
        var body = popupBody(anchor, "latex-color-popup", 220, 340);
        if (body == null) return;
        for (var format : LatexFormatting.COLORS) {
            var button =
                    new Button()
                            .noText()
                            .setOnClick(
                                    event -> {
                                        closePopup();
                                        format(format);
                                    });
            button.setId("latex-color-" + format.id());
            button.getLayout().widthPercent(100).height(24).paddingAll(1).gapAll(3);
            themeButton(button);
            button.getStyle().tooltips(Component.literal(format.wrap("")));
            var sample = formulaView(format.wrap("\\text{" + format.id() + "}"), 12);
            sample.getLayout().width(90).heightPercent(100).flexShrink(0);
            var name = label("latex_color_" + format.id());
            name.getLayout().flex(1).minWidth(0).heightPercent(100);
            name.textStyle(
                    style ->
                            style.textColor(TEXT)
                                    .textAlignVertical(
                                            com.lowdragmc.lowdraglib2.gui.ui.data.Vertical.CENTER));
            button.addChildren(sample, name);
            body.addChild(button);
        }
        var custom =
                button("latex_custom_color", "latex-color-custom", () -> customColorPopup(anchor));
        custom.getLayout().widthPercent(100).height(25);
        themeButton(custom);
        body.addChild(custom);
    }

    private void customColorPopup(Button anchor) {
        closePopup();
        var body = popupBody(anchor, "latex-custom-color-popup", 280, 310);
        body.addChild(label("latex_custom_color_title"));
        var picker = new ColorSelector();
        picker.setId("latex-custom-color-picker");
        picker.getLayout().widthPercent(100).flexShrink(0);
        picker.pickerContainer.getLayout().aspectRatioAuto().height(150).flexShrink(0);
        picker.alphaSlider.setDisplay(false);
        picker.setValue(customColor, false);
        var sample = formulaView(LatexFormatting.rgb(customColor).wrap("ABC+x"), 18);
        sample.setId("latex-custom-color-preview");
        sample.getStyle().backgroundTexture(OreSprites.SLOT_LIGHT);
        sample.getLayout().widthPercent(100).height(36);
        picker.setOnColorChangeListener(
                value -> {
                    customColor = value | 0xff000000;
                    sample.setFormula(
                            new LatexNode(
                                            LatexFormatting.rgb(customColor).wrap("ABC+x"),
                                            18,
                                            "#222222",
                                            "center")
                                    .element());
                });
        var apply =
                button(
                        "latex_custom_color_apply",
                        "latex-custom-color-apply",
                        () -> {
                            closePopup();
                            format(LatexFormatting.rgb(customColor));
                        });
        apply.getLayout().widthPercent(100).height(24);
        themeButton(apply);
        body.addChildren(picker, sample, apply);
    }

    /** 浮层是主弹窗的子元素，点击和 Esc 可先关闭菜单，超长面板可滚动。 */
    private UIElement popupBody(
            UIElement anchor, String id, float requestedWidth, float requestedHeight) {
        if (popupAnchor == anchor && popup != null && popup.getId().equals(id)) {
            closePopup();
            return null;
        }
        closePopup();
        popupAnchor = anchor;
        popup = new UIElement();
        popup.setId(id);
        float width = Math.min(requestedWidth, dialog.getSizeWidth() - 12);
        float height = Math.min(requestedHeight, dialog.getSizeHeight() - 24);
        float x =
                Math.clamp(
                        anchor.getPositionX() - dialog.getPositionX(),
                        6,
                        Math.max(6, dialog.getSizeWidth() - width - 6));
        float y = anchor.getPositionY() + anchor.getSizeHeight() - dialog.getPositionY() + 3;
        if (y + height > dialog.getSizeHeight() - 6)
            y = Math.max(6, dialog.getSizeHeight() - height - 6);
        popup.getLayout()
                .positionType(TaffyPosition.ABSOLUTE)
                .left(x)
                .top(y)
                .width(width)
                .height(height)
                .paddingAll(6);
        popup.getStyle().zIndex(20).backgroundTexture(OreSprites.BORDER_5);
        var scroll = new ScrollerView();
        scroll.getLayout().widthPercent(100).heightPercent(100);
        scroll.viewPort.getLayout().paddingAll(0);
        scroll.viewPort.getStyle().backgroundTexture(IGuiTexture.EMPTY);
        var body = new UIElement();
        body.getLayout().widthPercent(100).gapAll(4).paddingRight(3);
        scroll.addScrollViewChild(body);
        popup.addChild(scroll);
        dialog.addChild(popup);
        return body;
    }

    private void closePopup() {
        if (popup != null) popup.removeSelf();
        popup = null;
        popupAnchor = null;
    }

    private void syncSource() {
        sourceInput.setValue(source.split("\n", -1), false);
    }

    private void choose(LatexPalette.Category category, LatexPalette.Entry entry) {
        if (!visual) {
            sourceInput.insertFormula(entry.source() + " ");
            return;
        }
        if (lastSlot != null || category.id().equals("symbols") || category.id().equals("greek")) {
            if (!slots.isEmpty()) {
                var slot = lastSlot != null ? lastSlot : slots.getFirst();
                slot.insertText(entry.source() + " ");
                slot.focus();
            }
            return;
        }
        source = entry.source();
        template = null;
        rebuildFields();
        syncSource();
        refresh();
    }

    private void format(LatexFormatting.Format format) {
        if (!visual) sourceInput.format(format);
        else if (!slots.isEmpty()) {
            var slot = lastSlot != null ? lastSlot : slots.getFirst();
            slot.format(format);
        }
    }

    private void mode(boolean visual) {
        closePopup();
        this.visual = visual;
        if (visual) rebuildFields();
        else syncSource();
        visualFields.setDisplay(this.visual);
        sourceInput.setDisplay(!this.visual);
        selectedMode(visualButton, this.visual);
        selectedMode(sourceButton, !this.visual);
        hint.setText("gui.betterbook.latex_scope_hint");
        refresh();
    }

    private static void selectedMode(Button button, boolean selected) {
        button.buttonStyle(
                style ->
                        style.baseTexture(
                                        selected
                                                ? OreSprites.BTN_DEFAULT_GREEN
                                                : OreSprites.BTN_DEFAULT)
                                .hoverTexture(
                                        selected
                                                ? OreSprites.BTN_PRESSED_GREEN
                                                : OreSprites.BTN_PRESSED)
                                .pressedTexture(
                                        selected
                                                ? OreSprites.BTN_PRESSED_GREEN
                                                : OreSprites.BTN_PRESSED));
        button.text.textStyle(style -> style.textColor(selected ? 0xffffffff : TEXT));
    }

    private void rebuildFields() {
        visualFields.clearAllChildren();
        slots.clear();
        lastSlot = null;
        List<String> values = List.of(source);
        if (template == null)
            for (var candidate : LatexTemplates.ALL) {
                var match = candidate.match(source);
                if (match.isPresent()) {
                    template = candidate;
                    values = match.get();
                    break;
                }
            }
        else values = template.match(source).orElse(template.defaults());
        if (values.stream().anyMatch(value -> value.contains("\n"))) {
            visual = false;
            syncSource();
            return;
        }
        var fields = template == null ? List.of("expression") : template.fields();
        for (int i = 0; i < fields.size(); i++) {
            var row = row();
            row.getLayout().alignItems(AlignItems.CENTER);
            var name = label("latex_field_" + fields.get(i));
            name.getLayout().width(80).flexShrink(0);
            var input = new SlotInput();
            input.setId("latex-slot-" + i);
            input.getLayout().flex(1).minWidth(0).height(23);
            input.setValue(values.get(i), false);
            input.textFieldStyle(style -> style.textShadow(false));
            input.addEventListener(UIEvents.FOCUS, event -> lastSlot = input);
            input.setTextResponder(
                    value -> {
                        source =
                                template == null
                                        ? value
                                        : template.build(
                                                slots.stream().map(TextField::getValue).toList());
                        syncSource();
                        refresh();
                    });
            slots.add(input);
            row.addChildren(name, input);
            visualFields.addChild(row);
        }
    }

    private LatexNode currentNode() {
        return new LatexNode(source, original.size(), original.color(), align);
    }

    private void refresh() {
        try {
            var node = currentNode();
            var result = LatexRenderer.get(node);
            preview.setFormula(node.element());
            confirm.setActive(result.valid());
            error.setText(
                    result.valid()
                            ? Component.empty()
                            : Component.translatable("gui.betterbook.latex_error", result.error()));
        } catch (IllegalArgumentException e) {
            confirm.setActive(false);
            error.setText(Component.translatable("gui.betterbook.latex_error", e.getMessage()));
        }
    }

    private void commit() {
        var node = currentNode();
        if (!LatexRenderer.get(node).valid()) return;
        try {
            apply.accept(node);
            dialog.close();
        } catch (RuntimeException e) {
            error.setText(Component.translatable("gui.betterbook.latex_error", e.getMessage()));
        }
    }
}
