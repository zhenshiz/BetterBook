package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacket;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;
import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.api.BetterBookStages;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 从服务端书籍解析点击绑定，以点击者为执行者运行指令。 */
public final class BookCommands {
    public static final String EXECUTE = "betterbook:execute_text_command";
    private static final Map<ServerPlayer, Integer> LAST_CLICK = new WeakHashMap<>();

    private BookCommands() {}

    /**
     * 获取与 VSL 上传共用的服务端书籍目录。
     *
     * @return 书籍运行时文件目录
     */
    public static Path directory() {
        return BookFiles.FORMAT.functionDirectory().toPath();
    }

    /**
     * 接收点击标识；指令文本只从服务端文件读取，执行权限固定为 2。
     *
     * @param sender RPC 发送者
     * @param bookId 书籍标识
     * @param pageId 页面标识
     * @param language 阅读语言
     * @param actionId 指令绑定标识
     */
    @RPCPacket(EXECUTE)
    public static void receive(
            RPCSender sender, String bookId, String pageId, String language, String actionId) {
        var player = sender.asPlayer();
        if (sender.isServer()
                || player == null
                || bookId == null
                || pageId == null
                || language == null
                || actionId == null
                || bookId.length() > 64
                || pageId.length() > 64
                || language.length() > 35
                || actionId.length() > 64) return;
        var server = player.getServer();
        if (server == null) return;
        server.execute(
                () -> {
                    int tick = server.getTickCount();
                    Integer last = LAST_CLICK.put(player, tick);
                    if (last != null && tick - last < 5) return;
                    try {
                        var command = find(server, player, bookId, pageId, language, actionId);
                        if (command.isEmpty()) {
                            player.displayClientMessage(
                                    Component.translatable("gui.betterbook.command_unavailable"),
                                    false);
                            return;
                        }
                        var action = command.get();
                        server.getCommands()
                                .performPrefixedCommand(
                                        player.createCommandSourceStack()
                                                .withPermission(TextCommand.EXECUTION_PERMISSION),
                                        action.command());
                    } catch (IOException | IllegalArgumentException e) {
                        BetterBook.LOGGER.warn("Unable to resolve book command {}", actionId, e);
                        player.displayClientMessage(
                                Component.translatable("gui.betterbook.command_unavailable"),
                                false);
                    }
                });
    }

    private static Optional<TextCommand> find(
            MinecraftServer server,
            ServerPlayer player,
            String bookId,
            String pageId,
            String language,
            String actionId)
            throws IOException {
        var schema = StandardSchema.create();
        Path root = directory();
        if (Files.isDirectory(root))
            try (var files = Files.walk(root)) {
                var candidates =
                        files.filter(
                                        p ->
                                                Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                                                        && p.getFileName()
                                                                .toString()
                                                                .endsWith(".book"))
                                .iterator();
                while (candidates.hasNext()) {
                    var file = candidates.next();
                    try {
                        var book = BookFiles.read(file, schema);
                        var action = find(book, bookId, pageId, language, actionId);
                        if (action.isPresent()) return authorize(book, player, pageId, action);
                    } catch (IOException | IllegalArgumentException e) {
                        BetterBook.LOGGER.warn("Cannot read command book {}", file, e);
                    }
                }
            }
        for (var resource :
                server.getResourceManager()
                        .listResources("betterbook/books", id -> id.getPath().endsWith(".book"))
                        .values()) {
            try (var input = resource.open()) {
                var book = BookFiles.read(input, schema);
                var action = find(book, bookId, pageId, language, actionId);
                if (action.isPresent()) return authorize(book, player, pageId, action);
            }
        }
        return Optional.empty();
    }

    private static Optional<TextCommand> authorize(
            Book book, ServerPlayer player, String pageId, Optional<TextCommand> action) {
        // 命中后直接拒绝，不能继续从另一份旧书查找同一个绑定来绕过阶段要求。
        return new BookPageAccess(book, stage -> BetterBookStages.has(player, stage)).canRead(pageId)
                ? action
                : Optional.empty();
    }

    /**
     * 在服务端书籍中查找指定语言和页面的绑定。
     *
     * @param book 服务端读取的书籍
     * @param bookId 请求的书籍标识
     * @param pageId 请求的页面标识
     * @param language 阅读语言，缺失译文时回退默认语言
     * @param actionId 请求的指令标识
     * @return 匹配的绑定，不接收客户端指令文本或权限
     */
    public static Optional<TextCommand> find(
            Book book, String bookId, String pageId, String language, String actionId) {
        if (!book.id.equals(bookId)) return Optional.empty();
        for (int i = 0; i < book.pages.size(); i++) {
            if (!book.pages.get(i).id().equals(pageId)) continue;
            return book.page(i, language).document().blocks().stream()
                    .filter(b -> !b.code() && !b.atom())
                    .flatMap(b -> b.runs().stream())
                    .flatMap(r -> r.marks().stream())
                    .filter(m -> m.id().equals(TextCommand.MARK))
                    .map(m -> TextCommand.read(m.attributes()))
                    .flatMap(Optional::stream)
                    .filter(action -> action.id().equals(actionId))
                    .findFirst();
        }
        return Optional.empty();
    }
}
