package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.viscript_lib.gui.components.search.RegistrySearchBox;
import com.zhenshiz.betterbook.data.ServerBooks;

import dev.vfyjxf.taffy.style.*;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 空白书绑定服务端文件的原生菜单，候选与打开指令来自同一目录。 */
public final class BookBindingPanel extends UIElement {
    private final Label error = new Label();

    private BookBindingPanel() {
        setId("book-binding-panel");
        addClass("panel_bg");
        getLayout().width(290).maxWidthPercent(92).paddingAll(12).gapAll(8);
        addChild(new Label().setText("gui.betterbook.book_binding_loading"));
    }

    /**
     * 构建绑定文件的玩家菜单，保留 Esc 关闭行为。
     *
     * @param player 菜单所属客户端玩家
     * @return 包含 VSL 补全输入框的 LDLib2 界面
     */
    public static ModularUI create(Player player) {
        var root = new UIElement();
        root.getLayout()
                .widthPercent(100)
                .heightPercent(100)
                .alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER);
        root.addChild(new BookBindingPanel());
        return new ModularUI(
                        UI.of(
                                root,
                                List.of(
                                        StylesheetManager.INSTANCE.getStylesheetSafe(
                                                StylesheetManager.GDP)),
                                size -> size),
                        player)
                .shouldCloseOnKeyInventory(false);
    }

    /**
     * 使用服务端目录替换加载状态，确认时提交菜单编号和相对路径。
     *
     * @param menu 当前菜单编号
     * @param paths 服务端文件候选
     */
    public void configure(int menu, List<String> paths) {
        clearAllChildren();
        var search = new PathSearch(paths);
        search.setId("book-binding-path");
        search.textField.setId("book-binding-path-input");
        search.getSearchStyle().closeAfterSelect(true);
        search.getLayout().widthPercent(100).height(20);
        search.registerValueListener(value -> error.setDisplay(false));
        error.setId("book-binding-error");
        error.setDisplay(false);
        error.getLayout().widthPercent(100);
        error.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        var buttons = Widgets.row();
        buttons.addChildren(
                Widgets.button(
                        "cancel",
                        "book-binding-cancel",
                        () -> Minecraft.getInstance().player.closeContainer()),
                Widgets.button(
                        "link_confirm",
                        "book-binding-confirm",
                        () -> {
                            String path = search.textField.getValue().trim();
                            if (path.isBlank()) {
                                error(Component.translatable("gui.betterbook.book_binding_choose"));
                                return;
                            }
                            RPCPacketDistributor.rpcToServer(ServerBooks.BIND, menu, path);
                        }));
        addChildren(
                new Label().setText("gui.betterbook.book_binding_title"),
                new Label().setText("gui.betterbook.book_binding_path"),
                search,
                error,
                buttons);
    }

    private static final class PathSearch extends RegistrySearchBox<String> {
        PathSearch(List<String> paths) {
            super(
                    null,
                    () -> null,
                    value ->
                            ResourceLocation.fromNamespaceAndPath(
                                    "betterbook",
                                    HexFormat.of()
                                            .formatHex(value.getBytes(StandardCharsets.UTF_8))),
                    value -> value,
                    (query, handler) -> {
                        for (String path : paths) {
                            if (Thread.currentThread().isInterrupted()) return;
                            if (path.toLowerCase(Locale.ROOT)
                                    .contains(query.toLowerCase(Locale.ROOT)))
                                handler.acceptResult(path);
                        }
                    },
                    UIElementProvider.text(Component::literal));
        }
    }

    /**
     * 在输入框下显示绑定错误并保留已输入路径。
     *
     * @param message 要显示的错误组件
     */
    public void error(Component message) {
        error.setText(message);
        error.setDisplay(true);
    }
}
