package com.zhenshiz.betterbook.client;

import static com.zhenshiz.betterbook.client.Widgets.*;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.math.Size;
import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.zhenshiz.betterbook.core.*;
import com.zhenshiz.betterbook.data.BookCommands;

import dev.vfyjxf.taffy.style.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/** 支持单页、双页和阶段访问的阅读器；读者状态使用独立副本。 */
public final class BookReaderScreen extends ModularUIScreen {
    private static final float SINGLE_WIDTH = 210, SINGLE_HEIGHT = 300, SINGLE_CONTENT_SCALE = .8f;

    private static final class Root extends UIElement {
        private final boolean followGuiScale;
        private final boolean singlePage;

        private Root(boolean followGuiScale, boolean singlePage) {
            this.followGuiScale = followGuiScale;
            this.singlePage = singlePage;
        }

        private int autoScale() {
            var minecraft = Minecraft.getInstance();
            return minecraft.getWindow().calculateScale(0, minecraft.isEnforceUnicode());
        }

        private Size canvasSize(Size screenSize) {
            Size size = screenSize;
            if (!followGuiScale) {
                var window = Minecraft.getInstance().getWindow();
                int scale = autoScale();
                size = Size.of(
                        (int) Math.ceil(window.getWidth() / (double) scale),
                        (int) Math.ceil(window.getHeight() / (double) scale));
            }
            if (singlePage) {
                // 同时约束宽高，窗口变矮时仍保持竖版比例。
                float fit = Math.min(1, Math.min(size.width * .96f / SINGLE_WIDTH,
                        size.height * .92f / SINGLE_HEIGHT));
                selectId("reader-book").findFirst().ifPresent(shell ->
                        shell.getLayout().width(SINGLE_WIDTH * fit).height(SINGLE_HEIGHT * fit));
            }
            return size;
        }

        @Override
        public void initScreen(int screenWidth, int screenHeight) {
            super.initScreen(screenWidth, screenHeight);
            float scale = followGuiScale
                    ? 1f
                    : (float) (autoScale() / Minecraft.getInstance().getWindow().getGuiScale());
            // 布局与命中测试共用根节点变换，不修改 Minecraft 的全局 GUI 比例。
            transform(transform -> transform.pivot(.5f, .5f).scale(scale));
        }
    }

    private final Book book;
    private final BookPageAccess access;
    private final boolean authorPreview;
    private final Screen back;
    private final UIElement spread = new UIElement();
    private final UIElement noticeHost = new UIElement();
    private final Label noticeText = new Label();
    private final Label pageNumber = new Label();
    private final Button previousButton = new Button(),
            nextButton = new Button(),
            backButton = new Button();
    private final Map<String, RichSurface> surfaces = new HashMap<>();
    private final Deque<String> navigation = new ArrayDeque<>();
    private int first;
    private int currentPage;
    private boolean previewLayer;
    private int highlightedPage = -1;
    private long highlightUntil;
    private long stageRevision;
    private long noticeUntil;
    private String noticeMessage = "";

    /**
     * 以屏幕层打开预览，保留底层编辑菜单及未保存的书籍。
     *
     * @param book 当前书籍
     * @param language 当前编辑语言
     */
    public static void openPreview(Book book, String language) {
        var minecraft = Minecraft.getInstance();
        var reader = new BookReaderScreen(book, minecraft.screen, language, true);
        reader.previewLayer = true;
        minecraft.pushGuiLayer(reader);
    }

    public BookReaderScreen(Book book, Screen back) {
        this(book, back, Minecraft.getInstance().options.languageCode);
    }

    public BookReaderScreen(Book book, Screen back, String language) {
        this(book, back, language, false);
    }

    private BookReaderScreen(Book book, Screen back, String language, boolean authorPreview) {
        this(book.localized(language), back, new Root(book.followGuiScale, book.singlePage), authorPreview);
    }

    private BookReaderScreen(Book source, Screen back, Root root, boolean authorPreview) {
        super(
                new ModularUI(
                                UI.of(
                                        root,
                                        List.of(
                                                StylesheetManager.INSTANCE.getStylesheetSafe(
                                                        ResourceLocation.parse(
                                                                "ldlib2:lss/gdp.lss")),
                                                StylesheetManager.INSTANCE.getStylesheetSafe(
                                                        ResourceLocation.parse(
                                                                "betterbook:lss/book.lss"))),
                                        root::canvasSize),
                                Minecraft.getInstance().player)
                        .shouldCloseOnKeyInventory(false),
                Component.literal(source.title));
        this.book = source;
        this.authorPreview = authorPreview;
        access = new BookPageAccess(book, stage -> authorPreview || ClientBookStages.has(stage));
        stageRevision = ClientBookStages.revision();
        this.back = back;
        root.setId("book-reader-root");
        root.getLayout()
                .widthPercent(100)
                .heightPercent(100)
                .alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER);
        var shell = new UIElement().addClass("book-reader-spread");
        shell.setId("reader-book");
        shell.getLayout().width(book.singlePage ? SINGLE_WIDTH : 540).maxWidthPercent(96)
                .height(book.singlePage ? SINGLE_HEIGHT : 330).maxHeightPercent(92);
        spread.setId("book-spread");
        spread.getLayout()
                .flexDirection(FlexDirection.ROW)
                .widthPercent(100)
                .flex(1)
                .minHeight(0)
                .gapAll(0);
        shell.addChild(spread);
        var buttons = row();
        buttons.setId("reader-navigation");
        buttons.getLayout()
                .positionType(TaffyPosition.ABSOLUTE)
                .left(22)
                .right(22)
                .bottom(16)
                .widthAuto()
                .height(18)
                .alignItems(AlignItems.CENTER)
                .gapAll(0);
        navigationButton(previousButton, "reader-previous", "previous", () -> turn(-1));
        navigationButton(nextButton, "reader-next", "next", () -> turn(1));
        navigationButton(
                backButton,
                "reader-back",
                "back",
                this::returnToHistory);
        backButton.getLayout().width(16).height(16);
        var leftNavigation = row();
        leftNavigation
                .getLayout()
                .width(0)
                .flex(1)
                .minWidth(0)
                .paddingRight(16)
                .alignItems(AlignItems.CENTER);
        if (book.allowPageTurning) leftNavigation.addChild(previousButton);
        leftNavigation.addChildren(new UIElement().layout(l -> l.flex(1)), pageNumber);
        var rightNavigation = row();
        rightNavigation
                .getLayout()
                .width(0)
                .flex(1)
                .minWidth(0)
                .paddingLeft(16)
                .alignItems(AlignItems.CENTER);
        rightNavigation.addChildren(backButton, new UIElement().layout(l -> l.flex(1)));
        if (book.allowPageTurning) rightNavigation.addChild(nextButton);
        if (book.singlePage) {
            if (book.allowPageTurning) buttons.addChild(previousButton);
            buttons.addChild(backButton);
            buttons.addChildren(new UIElement().layout(l -> l.flex(1)), pageNumber);
            if (book.allowPageTurning) buttons.addChild(nextButton);
        } else buttons.addChildren(leftNavigation, rightNavigation);
        pageNumber.setId("reader-page-number");
        pageNumber.addClass("book-reader-page-number");
        pageNumber.getLayout().width(70).height(18).flexShrink(0);
        pageNumber.textStyle(
                s ->
                        s.textShadow(false)
                                .textAlignHorizontal(Horizontal.RIGHT)
                                .textAlignVertical(Vertical.CENTER));
        shell.addChild(buttons);
        root.addChild(shell);
        noticeHost.setId("reader-notice-host");
        noticeHost.getLayout().positionType(TaffyPosition.ABSOLUTE).left(0).right(0).top(8)
                .widthAuto().heightAuto().alignItems(AlignItems.CENTER);
        var notice = new UIElement().addClass("book-reader-notice");
        notice.setId("reader-notice");
        notice.getLayout().width(320).maxWidthPercent(90).heightAuto().minWidth(0)
                .flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).paddingAll(8).gapAll(6);
        noticeText.setId("reader-notice-text");
        noticeText.addClass("book-reader-notice-text");
        noticeText.getLayout().width(0).flex(1).minWidth(0).heightAuto();
        noticeText.textStyle(s -> s.adaptiveWidth(false).adaptiveHeight(true)
                .textWrap(TextWrap.WRAP).textShadow(false));
        notice.addChild(noticeText);
        noticeHost.addChild(notice);
        noticeHost.setDisplay(false);
        root.addChild(noticeHost);
        root.addEventListener(
                com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.TICK,
                e -> {
                    if (noticeHost.isDisplayed() && net.minecraft.Util.getMillis() >= noticeUntil)
                        noticeHost.setDisplay(false);
                    if (!authorPreview && stageRevision != ClientBookStages.revision()) {
                        stageRevision = ClientBookStages.revision();
                        refreshAccess();
                    }
                    if (highlightedPage >= 0 && net.minecraft.Util.getMillis() >= highlightUntil) {
                        highlightedPage = -1;
                        spread.getChildren()
                                .forEach(paper -> paper.removeClass("book-related-destination"));
                    }
                });
        renderSpread();
    }

    private void navigationButton(Button button, String id, String label, Runnable action) {
        button.setId(id);
        button.addClass("book-reader-arrow");
        button.noText().setOnClick(e -> action.run());
        button.getLayout().width(23).height(13).paddingAll(0).flexShrink(0);
        button.getStyle().tooltips(Component.translatable("gui.betterbook." + label));
    }

    private void enabled(Button button, boolean enabled) {
        button.setActive(enabled);
        if (enabled) button.removeClass("book-reader-arrow-disabled");
        else button.addClass("book-reader-arrow-disabled");
    }

    public int firstPage() {
        return first;
    }

    public RichSurface pageSurface(int index) {
        return surfaces.get(book.pages.get(index).id());
    }

    private void go(int index, boolean history) {
        int count = book.singlePage ? 1 : 2;
        int page = Math.clamp(index, 0, book.pages.size() - 1);
        int target = page / count * count;
        if (target == first && page == currentPage) return;
        // 双页布局也记录实际链接目标，不能把右页的访问历史换成同组左页。
        if (history && access.canRead(book.pages.get(currentPage).id()))
            navigation.addLast(book.pages.get(currentPage).id());
        first = target;
        currentPage = page;
        renderSpread();
    }

    private void renderSpread() {
        spread.clearAllChildren();
        int count = book.singlePage ? 1 : 2;
        for (int side = 0; side < count; side++) {
            var paper = new VanillaBookPage(side == 0, book.singlePage);
            paper.setId(side == 0 ? "reader-left-page" : "reader-right-page");
            paper.getLayout()
                    .flex(1)
                    .minWidth(0)
                    .heightPercent(100)
                    .paddingHorizontal(18)
                    .paddingTop(16)
                    .paddingBottom(42);
            int index = first + side;
            if (index < book.pages.size()) {
                var p = book.pages.get(index);
                if (!access.canRead(p.id())) {
                    paper.addChild(lockedPage(index, p.id()));
                    spread.addChild(paper);
                    continue;
                }
                var surface =
                        surfaces.computeIfAbsent(
                                p.id(),
                                id -> {
                                    var ext = BookExtensions.create();
                                    var single = new Book();
                                    single.defaultLanguage = book.defaultLanguage;
                                    single.pages.add(p);
                                    var s = new RichSurface(new BookSession(single), ext, false);
                                    s.navigationBook(book);
                                    s.pageAccess(access);
                                    s.setId("reader-content-" + index);
                                    s.onLink(href -> openLink(href, s));
                                    s.onCommand(
                                            action -> {
                                                if (Minecraft.getInstance().getConnection()
                                                        == null) {
                                                    showNotice(Component.translatable("gui.betterbook.command_requires_world"));
                                                    return;
                                                }
                                                RPCPacketDistributor.rpcToServer(
                                                        BookCommands.EXECUTE,
                                                        book.id,
                                                        p.id(),
                                                        book.defaultLanguage,
                                                        action);
                                            });
                                    return s;
                                });
                if (book.singlePage) {
                    var viewport = new UIElement();
                    viewport.setId("reader-viewport-" + index);
                    viewport.getLayout().widthPercent(100).heightPercent(100).minWidth(0).minHeight(0);
                    viewport.setOverflowVisible(false);
                    surface.getLayout().positionType(TaffyPosition.ABSOLUTE).left(0).top(0)
                            .widthPercent(100 / SINGLE_CONTENT_SCALE).heightPercent(100 / SINGLE_CONTENT_SCALE);
                    surface.transform(t -> t.pivot(0, 0).scale(SINGLE_CONTENT_SCALE));
                    viewport.addChild(surface);
                    paper.addChild(viewport);
                } else paper.addChild(surface);
            }
            spread.addChild(paper);
        }
        pageNumber.setText(
                (first + 1)
                        + (!book.singlePage && first + 1 < book.pages.size() ? "–" + (first + 2) : "")
                        + " / "
                        + book.pages.size(),
                false);
        enabled(previousButton, first > 0);
        enabled(nextButton, first + count < book.pages.size());
        navigation.removeIf(id -> !access.canRead(id));
        enabled(backButton, !navigation.isEmpty());
    }

    private void turn(int direction) {
        if (book.allowPageTurning) go(first + direction * (book.singlePage ? 1 : 2), true);
    }

    private void returnToHistory() {
        while (!navigation.isEmpty()) {
            String id = navigation.removeLast();
            if (!access.canRead(id)) continue;
            go(access.index(id), false);
            break;
        }
        enabled(backButton, !navigation.isEmpty());
    }

    private UIElement lockedPage(int index, String id) {
        var placeholder = new UIElement();
        placeholder.setId("reader-locked-" + index);
        placeholder.getLayout().widthPercent(100).flex(1).minHeight(0)
                .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER).gapAll(12);
        var icon = new UIElement().layout(l -> l.width(32).height(32).flexShrink(0))
                .style(s -> s.background(BookLocks.ICON));
        var title = new Label().setText("gui.betterbook.page_locked");
        title.addClass("book-locked-title");
        title.getLayout().widthPercent(100).heightAuto();
        title.textStyle(s -> s.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP)
                .textShadow(false).textAlignHorizontal(Horizontal.CENTER));
        var hint = new Label().setText(BookLocks.hint(access, id));
        hint.setId("reader-unlock-hint-" + index);
        hint.addClass("book-locked-hint");
        hint.getLayout().widthPercent(100).heightAuto();
        hint.textStyle(s -> s.textWrap(TextWrap.WRAP).textShadow(false)
                .textAlignHorizontal(Horizontal.CENTER).adaptiveHeight(true));
        placeholder.addChildren(icon, title, hint);
        return placeholder;
    }

    private void refreshAccess() {
        noticeHost.setDisplay(false);
        var iterator = surfaces.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!access.canRead(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            } else entry.getValue().refreshPageAccess();
        }
        highlightedPage = -1;
        renderSpread();
    }

    private void openLink(String href, RichSurface surface) {
        if (href.startsWith("book:")) {
            String id = href.substring(5);
            if (access.contains(id) && !access.canRead(id)) {
                modularUI.setHoverTooltip(List.of(BookLocks.message(access, id)),
                        net.minecraft.world.item.ItemStack.EMPTY, null, null);
                return;
            }
            for (int i = 0; i < book.pages.size(); i++)
                if (book.pages.get(i).id().equals(id)) {
                    go(i, true);
                    highlightedPage = i;
                    highlightUntil = net.minecraft.Util.getMillis() + 1500;
                    for (int side = 0; side < spread.getChildren().size(); side++) {
                        var paper = spread.getChildren().get(side);
                        if (first + side == i) paper.addClass("book-related-destination");
                        else paper.removeClass("book-related-destination");
                    }
                    return;
                }
            showNotice(Component.translatable("gui.betterbook.link_missing"));
        } else surface.externalLink(href);
    }

    private void showNotice(Component message) {
        String text = message.getString();
        noticeUntil = net.minecraft.Util.getMillis() + 3000;
        if (!text.equals(noticeMessage)) {
            noticeMessage = text;
            noticeText.setText(message);
        }
        noticeHost.setDisplay(true);
    }

    @Override
    public void onClose() {
        for (var s : surfaces.values()) s.close();
        surfaces.clear();
        if (previewLayer) Minecraft.getInstance().popGuiLayer();
        else Minecraft.getInstance().setScreen(back);
    }
}
