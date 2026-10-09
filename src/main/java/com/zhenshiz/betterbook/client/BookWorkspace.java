package com.zhenshiz.betterbook.client;

import static com.zhenshiz.betterbook.client.Widgets.*;

import static org.lwjgl.glfw.GLFW.*;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.accessors.ItemStackAccessor;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.codeeditor.language.Languages;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.utils.search.IResultHandler;
import com.viscript_lib.gui.components.DraggableUI;
import com.viscript_lib.gui.components.search.EntityTypeSearchBox;
import com.zhenshiz.betterbook.core.*;
import com.zhenshiz.betterbook.data.BookEntity;
import com.zhenshiz.betterbook.data.BookItem;

import dev.vfyjxf.taffy.style.*;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.jsoup.nodes.Element;

import java.util.*;
import java.util.function.Consumer;

/** 三栏书籍工作区：页面导航、正文工具栏及书籍信息。 */
public final class BookWorkspace implements AutoCloseable {
    private final BookProject project;
    private final BookEditor editor;
    private final BookSession session;
    private final View pagesView = new View("gui.betterbook.pages", Icons.FILE);
    private final View contentView = new View("gui.betterbook.content", Icons.FILE);
    private final View detailsView = new View("gui.betterbook.book_details", Icons.INFORMATION);
    private final UIElement detailsPanel = new UIElement(), colorPanel = new UIElement();
    private final ColorSelector colorSelector = new ColorSelector();
    private BookSession.Selection colorSelection;
    private boolean applyingColor;
    private final UIElement commandPanel = new UIElement();
    private final TextField commandField = new TextField();
    private final Toggle commandUnderline = new Toggle();
    private final Label commandError = new Label();
    private BookSession.Selection commandSelection;
    private long commandRevision = -1;
    private final UIElement itemPanel = new UIElement(), itemFields = new UIElement();
    private int itemBlock = -1;
    private String itemData = "";
    private ItemStack editedItem = ItemStack.EMPTY;
    private boolean applyingItem;
    private final UIElement structurePanel = new UIElement();
    private final StructureFileSearchBox structureFile = new StructureFileSearchBox();
    private final Toggle structureOrtho = new Toggle();
    private final Toggle structureProjectable = new Toggle();
    private final Toggle structureProjectionEditable = new Toggle();
    private final Label structureError = new Label();
    private int structureBlock = -1;
    private String structureData = "";
    private int structureRequest;
    private final RecipeInspector recipePanel;
    private final RelatedPagesInspector relatedPanel;
    private final UIElement entityPanel = new UIElement();
    private final EntityTypeSearchBox entityId = new EntityTypeSearchBox(EntityType.VILLAGER);
    private final TagField entityNbt = new TagField();
    private final Label entityError = new Label();
    private int entityBlock = -1;
    private String entityData = "";
    private final UIElement pages = new UIElement(), body = new UIElement(), bubble = row();
    private final UIElement linkBubble = row();
    private final TextField linkInput = new TextField();
    private BookSession.Selection linkSelection;
    private long linkRevision = -1;
    private boolean linkRequested, linkDismissed;
    private Boolean linkCloseOnEsc;
    private final ScrollerView pageScroll = new ScrollerView();
    private final UIElement languageList = new UIElement();
    private final Label status = new Label(), defaultLabel = new Label();
    private final TextField titleField = new TextField(), authorField = new TextField();
    private final Toggle followGuiScale = new Toggle();
    private final Toggle singlePage = new Toggle();
    private final Toggle allowPageTurning = new Toggle();
    private final Toggle lockedIcons = new Toggle();
    private final Tab visualTab = new Tab(), htmlTab = new Tab();
    private final List<UIElement> visualTools = new ArrayList<>();
    private final Map<String, Button> formats = new LinkedHashMap<>();
    private DraggableUI<String> sortable;
    private RichSurface surface;
    private UIElement paperFrame;
    private BookSourceEditor source;
    private boolean sourceMode, refreshing, rebuildingPages;
    private final Runnable listener = this::refresh;
    private String pageId = "", pageList = "", languageKey = "";
    private static BookSession.PageCopy clipboard;

    private record Action(String id, Component label, Runnable run, boolean enabled) {
        Action(String id, String key, Runnable run) {
            this(id, Component.translatable("gui.betterbook." + key), run, true);
        }
    }

    public BookWorkspace(BookProject project, BookEditor editor) {
        this.project = project;
        this.editor = editor;
        session = project.session;
        relatedPanel =
                new RelatedPagesInspector(
                        session,
                        () -> {
                            hideRelated();
                            if (surface != null) surface.focus();
                        });
        recipePanel =
                new RecipeInspector(
                        session,
                        () -> {
                            hideRelated();
                            hideRecipe();
                            if (surface != null) surface.focus();
                        });
    }

    public void mount() {
        editor.rootWindow.splitStyle(style -> style.percentage(78));
        editor.leftWindow.getParentWindow().splitStyle(style -> style.percentage(21));
        var stylesheet =
                StylesheetManager.INSTANCE.getStylesheetSafe(
                        ResourceLocation.parse("betterbook:lss/editor.lss"));
        for (var view : List.of(pagesView, contentView, detailsView)) {
            view.addLocalStylesheet(stylesheet);
            view.getLayout()
                    .flexDirection(FlexDirection.COLUMN)
                    .gapAll(4)
                    .minWidth(0)
                    .minHeight(0)
                    .paddingAll(4);
        }
        contentView.addLocalStylesheet(
                StylesheetManager.INSTANCE.getStylesheetSafe(
                        ResourceLocation.parse("betterbook:lss/book.lss")));
        pagesView.setId("book-pages-view");
        contentView.setId("book-content-view");
        detailsView.setId("book-details-view");
        detailsView.setDynamicName(
                () ->
                        Component.translatable(
                                relatedPanel.block() >= 0
                                        ? "gui.betterbook.related_pages"
                                        : recipePanel.block() >= 0
                                                ? "gui.betterbook.recipe"
                                                : structureBlock >= 0
                                                        ? "gui.betterbook.structure"
                                                        : entityBlock >= 0
                                                                ? "gui.betterbook.entity"
                                                                : itemBlock >= 0
                                                                        ? "gui.betterbook.item"
                                                                        : commandSelection != null
                                                                                ? "gui.betterbook.text_command"
                                                                                : colorSelection
                                                                                                == null
                                                                                        ? "gui.betterbook.book_details"
                                                                                        : "gui.betterbook.color"));
        pageScroll.getLayout().widthPercent(100).flex(1).minHeight(0);
        pages.getLayout().widthPercent(100).minWidth(0);
        pageScroll.addScrollViewChild(pages);
        pagesView.addChild(pageScroll);
        pagesView.addChild(
                button("page_add", "page-add", () -> pageOperation(() -> session.addPage(false))));
        buildDetails();
        buildColorPanel();
        buildCommandPanel();
        buildItemPanel();
        buildEntityPanel();
        buildStructurePanel();
        detailsView.addChild(recipePanel);
        detailsView.addChild(relatedPanel);
        buildToolbar();
        body.setId("book-body");
        body.getLayout()
                .widthPercent(100)
                .flex(1)
                .minHeight(0)
                .minWidth(0)
                .alignItems(AlignItems.CENTER);
        contentView.addChild(body);
        body.addEventListener(
                UIEvents.MOUSE_DOWN,
                e -> {
                    if (e.button == 0
                            && (colorSelection != null
                                    || commandSelection != null
                                    || itemBlock >= 0
                                    || entityBlock >= 0
                                    || structureBlock >= 0
                                    || recipePanel.block() >= 0
                                    || relatedPanel.block() >= 0)
                            && (e.target == body
                                    || e.target == paperFrame
                                    || e.target instanceof BookPaper)) {
                        var head = session.selection().head();
                        session.select(new BookSession.Selection(head, head));
                        selectionChanged();
                    }
                },
                true);
        bubble.setId("selection-toolbar");
        bubble.addClass("book-bubble");
        bubble.getLayout()
                .positionType(TaffyPosition.ABSOLUTE)
                .width(320)
                .maxWidthPercent(100)
                .heightAuto()
                .paddingAll(4)
                .paddingBottom(6)
                .gapAll(0)
                .flexWrap(FlexWrap.WRAP);
        addFormats(bubble);
        body.addChild(bubble);
        buildLinkBubble();
        body.addEventListener(UIEvents.LAYOUT_CHANGED, e -> selectionChanged());
        bubble.addEventListener(UIEvents.LAYOUT_CHANGED, e -> selectionChanged());
        status.setId("book-status");
        status.getLayout().height(14).widthPercent(100);
        contentView.addChild(status);
        editor.placeView(pagesView, () -> editor.leftWindow.getLeftTop());
        editor.placeView(contentView, () -> editor.centerWindow.getLeftTop());
        editor.placeView(detailsView, () -> editor.rightWindow.getRightTop());
        detailsView.getViewContainer().selectView(detailsView);
        session.listen(listener);
        refresh();
    }

    private void buildDetails() {
        detailsPanel.setId("book-details-panel");
        detailsPanel.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0).gapAll(4);
        var scroller = new ScrollerView();
        scroller.getLayout().widthPercent(100).flex(1).minHeight(0);
        var fields = new UIElement();
        fields.getLayout().widthPercent(100).minWidth(0).paddingAll(3).gapAll(6);
        fields.addChild(new Label().setText("gui.betterbook.title"));
        titleField.setId("book-title");
        titleField.getLayout().widthPercent(100).height(20).minWidth(0);
        titleField.registerValueListener(
                value -> {
                    if (!refreshing) session.renameBook(value, authorField.getValue());
                });
        fields.addChild(titleField);
        fields.addChild(new Label().setText("gui.betterbook.author"));
        authorField.setId("book-author");
        authorField.getLayout().widthPercent(100).height(20).minWidth(0);
        authorField.registerValueListener(
                value -> {
                    if (!refreshing) session.renameBook(titleField.getValue(), value);
                });
        fields.addChild(authorField);
        followGuiScale.setId("book-follow-gui-scale");
        followGuiScale.setText("gui.betterbook.follow_gui_scale");
        followGuiScale.getLayout().widthPercent(100).minWidth(0);
        followGuiScale.toggleLabel.textStyle(style -> style.textWrap(TextWrap.WRAP));
        followGuiScale.getStyle().tooltips(Component.translatable("gui.betterbook.follow_gui_scale_hint"));
        followGuiScale.setOnToggleChanged(
                value -> {
                    if (!refreshing) session.setFollowGuiScale(value);
                });
        fields.addChild(followGuiScale);
        addBookToggle(fields, singlePage, "single_page", "book-single-page", session::setSinglePage);
        addBookToggle(
                fields,
                allowPageTurning,
                "allow_page_turning",
                "book-allow-page-turning",
                session::setAllowPageTurning);
        addBookToggle(
                fields, lockedIcons, "locked_icons", "book-locked-icons", session::setLockedIcons);
        fields.addChild(new Label().setText("gui.betterbook.book_language"));
        languageList.setId("book-languages");
        languageList.getLayout().widthPercent(100).gapAll(3);
        fields.addChild(languageList);
        fields.addChild(button("language_add", "language-add", this::addLanguage));
        fields.addChild(
                button(
                        "language_make_default",
                        "language-default",
                        () -> pageOperation(session::useLanguageAsDefault)));
        defaultLabel.getLayout().widthPercent(100).heightAuto();
        fields.addChild(defaultLabel);
        scroller.addScrollViewChild(fields);
        detailsPanel.addChild(scroller);
        detailsPanel.addChild(button("preview", "book-preview", this::preview));
        detailsView.addChild(detailsPanel);
    }

    private void addBookToggle(
            UIElement parent, Toggle toggle, String key, String id, Consumer<Boolean> change) {
        toggle.setId(id);
        toggle.setText("gui.betterbook." + key);
        toggle.getLayout().widthPercent(100).minWidth(0).heightAuto().minHeight(14).flexShrink(0);
        toggle.toggleButton.setId(id + "-toggle");
        toggle.toggleButton.getLayout().width(12).height(12).flexShrink(0);
        toggle.toggleLabel.getLayout().minWidth(0).heightAuto();
        toggle.toggleLabel.textStyle(
                style -> style.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
        toggle.getStyle().tooltips(Component.translatable("gui.betterbook." + key + "_hint"));
        toggle.setOnToggleChanged(
                value -> {
                    if (!refreshing) change.accept(value);
                });
        parent.addChild(toggle);
    }

    private void buildColorPanel() {
        colorPanel.setId("text-color-panel");
        colorPanel.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0).gapAll(4);
        var scroll = new ScrollerView();
        scroll.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0);
        var fields = new UIElement();
        fields.getLayout().widthPercent(100).minWidth(0).paddingAll(3).gapAll(6);
        colorSelector.setId("text-color-picker");
        colorSelector.getLayout().widthPercent(100).minWidth(0);
        colorSelector.alphaSlider.setDisplay(false);
        colorSelector.hexConfigurator.textField.setId("text-color-hex");
        colorSelector.hexConfigurator.setTextValidator(value -> value.matches("#[0-9a-fA-F]{8}"));
        colorSelector.setOnColorChangeListener(
                value -> {
                    int opaque = value | 0xff000000;
                    if (opaque != value) colorSelector.setColor(opaque, false);
                    applyTextColor(opaque & 0xffffff);
                });
        fields.addChild(colorSelector);
        fields.addChild(button("color_reset", "text-color-reset", () -> applyTextColor(null)));
        scroll.addScrollViewChild(fields);
        colorPanel.addChild(scroll);
        colorPanel.setDisplay(false);
        detailsView.addChild(colorPanel);
    }

    private void buildCommandPanel() {
        commandPanel.setId("text-command-panel");
        commandPanel.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0);
        var scroll = new ScrollerView();
        scroll.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0);
        var fields = new UIElement();
        fields.getLayout().widthPercent(100).minWidth(0).paddingAll(3).gapAll(6);
        fields.addChild(new Label().setText("gui.betterbook.command_content"));
        commandField.setId("text-command-input");
        commandField.getLayout().widthPercent(100).height(20).minWidth(0);
        fields.addChild(commandField);
        commandUnderline.setId("text-command-underline");
        commandUnderline.toggleButton.setId("text-command-underline-toggle");
        commandUnderline.toggleLabel.setText("gui.betterbook.command_underline");
        commandUnderline.getLayout().widthPercent(100).minWidth(0).height(18);
        fields.addChild(commandUnderline);
        var hint = new Label().setText("gui.betterbook.command_hint");
        hint.getLayout().widthPercent(100).minWidth(0);
        hint.textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        fields.addChild(hint);
        commandError.setId("text-command-error");
        commandError.getLayout().widthPercent(100).minWidth(0);
        commandError.textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        commandError.setDisplay(false);
        fields.addChild(commandError);
        fields.addChild(button("apply", "text-command-apply", this::applyTextCommand));
        fields.addChild(
                button(
                        "command_remove",
                        "text-command-remove",
                        () -> {
                            if (commandSelection != null
                                    && commandSelection.equals(session.selection())) {
                                session.setTextCommand(null);
                                refreshCommandFields();
                            }
                        }));
        scroll.addScrollViewChild(fields);
        commandPanel.addChild(scroll);
        commandPanel.setDisplay(false);
        detailsView.addChild(commandPanel);
    }

    private void showTextCommand() {
        if (sourceMode || session.selection().empty()) return;
        hideRelated();
        hideRecipe();
        hideEntity();
        hideStructure();
        hideItem();
        hideTextColor();
        commandSelection = session.selection();
        refreshCommandFields();
        detailsPanel.setDisplay(false);
        commandPanel.setDisplay(true);
        var container = detailsView.getViewContainer();
        if (container != null) {
            container.expand();
            container.selectView(detailsView);
        }
        selectionChanged();
    }

    private void refreshCommandFields() {
        var command = session.selectedCommand();
        commandField.setValue(command.map(TextCommand::command).orElse(""), false);
        commandUnderline.setValue(command.map(TextCommand::underline).orElse(true), false);
        commandError.setDisplay(false);
        commandRevision = session.revision();
    }

    private void hideTextCommand() {
        if (commandSelection == null) return;
        commandSelection = null;
        commandPanel.setDisplay(false);
        detailsPanel.setDisplay(true);
    }

    private void applyTextCommand() {
        if (commandSelection == null || !commandSelection.equals(session.selection())) return;
        try {
            var command = TextCommand.create(commandField.getValue());
            var old = session.selectedCommand();
            boolean sameAction = old.isPresent() && old.get().command().equals(command.command());
            command =
                    new TextCommand(
                            sameAction ? old.get().id() : command.id(),
                            command.command(),
                            command.permission(),
                            commandUnderline.isOn());
            if (old.isEmpty() || !old.get().equals(command)) session.setTextCommand(command);
            refreshCommandFields();
        } catch (IllegalArgumentException e) {
            commandError.setText("gui.betterbook.command_invalid");
            commandError.setDisplay(true);
        }
    }

    private int selectionColor() {
        var start = session.selection().start();
        var end = session.selection().end();
        var blocks = session.document().blocks();
        for (int i = start.block(); i <= end.block(); i++) {
            var block = blocks.get(i);
            if (block.atom() || block.code()) continue;
            for (var run :
                    RichDocument.slice(
                            block.runs(),
                            i == start.block() ? start.offset() : 0,
                            i == end.block() ? end.offset() : block.length())) {
                Integer color = null;
                for (var mark : run.marks()) {
                    if (!mark.id().equals("betterbook:color")) continue;
                    var value = TextColor.read(mark.attributes().getOrDefault("style", ""));
                    if (value != null) color = value;
                }
                return color == null ? surface.getTextStyle().textColor() : color | 0xff000000;
            }
        }
        return surface.getTextStyle().textColor();
    }

    private void showTextColor() {
        if (sourceMode || session.selection().empty()) return;
        hideRelated();
        hideRecipe();
        hideEntity();
        hideStructure();
        hideItem();
        hideTextCommand();
        colorSelection = session.selection();
        colorSelector.setColor(selectionColor(), false);
        detailsPanel.setDisplay(false);
        colorPanel.setDisplay(true);
        var container = detailsView.getViewContainer();
        if (container != null) {
            container.expand();
            container.selectView(detailsView);
        }
        selectionChanged();
    }

    private void hideTextColor() {
        if (colorSelection == null) return;
        colorSelection = null;
        colorPanel.setDisplay(false);
        detailsPanel.setDisplay(true);
    }

    private void applyTextColor(Integer color) {
        if (colorSelection == null || applyingColor || !colorSelection.equals(session.selection()))
            return;
        applyingColor = true;
        try {
            session.setTextColor(color);
        } finally {
            applyingColor = false;
        }
        if (color == null) colorSelector.setColor(selectionColor(), false);
    }

    private void buildItemPanel() {
        itemPanel.setId("item-panel");
        itemPanel.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0).gapAll(4);
        var scroller = new ScrollerView();
        scroller.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0);
        itemFields.getLayout().widthPercent(100).minWidth(0).paddingAll(3);
        scroller.addScrollViewChild(itemFields);
        itemPanel.addChild(scroller);
        itemPanel.addChild(
                button(
                        "item_remove",
                        "item-remove",
                        () -> {
                            if (itemBlock < 0) return;
                            session.delete(1);
                            if (surface != null) surface.focus();
                        }));
        itemPanel.setDisplay(false);
        detailsView.addChild(itemPanel);
    }

    private void showItem(int index) {
        if (sourceMode
                || index < 0
                || index >= session.document().blocks().size()
                || !session.document().blocks().get(index).element().is("div[data-type=item]"))
            return;
        hideRelated();
        hideRecipe();
        hideEntity();
        hideStructure();
        hideTextColor();
        hideTextCommand();
        itemBlock = index;
        session.select(
                new BookSession.Selection(
                        new BookSession.Position(index, 0), new BookSession.Position(index, 1)));
        refreshItemFields();
        detailsPanel.setDisplay(false);
        itemPanel.setDisplay(true);
        var container = detailsView.getViewContainer();
        if (container != null) {
            container.expand();
            container.selectView(detailsView);
        }
        selectionChanged();
    }

    private void refreshItemFields() {
        var element = session.document().blocks().get(itemBlock).element();
        itemData = element.attr("data-stack");
        editedItem = BookItem.read(element, Platform.getFrozenRegistry());
        itemFields.clearAllChildren();
        var configurator =
                (ConfiguratorGroup)
                        new ItemStackAccessor()
                                .create(
                                        "gui.betterbook.item",
                                        () -> editedItem,
                                        this::applyItem,
                                        true,
                                        null,
                                        null);
        configurator.setId("item-configurator");
        configurator.setCollapse(false);
        configurator.getLayout().widthPercent(100).minWidth(0);
        itemFields.addChild(configurator);
    }

    private void applyItem(ItemStack value) {
        if (itemBlock < 0 || applyingItem) return;
        String data = BookItem.write(value, Platform.getFrozenRegistry());
        if (data.equals(itemData)) return;
        applyingItem = true;
        try {
            session.transact(
                    () -> {
                        session.document()
                                .blocks()
                                .get(itemBlock)
                                .element()
                                .attr("data-stack", data);
                        session.document().invalidate();
                    });
            editedItem = value.copy();
            itemData = data;
        } finally {
            applyingItem = false;
        }
    }

    private void hideItem() {
        if (itemBlock < 0) return;
        itemBlock = -1;
        editedItem = ItemStack.EMPTY;
        itemFields.clearAllChildren();
        itemPanel.setDisplay(false);
        detailsPanel.setDisplay(true);
    }

    private void showRecipe(int index, String part) {
        if (sourceMode
                || index < 0
                || index >= session.document().blocks().size()
                || !session.document().blocks().get(index).element().is("div[data-type=recipe]"))
            return;
        hideRelated();
        hideEntity();
        hideStructure();
        hideItem();
        hideTextColor();
        hideTextCommand();
        session.select(
                new BookSession.Selection(
                        new BookSession.Position(index, 0), new BookSession.Position(index, 1)));
        recipePanel.show(index, part);
        detailsPanel.setDisplay(false);
        var container = detailsView.getViewContainer();
        if (container != null) {
            container.expand();
            container.selectView(detailsView);
        }
        selectionChanged();
    }

    private void hideRecipe() {
        if (recipePanel.block() < 0) return;
        recipePanel.hide();
        detailsPanel.setDisplay(true);
    }

    private void showRelated(int index, String entry) {
        if (sourceMode
                || index < 0
                || index >= session.document().blocks().size()
                || !session.document()
                        .blocks()
                        .get(index)
                        .element()
                        .is("div[data-type=related-pages]")) return;
        hideRecipe();
        hideEntity();
        hideStructure();
        hideItem();
        hideTextColor();
        hideTextCommand();
        session.select(
                new BookSession.Selection(
                        new BookSession.Position(index, 0), new BookSession.Position(index, 1)));
        relatedPanel.show(index, entry);
        detailsPanel.setDisplay(false);
        var container = detailsView.getViewContainer();
        if (container != null) {
            container.expand();
            container.selectView(detailsView);
        }
        selectionChanged();
    }

    private void hideRelated() {
        if (relatedPanel.block() < 0) return;
        relatedPanel.hide();
        detailsPanel.setDisplay(true);
    }

    private void buildStructurePanel() {
        structurePanel.setId("structure-panel");
        structurePanel.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0).gapAll(6);
        structureFile.setId("structure-file");
        structureFile.textField.setId("structure-file-input");
        structureFile.getLayout().widthPercent(100).height(20).minWidth(0);
        structureOrtho.setId("structure-ortho");
        structureOrtho.toggleButton.setId("structure-ortho-toggle");
        structureOrtho.setText("gui.betterbook.structure_ortho");
        structureProjectable.setId("structure-projectable");
        structureProjectable.toggleButton.setId("structure-projectable-toggle");
        structureProjectable.setText("gui.betterbook.structure_projectable");
        structureProjectionEditable.setId("structure-projection-editable");
        structureProjectionEditable.toggleButton.setId("structure-projection-editable-toggle");
        structureProjectionEditable.setText("gui.betterbook.structure_projection_editable");
        structureProjectionEditable
                .getStyle()
                .tooltips(
                        Component.translatable("gui.betterbook.structure_projection_editable_tip"));
        structureError.setId("structure-error");
        structureError.setDisplay(false);
        structureError.getLayout().widthPercent(100).minWidth(0);
        structureError.textStyle(t -> t.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        structurePanel.addChildren(
                new Label().setText("gui.betterbook.structure_file"),
                structureFile,
                structureOrtho,
                structureProjectable,
                structureProjectionEditable,
                structureError,
                button("apply", "structure-apply", this::applyStructure),
                button(
                        "entity_reset_camera",
                        "structure-reset-camera",
                        () -> surface.resetStructureCamera()),
                button(
                        "structure_remove",
                        "structure-remove",
                        () -> {
                            if (structureBlock >= 0) {
                                session.delete(1);
                                surface.focus();
                            }
                        }));
        structurePanel.setDisplay(false);
        detailsView.addChild(structurePanel);
    }

    private void showStructure(int index) {
        if (sourceMode
                || index < 0
                || index >= session.document().blocks().size()
                || !session.document().blocks().get(index).element().is("div[data-type=structure]"))
            return;
        hideRelated();
        hideRecipe();
        hideItem();
        hideEntity();
        hideTextColor();
        hideTextCommand();
        if (structureBlock != index) {
            structureBlock = index;
            refreshStructureFields();
        }
        session.select(
                new BookSession.Selection(
                        new BookSession.Position(index, 0), new BookSession.Position(index, 1)));
        detailsPanel.setDisplay(false);
        structurePanel.setDisplay(true);
        var container = detailsView.getViewContainer();
        if (container != null) {
            container.expand();
            container.selectView(detailsView);
        }
        selectionChanged();
    }

    private void refreshStructureFields() {
        var element = session.document().blocks().get(structureBlock).element();
        structureData = element.outerHtml();
        structureFile.reference(element.attr("data-structure-file"));
        structureOrtho.setValue(Boolean.parseBoolean(element.attr("data-structure-ortho")), false);
        structureProjectable.setValue(
                Boolean.parseBoolean(element.attr("data-structure-projectable")), false);
        structureProjectionEditable.setValue(
                Boolean.parseBoolean(element.attr("data-structure-projection-editable")), false);
        structureError.setDisplay(false);
    }

    private void applyStructure() {
        if (structureBlock < 0) return;
        int request = ++structureRequest, block = structureBlock;
        var document = session.document();
        var element = document.blocks().get(block).element();
        String before = element.outerHtml(), reference = structureFile.reference();
        boolean ortho = structureOrtho.isOn();
        boolean projectable = structureProjectable.isOn();
        boolean projectionEditable = structureProjectionEditable.isOn();
        structureError.setText("gui.betterbook.structure_loading");
        structureError.setDisplay(true);
        StructureFiles.load(reference)
                .whenCompleteAsync(
                        (tag, error) -> {
                            if (request != structureRequest
                                    || sourceMode
                                    || structureBlock != block
                                    || structurePanel.getModularUI() == null
                                    || session.document() != document
                                    || block >= document.blocks().size()
                                    || document.blocks().get(block).element() != element
                                    || !before.equals(element.outerHtml())
                                    || !reference.equals(structureFile.reference())
                                    || ortho != structureOrtho.isOn()
                                    || projectable != structureProjectable.isOn()
                                    || projectionEditable != structureProjectionEditable.isOn())
                                return;
                            if (error != null) {
                                structureError.setText(
                                        Component.translatable(
                                                "gui.betterbook.structure_failed",
                                                error.getMessage()));
                                structureError.setDisplay(true);
                                return;
                            }
                            session.transact(
                                    () -> {
                                        var current =
                                                session.document().blocks().get(block).element();
                                        current.attr("data-structure-file", reference)
                                                .attr(
                                                        "data-structure-ortho",
                                                        Boolean.toString(ortho))
                                                .attr(
                                                        "data-structure-projectable",
                                                        Boolean.toString(projectable))
                                                .attr(
                                                        "data-structure-projection-editable",
                                                        Boolean.toString(projectionEditable));
                                        session.document().invalidate();
                                    });
                            refreshStructureFields();
                        },
                        Minecraft.getInstance());
    }

    private void hideStructure() {
        structureRequest++;
        if (structureBlock < 0) return;
        structureBlock = -1;
        structurePanel.setDisplay(false);
        detailsPanel.setDisplay(true);
    }

    private void buildEntityPanel() {
        entityPanel.setId("entity-panel");
        entityPanel.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0);
        var scroller = new ScrollerView();
        scroller.getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0);
        var fields = new UIElement();
        fields.getLayout().widthPercent(100).minWidth(0).paddingAll(3).gapAll(6);
        fields.addChild(new Label().setText("gui.betterbook.entity_id"));
        entityId.setId("entity-id");
        entityId.textField.setId("entity-id-input");
        entityId.setEntityTypeFilter(type -> type != EntityType.PLAYER);
        entityId.getSearchStyle().closeAfterSelect(true);
        entityId.getLayout().widthPercent(100).height(20).minWidth(0);
        fields.addChild(entityId);
        fields.addChild(new Label().setText("gui.betterbook.entity_nbt"));
        entityNbt.setId("entity-nbt");
        entityNbt.textField.setId("entity-nbt-input");
        entityNbt.editButton.setId("entity-nbt-edit");
        entityNbt.setTagValidator(tag -> tag instanceof net.minecraft.nbt.CompoundTag);
        entityNbt.getLayout().widthPercent(100).minWidth(0);
        fields.addChild(entityNbt);
        entityError.setId("entity-error");
        entityError.getLayout().widthPercent(100).minWidth(0);
        entityError.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        entityError.setDisplay(false);
        fields.addChild(entityError);
        fields.addChild(button("apply", "entity-apply", this::applyEntity));
        fields.addChild(
                button(
                        "entity_reset_camera",
                        "entity-reset-camera",
                        () -> surface.resetEntityCamera()));
        fields.addChild(
                button(
                        "entity_remove",
                        "entity-remove",
                        () -> {
                            if (entityBlock < 0) return;
                            session.delete(1);
                            surface.focus();
                        }));
        scroller.addScrollViewChild(fields);
        entityPanel.addChild(scroller);
        entityPanel.setDisplay(false);
        detailsView.addChild(entityPanel);
    }

    private void showEntity(int index) {
        hideRelated();
        hideRecipe();
        hideStructure();
        if (sourceMode
                || index < 0
                || index >= session.document().blocks().size()
                || !session.document().blocks().get(index).element().is("div[data-type=entity]"))
            return;
        hideItem();
        hideTextColor();
        hideTextCommand();
        if (entityBlock != index) {
            entityBlock = index;
            refreshEntityFields();
        }
        session.select(
                new BookSession.Selection(
                        new BookSession.Position(index, 0), new BookSession.Position(index, 1)));
        detailsPanel.setDisplay(false);
        entityPanel.setDisplay(true);
        var container = detailsView.getViewContainer();
        if (container != null) {
            container.expand();
            container.selectView(detailsView);
        }
        selectionChanged();
    }

    private void refreshEntityFields() {
        var element = session.document().blocks().get(entityBlock).element();
        entityData = element.outerHtml();
        var id = ResourceLocation.tryParse(element.attr("data-entity-id"));
        entityId.setValue(
                id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null),
                false);
        String nbt = element.attr("data-entity-nbt");
        try {
            entityNbt.setValue(
                    nbt.isBlank()
                            ? new net.minecraft.nbt.CompoundTag()
                            : net.minecraft.nbt.TagParser.parseTag(nbt),
                    false);
            entityNbt.textField.setValue(nbt.isBlank() ? "{}" : nbt, false);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            entityNbt.textField.setValue(nbt, false);
        }
        entityError.setDisplay(false);
    }

    private void applyEntity() {
        if (entityBlock < 0) return;
        try {
            var config =
                    BookEntity.parse(
                            entityId.getSelectedEntityTypeIdString(),
                            entityNbt.textField.getRawText());
            var world = new com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld();
            var entity = config.create(world);
            if (Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity) == null)
                throw new IllegalArgumentException("Missing entity renderer");
            var replacement = session.document().blocks().get(entityBlock).element().clone();
            config.writeTo(replacement);
            if (!replacement.outerHtml().equals(entityData))
                session.transact(
                        () -> {
                            config.writeTo(session.document().blocks().get(entityBlock).element());
                            session.document().invalidate();
                        });
            refreshEntityFields();
        } catch (RuntimeException e) {
            entityError.setText(
                    Component.translatable(
                            "gui.betterbook.entity_invalid",
                            e.getMessage() == null
                                    ? e.getClass().getSimpleName()
                                    : e.getMessage()));
            entityError.setDisplay(true);
        }
    }

    private void hideEntity() {
        if (entityBlock < 0) return;
        entityBlock = -1;
        entityPanel.setDisplay(false);
        detailsPanel.setDisplay(true);
    }

    private void buildToolbar() {
        var toolbar = row();
        toolbar.setId("book-toolbar");
        toolbar.addClass("book-toolbar");
        toolbar.getLayout()
                .paddingAll(4)
                .paddingBottom(6)
                .gapAll(0)
                .alignItems(AlignItems.CENTER)
                .flexWrap(FlexWrap.WRAP);
        for (var tab : List.of(visualTab, htmlTab)) {
            tab.addClass("book-mode");
            tab.text.addClass("book-mode-label");
            tab.getLayout().height(26).paddingHorizontal(7).flexShrink(0);
        }
        visualTab.setId("mode-visual");
        visualTab.setText(Component.translatable("gui.betterbook.visual"));
        visualTab.addEventListener(
                UIEvents.MOUSE_DOWN,
                e -> {
                    if (e.button == 0) mode(false);
                });
        htmlTab.setId("mode-html");
        htmlTab.setText(Component.translatable("gui.betterbook.html"));
        htmlTab.addEventListener(
                UIEvents.MOUSE_DOWN,
                e -> {
                    if (e.button == 0) mode(true);
                });
        toolbar.addChildren(visualTab, htmlTab);
        visualTools.add(divider(toolbar));
        var heading = tool("heading", "heading", "heading-menu", () -> headings());
        toolbar.addChild(heading);
        visualTools.add(heading);
        addTool(toolbar, "info", "admonition", "insert-admonition", this::admonitions);
        addTool(toolbar, "bullet", "bullet_list", "list-bullet", () -> command("bullet_list"));
        addTool(toolbar, "ordered", "ordered_list", "list-ordered", () -> command("ordered_list"));
        addTool(toolbar, "quote", "quote", "insert-quote", () -> insert("insert_quote"));
        addTool(toolbar, "rule", "rule", "insert-rule", () -> insert("insert_rule"));
        addTool(toolbar, "link", "insert_link", "insert-link", this::linkDialog);
        addTool(
                toolbar,
                "code_block",
                "code_block",
                "insert-code",
                () -> insert("insert_code_block"));
        addTool(
                toolbar,
                "latex",
                "latex",
                "insert-latex",
                () -> BookLatexEditor.insertOrEdit(contentView, session));
        addTool(toolbar, "mermaid", "mermaid", "insert-mermaid", () -> insert("insert_mermaid"));
        addTool(toolbar, "task", "task_list", "list-task", () -> command("task_list"));
        addTool(toolbar, "image", "image", "insert-image", this::imageDialog);
        addTool(toolbar, "table", "table", "insert-table", this::tableDialog);
        addTool(toolbar, "steps", "steps", "insert-steps", () -> insert("insert_steps"));
        var itemTool =
                tool(
                        new ItemStackTexture(Items.CHEST),
                        "insert_item",
                        "insert-item",
                        () -> {
                            command("insert_item");
                            showItem(session.selection().head().block());
                        });
        toolbar.addChild(itemTool);
        visualTools.add(itemTool);
        var entityTool =
                tool(
                        new ItemStackTexture(Items.VILLAGER_SPAWN_EGG),
                        "insert_entity",
                        "insert-entity",
                        () -> {
                            command("insert_entity");
                            showEntity(session.selection().head().block());
                        });
        toolbar.addChild(entityTool);
        visualTools.add(entityTool);
        var relatedTool =
                tool(
                        new ItemStackTexture(Items.KNOWLEDGE_BOOK),
                        "insert_related_pages",
                        "insert-related-pages",
                        () -> {
                            command("insert_related_pages");
                            showRelated(session.selection().head().block(), "");
                        });
        toolbar.addChild(relatedTool);
        visualTools.add(relatedTool);
        var recipeTool =
                tool(
                        new ItemStackTexture(Items.CRAFTING_TABLE),
                        "insert_recipe",
                        "insert-recipe",
                        () -> {
                            command("insert_recipe");
                            showRecipe(session.selection().head().block(), "");
                        });
        toolbar.addChild(recipeTool);
        visualTools.add(recipeTool);
        var structureTool =
                tool(
                        new ItemStackTexture(Items.STRUCTURE_BLOCK),
                        "insert_structure",
                        "insert-structure",
                        () -> {
                            command("insert_structure");
                            showStructure(session.selection().head().block());
                        });
        toolbar.addChild(structureTool);
        visualTools.add(structureTool);
        divider(toolbar);
        toolbar.addChildren(
                tool("undo", "undo", "book-undo", session::undo),
                tool("redo", "redo", "book-redo", session::redo));
        contentView.addChild(toolbar);
    }

    private void addTool(UIElement parent, String icon, String key, String id, Runnable run) {
        var b = tool(icon, key, id, run);
        visualTools.add(b);
        parent.addChild(b);
    }

    private UIElement divider(UIElement parent) {
        var line = new UIElement().addClass("book-divider");
        line.getLayout().width(1).height(14).marginHorizontal(4).flexShrink(0);
        parent.addChild(line);
        return line;
    }

    private void command(String id) {
        if (!sourceMode) {
            project.extensions.execute("betterbook:" + id, session);
            if (surface != null) surface.focus();
            selectionChanged();
        }
    }

    private void insert(String id) {
        command(id);
        if (id.equals("insert_image")) properties();
    }

    private void tableDialog() {
        var d = dialog("table");
        var row = row();
        var rows = new TextField().setValue("2");
        var columns = new TextField().setValue("2");
        rows.setId("table-rows");
        columns.setId("table-columns");
        rows.getLayout().width(0).flexGrow(1).flexShrink(1).minWidth(0);
        columns.getLayout().width(0).flexGrow(1).flexShrink(1).minWidth(0);
        row.addChildren(
                rows,
                new Label()
                        .setText("gui.betterbook.rows")
                        .layout(l -> l.width(32).height(20).flexShrink(0)),
                columns,
                new Label()
                        .setText("gui.betterbook.columns")
                        .layout(l -> l.width(46).height(20).flexShrink(0)));
        d.addContent(row);
        var error = new Label().setText("");
        error.getLayout().widthPercent(100).minWidth(0);
        error.textStyle(style -> style.adaptiveWidth(false).textWrap(TextWrap.WRAP));
        d.addContent(error);
        d.buttonContainer.addChildren(
                button(
                        "insert",
                        "table-insert",
                        () -> {
                            try {
                                session.insertTable(
                                        Integer.parseInt(rows.getValue()),
                                        Integer.parseInt(columns.getValue()));
                                d.close();
                                surface.focus();
                            } catch (IllegalArgumentException e) {
                                error.setText("gui.betterbook.table_size_error");
                            }
                        }),
                button("cancel", "table-cancel", d::close));
        d.show(editor.getModularUI());
    }

    private void imageDialog() {
        var d = dialog("image");
        var options = row();
        final boolean[] resource = {false};
        var external = new TextField();
        external.setId("image-url");
        external.setValue("https://");
        external.getLayout().widthPercent(100).minWidth(0).height(20);
        var paths =
                Minecraft.getInstance()
                        .getResourceManager()
                        .listResources(
                                "textures",
                                location ->
                                        location.getPath().endsWith(".png")
                                                || location.getPath().endsWith(".jpg")
                                                || location.getPath().endsWith(".jpeg"))
                        .keySet()
                        .stream()
                        .map(Object::toString)
                        .sorted()
                        .toList();
        var assets =
                new SearchComponent<String>(
                        new SearchComponent.ISearchUI.Empty<>() {
                            @Override
                            public void search(String word, IResultHandler<String> results) {
                                String query = word.trim().toLowerCase(Locale.ROOT);
                                for (var path : paths)
                                    if (path.contains(query)) results.accept(path);
                            }
                        });
        assets.setCandidateUIProvider(
                path -> {
                    var label = new Label().setText(path, false);
                    label.getLayout().widthPercent(100).height(18).minWidth(0);
                    label.textStyle(
                            style ->
                                    style.adaptiveWidth(false)
                                            .textWrap(TextWrap.HOVER_ROLL)
                                            .textAlignHorizontal(Horizontal.LEFT));
                    label.setOverflowVisible(false);
                    return label;
                });
        assets.textField.addEventListener(
                UIEvents.BLUR, event -> assets.setSelected(assets.textField.getValue(), false));
        assets.setId("image-resource");
        assets.textField.setId("image-resource-path");
        assets.getLayout().widthPercent(100).minWidth(0).height(20);
        assets.searchStyle(
                style -> style.maxItemCount(6).scrollerViewHeight(100).closeAfterSelect(true));
        assets.setDisplay(false);
        var hint = new Label().setText("gui.betterbook.image_external_hint");
        hint.getLayout().widthPercent(100).minWidth(0);
        hint.textStyle(
                style ->
                        style.adaptiveWidth(false)
                                .adaptiveHeight(true)
                                .textWrap(TextWrap.WRAP)
                                .textAlignHorizontal(Horizontal.CENTER));
        options.addChildren(
                button(
                        "image_external",
                        "image-external-option",
                        () -> {
                            resource[0] = false;
                            external.setDisplay(true);
                            assets.setDisplay(false);
                            hint.setText("gui.betterbook.image_external_hint");
                        }),
                button(
                        "image_resource",
                        "image-resource-option",
                        () -> {
                            resource[0] = true;
                            external.setDisplay(false);
                            assets.setDisplay(true);
                            hint.setText("gui.betterbook.image_resource_hint");
                        }));
        d.addContent(options);
        d.addContent(hint);
        d.addContent(external);
        d.addContent(assets);
        var error = new Label().setText("");
        error.getLayout().widthPercent(100).minWidth(0);
        error.textStyle(style -> style.adaptiveWidth(false).textWrap(TextWrap.WRAP));
        d.addContent(error);
        d.buttonContainer.addChildren(
                button(
                        "insert",
                        "image-insert",
                        () -> {
                            String src =
                                    (resource[0]
                                                    ? assets.textField.getValue()
                                                    : external.getValue())
                                            .trim();
                            boolean valid;
                            if (resource[0]) {
                                var id = ResourceLocation.tryParse(src);
                                valid =
                                        id != null
                                                && Minecraft.getInstance()
                                                        .getResourceManager()
                                                        .getResource(id)
                                                        .isPresent();
                            } else {
                                try {
                                    var uri = java.net.URI.create(src);
                                    valid =
                                            Set.of("http", "https").contains(uri.getScheme())
                                                    && uri.getHost() != null;
                                } catch (IllegalArgumentException e) {
                                    valid = false;
                                }
                            }
                            if (!valid) {
                                error.setText("gui.betterbook.image_source_error");
                                return;
                            }
                            session.insertHtml(new Element("img").attr("src", src).outerHtml());
                            d.close();
                            surface.focus();
                        }),
                button("cancel", "image-cancel", d::close));
        d.show(editor.getModularUI());
    }

    private void pageOperation(Runnable action) {
        try {
            session.applySources();
            action.run();
        } catch (Exception e) {
            editor.error(e);
        }
    }

    private Dialog dialog(String key) {
        var d = new Dialog().setTitle("gui.betterbook." + key);
        d.overlay.getLayout().width(270).maxWidthPercent(94);
        return d;
    }

    private void addLanguage() {
        var d = dialog("language_add");
        var hint = new Label().setText("gui.betterbook.language_code");
        hint.setId("language-hint");
        hint.getLayout().widthPercent(100).minWidth(0);
        hint.textStyle(
                style ->
                        style.adaptiveWidth(false)
                                .adaptiveHeight(true)
                                .textWrap(TextWrap.WRAP)
                                .textAlignHorizontal(Horizontal.CENTER));
        hint.setOverflowVisible(false);
        d.addContent(hint);
        var languages = new TreeMap<String, String>();
        Minecraft.getInstance()
                .getLanguageManager()
                .getLanguages()
                .forEach(
                        (code, info) -> {
                            if (!session.book().languages().contains(code))
                                languages.put(code, info.toComponent().getString());
                        });
        var input =
                new SearchComponent<String>(
                        new SearchComponent.ISearchUI.Empty<>() {
                            @Override
                            public void search(String word, IResultHandler<String> find) {
                                String query =
                                        word.strip().replace('-', '_').toLowerCase(Locale.ROOT);
                                languages.forEach(
                                        (code, name) -> {
                                            if (code.contains(query)
                                                    || name.toLowerCase(Locale.ROOT)
                                                            .contains(query)) find.accept(code);
                                        });
                            }
                        });
        input.setId("language-code");
        input.textField.setId("language-query");
        input.dialog.setId("language-suggestions");
        input.getLayout().widthPercent(100).height(20).minWidth(0);
        input.searchStyle(
                style -> style.maxItemCount(5).scrollerViewHeight(100).closeAfterSelect(true));
        input.setCandidateUIProvider(
                code -> {
                    var label =
                            new Label()
                                    .setText(
                                            code + " · " + languages.getOrDefault(code, ""), false);
                    label.setId("language-candidate-" + code);
                    label.getLayout().widthPercent(100).height(18).minWidth(0);
                    label.textStyle(
                            style ->
                                    style.adaptiveWidth(false)
                                            .textWrap(TextWrap.HOVER_ROLL)
                                            .textAlignHorizontal(Horizontal.LEFT)
                                            .textAlignVertical(Vertical.CENTER));
                    label.setOverflowVisible(false);
                    return label;
                });
        String preferred = session.language().equals("zh_cn") ? "en_us" : "zh_cn";
        input.setSelected(
                languages.containsKey(preferred)
                        ? preferred
                        : languages.isEmpty() ? null : languages.firstKey(),
                false);
        d.addContent(input);
        d.addButton(
                button(
                                "apply",
                                "language-add-apply",
                                () -> {
                                    try {
                                        session.addLanguage(input.getValue());
                                        d.close();
                                    } catch (Exception failure) {
                                        editor.error(failure);
                                    }
                                })
                        .setActive(!languages.isEmpty()));
        d.addButton(button("cancel", "language-add-cancel", d::close));
        d.show(editor.getModularUI());
    }

    private void addFormats(UIElement parent) {
        for (String id :
                List.of("bold", "italic", "strike", "superscript", "subscript", "hidden", "code")) {
            var b = tool(id, id, "bubble-" + id, () -> command(id));
            formats.put(id, b);
            parent.addChild(b);
        }
        for (String align : List.of("left", "center", "right"))
            parent.addChild(
                    tool(align, align, "bubble-align-" + align, () -> command("align_" + align)));
        parent.addChild(tool("link", "link", "bubble-link", this::link));
        parent.addChild(tool(Icons.PALETTE, "color", "bubble-color", this::showTextColor));
        parent.addChild(tool(Icons.PLAY, "text_command", "bubble-command", this::showTextCommand));
    }

    private void popup(UIElement origin, float x, float y, String id, List<Action> actions) {
        var tree = TreeBuilder.Menu.start();
        for (var action : actions) tree.leaf(action.label(), action.run());
        var menu =
                editor.openMenu(origin, x, y, tree.build(), TreeBuilder.Menu::uiProvider)
                        .setHoverTextureProvider(TreeBuilder.Menu::hoverTextureProvider)
                        .setOnNodeClicked(TreeBuilder.Menu::handle);
        menu.setId(id);
        int index = 0;
        for (var ui : menu.getNodeUIs().values()) {
            var action = actions.get(index++);
            ui.setId(action.id());
            ui.setActive(action.enabled());
        }
    }

    private void popup(String anchorId, String id, List<Action> actions) {
        var anchor = contentView.selectId(anchorId).findFirst().orElseThrow();
        popup(
                anchor,
                anchor.getPositionX(),
                anchor.getPositionY() + anchor.getSizeHeight(),
                id,
                actions);
    }

    private void headings() {
        var choices = new ArrayList<Action>();
        for (int level = 1; level <= 4; level++) {
            int h = level;
            choices.add(
                    new Action(
                            "block-h" + h,
                            Component.literal("H" + h),
                            () -> command("h" + h),
                            true));
        }
        popup("heading-menu", "heading-options", choices);
    }

    private void admonitions() {
        var choices = new ArrayList<Action>();
        for (String type : List.of("info", "warning", "important")) {
            choices.add(
                    new Action(
                            "admonition-insert-" + type,
                            type,
                            () -> {
                                var node =
                                        new Element("div")
                                                .attr("data-type", "admonition")
                                                .attr("type", type)
                                                .attr("data-admo-type", type);
                                node.appendElement("div")
                                        .attr("data-type", "admonition-title")
                                        .text(type.toUpperCase(Locale.ROOT));
                                node.appendElement("div")
                                        .attr("data-type", "admonition-content")
                                        .appendElement("p");
                                session.insertHtml(node.outerHtml());
                                surface.focus();
                                selectionChanged();
                            }));
        }
        popup("insert-admonition", "admonition-options", choices);
    }

    private void pageMenu(UIElement origin, float x, float y, String id) {
        pageOperation(
                () -> {
                    int target = indexOf(id);
                    if (target < 0) return;
                    session.switchPage(target);
                    popup(
                            origin,
                            x,
                            y,
                            "page-context-menu",
                            List.of(
                                    new Action(
                                            "page-menu-add",
                                            "page_add",
                                            () -> pageOperation(() -> session.addPage(false))),
                                    new Action(
                                            "page-menu-copy",
                                            "copy",
                                            () ->
                                                    pageOperation(
                                                            () -> clipboard = session.copyPage())),
                                    new Action(
                                            "page-menu-paste",
                                            Component.translatable("gui.betterbook.paste"),
                                            () -> pageOperation(() -> session.pastePage(clipboard)),
                                            clipboard != null),
                                    new Action("page-menu-rename", "rename", this::renamePage),
                                    new Action(
                                            "page-menu-settings",
                                            "page_settings",
                                            this::pageAccessSettings),
                                    new Action(
                                            "page-menu-delete",
                                            Component.translatable("gui.betterbook.delete"),
                                            () -> pageOperation(session::removePage),
                                            session.book().pages.size() > 1)));
                });
    }

    private void renamePage() {
        var d = dialog("page_title");
        var input = new TextField().setValue(session.page().title());
        input.setId("page-title-input");
        input.getLayout().widthPercent(100);
        d.addContent(input);
        d.addButton(
                button(
                        "apply",
                        "page-title-apply",
                        () -> {
                            session.renamePage(input.getValue());
                            d.close();
                        }));
        d.addButton(button("cancel", "page-title-cancel", d::close));
        d.show(editor.getModularUI());
    }

    private void pageAccessSettings() {
        var d = dialog("page_settings");
        d.setId("page-access-settings");
        d.overlay.getLayout().width(320).maxHeightPercent(94);
        d.titleBar.getLayout().flexShrink(0);
        d.buttonContainer.getLayout().flexShrink(0);
        d.contentContainer.getLayout().minHeight(0).flexShrink(1);
        var scroll = new ScrollerView();
        scroll.getLayout().widthPercent(100).height(250).minHeight(0).minWidth(0).flexShrink(1);
        var settings = new PageAccessSettings(session);
        var fields = new ConfiguratorGroup("", false).hideTitle();
        fields.setId("page-access-fields");
        settings.buildConfigurator(fields);
        fields.getLayout().widthPercent(100).minWidth(0).paddingAll(3);
        scroll.addScrollViewChild(fields);
        d.addContent(scroll);
        d.addButton(
                button(
                        "apply",
                        "page-access-apply",
                        () -> {
                            try {
                                session.setPageAccess(
                                        settings.stages, settings.unlockHint);
                                d.close();
                            } catch (IllegalArgumentException failure) {
                                editor.error(
                                        new IllegalArgumentException(
                                                Component.translatable(
                                                                "gui.betterbook.page_stage_invalid",
                                                                settings.stages)
                                                        .getString(),
                                                failure));
                            } catch (Exception failure) {
                                editor.error(failure);
                            }
                        }));
        d.addButton(button("cancel", "page-access-cancel", d::close));
        d.show(editor.getModularUI());
    }

    private int indexOf(String id) {
        for (int i = 0; i < session.book().pages.size(); i++)
            if (session.book().pages.get(i).id().equals(id)) return i;
        return -1;
    }

    private void rebuildPages() {
        if (rebuildingPages) return;
        rebuildingPages = true;
        try {
            pages.clearAllChildren();
            var ids = session.book().pages.stream().map(Book.Page::id).toList();
            sortable =
                    new DraggableUI<>(
                            ids, order -> pageOperation(() -> session.reorderPages(order)));
            sortable.setAutoScrollView(pageScroll);
            sortable.getLayout()
                    .flexDirection(FlexDirection.COLUMN)
                    .flexWrap(FlexWrap.NO_WRAP)
                    .widthPercent(100)
                    .minWidth(0)
                    .paddingAll(0)
                    .gapAll(3);
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                var p = session.book().page(i, session.language());
                var card = new Button().noText();
                card.setId("page-entry-" + i);
                card.addClass("book-page");
                card.getLayout().widthPercent(100).minWidth(0).height(28).paddingAll(5);
                var label = new Label().setText((i + 1) + "  " + p.title(), false);
                label.addClass("book-page-label");
                label.getLayout().flex(1).minWidth(0).heightPercent(100);
                label.textStyle(
                        style ->
                                style.adaptiveWidth(false)
                                        .textColor(0xfff3f3f3)
                                        .textAlignHorizontal(
                                                com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal
                                                        .LEFT)
                                        .textAlignVertical(
                                                com.lowdragmc.lowdraglib2.gui.ui.data.Vertical
                                                        .CENTER));
                card.addChild(label);
                if (i == session.pageIndex()) card.addClass("__selected__");
                card.setOnClick(e -> pageOperation(() -> session.switchPage(indexOf(id))));
                card.addEventListener(
                        UIEvents.MOUSE_DOWN,
                        e -> {
                            if (e.button == 1) {
                                pageMenu(card, e.x, e.y, id);
                                e.stopPropagation();
                            }
                        });
                sortable.addSortableCard(id, card);
                card.addEventListener(UIEvents.DRAG_END, e -> refresh());
                card.addEventListener(UIEvents.MOUSE_UP, e -> refresh());
            }
            pages.addChild(sortable);
        } finally {
            rebuildingPages = false;
        }
    }

    private void buildLinkBubble() {
        linkBubble.setId("link-editor");
        linkBubble.addClass("book-bubble");
        linkBubble
                .getLayout()
                .positionType(TaffyPosition.ABSOLUTE)
                .width(240)
                .maxWidthPercent(100)
                .height(36)
                .paddingAll(4)
                .paddingBottom(6)
                .gapAll(2)
                .alignItems(AlignItems.CENTER);
        linkInput.setId("link-address");
        linkInput.getLayout().flex(1).minWidth(0).height(24);
        linkInput.textFieldStyle(style -> style.placeholder(Component.literal("https://...")));
        linkInput.addEventListener(
                UIEvents.FOCUS,
                e -> {
                    if (linkCloseOnEsc == null) {
                        linkCloseOnEsc = editor.getModularUI().shouldCloseOnEsc();
                        editor.getModularUI().shouldCloseOnEsc(false);
                    }
                });
        linkInput.addEventListener(UIEvents.BLUR, e -> restoreLinkEscape());
        var apply = button("link_confirm", "link-apply", () -> applyLink(linkInput.getValue()));
        apply.addClass("book-tool");
        apply.getLayout().width(46).height(26);
        linkBubble.addChildren(
                linkInput, apply, tool("unlink", "unlink", "link-remove", () -> applyLink("")));
        linkBubble.addEventListener(
                UIEvents.KEY_DOWN,
                e -> {
                    if (e.keyCode == GLFW_KEY_ENTER || e.keyCode == GLFW_KEY_KP_ENTER) {
                        applyLink(linkInput.getValue());
                        e.stopImmediatePropagation();
                    } else if (e.keyCode == GLFW_KEY_ESCAPE) {
                        linkRequested = false;
                        linkDismissed = true;
                        linkInput.setValue(
                                session.selectedLink()
                                        .map(
                                                value ->
                                                        value.mark()
                                                                .attributes()
                                                                .getOrDefault("href", ""))
                                        .orElse(""),
                                false);
                        selectionChanged();
                        surface.focus();
                        e.stopImmediatePropagation();
                    }
                },
                true);
        linkBubble.addEventListener(UIEvents.LAYOUT_CHANGED, e -> selectionChanged());
        body.addChild(linkBubble);
    }

    private void link() {
        if (sourceMode || surface == null) return;
        selectionChanged();
        if (session.selection().empty() && session.selectedLink().isEmpty()) return;
        linkRequested = true;
        linkDismissed = false;
        selectionChanged();
        linkInput.focus();
    }

    private void linkDialog() {
        var selection = session.selection();
        var d = dialog("insert_link");
        var url = new TextField();
        url.setId("link-insert-url");
        url.getLayout().widthPercent(100).minWidth(0).height(20);
        url.textFieldStyle(
                style -> style.placeholder(Component.translatable("gui.betterbook.link_url")));
        var text = new TextField();
        text.setValue(session.selectedText());
        text.setId("link-insert-text");
        text.getLayout().widthPercent(100).minWidth(0).height(20);
        text.textFieldStyle(
                style -> style.placeholder(Component.translatable("gui.betterbook.link_text")));
        d.addContent(url);
        d.addContent(text);
        var insert =
                button(
                        "insert",
                        "link-insert-apply",
                        () -> {
                            session.select(selection);
                            session.insertLink(url.getValue(), text.getValue());
                            d.close();
                            surface.focus();
                        });
        insert.setActive(false);
        url.registerValueListener(value -> insert.setActive(!value.isBlank()));
        d.addButton(button("cancel", "link-insert-cancel", d::close));
        d.addButton(insert);
        d.show(editor.getModularUI());
        url.focus();
    }

    private void applyLink(String href) {
        if (sourceMode || surface == null || linkSelection == null) return;
        session.select(linkSelection);
        try {
            session.updateLink(href);
            surface.focus();
        } catch (Exception e) {
            editor.error(e);
        }
    }

    private void mode(boolean html) {
        if (sourceMode == html) return;
        try {
            if (!html) session.applySources();
            sourceMode = html;
            rebuild();
            refresh();
        } catch (Exception e) {
            editor.error(e);
        }
    }

    private void preview() {
        try {
            session.applySources();
            BookReaderScreen.openPreview(session.book(), session.language());
        } catch (Exception e) {
            editor.error(e);
        }
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        try {
            String list =
                    session.language()
                            + java.util.stream.IntStream.range(0, session.book().pages.size())
                                    .mapToObj(i -> session.book().page(i, session.language()))
                                    .map(p -> p.id() + ":" + p.title())
                                    .toList();
            if (!list.equals(pageList) && (sortable == null || !sortable.isDragging())) {
                pageList = list;
                rebuildPages();
            }
            if (sortable != null)
                for (var entry : sortable.getUiToDataMap().entrySet()) {
                    if (entry.getValue().equals(session.page().id()))
                        entry.getKey().addClass("__selected__");
                    else entry.getKey().removeClass("__selected__");
                }
            String languages =
                    session.book().languages()
                            + ":"
                            + session.language()
                            + ":"
                            + session.book().defaultLanguage;
            if (!languages.equals(languageKey)) {
                languageKey = languages;
                languageList.clearAllChildren();
                for (String locale : session.book().languages()) {
                    var b =
                            new Button()
                                    .setText(
                                            (locale.equals(session.language()) ? "› " : "")
                                                    + locale,
                                            false)
                                    .setOnClick(
                                            e ->
                                                    pageOperation(
                                                            () -> session.switchLanguage(locale)));
                    b.setId("language-select-" + locale);
                    b.getLayout().widthPercent(100).height(20);
                    languageList.addChild(b);
                }
            }
            if (!titleField.getValue().equals(session.book().title(session.language())))
                titleField.setValue(session.book().title(session.language()), false);
            if (!authorField.getValue().equals(session.book().author(session.language())))
                authorField.setValue(session.book().author(session.language()), false);
            followGuiScale.setValue(session.book().followGuiScale, false);
            singlePage.setValue(session.book().singlePage, false);
            allowPageTurning.setValue(session.book().allowPageTurning, false);
            lockedIcons.setValue(session.book().lockedIcons, false);
            defaultLabel.setText(
                    Component.translatable(
                            "gui.betterbook.default_language_value",
                            session.book().defaultLanguage));
            visualTab.setSelected(!sourceMode);
            htmlTab.setSelected(sourceMode);
            visualTab.text.textStyle(
                    style -> style.textColor(sourceMode ? 0xffe7eef5 : 0xffa8df85));
            htmlTab.text.textStyle(style -> style.textColor(sourceMode ? 0xffa8df85 : 0xffe7eef5));
            visualTools.forEach(tool -> tool.setDisplay(!sourceMode));
            if (!pageId.equals(session.language() + ":" + session.page().id())) rebuild();
            else if (sourceMode
                    && source != null
                    && !String.join("\n", source.getValue()).equals(session.source()))
                source.restoreSource();
            var ids = new HashSet<String>();
            session.book().pages.forEach(p -> ids.add(p.id()));
            long broken =
                    session.document().body().select("a[href^=book:]").stream()
                            .filter(e -> !ids.contains(e.attr("href").substring(5)))
                            .count();
            status.setText(
                    broken > 0
                            ? Component.translatable("gui.betterbook.broken_links", broken)
                            : Component.literal(
                                    (session.pageIndex() + 1)
                                            + " / "
                                            + session.book().pages.size()
                                            + "   "
                                            + session.page().title()));
            selectionChanged();
        } finally {
            refreshing = false;
        }
    }

    private void rebuild() {
        hideRelated();
        hideRecipe();
        hideEntity();
        hideStructure();
        hideItem();
        hideTextCommand();
        hideTextColor();
        restoreLinkEscape();
        linkSelection = null;
        linkRequested = false;
        linkDismissed = false;
        if (surface != null) {
            surface.close();
            surface.removeSelf();
            surface = null;
        }
        if (paperFrame != null) {
            paperFrame.removeSelf();
            paperFrame = null;
        }
        if (source != null) {
            source.removeSelf();
            source = null;
        }
        pageId = session.language() + ":" + session.page().id();
        if (sourceMode) {
            source = new BookSourceEditor(session, () -> editor.saveProject(null));
            source.setId("html-source");
            source.setLanguage(Languages.XML);
            source.restoreSource();
            source.getLayout().widthPercent(100).heightPercent(100).minHeight(0);
            body.addChildAt(source, 0);
        } else {
            surface = new RichSurface(session, project.extensions, true);
            surface.setId("rich-editor");
            surface.getLayout().widthPercent(100).heightPercent(100).minWidth(0).minHeight(0);
            surface.onSelection(this::selectionChanged);
            surface.onItem(this::showItem);
            surface.onEntity(this::showEntity);
            surface.onStructure(this::showStructure);
            surface.onRecipe(this::showRecipe, recipePanel::replace);
            surface.onRelatedPages(this::showRelated, relatedPanel::replace);
            surface.onLinkEdit(
                    () -> {
                        linkDismissed = false;
                        selectionChanged();
                    });
            surface.onSave(() -> editor.saveProject(null));
            surface.addEventListener(
                    UIEvents.MOUSE_DOWN,
                    e -> {
                        if (e.button == 1) {
                            popup(
                                    surface,
                                    e.x,
                                    e.y,
                                    "content-context-menu",
                                    List.of(
                                            new Action(
                                                    "content-properties",
                                                    "properties",
                                                    this::properties)));
                            e.stopPropagation();
                        }
                    });
            paperFrame = new UIElement().addClass("book_cover");
            paperFrame.setId("book-editor-page");
            paperFrame
                    .getLayout()
                    .width(268)
                    .maxWidthPercent(100)
                    .heightPercent(100)
                    .minWidth(0)
                    .minHeight(0)
                    .paddingAll(6);
            var paper = new BookPaper(true);
            paper.getLayout().widthPercent(100).heightPercent(100).minWidth(0).minHeight(0);
            paper.addChild(surface);
            paperFrame.addChild(paper);
            body.addChildAt(paperFrame, 0);
        }
        selectionChanged();
    }

    private void selectionChanged() {
        if (relatedPanel.block() >= 0) {
            int block = relatedPanel.block();
            var selected = session.selection();
            var blocks = session.document().blocks();
            if (sourceMode
                    || selected.empty()
                    || selected.start().block() != block
                    || selected.end().block() != block
                    || block >= blocks.size()
                    || !blocks.get(block).element().is("div[data-type=related-pages]"))
                hideRelated();
            else relatedPanel.refresh(false);
        }
        if (recipePanel.block() >= 0) {
            int block = recipePanel.block();
            var selected = session.selection();
            var blocks = session.document().blocks();
            if (sourceMode
                    || selected.empty()
                    || selected.start().block() != block
                    || selected.end().block() != block
                    || block >= blocks.size()
                    || !blocks.get(block).element().is("div[data-type=recipe]")) hideRecipe();
            else recipePanel.refresh(false);
        }
        if (structureBlock >= 0) {
            var selected = session.selection();
            var blocks = session.document().blocks();
            if (sourceMode
                    || selected.empty()
                    || selected.start().block() != structureBlock
                    || selected.end().block() != structureBlock
                    || structureBlock >= blocks.size()
                    || !blocks.get(structureBlock).element().is("div[data-type=structure]"))
                hideStructure();
            else if (!structureData.equals(blocks.get(structureBlock).element().outerHtml()))
                refreshStructureFields();
        }
        if (entityBlock >= 0) {
            var selection = session.selection();
            var blocks = session.document().blocks();
            if (sourceMode
                    || surface == null
                    || selection.empty()
                    || selection.start().block() != entityBlock
                    || selection.end().block() != entityBlock
                    || entityBlock >= blocks.size()
                    || !blocks.get(entityBlock).element().is("div[data-type=entity]")) hideEntity();
            else if (!entityData.equals(blocks.get(entityBlock).element().outerHtml()))
                refreshEntityFields();
        }
        if (itemBlock >= 0) {
            var selection = session.selection();
            var blocks = session.document().blocks();
            if (sourceMode
                    || surface == null
                    || selection.empty()
                    || selection.start().block() != itemBlock
                    || selection.end().block() != itemBlock
                    || itemBlock >= blocks.size()
                    || !blocks.get(itemBlock).element().is("div[data-type=item]")) {
                hideItem();
            } else if (!applyingItem
                    && !itemData.equals(blocks.get(itemBlock).element().attr("data-stack"))) {
                refreshItemFields();
            }
        }
        if (commandSelection != null) {
            if (sourceMode
                    || surface == null
                    || session.selection().empty()
                    || !commandSelection.equals(session.selection())) hideTextCommand();
            else if (commandRevision != session.revision()) refreshCommandFields();
        }
        if (colorSelection != null) {
            if (sourceMode
                    || surface == null
                    || session.selection().empty()
                    || !colorSelection.equals(session.selection())) hideTextColor();
            else if (!applyingColor) colorSelector.setColor(selectionColor(), false);
        }
        if (sourceMode || surface == null) {
            bubble.setDisplay(false);
            linkBubble.setDisplay(false);
            return;
        }
        var selection = session.selection();
        var link = session.selectedLink();
        if (!selection.equals(linkSelection) || linkRevision != session.revision()) {
            linkSelection = selection;
            linkRevision = session.revision();
            linkRequested = false;
            linkDismissed = false;
            linkInput.setValue(
                    link.map(value -> value.mark().attributes().getOrDefault("href", ""))
                            .orElse(""),
                    false);
        }
        boolean editingLink = !linkDismissed && (linkRequested || link.isPresent());
        linkBubble.setDisplay(editingLink);
        boolean formatting =
                !editingLink
                        && !selection.empty()
                        && itemBlock < 0
                        && entityBlock < 0
                        && structureBlock < 0
                        && recipePanel.block() < 0
                        && relatedPanel.block() < 0;
        bubble.setDisplay(formatting);
        if (editingLink) positionBubble(linkBubble, Math.min(240, body.getContentWidth()), 36);
        if (!formatting) return;
        float width = Math.min(320, body.getContentWidth());
        int columns = Math.max(1, (int) ((width - 8) / 24));
        float height = 10 + 26 * (float) Math.ceil((float) bubble.getChildren().size() / columns);
        positionBubble(bubble, width, height);
        var marks = session.marksAtCursor();
        formats.forEach(
                (id, b) -> {
                    if (marks.stream().anyMatch(m -> m.id().equals("betterbook:" + id)))
                        b.addClass("__selected__");
                    else b.removeClass("__selected__");
                });
    }

    private void positionBubble(UIElement popup, float width, float height) {
        float x = surface.getPositionX() - body.getContentX() + surface.selectionX() - width / 2;
        float caretY = surface.getContentY() - body.getContentY() + surface.selectionY();
        float y = caretY - height - 5;
        if (y < 0) y = caretY + 18;
        popup.getLayout()
                .width(width)
                .left(Math.clamp(x, 0, Math.max(0, body.getContentWidth() - width)))
                .top(Math.clamp(y, 0, Math.max(0, body.getContentHeight() - height)));
    }

    public void properties() {
        if (sourceMode) return;
        var el = session.document().blocks().get(session.selection().head().block()).element();
        var spec = project.extensions.schema.node(el);
        if (spec != null && project.extensions.inspectors.containsKey(spec.id())) {
            project.extensions.inspectors.get(spec.id()).accept(session, contentView);
            return;
        }
        var d = dialog("properties");
        var scroll = new ScrollerView();
        scroll.getLayout()
                .widthPercent(100)
                .height(Math.max(60, Math.min(210, editor.getModularUI().getScreenHeight() - 70)));
        var fields = new UIElement().layout(l -> l.gapAll(4).widthPercent(100));
        scroll.addScrollViewChild(fields);
        d.addContent(scroll);
        if (el.is("img")) {
            var values = new LinkedHashMap<String, TextField>();
            for (String key : List.of("src", "alt", "width", "height")) {
                fields.addChild(new Label().setText("gui.betterbook." + key));
                var input = new TextField();
                input.setValue(el.attr(key));
                input.getLayout().widthPercent(100);
                fields.addChild(input);
                values.put(key, input);
            }
            fields.addChild(button("retry", "image-retry", () -> surface.retrySelectedImage()));
            fields.addChild(
                    button(
                            "apply",
                            "image-apply",
                            () -> {
                                session.editElement(
                                        e ->
                                                values.forEach(
                                                        (key, value) ->
                                                                e.attr(key, value.getValue())));
                                d.close();
                            }));
        } else if (el.is("pre"))
            fields.addChild(
                    button(
                            "language",
                            "code-language",
                            () ->
                                    prompt(
                                            contentView,
                                            "language",
                                            el.attr("language"),
                                            value ->
                                                    session.editElement(
                                                            e -> {
                                                                e.attr("language", value);
                                                                if (e.selectFirst("code") != null)
                                                                    e.selectFirst("code")
                                                                            .attr(
                                                                                    "class",
                                                                                    "language-"
                                                                                            + value);
                                                            }))));
        var table = BookSession.ancestor(el, "table");
        if (table != null) {
            fields.addChildren(
                    button("row_add", "table-row-add", () -> tableEdit("row+")),
                    button("row_delete", "table-row-delete", () -> tableEdit("row-")),
                    button("column_add", "table-col-add", () -> tableEdit("col+")),
                    button("column_delete", "table-col-delete", () -> tableEdit("col-")),
                    button("header", "table-header", () -> tableEdit("header")));
        }
        var admo = BookSession.ancestor(el, "div[data-type=admonition]");
        if (admo != null)
            for (String type : List.of("info", "warning", "important"))
                fields.addChild(
                        button(
                                type,
                                "admo-" + type,
                                () ->
                                        session.editElement(
                                                e -> {
                                                    var a =
                                                            BookSession.ancestor(
                                                                    e, "div[data-type=admonition]");
                                                    a.attr("type", type)
                                                            .attr("data-admo-type", type);
                                                })));
        var steps = BookSession.ancestor(el, "div[data-type=steps]");
        if (steps != null)
            fields.addChildren(
                    button("step_add", "step-add", () -> stepEdit("add")),
                    button("step_delete", "step-delete", () -> stepEdit("delete")),
                    button("up", "step-up", () -> stepEdit("up")),
                    button("down", "step-down", () -> stepEdit("down")),
                    button("step_current", "step-current", () -> stepEdit("current")));
        fields.addChild(
                button(
                        "delete",
                        "node-delete",
                        () -> {
                            if (table != null)
                                session.editElement(e -> BookSession.ancestor(e, "table").remove());
                            else session.editElement(Element::remove);
                            d.close();
                        }));
        d.addButton(button("close", "properties-close", d::close));
        d.show(editor.getModularUI());
    }

    private void tableEdit(String action) {
        session.editTable(action);
    }

    private void stepEdit(String action) {
        session.editStep(action);
    }

    @Override
    public void close() {
        structureRequest++;
        restoreLinkEscape();
        session.unlisten(listener);
        if (surface != null) surface.close();
        pagesView.removeSelf();
        contentView.removeSelf();
        detailsView.removeSelf();
    }

    private void restoreLinkEscape() {
        if (linkCloseOnEsc != null) {
            editor.getModularUI().shouldCloseOnEsc(linkCloseOnEsc);
            linkCloseOnEsc = null;
        }
    }
}
