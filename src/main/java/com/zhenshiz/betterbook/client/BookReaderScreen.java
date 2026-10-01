package com.zhenshiz.betterbook.client;

import static com.zhenshiz.betterbook.client.Widgets.*;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.zhenshiz.betterbook.core.*;
import com.zhenshiz.betterbook.data.BookCommands;

import dev.vfyjxf.taffy.style.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/** 双页阅读器；内容和交互状态使用独立副本，永不改写作者的书籍。 */
public final class BookReaderScreen extends ModularUIScreen {
    private static final class Root extends UIElement {}

    private final Book book;
    private final Screen back;
    private final UIElement spread = new UIElement();
    private final Label pageNumber = new Label();
    private final Button previousButton = new Button(),
            nextButton = new Button(),
            backButton = new Button();
    private final Map<String, RichSurface> surfaces = new HashMap<>();
    private final Deque<Integer> navigation = new ArrayDeque<>();
    private int first;
    private boolean previewLayer;
    private int highlightedPage = -1;
    private long highlightUntil;

    /**
     * 以屏幕层打开预览，保留底层编辑菜单及未保存的书籍。
     *
     * @param book 当前书籍
     * @param language 当前编辑语言
     */
    public static void openPreview(Book book, String language) {
        var minecraft = Minecraft.getInstance();
        var reader = new BookReaderScreen(book, minecraft.screen, language);
        reader.previewLayer = true;
        minecraft.pushGuiLayer(reader);
    }

    public BookReaderScreen(Book book, Screen back) {
        this(book, back, Minecraft.getInstance().options.languageCode);
    }

    public BookReaderScreen(Book book, Screen back, String language) {
        this(book.localized(language), back, new Root());
    }

    private BookReaderScreen(Book source, Screen back, Root root) {
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
                                        size -> size),
                                Minecraft.getInstance().player)
                        .shouldCloseOnKeyInventory(false),
                Component.literal(source.title));
        this.book = source;
        this.back = back;
        root.setId("book-reader-root");
        root.getLayout()
                .widthPercent(100)
                .heightPercent(100)
                .alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER);
        var shell = new UIElement().addClass("book-reader-spread");
        shell.setId("reader-book");
        shell.getLayout().width(540).maxWidthPercent(96).height(330).maxHeightPercent(92);
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
        navigationButton(previousButton, "reader-previous", "previous", () -> go(first - 2, true));
        navigationButton(nextButton, "reader-next", "next", () -> go(first + 2, true));
        navigationButton(
                backButton,
                "reader-back",
                "back",
                () -> {
                    if (!navigation.isEmpty()) go(navigation.removeLast(), false);
                });
        backButton.getLayout().width(16).height(16);
        var leftNavigation = row();
        leftNavigation
                .getLayout()
                .width(0)
                .flex(1)
                .minWidth(0)
                .paddingRight(16)
                .alignItems(AlignItems.CENTER);
        leftNavigation.addChildren(
                previousButton, new UIElement().layout(l -> l.flex(1)), pageNumber);
        var rightNavigation = row();
        rightNavigation
                .getLayout()
                .width(0)
                .flex(1)
                .minWidth(0)
                .paddingLeft(16)
                .alignItems(AlignItems.CENTER);
        rightNavigation.addChildren(backButton, new UIElement().layout(l -> l.flex(1)), nextButton);
        buttons.addChildren(leftNavigation, rightNavigation);
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
        root.addEventListener(
                com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.TICK,
                e -> {
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
        int target = Math.clamp(index, 0, (book.pages.size() - 1) / 2 * 2);
        target = target / 2 * 2;
        if (target == first) return;
        if (history) navigation.addLast(first);
        first = target;
        renderSpread();
    }

    private void renderSpread() {
        spread.clearAllChildren();
        for (int side = 0; side < 2; side++) {
            var paper = new VanillaBookPage(side == 0);
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
                                    s.setId("reader-content-" + index);
                                    s.onLink(href -> openLink(href, s));
                                    s.onCommand(
                                            action -> {
                                                if (Minecraft.getInstance().getConnection()
                                                        == null) {
                                                    Dialog.showNotification(
                                                                    "gui.betterbook.command_requires_world",
                                                                    3)
                                                            .show(modularUI);
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
                paper.addChild(surface);
            }
            spread.addChild(paper);
        }
        pageNumber.setText(
                (first + 1)
                        + (first + 1 < book.pages.size() ? "–" + (first + 2) : "")
                        + " / "
                        + book.pages.size(),
                false);
        enabled(previousButton, first > 0);
        enabled(nextButton, first + 2 < book.pages.size());
        enabled(backButton, !navigation.isEmpty());
    }

    private void openLink(String href, RichSurface surface) {
        if (href.startsWith("book:")) {
            String id = href.substring(5);
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
            Dialog.showNotification("gui.betterbook.link_missing", 3).show(modularUI);
        } else surface.externalLink(href);
    }

    @Override
    public void onClose() {
        for (var s : surfaces.values()) s.close();
        surfaces.clear();
        if (previewLayer) Minecraft.getInstance().popGuiLayer();
        else Minecraft.getInstance().setScreen(back);
    }
}
