package com.zhenshiz.betterbook.client;

import static com.zhenshiz.betterbook.client.Widgets.*;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.accessors.ItemStackAccessor;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.zhenshiz.betterbook.core.*;
import com.zhenshiz.betterbook.data.*;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/** 分别显示整组设置与单个页面入口设置，所有修改参与书页撤销。 */
final class RelatedPagesInspector extends UIElement {
    private final BookSession session;
    private final UIElement fields = new UIElement();
    private final Label error = new Label();
    private final Button removeGroup;
    private int block = -1;
    private String entryId = "", source = "";
    private boolean applyingItem;
    private ItemStack stack = ItemStack.EMPTY;

    RelatedPagesInspector(BookSession session, Runnable removed) {
        this.session = session;
        setId("related-panel");
        getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0);
        var scroll = new ScrollerView();
        scroll.getLayout().widthPercent(100).flex(1).minHeight(0);
        fields.getLayout().widthPercent(100).minWidth(0).gapAll(5).paddingAll(3);
        scroll.addScrollViewChild(fields);
        error.setId("related-error");
        error.getLayout().widthPercent(100).minWidth(0);
        error.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        error.setDisplay(false);
        removeGroup =
                button(
                        "related_remove_group",
                        "related-remove-group",
                        () -> {
                            if (block < 0) return;
                            session.delete(1);
                            removed.run();
                        });
        addChildren(scroll, error, removeGroup);
        setDisplay(false);
    }

    int block() {
        return block;
    }

    void hide() {
        block = -1;
        entryId = "";
        setDisplay(false);
    }

    void show(int block, String entry) {
        boolean force = this.block != block || !entryId.equals(entry);
        this.block = block;
        entryId = entry;
        setDisplay(true);
        refresh(force);
    }

    void refresh(boolean force) {
        if (block < 0 || applyingItem) return;
        var pages =
                IntStream.range(0, session.book().pages.size())
                        .mapToObj(i -> session.book().page(i, session.language()))
                        .toList();
        var element = session.document().blocks().get(block).element();
        String current =
                element.outerHtml() + pages.stream().map(p -> p.id() + ":" + p.title()).toList();
        if (!force && source.equals(current)) return;
        source = current;
        fields.clearAllChildren();
        error.setDisplay(false);
        try {
            var data = BookRelatedPages.read(element, Platform.getFrozenRegistry());
            var entry = data.entry(entryId);
            removeGroup.setDisplay(entry.isEmpty());
            if (entry.isPresent()) entryFields(entry.get(), pages);
            else groupFields(data);
        } catch (Exception e) {
            fail(e);
        }
    }

    private TextField input(String id, String value) {
        var field = new TextField();
        field.setId(id);
        field.setValue(value, false);
        field.getLayout().widthPercent(100).minWidth(0).height(20);
        return field;
    }

    private void groupFields(BookRelatedPages data) {
        var title = input("related-title", data.title);
        var columns = new Selector<Integer>();
        columns.setId("related-columns");
        columns.setCandidates(List.of(2, 3, 4, 5, 6));
        columns.setValue(data.columns, false);
        columns.setOnValueChanged(value -> edit(d -> d.columns = value));
        columns.getLayout().widthPercent(100).height(20);
        var names = new Toggle().setText("gui.betterbook.related_show_names");
        names.toggleButton.setId("related-show-names");
        names.setValue(data.showNames, false);
        names.setOnToggleChanged(value -> edit(d -> d.showNames = value));
        fields.addChildren(
                new Label().setText("gui.betterbook.related_group_title"),
                title,
                button(
                        "apply",
                        "related-title-apply",
                        () -> edit(d -> d.title = title.getValue().trim())),
                new Label().setText("gui.betterbook.related_columns"),
                columns,
                names,
                button(
                        "related_add",
                        "related-add",
                        () ->
                                edit(
                                        d -> {
                                            var entry = new BookRelatedPages.Entry();
                                            entry.stack =
                                                    BookItem.write(
                                                            Items.BOOK.getDefaultInstance(),
                                                            Platform.getFrozenRegistry());
                                            d.entries.add(entry);
                                            entryId = entry.id;
                                        })));
    }

    private void entryFields(BookRelatedPages.Entry entry, List<Book.Page> pages) {
        var target = new BookPageSearchBox(pages, entry.target);
        target.setId("related-target");
        var name = input("related-name", entry.name);
        fields.addChildren(
                new Label().setText("gui.betterbook.related_target"),
                target,
                new Label().setText("gui.betterbook.related_name"),
                name,
                button(
                        "apply",
                        "related-entry-apply",
                        () -> {
                            if (target.getValue() == null) {
                                error.setText("gui.betterbook.related_choose_target");
                                error.setDisplay(true);
                                return;
                            }
                            edit(
                                    d -> {
                                        var next = d.entry(entryId).orElseThrow();
                                        next.target = target.getValue().id();
                                        next.name = name.getValue().trim();
                                    });
                        }));
        if (!entry.target.isBlank() && pages.stream().noneMatch(p -> p.id().equals(entry.target)))
            fields.addChild(new Label().setText("gui.betterbook.related_missing"));
        var type = new Selector<String>();
        type.setId("related-icon-type");
        type.setCandidates(List.of("item", "image"));
        type.setValue(entry.icon, false);
        type.setCandidateUIProvider(
                UIElementProvider.text(
                        value -> Component.translatable("gui.betterbook.related_icon_" + value)));
        type.getLayout().widthPercent(100).height(20);
        type.setOnValueChanged(value -> edit(d -> d.entry(entryId).orElseThrow().icon = value));
        fields.addChildren(new Label().setText("gui.betterbook.related_icon"), type);
        if (entry.icon.equals("image")) {
            var path = input("related-image", entry.image);
            fields.addChildren(
                    path,
                    button(
                            "apply",
                            "related-image-apply",
                            () -> {
                                var id = ResourceLocation.tryParse(path.getValue().trim());
                                if (id == null
                                        || Minecraft.getInstance()
                                                .getResourceManager()
                                                .getResource(id)
                                                .isEmpty()) {
                                    error.setText("gui.betterbook.related_image_missing");
                                    error.setDisplay(true);
                                    return;
                                }
                                edit(d -> d.entry(entryId).orElseThrow().image = id.toString());
                            }));
        } else {
            stack =
                    BookItem.read(
                            new org.jsoup.nodes.Element("div").attr("data-stack", entry.stack),
                            Platform.getFrozenRegistry());
            var config =
                    (ConfiguratorGroup)
                            new ItemStackAccessor()
                                    .create(
                                            "gui.betterbook.item",
                                            () -> stack,
                                            next -> {
                                                String encoded =
                                                        BookItem.write(
                                                                next, Platform.getFrozenRegistry());
                                                if (encoded.equals(entry.stack)) return;
                                                applyingItem = true;
                                                try {
                                                    edit(
                                                            d ->
                                                                    d.entry(entryId)
                                                                                    .orElseThrow()
                                                                                    .stack =
                                                                            encoded);
                                                    stack = next.copy();
                                                    entry.stack = encoded;
                                                } finally {
                                                    applyingItem = false;
                                                }
                                            },
                                            true,
                                            null,
                                            null);
            config.setId("related-item-configurator");
            config.setCollapse(false);
            config.getLayout().widthPercent(100).minWidth(0);
            fields.addChild(config);
        }
        fields.addChild(
                button(
                        "related_remove_entry",
                        "related-remove-entry",
                        () ->
                                edit(
                                        d -> {
                                            d.entries.removeIf(e -> e.id.equals(entryId));
                                            entryId = "";
                                        })));
    }

    void replace(BookRelatedPages data) {
        if (block < 0) return;
        try {
            data.validate();
            session.transact(
                    () -> {
                        data.write(
                                session.document().blocks().get(block).element(),
                                Platform.getFrozenRegistry());
                        session.document().invalidate();
                    });
            refresh(false);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void edit(Consumer<BookRelatedPages> action) {
        if (block < 0) return;
        try {
            var data =
                    BookRelatedPages.read(
                            session.document().blocks().get(block).element(),
                            Platform.getFrozenRegistry());
            action.accept(data);
            replace(data);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void fail(Exception e) {
        error.setText(Component.translatable("gui.betterbook.related_error", e.getMessage()));
        error.setDisplay(true);
    }
}
