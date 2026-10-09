package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.gui.texture.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import com.zhenshiz.betterbook.core.Book;
import com.zhenshiz.betterbook.core.BookPageAccess;
import com.zhenshiz.betterbook.data.*;

import dev.vfyjxf.taffy.style.*;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

import org.jsoup.nodes.Element;

import java.util.*;
import java.util.function.Consumer;

/** 羊皮纸页面入口网格，编辑时选中和排序，阅读时跳转。 */
final class BookRelatedPagesView extends UIElement {
    private record Drag(BookRelatedPagesView owner, String entry) {}

    private final String source;
    private final boolean editable;
    private BookRelatedPages data;
    private List<Book.Page> pages = List.of();
    private BookPageAccess access;
    private boolean lockedIcons = true;
    private String catalogKey = "", selection = "";
    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private Consumer<String> selected = id -> {}, navigate = id -> {};
    private Consumer<BookRelatedPages> changed = value -> {};

    BookRelatedPagesView(Element element, boolean editable) {
        source = element.outerHtml();
        this.editable = editable;
        addClass("book-related-pages");
        try {
            data = BookRelatedPages.read(element, Platform.getFrozenRegistry());
            rebuild();
        } catch (IllegalArgumentException e) {
            addChild(
                    new Label()
                            .setText(
                                    Component.translatable(
                                            "gui.betterbook.related_error", e.getMessage())));
        }
        addEventListener(
                UIEvents.MOUSE_DOWN,
                e -> {
                    if (editable && e.button == 0) selectEntry("");
                    e.stopPropagation();
                });
        addEventListener(UIEvents.DOUBLE_CLICK, UIEvent::stopPropagation);
    }

    boolean matches(Element element) {
        return source.equals(element.outerHtml());
    }

    String selectedEntry() {
        return selection;
    }

    void callbacks(
            Consumer<String> selected,
            Consumer<BookRelatedPages> changed,
            Consumer<String> navigate) {
        this.selected = selected;
        this.changed = changed;
        this.navigate = navigate;
    }

    void pages(List<Book.Page> pages) {
        String key = pages.stream().map(p -> p.id() + ":" + p.title()).toList().toString();
        if (key.equals(catalogKey)) return;
        this.pages = List.copyOf(pages);
        catalogKey = key;
        if (data != null) rebuild();
    }

    void pageAccess(BookPageAccess access, boolean lockedIcons) {
        this.access = access;
        this.lockedIcons = lockedIcons;
        if (data != null) rebuild();
    }

    void highlight(String id) {
        selection = id;
        buttons.forEach(
                (key, button) -> {
                    if (editable && key.equals(id)) button.addClass("book-related-selected");
                    else button.removeClass("book-related-selected");
                });
    }

    private void selectEntry(String id) {
        highlight(id);
        selected.accept(id);
    }

    private void rebuild() {
        clearAllChildren();
        buttons.clear();
        if (!data.title.isBlank()) {
            var title = Widgets.row().addClass("book-related-heading");
            title.setId("related-heading");
            title.getLayout()
                    .positionType(TaffyPosition.ABSOLUTE)
                    .left(8)
                    .right(8)
                    .widthAuto()
                    .top(2)
                    .height(18)
                    .alignItems(AlignItems.CENTER);
            var text = new Label().setText(data.title, false);
            text.addClass("book-related-title");
            text.textStyle(s -> s.textShadow(false).adaptiveWidth(true).textWrap(TextWrap.ROLL));
            text.getLayout().flexShrink(1).minWidth(0).maxWidthPercent(75);
            title.addChildren(line(), text, line());
            addChild(title);
        }
        for (var entry : data.entries) {
            int pageIndex = -1;
            for (int i = 0; i < pages.size(); i++)
                if (pages.get(i).id().equals(entry.target)) {
                    pageIndex = i;
                    break;
                }
            final boolean available = pageIndex >= 0;
            final boolean locked = !editable && available && access != null && !access.canRead(entry.target);
            String label =
                    entry.name.isBlank()
                            ? available
                                    ? pages.get(pageIndex).title()
                                    : Component.translatable("gui.betterbook.related_missing")
                                            .getString()
                            : entry.name;
            var button = new Button().noText();
            button.setId("related-entry-" + entry.id);
            button.addClass("book-related-button");
            button.getLayout()
                    .positionType(TaffyPosition.ABSOLUTE)
                    .flexDirection(FlexDirection.COLUMN)
                    .paddingAll(3)
                    .alignItems(AlignItems.CENTER)
                    .gapAll(2);
            if (!available) button.addClass("book-related-missing");
            if (locked) button.addClass("book-related-locked");
            button.getStyle()
                    .tooltips(
                            locked ? BookLocks.message(access, entry.target) : available
                                    ? Component.translatable(
                                            "gui.betterbook.related_page_candidate",
                                            pageIndex + 1,
                                            label)
                                    : Component.translatable(
                                            entry.target.isBlank()
                                                    ? "gui.betterbook.related_unconfigured"
                                                    : "gui.betterbook.related_missing"));
            var texture = locked && lockedIcons ? BookLocks.ICON : texture(entry);
            var icon = new UIElement()
                            .layout(l -> l.width(22).height(22).flexShrink(0))
                            .style(s -> s.background(texture));
            icon.setId("related-icon-" + entry.id);
            if (locked && lockedIcons) icon.addClass("book-lock-icon");
            button.addChild(icon);
            if (data.showNames) {
                var name = new Label().setText(label, false);
                name.addClass("book-related-name");
                name.getLayout().widthPercent(100).height(20);
                name.textStyle(
                        s ->
                                s.textWrap(TextWrap.WRAP)
                                        .textShadow(false)
                                        .textAlignHorizontal(Horizontal.CENTER));
                button.addChild(name);
            }
            if (editable) {
                button.addEventListener(
                        UIEvents.MOUSE_DOWN,
                        e -> {
                            if (e.button != 0) return;
                            selectEntry(entry.id);
                            button.startDrag(new Drag(this, entry.id), texture);
                            e.hasHandler = true;
                            e.stopPropagation();
                        },
                        true);
                button.addEventListener(
                        UIEvents.DRAG_ENTER,
                        e -> {
                            if (e.dragHandler != null
                                    && e.dragHandler.draggingObject instanceof Drag drag
                                    && drag.owner == this) button.addClass("book-related-drop");
                        });
                button.addEventListener(
                        UIEvents.DRAG_LEAVE, e -> button.removeClass("book-related-drop"));
                button.addEventListener(
                        UIEvents.DRAG_END,
                        e -> buttons.values().forEach(b -> b.removeClass("book-related-drop")));
                button.addEventListener(
                        UIEvents.DRAG_PERFORM,
                        e -> {
                            if (!(e.dragHandler.draggingObject instanceof Drag drag)
                                    || drag.owner != this
                                    || drag.entry.equals(entry.id)) return;
                            var moved = data.entry(drag.entry).orElseThrow();
                            int target = data.entries.indexOf(entry);
                            data.entries.remove(moved);
                            data.entries.add(target, moved);
                            selectEntry(moved.id);
                            changed.accept(data);
                            e.hasHandler = true;
                            e.stopPropagation();
                        },
                        true);
            } else
                button.setOnClick(
                        e -> {
                            if (available) navigate.accept("book:" + entry.target);
                        });
            buttons.put(entry.id, button);
            addChild(button);
        }
        if (data.entries.isEmpty() && editable) {
            var hint = new Label().setText("gui.betterbook.related_empty");
            hint.addClass("book-related-hint");
            hint.getLayout()
                    .positionType(TaffyPosition.ABSOLUTE)
                    .left(8)
                    .right(8)
                    .top(data.title.isBlank() ? 8 : 28)
                    .height(28);
            hint.textStyle(s -> s.textWrap(TextWrap.WRAP));
            addChild(hint);
        }
        highlight(selection);
        layoutButtons();
    }

    private UIElement line() {
        return new UIElement().addClass("book-related-line").layout(l -> l.flex(1).height(1));
    }

    private IGuiTexture texture(BookRelatedPages.Entry entry) {
        if (entry.icon.equals("image")
                && !entry.image.isBlank()
                && ResourceLocation.tryParse(entry.image) != null)
            return SpriteTexture.of(entry.image);
        var stack =
                BookItem.read(
                        new Element("div").attr("data-stack", entry.stack),
                        Platform.getFrozenRegistry());
        return new ItemStackTexture(stack.isEmpty() ? Items.BOOK.getDefaultInstance() : stack);
    }

    private void layoutButtons() {
        if (data == null) return;
        int columns = data.visibleColumns(getSizeWidth());
        float gap = 6,
                cell =
                        Math.min(
                                data.showNames ? 52 : 32,
                                Math.max(
                                        16, (getSizeWidth() - 16 - (columns - 1) * gap) / columns));
        float left = (getSizeWidth() - (columns * cell + (columns - 1) * gap)) / 2;
        float top = data.title.isBlank() ? 8 : 28;
        int index = 0;
        for (var button : buttons.values()) {
            button.getLayout()
                    .left(left + (index % columns) * (cell + gap))
                    .top(top + (index / columns) * (data.showNames ? 54 : 38))
                    .width(cell)
                    .height(data.showNames ? 48 : 32);
            index++;
        }
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        layoutButtons();
    }
}
