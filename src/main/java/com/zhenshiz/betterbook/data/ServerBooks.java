package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.lowdragmc.lowdraglib2.networking.rpc.*;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;
import com.viscript_lib.gui.editor.EditorAssetFiles;
import com.viscript_lib.gui.editor.EditorFileFormat;
import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.client.RuntimeBooks;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 服务端运行时书籍的目录、打开及物品绑定入口，传输使用 LDLib2 RPC。 */
@EventBusSubscriber(modid = BetterBook.ID)
public final class ServerBooks {
    public static final EditorFileFormat FORMAT = BookFiles.FORMAT;
    public static final int MAX_BYTES = 64 * 1024 * 1024, CHUNK_SIZE = 256 * 1024;
    public static final String BEGIN = "betterbook:book_begin",
            CHUNK = "betterbook:book_chunk",
            CONFIGURE = "betterbook:book_configure",
            BIND = "betterbook:book_bind",
            SAVE = "betterbook:book_save",
            ERROR = "betterbook:book_binding_error";

    private record Binding(InteractionHand hand, ItemStack stack, int menu) {}

    private record Loaded(String path, Book book, byte[] bytes) {}

    private static final Map<ServerPlayer, Binding> BINDINGS = new WeakHashMap<>();

    private ServerBooks() {}

    /**
     * 在菜单关闭时撤销待绑定请求，防止后续菜单重复使用编号。
     *
     * @param event 玩家关闭容器事件
     */
    @SubscribeEvent
    public static void closed(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) BINDINGS.remove(player);
    }

    /**
     * 清理退出玩家尚未确认的绑定操作。
     *
     * @param event 玩家退出事件
     */
    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BINDINGS.remove(player);
    }

    /**
     * 解析 VSL 运行时相对路径并阻止符号链接逃出书籍目录。
     *
     * @param name 相对路径，允许省略 .book
     * @return 服务端绝对路径
     * @throws IOException 路径无效或链接指向目录外
     */
    public static Path resolve(String name) throws IOException {
        if (name == null
                || name.isBlank()
                || name.length() > 256
                || name.startsWith("/")
                || name.contains(":")) throw new IOException("Invalid book path");
        var file = EditorAssetFiles.resolveRuntimeFile(FORMAT, name, true);
        var root = FORMAT.functionDirectory().toPath().toAbsolutePath().normalize();
        if (Files.exists(root)) {
            Path existing = file;
            while (!Files.exists(existing)) existing = existing.getParent();
            if (!existing.toRealPath().startsWith(root.toRealPath()))
                throw new IOException("Book path is outside the runtime directory");
        }
        return file;
    }

    /**
     * 列出可读取的运行时相对路径，最多返回 2048 项和 20000 个路径字符。
     *
     * @return 按路径排序的不可变列表，不包含 .book 后缀
     */
    public static List<String> list() {
        var result = new ArrayList<String>();
        int size = 0;
        for (String name : EditorAssetFiles.listRuntimeFiles(FORMAT, true)) {
            try {
                resolve(name);
            } catch (Exception ignored) {
                continue;
            }
            if ((size += name.length()) > 20000 || result.size() >= 2048) break;
            result.add(name);
        }
        return List.copyOf(result);
    }

    private static Loaded load(String name) throws IOException {
        Path file = resolve(name);
        if (Files.size(file) > MAX_BYTES) throw new IOException("Book exceeds 64 MiB");
        byte[] bytes;
        try (var in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Book exceeds 64 MiB");
        var book = BookFiles.read(new ByteArrayInputStream(bytes), StandardSchema.create());
        String relative =
                FORMAT.functionDirectory()
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                        .relativize(file)
                        .toString()
                        .replace('\\', '/');
        return new Loaded(relative, book, bytes);
    }

    /**
     * 从服务端读取并打开编辑器或阅读界面。
     *
     * @param player 目标玩家
     * @param name 服务端相对路径
     * @param editor 是否编辑
     * @return 成功时为 1，读取失败时为 0
     */
    public static int open(ServerPlayer player, String name, boolean editor) {
        if (editor && !player.hasPermissions(2)) return 0;
        try {
            var loaded = load(name);
            send(player, loaded, editor);
            return 1;
        } catch (Exception e) {
            player.displayClientMessage(
                    Component.translatable(
                            "gui.betterbook.read_failed", name + ": " + e.getMessage()),
                    false);
            return 0;
        }
    }

    private static void send(ServerPlayer player, Loaded loaded, boolean editor) {
        if (editor) PlayerUIMenuType.openUI(player, BookMenus.EDITOR);
        else {
            player.closeContainer();
            PlayerStages.sync(player);
        }
        String transfer = UUID.randomUUID().toString();
        int total = (loaded.bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        RPCPacketDistributor.rpcToPlayer(
                player,
                BEGIN,
                transfer,
                loaded.path,
                editor,
                player.containerMenu.containerId,
                total);
        for (int i = 0; i < total; i++) {
            var data = new CompoundTag();
            data.putByteArray(
                    "bytes",
                    Arrays.copyOfRange(
                            loaded.bytes,
                            i * CHUNK_SIZE,
                            Math.min(loaded.bytes.length, (i + 1) * CHUNK_SIZE)));
            RPCPacketDistributor.rpcToPlayer(player, CHUNK, transfer, i, data);
        }
    }

    /**
     * 读取成书指向的最新文件并刷新书名、作者；读取失败保留原物品。
     *
     * @param player 持书玩家
     * @param stack 要打开的成书
     */
    public static void openBound(ServerPlayer player, ItemStack stack) {
        try {
            var loaded = load(BookBinding.read(stack, player.registryAccess()).path);
            BookBinding.write(stack, loaded.path, loaded.book, player.registryAccess());
            player.inventoryMenu.broadcastChanges();
            send(player, loaded, false);
        } catch (Exception e) {
            player.displayClientMessage(
                    Component.translatable("gui.betterbook.read_failed", e.getMessage()), false);
        }
    }

    /**
     * 为指定手中的未绑定手册打开菜单并发送服务端候选列表。
     *
     * @param player 持书玩家
     * @param hand 交互使用的手
     */
    public static void configure(ServerPlayer player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!RuntimeBookItem.canBind(stack, player.registryAccess())) return;
        PlayerUIMenuType.openUI(player, BookMenus.BINDING);
        int menu = player.containerMenu.containerId;
        BINDINGS.put(player, new Binding(hand, stack, menu));
        RPCPacketDistributor.rpcToPlayer(player, CONFIGURE, menu, String.join("\n", list()));
    }

    /**
     * 校验菜单和原始手持物品后完成绑定，过期请求不修改背包。
     *
     * @param sender 客户端发送者
     * @param menu 打开绑定窗口时的菜单编号
     * @param path 所选服务端相对路径
     */
    @RPCPacket(BIND)
    public static void bind(RPCSender sender, int menu, String path) {
        var player = sender.asPlayer();
        if (sender.isServer() || player == null) return;
        player.server.execute(
                () -> {
                    var binding = BINDINGS.get(player);
                    if (binding == null
                            || binding.menu != menu
                            || player.containerMenu.containerId != menu
                            || player.getItemInHand(binding.hand) != binding.stack
                            || !RuntimeBookItem.canBind(binding.stack, player.registryAccess()))
                        return;
                    try {
                        var loaded = load(path);
                        var result = new ItemStack(BookItems.BOUND_BOOK.get());
                        BookBinding.write(
                                result, loaded.path, loaded.book, player.registryAccess());
                        player.setItemInHand(binding.hand, result);
                        BINDINGS.remove(player);
                        player.closeContainer();
                        player.inventoryMenu.broadcastChanges();
                    } catch (Exception e) {
                        RPCPacketDistributor.rpcToPlayer(
                                player,
                                ERROR,
                                menu,
                                Component.translatable(
                                        "gui.betterbook.read_failed", e.getMessage()));
                    }
                });
    }

    /**
     * 接收书籍传输头，仅处理服务端发送的数据。
     *
     * @param sender RPC 发送方
     * @param transfer 本次传输标识
     * @param path 运行时相对路径
     * @param editor 是否打开编辑器
     * @param menu 对应的编辑器菜单编号
     * @param total 分片数量
     */
    @RPCPacket(BEGIN)
    public static void begin(
            RPCSender sender, String transfer, String path, boolean editor, int menu, int total) {
        if (sender.isServer()) RuntimeBooks.begin(transfer, path, editor, menu, total);
    }

    /**
     * 接收有序文件分片，内容只暂存在客户端内存中。
     *
     * @param sender RPC 发送方
     * @param transfer 传输标识
     * @param index 从零开始的分片编号
     * @param data 包含 bytes 数组的 NBT
     */
    @RPCPacket(CHUNK)
    public static void chunk(RPCSender sender, String transfer, int index, CompoundTag data) {
        if (sender.isServer()) RuntimeBooks.chunk(transfer, index, data.getByteArray("bytes"));
    }

    /**
     * 保存通过服务端路径打开的书籍，保留子目录；仅接受 2 级以上权限的上传。
     *
     * @param sender RPC 发送方
     * @param path 服务端相对路径
     * @param data 完整书籍 NBT，校验失败不覆盖文件
     */
    @RPCPacket(SAVE)
    public static void save(RPCSender sender, String path, CompoundTag data) {
        var player = sender.asPlayer();
        if (sender.isServer() || player == null) return;
        player.server.execute(
                () -> {
                    if (!player.hasPermissions(2)) return;
                    try {
                        BookFiles.decode(data, StandardSchema.create());
                        BookFiles.write(resolve(path), data);
                        player.displayClientMessage(
                                Component.translatable("gui.betterbook.server_book_saved", path),
                                false);
                    } catch (Exception e) {
                        player.displayClientMessage(
                                Component.translatable(
                                        "gui.betterbook.server_book_save_failed", e.getMessage()),
                                false);
                    }
                });
    }

    /**
     * 将候选路径交给对应的客户端绑定菜单。
     *
     * @param sender RPC 发送方
     * @param menu 菜单编号
     * @param catalog 以换行分隔的相对路径
     */
    @RPCPacket(CONFIGURE)
    public static void configureClient(RPCSender sender, int menu, String catalog) {
        if (sender.isServer()) RuntimeBooks.configure(menu, catalog);
    }

    /**
     * 在仍然打开的绑定菜单内显示读取错误。
     *
     * @param sender RPC 发送方
     * @param menu 菜单编号
     * @param message 已本地化的错误组件
     */
    @RPCPacket(ERROR)
    public static void error(RPCSender sender, int menu, Component message) {
        if (sender.isServer()) RuntimeBooks.error(menu, message);
    }
}
