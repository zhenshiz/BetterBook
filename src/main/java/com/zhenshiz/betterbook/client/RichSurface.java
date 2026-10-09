package com.zhenshiz.betterbook.client;

import static org.lwjgl.glfw.GLFW.*;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.PropertyRegistry;
import com.lowdragmc.lowdraglib2.math.interpolate.Eases;
import com.lowdragmc.lowdraglib2.syncdata.ISubscription;
import com.lowdragmc.lowdraglib2.utils.animation.*;
import com.zhenshiz.betterbook.api.ExtensionContext;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** 共用于编辑器和阅读器的原生富文本表面，滚动位置不写入书籍文件。 */
public final class RichSurface extends TextElement implements AutoCloseable {
    private final BookSession session;
    private final ExtensionContext extensions;
    private final boolean editable;
    private final DocumentLayout layout = new DocumentLayout();
    private final Set<Integer> collapsedCode = new HashSet<>();
    private int copiedBlock = -1, hoveredTable = -1, tableCell = -1;
    private long copiedUntil;
    private final ImageCache images = new ImageCache(() -> layoutRevision = -1);
    private final Map<Integer, UIElement> customViews = new HashMap<>();
    private IntConsumer itemHandler = index -> {};
    private java.util.function.BiConsumer<Integer, String> recipeHandler = (index, part) -> {};
    private java.util.function.Consumer<com.zhenshiz.betterbook.data.BookRecipe> recipeChanged =
            data -> {};

    public void onRecipe(
            java.util.function.BiConsumer<Integer, String> handler,
            java.util.function.Consumer<com.zhenshiz.betterbook.data.BookRecipe> changed) {
        recipeHandler = handler;
        recipeChanged = changed;
    }

    private java.util.function.BiConsumer<Integer, String> relatedHandler = (index, entry) -> {};
    private Consumer<com.zhenshiz.betterbook.data.BookRelatedPages> relatedChanged = data -> {};
    private Book navigationBook;
    private BookPageAccess pageAccess;

    /**
     * 将书内链接和入口的显示绑定到读者的页面访问条件。
     *
     * @param access 当前书籍的页面访问判断
     */
    public void pageAccess(BookPageAccess access) {
        pageAccess = access;
        refreshPageAccess();
    }

    /** 刷新阶段变化后的书内入口，不修改文档。 */
    public void refreshPageAccess() {
        layoutRevision = -1;
    }

    private boolean lockedLink(String href) {
        return !editable && pageAccess != null && href.startsWith("book:")
                && pageAccess.contains(href.substring(5)) && !pageAccess.canRead(href.substring(5));
    }

    public void onRelatedPages(
            java.util.function.BiConsumer<Integer, String> handler,
            Consumer<com.zhenshiz.betterbook.data.BookRelatedPages> changed) {
        relatedHandler = handler;
        relatedChanged = changed;
    }

    public void navigationBook(Book book) {
        navigationBook = book;
        layoutRevision = -1;
    }

    private List<Book.Page> relatedPages() {
        if (navigationBook != null) return navigationBook.pages;
        return java.util.stream.IntStream.range(0, session.book().pages.size())
                .mapToObj(i -> session.book().page(i, session.language()))
                .toList();
    }

    private IntConsumer structureHandler = index -> {};
    private IntConsumer entityHandler = index -> {};
    private final Map<BookSession.Position, HiddenText> hiddenTexts = new LinkedHashMap<>();
    private final Map<DocumentLayout.Glyph, HiddenText> hiddenGlyphs = new IdentityHashMap<>();

    private final class HiddenText implements IFrameValueHandler<Float> {
        float opacity, target;
        ISubscription animation;

        void reveal(boolean hovered) {
            float next = hovered ? 1 : 0;
            if (next == target) return;
            stop();
            target = next;
            animation =
                    getModularUI()
                            .getAnimationEngine()
                            .play(
                                    KeyFrameAnimation.of(
                                            new Animation(
                                                    .2f * Math.abs(target - opacity),
                                                    0,
                                                    Eases.LINEAR),
                                            new KFExecutor<>(
                                                    KeyFrames.of(
                                                            PropertyRegistry.OPACITY
                                                                    .getInterpolator(),
                                                            opacity,
                                                            target),
                                                    this)));
        }

        void stop() {
            if (animation != null) animation.unsubscribe();
            animation = null;
        }

        @Override
        public void accept(AnimationRuntime runtime, Float value) {
            opacity = value;
        }

        @Override
        public void onFinished(AnimationRuntime runtime) {
            opacity = target;
            animation = null;
        }

        @Override
        public Object owner() {
            return RichSurface.this;
        }
    }

    private long layoutRevision = -1;
    private float layoutWidth = -1, scroll;
    private DocumentLayout.Caret lineEdgeCaret;
    private boolean dragging, pastingImage, closed;
    private Runnable onSelection = () -> {}, onSave = () -> {}, onLinkEdit = () -> {};
    private Consumer<String> linkHandler = this::externalLink;
    private Consumer<String> commandHandler = id -> {};
    private final Runnable changed = this::onChanged;

    public RichSurface(BookSession session, ExtensionContext extensions, boolean editable) {
        this.session = session;
        this.extensions = extensions;
        this.editable = editable;
        setFocusable(true);
        setOverflowVisible(false);
        addClass("book_document");
        getLayout().widthPercent(100).heightPercent(100).minWidth(0).minHeight(0);
        textStyle(s -> s.fontSize(9).textShadow(false).adaptiveHeight(false).adaptiveWidth(false));
        session.listen(changed);
        addEventListener(UIEvents.MOUSE_DOWN, this::mouseDown);
        addEventListener(UIEvents.MOUSE_UP, e -> dragging = false);
        addEventListener(
                UIEvents.DRAG_SOURCE_UPDATE,
                e -> {
                    if (editable
                            && e.dragHandler.draggingObject
                                    instanceof BookSession.Position anchor) {
                        var mouse = getLocalMouse(e.x, e.y);
                        session.select(new BookSession.Selection(anchor, hit(mouse.x, mouse.y)));
                        autoScroll(mouse.y);
                        onSelection.run();
                        e.stopPropagation();
                    }
                });
        addEventListener(
                UIEvents.MOUSE_WHEEL,
                e -> {
                    // 保留触控板的小幅输入，避免高灵敏度滚轮一次跳过整组内容。
                    setScroll(scroll - Math.clamp(e.deltaY, -1, 1) * 16);
                    e.stopPropagation();
                });
        addEventListener(
                UIEvents.DOUBLE_CLICK,
                e -> {
                    if (!editable) return;
                    var mouse = getLocalMouse(e.x, e.y);
                    var p = hit(mouse.x, mouse.y);
                    String t = session.document().blocks().get(p.block()).text();
                    int a = p.offset(), b = a;
                    while (a > 0 && !Character.isWhitespace(t.charAt(a - 1)))
                        a = t.offsetByCodePoints(a, -1);
                    while (b < t.length() && !Character.isWhitespace(t.charAt(b)))
                        b = t.offsetByCodePoints(b, 1);
                    session.select(
                            new BookSession.Selection(
                                    new BookSession.Position(p.block(), a),
                                    new BookSession.Position(p.block(), b)));
                    onSelection.run();
                });
        addEventListener(
                UIEvents.CHAR_TYPED,
                e -> {
                    if (!editable || Character.isISOControl(e.codePoint)) return;
                    session.insert(String.valueOf(e.codePoint));
                    extensions.applyInputRules(session);
                    ensureCursor();
                    onSelection.run();
                    e.stopPropagation();
                });
        addEventListener(UIEvents.KEY_DOWN, this::key);
        addEventListener(
                UIEvents.VALIDATE_COMMAND,
                e -> {
                    if (editable
                            && Set.of(
                                            CommandEvents.UNDO,
                                            CommandEvents.REDO,
                                            CommandEvents.COPY,
                                            CommandEvents.PASTE,
                                            CommandEvents.CUT,
                                            CommandEvents.SELECT_ALL)
                                    .contains(e.command)) e.stopPropagation();
                });
        addEventListener(
                UIEvents.EXECUTE_COMMAND,
                e -> {
                    if (command(e.command)) e.stopPropagation();
                });
    }

    public BookSession session() {
        return session;
    }

    public float scroll() {
        return scroll;
    }

    public boolean imagesReady() {
        return session.document().body().select("img").stream()
                .allMatch(e -> images.get(e.attr("src")).ready);
    }

    public void retrySelectedImage() {
        var e = session.document().blocks().get(session.selection().head().block()).element();
        if (e.is("img")) images.retry(e.attr("src"));
    }

    public float contentHeight() {
        ensureLayout();
        return layout.height;
    }

    public void onSave(Runnable callback) {
        onSave = callback;
    }

    public void onSelection(Runnable callback) {
        onSelection = callback;
    }

    /**
     * 注册编辑模式物品槽位的选中回调。
     *
     * @param callback 接收当前文档中的原子块索引
     */
    public void onItem(IntConsumer callback) {
        itemHandler = callback;
    }

    /**
     * 注册编辑模式结构场景的选中回调。
     *
     * @param callback 接收文档中的结构原子块索引
     */
    public void onStructure(IntConsumer callback) {
        structureHandler = callback;
    }

    /** 恢复当前结构场景的默认镜头，不修改书籍内容。 */
    public void resetStructureCamera() {
        ensureLayout();
        if (customViews.get(session.selection().head().block())
                instanceof BookStructureView structure) structure.resetCamera();
    }

    /**
     * 注册编辑模式实体场景的选中回调。
     *
     * @param callback 接收文档中的实体原子块索引
     */
    public void onEntity(IntConsumer callback) {
        entityHandler = callback;
    }

    /** 恢复当前实体场景的默认镜头，不修改书籍内容。 */
    public void resetEntityCamera() {
        ensureLayout();
        if (customViews.get(session.selection().head().block()) instanceof BookEntityView entity)
            entity.resetCamera();
    }

    public void onLink(Consumer<String> handler) {
        linkHandler = handler;
    }

    /**
     * 注册阅读模式的文字指令点击回调；编辑模式不会触发执行。
     *
     * @param handler 接收绑定标识的回调，不接收客户端指令文本
     */
    public void onCommand(Consumer<String> handler) {
        commandHandler = handler;
    }

    public void onLinkEdit(Runnable handler) {
        onLinkEdit = handler;
    }

    public void setScroll(float value) {
        ensureLayout();
        scroll = Math.clamp(value, 0, Math.max(0, layout.height - getContentHeight()));
        positionViews();
        onSelection.run();
    }

    private void onChanged() {
        lineEdgeCaret = null;
        collapsedCode.clear();
        clearHiddenTexts();
        layoutRevision = -1;
        onSelection.run();
    }

    private void ensureLayout() {
        float width = Math.max(30, getContentWidth());
        if (layoutRevision == session.revision() && layoutWidth == width) return;
        layout.build(session.document(), width, editable, images, extensions, collapsedCode);
        rebuildHiddenTexts();
        layoutRevision = session.revision();
        layoutWidth = width;
        scroll = Math.clamp(scroll, 0, Math.max(0, layout.height - getContentHeight()));
        var previousViews = new HashMap<>(customViews);
        customViews.clear();
        for (var box : layout.boxes) {
            var el = session.document().blocks().get(box.block()).element();
            var spec = extensions.schema.node(el);
            if (spec == null) continue;
            var factory = extensions.nodeViews.get(spec.id());
            if (factory == null) continue;
            int index = box.block();
            UIElement view = previousViews.get(index);
            String selectedRecipePart =
                    view instanceof BookRecipeView oldRecipe ? oldRecipe.selectedPart() : "";
            String selectedRelatedEntry =
                    view instanceof BookRelatedPagesView oldRelated
                            ? oldRelated.selectedEntry()
                            : "";
            if (view instanceof BookMermaidView mermaid && el.is(MermaidNode.SELECTOR)) {
                mermaid.sync(el);
                previousViews.remove(index);
            } else if (view instanceof BookLatexView latex && latex.matches(el)) {
                previousViews.remove(index);
            } else if (view instanceof BookRelatedPagesView related && related.matches(el)) {
                previousViews.remove(index);
            } else if (view instanceof BookEntityView entity && entity.matches(el)) {
                previousViews.remove(index);
            } else if (view instanceof BookRecipeView recipe && recipe.matches(el)) {
                previousViews.remove(index);
            } else if (view instanceof BookStructureView structure && structure.matches(el)) {
                previousViews.remove(index);
            } else
                view =
                        factory.apply(
                                new ExtensionContext.NodeViewContext(
                                        el.clone(),
                                        editable,
                                        replacement ->
                                                session.transact(
                                                        () -> {
                                                            var old =
                                                                    session.document()
                                                                            .blocks()
                                                                            .get(index)
                                                                            .element();
                                                            old.replaceWith(replacement.clone());
                                                            session.document().invalidate();
                                                        })));
            if (view != null) {
                if (view instanceof BookRelatedPagesView related) {
                    related.configure(relatedPages(), pageAccess,
                            navigationBook == null || navigationBook.lockedIcons);
                    related.highlight(selectedRelatedEntry);
                    related.callbacks(
                            entry -> {
                                focus();
                                session.select(
                                        new BookSession.Selection(
                                                new BookSession.Position(index, 0),
                                                new BookSession.Position(index, 1)));
                                relatedHandler.accept(index, entry);
                                onSelection.run();
                            },
                            relatedChanged,
                            href -> linkHandler.accept(href));
                }
                if (editable && view instanceof BookRecipeView recipe) {
                    recipe.highlight(selectedRecipePart);
                    recipe.callbacks(
                            part -> {
                                focus();
                                session.select(
                                        new BookSession.Selection(
                                                new BookSession.Position(index, 0),
                                                new BookSession.Position(index, 1)));
                                recipeHandler.accept(index, part);
                                onSelection.run();
                            },
                            recipeChanged);
                }
                if (editable && view instanceof BookStructureView structure) {
                    structure.onSelect(
                            () -> {
                                focus();
                                session.select(
                                        new BookSession.Selection(
                                                new BookSession.Position(index, 0),
                                                new BookSession.Position(index, 1)));
                                structureHandler.accept(index);
                                onSelection.run();
                            });
                }
                if (editable && view instanceof BookEntityView entity) {
                    entity.onSelect(
                            () -> {
                                focus();
                                session.select(
                                        new BookSession.Selection(
                                                new BookSession.Position(index, 0),
                                                new BookSession.Position(index, 1)));
                                entityHandler.accept(index);
                                onSelection.run();
                            });
                }
                if (editable && view instanceof BookItemView item) {
                    item.onSelect(
                            () -> {
                                focus();
                                session.select(
                                        new BookSession.Selection(
                                                new BookSession.Position(index, 0),
                                                new BookSession.Position(index, 1)));
                                itemHandler.accept(index);
                                onSelection.run();
                            });
                }
                view.getLayout()
                        .positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                        .left(box.x())
                        .top(box.y() - scroll)
                        .width(box.width())
                        .height(box.height());
                if (view.getParent() != this) addChild(view);
                customViews.put(index, view);
            }
        }
        for (var view : previousViews.values()) view.removeSelf();
    }

    private void rebuildHiddenTexts() {
        var old = new HashMap<>(hiddenTexts);
        hiddenTexts.clear();
        hiddenGlyphs.clear();
        DocumentLayout.Glyph previous = null;
        HiddenText current = null;
        for (var glyph : layout.glyphs) {
            if (glyph.hidden()) {
                if (previous == null
                        || !previous.hidden()
                        || previous.block() != glyph.block()
                        || previous.to() != glyph.from()) {
                    var key = new BookSession.Position(glyph.block(), glyph.from());
                    current = old.remove(key);
                    if (current == null) current = new HiddenText();
                    hiddenTexts.put(key, current);
                }
                hiddenGlyphs.put(glyph, current);
            }
            previous = glyph;
        }
        old.values().forEach(HiddenText::stop);
    }

    private void clearHiddenTexts() {
        hiddenTexts.values().forEach(HiddenText::stop);
        hiddenTexts.clear();
        hiddenGlyphs.clear();
    }

    /**
     * 获取指定正文位置的隐藏文字显示透明度。
     *
     * @param position 正文块索引和 UTF-16 字符位置
     * @return 从完全隐藏的 0 到完全显示的 1；普通文字返回 1
     */
    public float hiddenTextOpacity(BookSession.Position position) {
        ensureLayout();
        for (var entry : hiddenGlyphs.entrySet()) {
            var glyph = entry.getKey();
            if (glyph.block() == position.block()
                    && position.offset() >= glyph.from()
                    && position.offset() < glyph.to()) return entry.getValue().opacity;
        }
        return 1;
    }

    private void positionViews() {
        for (var box : layout.boxes) {
            var view = customViews.get(box.block());
            if (view != null) view.getLayout().top(box.y() - scroll);
        }
    }

    private BookSession.Position hit(float x, float y) {
        ensureLayout();
        return layout.hit(x - getContentX(), y - getContentY() + scroll);
    }

    private void selectTo(BookSession.Position pos, boolean extend) {
        lineEdgeCaret = null;
        session.select(new BookSession.Selection(extend ? session.selection().anchor() : pos, pos));
        onSelection.run();
    }

    private DocumentLayout.Caret currentCaret() {
        var position = session.selection().head();
        return lineEdgeCaret != null
                        && lineEdgeCaret.position().equals(position)
                        && layout.carets.contains(lineEdgeCaret)
                ? lineEdgeCaret
                : layout.caret(position);
    }

    private void moveToLineEdge(boolean end, boolean extend) {
        ensureLayout();
        var current = currentCaret();
        var edge = current;
        for (var caret : layout.carets) {
            if (caret.position().block() != current.position().block()
                    || Math.abs(caret.y() - current.y()) > .01f) continue;
            if (end
                    ? caret.position().offset() > edge.position().offset()
                    : caret.position().offset() < edge.position().offset()) edge = caret;
        }
        selectTo(edge.position(), extend);
        lineEdgeCaret = edge;
    }

    private void mouseDown(UIEvent e) {
        if (e.button != 0) return;
        focus();
        ensureLayout();
        var mouse = getLocalMouse(e.x, e.y);
        if (codeAction(mouse.x - getContentX(), mouse.y - getContentY() + scroll)) {
            e.stopPropagation();
            return;
        }
        if (editable) {
            for (var box : layout.boxes)
                if (box.kind().equals("task")
                        && mouse.x - getContentX() >= box.x()
                        && mouse.x - getContentX() <= box.x() + box.width()
                        && mouse.y - getContentY() + scroll >= box.y()
                        && mouse.y - getContentY() + scroll <= box.y() + box.height()) {
                    activate(mouse.x - getContentX(), mouse.y - getContentY() + scroll);
                    e.stopPropagation();
                    return;
                }
            selectTo(hit(mouse.x, mouse.y), (e.modifiers & GLFW_MOD_SHIFT) != 0 || e.isShiftDown());
            if (layout.glyphs.stream()
                    .anyMatch(
                            g ->
                                    !g.href().isEmpty()
                                            && mouse.x - getContentX() >= g.x()
                                            && mouse.x - getContentX() <= g.x() + g.width()
                                            && mouse.y - getContentY() + scroll >= g.y()
                                            && mouse.y - getContentY() + scroll
                                                    <= g.y() + g.height())) onLinkEdit.run();
            startDrag(session.selection().anchor(), null);
            dragging = true;
            onSelection.run();
        } else activate(mouse.x - getContentX(), mouse.y - getContentY() + scroll);
        e.stopPropagation();
    }

    private boolean codeAction(float x, float y) {
        for (var box : layout.boxes) {
            if ((box.kind().equals("step-tab")
                            || box.kind().equals("step-active")
                            || editable
                                    && (box.kind().equals("step-add")
                                            || box.kind().equals("step-remove")))
                    && x >= box.x()
                    && x < box.x() + box.width()
                    && y >= box.y()
                    && y < box.y() + box.height()) {
                if (box.kind().equals("step-add") || box.kind().equals("step-remove")) {
                    session.select(BookSession.Selection.at(box.block(), 0));
                    session.editStep(box.kind().equals("step-add") ? "add" : "delete");
                } else session.selectStep(box.block());
                dragging = false;
                onSelection.run();
                return true;
            }
            if (editable
                    && box.kind().equals("table-tools")
                    && box.block() == hoveredTable
                    && x >= box.x()
                    && x < box.x() + box.width()
                    && y >= box.y()
                    && y < box.y() + box.height()) {
                int action = Math.clamp((int) ((x - box.x()) / (box.width() / 6)), 0, 5);
                session.select(BookSession.Selection.at(tableCell, 0));
                session.editTable(
                        new String[] {"header", "row+", "row-", "col+", "col-", "delete"}[action]);
                return true;
            }
            if (!(box.kind().equals("code-copy") || box.kind().equals("code-fold"))
                    || x < box.x()
                    || x >= box.x() + box.width()
                    || y < box.y()
                    || y >= box.y() + box.height()) continue;
            if (box.kind().equals("code-copy")) {
                Minecraft.getInstance()
                        .keyboardHandler
                        .setClipboard(session.document().blocks().get(box.block()).text());
                copiedBlock = box.block();
                copiedUntil = System.currentTimeMillis() + 1500;
            } else {
                if (!collapsedCode.remove(box.block())) collapsedCode.add(box.block());
                layoutRevision = -1;
                ensureLayout();
                onSelection.run();
            }
            return true;
        }
        return false;
    }

    private void autoScroll(float y) {
        if (y < getContentY() + 8) setScroll(scroll - 8);
        else if (y > getContentY() + getContentHeight() - 8) setScroll(scroll + 8);
    }

    private void activate(float x, float y) {
        if (!editable)
            for (var glyph : layout.glyphs) {
                var click = glyph.text().getStyle().getClickEvent();
                if (click != null
                        && !lockedLink(glyph.href())
                        && click.getAction()
                                == net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND
                        && x >= glyph.x()
                        && x < glyph.x() + glyph.width()
                        && y >= glyph.y()
                        && y < glyph.y() + glyph.height()) {
                    commandHandler.accept(click.getValue());
                    return;
                }
            }
        for (var glyph : layout.glyphs)
            if (!glyph.href().isEmpty()
                    && x >= glyph.x()
                    && x <= glyph.x() + glyph.width()
                    && y >= glyph.y()
                    && y <= glyph.y() + glyph.height()) {
                linkHandler.accept(glyph.href());
                return;
            }
        for (var box : layout.boxes)
            if (Set.of("task", "image").contains(box.kind())
                    && x >= box.x()
                    && x <= box.x() + box.width()
                    && y >= box.y()
                    && y <= box.y() + box.height()) {
                int index = box.block();
                if (box.kind().equals("task"))
                    session.transact(
                            () -> {
                                var li =
                                        BookSession.ancestor(
                                                session.document().blocks().get(index).element(),
                                                "li");
                                li.attr(
                                        "data-checked",
                                        Boolean.toString(!li.attr("data-checked").equals("true")));
                                session.document().invalidate();
                            });
                if (box.kind().equals("image")) {
                    String src = session.document().blocks().get(index).element().attr("src");
                    if (!images.get(src).error.isEmpty()) images.retry(src);
                }
                return;
            }
    }

    public void externalLink(String href) {
        try {
            URI uri = URI.create(href);
            if (!Set.of("http", "https").contains(uri.getScheme())) return;
            var mc = Minecraft.getInstance();
            var old = mc.screen;
            mc.setScreen(
                    new ConfirmLinkScreen(
                            yes -> {
                                if (yes) Util.getPlatform().openUri(uri);
                                mc.setScreen(old);
                            },
                            href,
                            true));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public boolean isTextInput() {
        return editable;
    }

    @Override
    public boolean ownsKey(UIEvent e) {
        return editable && e.keyCode != GLFW_KEY_ESCAPE;
    }

    private boolean modifier(UIEvent e) {
        return (e.modifiers & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0 || e.isCtrlDown();
    }

    private void key(UIEvent e) {
        if (!editable) {
            if (e.keyCode == GLFW_KEY_PAGE_DOWN) setScroll(scroll + getContentHeight() * .8f);
            else if (e.keyCode == GLFW_KEY_PAGE_UP) setScroll(scroll - getContentHeight() * .8f);
            else return;
            e.stopPropagation();
            return;
        }
        boolean mod = modifier(e), shift = (e.modifiers & GLFW_MOD_SHIFT) != 0 || e.isShiftDown();
        if (mod && e.keyCode == GLFW_KEY_S) {
            onSave.run();
            e.stopPropagation();
            return;
        }
        if (mod && (e.keyCode == GLFW_KEY_LEFT || e.keyCode == GLFW_KEY_RIGHT)) {
            moveToLineEdge(e.keyCode == GLFW_KEY_RIGHT, shift);
            ensureCursor();
            onSelection.run();
            e.stopPropagation();
            return;
        }
        if (mod) {
            String command =
                    switch (e.keyCode) {
                        case GLFW_KEY_A -> CommandEvents.SELECT_ALL;
                        case GLFW_KEY_C -> CommandEvents.COPY;
                        case GLFW_KEY_X -> CommandEvents.CUT;
                        case GLFW_KEY_V -> CommandEvents.PASTE;
                        case GLFW_KEY_Z -> shift ? CommandEvents.REDO : CommandEvents.UNDO;
                        case GLFW_KEY_Y -> CommandEvents.REDO;
                        default -> null;
                    };
            if (command != null && command(command)) {
                e.stopPropagation();
                ensureCursor();
                return;
            }
        }
        for (var shortcut : extensions.shortcuts)
            if (shortcut.key() == e.keyCode
                    && shortcut.modifier() == mod
                    && shortcut.shift() == shift) {
                extensions.execute(shortcut.command(), session);
                onSelection.run();
                e.stopPropagation();
                return;
            }
        var p = session.selection().head();
        var blocks = session.document().blocks();
        var b = blocks.get(p.block());
        BookSession.Position target = null;
        switch (e.keyCode) {
            case GLFW_KEY_LEFT -> {
                if (p.offset() > 0)
                    target =
                            new BookSession.Position(
                                    p.block(), b.text().offsetByCodePoints(p.offset(), -1));
                else if (p.block() > 0)
                    target =
                            new BookSession.Position(
                                    p.block() - 1, blocks.get(p.block() - 1).length());
            }
            case GLFW_KEY_RIGHT -> {
                if (p.offset() < b.length())
                    target =
                            new BookSession.Position(
                                    p.block(), b.text().offsetByCodePoints(p.offset(), 1));
                else if (p.block() + 1 < blocks.size())
                    target = new BookSession.Position(p.block() + 1, 0);
            }
            case GLFW_KEY_UP, GLFW_KEY_DOWN -> {
                ensureLayout();
                var caret = currentCaret();
                target =
                        layout.hit(
                                caret.x(),
                                caret.y() + (e.keyCode == GLFW_KEY_UP ? -3 : caret.height() + 3));
            }
            case GLFW_KEY_HOME -> target = new BookSession.Position(mod ? 0 : p.block(), 0);
            case GLFW_KEY_END -> {
                int i = mod ? blocks.size() - 1 : p.block();
                target = new BookSession.Position(i, blocks.get(i).length());
            }
            case GLFW_KEY_BACKSPACE -> session.delete(-1);
            case GLFW_KEY_DELETE -> session.delete(1);
            case GLFW_KEY_ENTER -> {
                if (mod) session.exitBlock();
                else session.enter(shift);
                extensions.applyInputRules(session);
            }
            case GLFW_KEY_TAB -> {
                if (b.element().is("td,th")) {
                    int i = Math.clamp(p.block() + (shift ? -1 : 1), 0, blocks.size() - 1);
                    selectTo(new BookSession.Position(i, 0), false);
                } else if (BookSession.ancestor(b.element(), "li") != null) session.indent(shift);
                else session.insert("    ");
            }
            case GLFW_KEY_PAGE_UP -> setScroll(scroll - getContentHeight() * .8f);
            case GLFW_KEY_PAGE_DOWN -> setScroll(scroll + getContentHeight() * .8f);
            default -> {
                return;
            }
        }
        if (target != null) selectTo(target, shift);
        ensureCursor();
        onSelection.run();
        e.stopPropagation();
    }

    private boolean command(String command) {
        if (!editable || command == null) return false;
        var keyboard = Minecraft.getInstance().keyboardHandler;
        switch (command) {
            case CommandEvents.UNDO -> session.undo();
            case CommandEvents.REDO -> session.redo();
            case CommandEvents.COPY -> keyboard.setClipboard(session.selectedText());
            case CommandEvents.CUT -> {
                keyboard.setClipboard(session.selectedText());
                if (!session.selection().empty()) session.insert("");
            }
            case CommandEvents.PASTE -> paste();
            case CommandEvents.SELECT_ALL -> session.selectAll();
            default -> {
                return false;
            }
        }
        onSelection.run();
        return true;
    }

    private void paste() {
        var mc = Minecraft.getInstance();
        String text = mc.keyboardHandler.getClipboard();
        if (!text.isEmpty()) {
            session.insert(text);
            return;
        }
        if (pastingImage) return;
        pastingImage = true;
        long revision = session.revision();
        var selection = session.selection();
        java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return ClipboardImages.read();
                            } catch (Exception e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                        })
                .whenComplete(
                        (src, error) ->
                                mc.execute(
                                        () -> {
                                            pastingImage = false;
                                            if (closed
                                                    || revision != session.revision()
                                                    || !selection.equals(session.selection()))
                                                return;
                                            if (error != null) {
                                                com.zhenshiz.betterbook.BetterBook.LOGGER.warn(
                                                        "Clipboard image read failed", error);
                                                com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog
                                                        .showNotification(
                                                                "gui.betterbook.clipboard_image_error",
                                                                "",
                                                                null)
                                                        .show(getModularUI());
                                                return;
                                            }
                                            if (src != null) {
                                                session.insertHtml(
                                                        new org.jsoup.nodes.Element("img")
                                                                .attr("src", src)
                                                                .outerHtml());
                                                ensureCursor();
                                                onSelection.run();
                                            }
                                        }));
    }

    public void ensureCursor() {
        if (editable) session.revealSelectedStep();
        if (collapsedCode.remove(session.selection().head().block())) layoutRevision = -1;
        ensureLayout();
        var c = currentCaret();
        if (c.y() < scroll) setScroll(c.y());
        else if (c.y() + c.height() > scroll + getContentHeight())
            setScroll(c.y() + c.height() - getContentHeight());
    }

    public float selectionX() {
        ensureLayout();
        return currentCaret().x();
    }

    public float selectionY() {
        ensureLayout();
        return currentCaret().y() - scroll;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext c) {
        ensureLayout();
        float x = getContentX(), y = getContentY();
        int color = getTextStyle().textColor();
        int codeBackground = 0xfff5ecd6;
        int codeHeader = 0xffe3d2ac;
        int codeBorder = 0xffb8a079;
        int codeIcon = 0xff78603f;
        int inlineBackground = 0xffdfcba2;
        int inlineText = color;
        c.enableScissor(x, y, getContentWidth(), getContentHeight());
        int tableUnderMouse = -1;
        if (editable && isSelfOrChildHover()) {
            for (var area : layout.boxes)
                if (area.kind().equals("table-area")
                        && c.localMouseX >= x + area.x()
                        && c.localMouseX < x + area.x() + area.width()
                        && c.localMouseY >= y + area.y() - scroll
                        && c.localMouseY < y + area.y() - scroll + area.height()) {
                    tableUnderMouse = area.block();
                    if (hoveredTable != tableUnderMouse) tableCell = tableUnderMouse;
                }
            for (var cell : layout.boxes)
                if (cell.kind().equals("cell")
                        && c.localMouseX >= x + cell.x()
                        && c.localMouseX < x + cell.x() + cell.width()
                        && c.localMouseY >= y + cell.y() - scroll
                        && c.localMouseY < y + cell.y() - scroll + cell.height())
                    tableCell = cell.block();
        }
        hoveredTable = tableUnderMouse;
        for (var box : layout.boxes) {
            float by = y + box.y() - scroll, bx = x + box.x();
            if (by + box.height() < y || by > y + getContentHeight()) continue;
            switch (box.kind()) {
                case "table-tools" -> {
                    if (hoveredTable == box.block()) {
                        fill(c, bx, by, box.width(), box.height(), codeHeader);
                        String[] labels = {"TH", "+R", "-R", "+C", "-C", "×"};
                        float slot = box.width() / labels.length;
                        for (int i = 0; i < labels.length; i++) {
                            float buttonX = bx + i * slot;
                            boolean hovered =
                                    c.localMouseX >= buttonX
                                            && c.localMouseX < buttonX + slot
                                            && c.localMouseY >= by
                                            && c.localMouseY < by + box.height();
                            if (hovered) {
                                fill(
                                        c,
                                        buttonX + 1,
                                        by + 1,
                                        slot - 2,
                                        box.height() - 2,
                                        0xfff5ecd6);
                                outline(
                                        c,
                                        buttonX + 1,
                                        by + 1,
                                        slot - 2,
                                        box.height() - 2,
                                        0xff9a743d);
                            }
                            String label =
                                    c.mc.font.plainSubstrByWidth(
                                            labels[i], Math.max(1, (int) slot - 2));
                            c.graphics.drawString(
                                    c.mc.font,
                                    label,
                                    (int) (bx + i * slot + (slot - c.mc.font.width(label)) / 2),
                                    (int) by + 5,
                                    hovered ? 0xff95563b : color,
                                    false);
                        }
                    }
                }
                case "cell" -> {
                    fill(c, bx, by, box.width(), 1, 0x88b0915c);
                    fill(c, bx, by, 1, box.height(), 0x88b0915c);
                    fill(c, bx, by + box.height() - 1, box.width(), 1, 0x88b0915c);
                    fill(c, bx + box.width() - 1, by, 1, box.height(), 0x88b0915c);
                }
                case "quote" -> {
                    int background = 0xffe5d5af;
                    int border = 0xffb0915c;
                    fill(c, bx + 2, by, box.width() - 4, box.height(), background);
                    fill(c, bx, by + 2, box.width(), box.height() - 4, background);
                    fill(c, bx, by + 2, 2, box.height() - 4, border);
                    fill(c, bx + 1, by + 1, 2, 1, border);
                    fill(c, bx + 1, by + box.height() - 2, 2, 1, border);
                }
                case "admonition" -> {
                    rounded(c, bx, by, box.width(), box.height(), noticeColor(box.label(), 0));
                    fill(c, bx, by + 2, 2, box.height() - 4, noticeColor(box.label(), 2));
                }
                case "admonition-header", "step-header" -> {
                    int inset = box.kind().equals("admonition-header") ? 2 : 1;
                    int header =
                            box.kind().equals("admonition-header")
                                    ? noticeColor(box.label(), 1)
                                    : 0xffe4d3af;
                    fill(c, bx + inset, by, box.width() - inset - 1, box.height(), header);
                    fill(c, bx + inset, by + 2, box.width() - inset, box.height() - 2, header);
                    fill(c, bx + inset, by + box.height() - 1, box.width() - inset, 1, 0x337d6039);
                }
                case "admonition-icon" ->
                        noticeIcon(c, bx, by, box.label(), noticeColor(box.label(), 2));
                case "steps", "step-card" -> {
                    boolean outer = box.kind().equals("steps");
                    rounded(c, bx, by, box.width(), box.height(), 0xffc9b48b);
                    rounded(
                            c,
                            bx + 1,
                            by + 1,
                            box.width() - 2,
                            box.height() - 2,
                            outer ? 0xffe8d9b8 : 0xfff4ead2);
                }
                case "step-connector" -> fill(c, bx, by, box.width(), 1, 0xffc5af81);
                case "step-tab", "step-active", "step-add", "step-remove" -> {
                    boolean active = box.kind().equals("step-active"),
                            add = box.kind().equals("step-add") || box.kind().equals("step-remove");
                    int accent = 0xff9a743d;
                    if (!add) {
                        circle(c, bx, by, 18, active ? accent : 0xffc3ac81);
                        if (!active) circle(c, bx + 1, by + 1, 16, 0xffefe4c7);
                    }
                    String label = box.label();
                    c.graphics.drawString(
                            c.mc.font,
                            label,
                            (int) (bx + (18 - c.mc.font.width(label)) / 2),
                            (int) by + 5,
                            active ? 0xfffff8e7 : accent,
                            false);
                }
                case "code" -> {
                    fill(c, bx, by, box.width(), box.height(), codeBorder);
                    fill(c, bx + 1, by + 1, box.width() - 2, box.height() - 2, codeBackground);
                }
                case "code-header" -> {
                    fill(c, bx + 1, by + 1, box.width() - 2, box.height() - 2, codeHeader);
                    float labelX = bx + 6;
                    if (box.width() >= 110) {
                        disc(c, bx + 6, by + 8, 0xffff6059);
                        disc(c, bx + 14, by + 8, 0xffffbd2e);
                        disc(c, bx + 22, by + 8, 0xff28c840);
                        labelX = bx + 34;
                    }
                    String language =
                            box.label().isBlank() ? "TEXT" : box.label().toUpperCase(Locale.ROOT);
                    String label =
                            c.mc.font.plainSubstrByWidth(
                                    language, (int) Math.max(0, bx + box.width() - 42 - labelX));
                    c.graphics.drawString(
                            c.mc.font, label, (int) labelX, (int) by + 7, 0xff75532e, false);
                }
                case "code-copy" -> {
                    if (copiedBlock == box.block() && System.currentTimeMillis() < copiedUntil)
                        checkmark(c, bx + 5, by + 5, 0xff47622e);
                    else {
                        outline(c, bx + 5, by + 5, 6, 7, codeIcon);
                        fill(c, bx + 8, by + 8, 6, 7, codeHeader);
                        outline(c, bx + 8, by + 8, 6, 7, codeIcon);
                    }
                }
                case "code-fold" -> {
                    for (int i = 0; i < 4; i++) {
                        float dy = box.label().equals(">") ? 3 - i : i;
                        fill(c, bx + 5 + i, by + 7 + dy, 1, 1, codeIcon);
                        fill(c, bx + 11 - i, by + 7 + dy, 1, 1, codeIcon);
                    }
                }
                case "code-line" ->
                        c.graphics.drawString(
                                c.mc.font,
                                box.label(),
                                (int) (bx + box.width() - c.mc.font.width(box.label())),
                                (int) by,
                                0xff827052,
                                false);
                case "rule" -> fill(c, bx, by + 5, box.width(), 1, 0xffb0915c);
                case "bullet" -> drawBullet(c, bx, by, box.label(), color);
                case "task" -> {
                    boolean checked = box.label().equals("[x]");
                    int background = checked ? 0xffa5844f : 0xfff5ecd6;
                    fill(c, bx + 4, by + 1, 8, 10, background);
                    fill(c, bx + 3, by + 2, 10, 8, background);
                    if (checked) checkmark(c, bx + 4, by + 2, 0xfffff8e7);
                    else outline(c, bx + 3, by + 1, 10, 10, 0xffb0915c);
                }
                case "image" -> {
                    var e = session.document().blocks().get(box.block()).element();
                    var entry = images.get(e.attr("src"));
                    if (entry.texture != null)
                        c.graphics.blit(
                                entry.texture,
                                (int) bx,
                                (int) by,
                                (int) box.width(),
                                (int) box.height(),
                                0,
                                0,
                                entry.width,
                                entry.height,
                                entry.width,
                                entry.height);
                    else {
                        fill(c, bx, by, box.width(), box.height(), 0x22788899);
                        String msg =
                                entry.error.isEmpty()
                                        ? "gui.betterbook.image_loading"
                                        : entry.error;
                        c.graphics.drawString(
                                c.mc.font,
                                Component.translatable(msg),
                                (int) bx + 3,
                                (int) by + 3,
                                color,
                                false);
                    }
                }
                case "caption" -> {
                    int line = 0;
                    for (var text :
                            c.mc.font.split(Component.literal(box.label()), (int) box.width()))
                        c.graphics.drawString(
                                c.mc.font, text, (int) bx, (int) by + 11 * line++, color, false);
                }
                case "unknown" -> {
                    if (!customViews.containsKey(box.block())) {
                        fill(c, bx, by, box.width(), box.height(), 0x33788899);
                        c.graphics.drawString(
                                c.mc.font,
                                Component.translatable("gui.betterbook.unknown", box.label()),
                                (int) bx + 3,
                                (int) by + 5,
                                color,
                                false);
                    }
                }
            }
        }
        var start = session.selection().start();
        var end = session.selection().end();
        HiddenText hovered = null;
        if (isSelfOrChildHover()
                && c.localMouseX >= x
                && c.localMouseX < x + getContentWidth()
                && c.localMouseY >= y
                && c.localMouseY < y + getContentHeight()) {
            for (var g : layout.glyphs)
                if (g.hidden()
                        && c.localMouseX >= x + g.x()
                        && c.localMouseX < x + g.x() + g.width()
                        && c.localMouseY >= y + g.y() - scroll
                        && c.localMouseY < y + g.y() - scroll + g.height()) {
                    hovered = hiddenGlyphs.get(g);
                    break;
                }
        }
        for (var hidden : hiddenTexts.values()) hidden.reveal(hidden == hovered);
        int renderedBlock = -1, blockColor = color;
        for (var g : layout.glyphs) {
            float gx = x + g.x(), gy = y + g.y() - scroll;
            if (gy + g.height() < y || gy > y + getContentHeight()) continue;
            boolean lockedLink = lockedLink(g.href());
            if (lockedLink && isSelfOrChildHover()
                    && c.localMouseX >= gx && c.localMouseX < gx + g.width()
                    && c.localMouseY >= gy && c.localMouseY < gy + g.height())
                getModularUI().setHoverTooltip(
                        List.of(BookLocks.message(pageAccess, g.href().substring(5))),
                        net.minecraft.world.item.ItemStack.EMPTY, null, null);
            if (renderedBlock != g.block()) {
                renderedBlock = g.block();
                blockColor = color;
                var element = session.document().blocks().get(g.block()).element();
                if (element.is("div[data-type=admonition-title]")
                        && element.parent().is("div[data-type=admonition]"))
                    blockColor = noticeColor(element.parent().attr("data-admo-type"), 2);
            }
            var a = new BookSession.Position(g.block(), g.from());
            var b = new BookSession.Position(g.block(), g.to());
            if (g.inlineCode()) {
                fill(c, gx, gy - 1, g.width(), 11 * g.scale(), inlineBackground);
                fill(c, gx - 1, gy, g.width() + 2, 9 * g.scale(), inlineBackground);
            }
            // 选区随字形缩放；布局高度包含行间距，只用于排版和命中。
            if (editable && a.compareTo(end) < 0 && b.compareTo(start) > 0)
                fill(c, gx, gy, g.width(), c.mc.font.lineHeight * g.scale(), 0x88669dcc);
            float opacity = g.hidden() ? hiddenGlyphs.get(g).opacity : 1;
            if (g.hidden()) {
                int maskAlpha = Math.round(255 * (1 - opacity));
                if (maskAlpha > 0)
                    fill(c, gx, gy, g.width(), 9 * g.scale(), maskAlpha << 24 | 0x444444);
            }
            int textAlpha = Math.round(255 * opacity);
            // Minecraft 将最低的 alpha 值视为未指定透明度，因此完全隐藏时跳过绘制。
            if (textAlpha < 4) continue;
            c.pose.pushPose();
            c.pose.translate(gx, gy, 0);
            c.pose.scale(g.scale(), g.scale(), 1);
            c.graphics.drawString(
                    c.mc.font,
                    lockedLink ? g.text().copy().withStyle(style -> style.withColor(0x8b7657)) : g.text(),
                    0,
                    0,
                    (lockedLink ? 0xff8b7657 : g.href().isEmpty() ? g.inlineCode() ? inlineText : blockColor : 0xff3c91bc)
                                    & 0x00ffffff
                            | textAlpha << 24,
                    false);
            c.pose.popPose();
        }
        if (editable
                && !collapsedCode.contains(session.selection().head().block())
                && isFocused()
                && System.currentTimeMillis() / 500 % 2 == 0) {
            var point = currentCaret();
            fill(c, x + point.x(), y + point.y() - scroll, 1, point.height() - 2, color);
        }
        c.disableScissor();
    }

    private static int noticeColor(String type, int part) {
        return switch (type) {
            case "warning" ->
                    switch (part) {
                        case 0 -> 0xffeed9c6;
                        case 1 -> 0xffe4c5ae;
                        default -> 0xff95563b;
                    };
            case "important" ->
                    switch (part) {
                        case 0 -> 0xffefe0b5;
                        case 1 -> 0xffe7d29c;
                        default -> 0xff866122;
                    };
            default ->
                    switch (part) {
                        case 0 -> 0xffe1e1c6;
                        case 1 -> 0xffd2d8b5;
                        default -> 0xff576b3e;
                    };
        };
    }

    private static void noticeIcon(GUIContext c, float x, float y, String type, int color) {
        if (type.equals("warning")) {
            for (int i = 0; i < 8; i++) {
                float half = i * .6f;
                fill(c, x + 5 - half, y + i, 1, 1, color);
                fill(c, x + 5 + half, y + i, 1, 1, color);
            }
            fill(c, x + 1, y + 8, 9, 1, color);
            fill(c, x + 5, y + 3, 1, 2, color);
            fill(c, x + 5, y + 6, 1, 1, color);
        } else if (type.equals("important")) {
            fill(c, x + 4, y, 2, 6, color);
            fill(c, x + 4, y + 8, 2, 1, color);
        } else {
            for (int i = 0; i < 10; i++) {
                int inset = i == 0 || i == 9 ? 3 : i == 1 || i == 8 ? 1 : 0;
                if (i == 0 || i == 9) fill(c, x + inset, y + i, 10 - inset * 2, 1, color);
                else {
                    fill(c, x + inset, y + i, 1, 1, color);
                    fill(c, x + 9 - inset, y + i, 1, 1, color);
                }
            }
            fill(c, x + 4, y + 2, 2, 1, color);
            fill(c, x + 4, y + 4, 2, 4, color);
        }
    }

    private static void rounded(GUIContext c, float x, float y, float w, float h, int color) {
        fill(c, x + 2, y, Math.max(0, w - 4), h, color);
        fill(c, x, y + 2, w, Math.max(0, h - 4), color);
    }

    private static void circle(GUIContext c, float x, float y, int diameter, int color) {
        float radius = diameter / 2f;
        for (int row = 0; row < diameter; row++) {
            float distance = row + .5f - radius;
            int inset = (int) Math.ceil(radius - Math.sqrt(radius * radius - distance * distance));
            fill(c, x + inset, y + row, diameter - inset * 2, 1, color);
        }
    }

    private static void outline(GUIContext c, float x, float y, float w, float h, int color) {
        fill(c, x, y, w, 1, color);
        fill(c, x, y + h - 1, w, 1, color);
        fill(c, x, y, 1, h, color);
        fill(c, x + w - 1, y, 1, h, color);
    }

    private static void disc(GUIContext c, float x, float y, int color) {
        fill(c, x + 1, y, 3, 5, color);
        fill(c, x, y + 1, 5, 3, color);
    }

    private static void checkmark(GUIContext c, float x, float y, int color) {
        fill(c, x + 1, y + 4, 2, 2, color);
        fill(c, x + 2, y + 5, 2, 2, color);
        for (int i = 0; i < 4; i++) fill(c, x + 3 + i, y + 4 - i, 2, 2, color);
    }

    private static void drawBullet(GUIContext c, float x, float y, String label, int color) {
        float bx = x + 6, by = y + 3;
        switch (label) {
            case "•" -> {
                fill(c, bx + 1, by, 3, 1, color);
                fill(c, bx, by + 1, 5, 3, color);
                fill(c, bx + 1, by + 4, 3, 1, color);
            }
            case "◦" -> {
                fill(c, bx + 1, by, 3, 1, color);
                fill(c, bx, by + 1, 1, 3, color);
                fill(c, bx + 4, by + 1, 1, 3, color);
                fill(c, bx + 1, by + 4, 3, 1, color);
            }
            case "▪" -> fill(c, bx, by, 4, 4, color);
            default -> c.graphics.drawString(c.mc.font, label, (int) x, (int) y, color, false);
        }
    }

    private static void fill(GUIContext c, float x, float y, float w, float h, int color) {
        c.graphics.fill((int) x, (int) y, (int) Math.ceil(x + w), (int) Math.ceil(y + h), color);
    }

    @Override
    public void close() {
        closed = true;
        clearHiddenTexts();
        session.unlisten(changed);
        images.close();
        for (var view : customViews.values()) view.removeSelf();
        customViews.clear();
    }
}
