package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.client.BookClientMenus;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** 通过 LDLib2 玩家菜单打开编辑器，服务端只持有菜单生命周期。 */
@EventBusSubscriber(modid = BetterBook.ID)
public final class BookMenus {
    public static final ResourceLocation EDITOR =
            ResourceLocation.fromNamespaceAndPath(BetterBook.ID, "editor");
    public static final ResourceLocation BINDING =
            ResourceLocation.fromNamespaceAndPath(BetterBook.ID, "binding");

    private BookMenus() {}

    /** 注册两端共用的菜单标识；客户端负责构建 VSL 编辑窗口。 */
    public static void register() {
        PlayerUIMenuType.register(
                EDITOR,
                ignored ->
                        player -> {
                            if (player.level().isClientSide)
                                return BookClientMenus.editorUI(player);
                            return new ModularUI(UI.empty(), player);
                        });
        PlayerUIMenuType.register(
                BINDING,
                ignored ->
                        player ->
                                player.level().isClientSide
                                        ? com.zhenshiz.betterbook.client.BookBindingPanel.create(
                                                player)
                                        : new ModularUI(UI.empty(), player));
    }

    /**
     * 注册玩家编辑入口，使用原生菜单打开和关闭协议。
     *
     * @param event 服务端命令注册事件
     */
    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("betterbook")
                                .then(
                                        Commands.literal("editor")
                                                .executes(
                                                        context ->
                                                                PlayerUIMenuType.openUI(
                                                                                context.getSource()
                                                                                        .getPlayerOrException(),
                                                                                EDITOR)
                                                                        ? 1
                                                                        : 0)
                                                .then(
                                                        Commands.argument(
                                                                        "path",
                                                                        StringArgumentType
                                                                                .greedyString())
                                                                .requires(
                                                                        source ->
                                                                                source
                                                                                        .hasPermission(
                                                                                                2))
                                                                .suggests(
                                                                        (context, builder) ->
                                                                                SharedSuggestionProvider
                                                                                        .suggest(
                                                                                                ServerBooks
                                                                                                        .list(),
                                                                                                builder))
                                                                .executes(
                                                                        context ->
                                                                                ServerBooks.open(
                                                                                        context.getSource()
                                                                                                .getPlayerOrException(),
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        context,
                                                                                                        "path"),
                                                                                        true))))
                                .then(
                                        Commands.literal("open")
                                                .then(
                                                        Commands.argument(
                                                                        "path",
                                                                        StringArgumentType
                                                                                .greedyString())
                                                                .suggests(
                                                                        (context, builder) ->
                                                                                SharedSuggestionProvider
                                                                                        .suggest(
                                                                                                ServerBooks
                                                                                                        .list(),
                                                                                                builder))
                                                                .executes(
                                                                        context ->
                                                                                ServerBooks.open(
                                                                                        context.getSource()
                                                                                                .getPlayerOrException(),
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        context,
                                                                                                        "path"),
                                                                                        false)))));
    }
}
