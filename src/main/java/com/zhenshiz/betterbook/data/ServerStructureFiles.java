package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacket;
import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 结构文件的服务端存储及只读下载接口；客户端不参与选区文件的保存。 */
public final class ServerStructureFiles {
    public static final String LIST = "betterbook:structure_list",
            CATALOG = "betterbook:structure_catalog",
            READ = "betterbook:structure_read",
            FILE = "betterbook:structure_file",
            ERROR = "betterbook:structure_error";
    public static final int CHUNK_SIZE = 24576;

    private ServerStructureFiles() {}

    /**
     * 返回当前服务端的 LDLib2 结构资源目录。
     *
     * @return 服务端游戏目录下的结构目录
     */
    public static Path directory() {
        return LDLib2.getAssetsDir().toPath().resolve("betterbook/nbt");
    }

    /**
     * 统一旧版文件引用；旧客户端引用现在也从服务端读取。
     *
     * @param reference 书页文件引用
     * @return 服务端相对文件引用
     */
    public static String canonical(String reference) {
        if (reference.startsWith("local:")) return "server:" + reference.substring(6);
        if (reference.startsWith("resource:betterbook:nbt/"))
            return "server:" + reference.substring("resource:betterbook:nbt/".length());
        return reference;
    }

    /**
     * 解析并限制文件路径在服务端结构目录内。
     *
     * @param reference 服务端相对文件引用
     * @return 规范化文件路径
     * @throws IOException 引用无效或指向目录外时抛出
     */
    public static Path resolve(String reference) throws IOException {
        reference = canonical(reference);
        if (!reference.startsWith("server:"))
            throw new IOException("Choose a server structure file");
        String relative = reference.substring(7);
        if (relative.isBlank()
                || relative.length() > 256
                || relative.contains("\\")
                || relative.contains(":")) throw new IOException("Invalid structure path");
        Path base = directory().toAbsolutePath().normalize();
        Path path = Path.of(relative);
        Path file = base.resolve(path).normalize();
        if (path.isAbsolute()
                || !file.startsWith(base)
                || file.equals(base)
                || !supported(relative)) throw new IOException("Invalid structure path");
        for (Path part : path)
            if (part.toString().equals("..")) throw new IOException("Invalid structure path");
        if (Files.exists(file) && !file.toRealPath().startsWith(base.toRealPath()))
            throw new IOException("Structure symlink is outside its directory");
        return file;
    }

    private static boolean supported(String name) {
        return name.endsWith(".litematic") || name.endsWith(".nbt");
    }

    /**
     * 列出服务端结构文件，每个文件只返回一个引用。
     *
     * @return 按名称排序的补全列表
     * @throws IOException 服务端目录无法读取时抛出
     */
    public static List<String> list() throws IOException {
        Path root = directory();
        if (!Files.isDirectory(root)) return List.of();
        var result = new TreeSet<String>();
        try (var paths = Files.walk(root, 16)) {
            var iterator =
                    paths.filter(Files::isRegularFile)
                            .filter(p -> supported(p.toString()))
                            .iterator();
            int characters = 0;
            while (iterator.hasNext() && result.size() < 4096) {
                String reference =
                        "server:" + root.relativize(iterator.next()).toString().replace('\\', '/');
                try {
                    resolve(reference);
                } catch (IOException e) {
                    continue;
                }
                characters += reference.length();
                if (characters > 60000) break;
                result.add(reference);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 读取有大小上限的压缩结构文件。
     *
     * @param reference 服务端相对文件引用
     * @return 压缩 NBT 字节
     * @throws IOException 文件不存在、过大或无法读取时抛出
     */
    public static byte[] read(String reference) throws IOException {
        Path file = resolve(reference);
        if (Files.size(file) > BookSchematic.MAX_BYTES)
            throw new IOException("Structure file exceeds 8 MiB");
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(BookSchematic.MAX_BYTES + 1);
            if (bytes.length > BookSchematic.MAX_BYTES)
                throw new IOException("Structure file exceeds 8 MiB");
            return bytes;
        }
    }

    /**
     * 保存已捕获的结构到服务端，不覆盖已有文件。
     *
     * @param name 不含扩展名的结构名称
     * @param bytes 压缩 NBT
     * @return 保存后的服务端路径
     * @throws IOException 名称无效、同名或写入失败时抛出
     */
    public static Path save(String name, byte[] bytes) throws IOException {
        if (!name.matches("[a-zA-Z0-9_\\-]{1,64}")) throw new IOException("Invalid structure name");
        if (bytes.length > BookSchematic.MAX_BYTES)
            throw new IOException("Structure file exceeds 8 MiB");
        Files.createDirectories(directory());
        Path file = resolve("server:" + name + ".litematic");
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        return file;
    }

    /**
     * 响应客户端的服务端目录查询。
     *
     * @param sender 请求玩家
     * @param request 请求标识
     */
    @RPCPacket(LIST)
    public static void requestList(RPCSender sender, String request) {
        var player = sender.asPlayer();
        if (sender.isServer() || player == null || request.length() > 64) return;
        player.server.execute(
                () -> {
                    var result = new StructureFileList();
                    result.request = request;
                    try {
                        result.files = new ArrayList<>(list());
                    } catch (Exception e) {
                        result.error = "Cannot list server structures";
                    }
                    RPCPacketDistributor.rpcToPlayer(player, CATALOG, result);
                });
    }

    /**
     * 按客户端请求分块发送文件，仅用于渲染，不写入客户端磁盘。
     *
     * @param sender 请求玩家
     * @param request 请求标识
     * @param reference 服务端文件引用
     */
    @RPCPacket(READ)
    public static void requestFile(RPCSender sender, String request, String reference) {
        var player = sender.asPlayer();
        if (sender.isServer() || player == null || request.length() > 64) return;
        player.server.execute(
                () -> {
                    try {
                        byte[] bytes = read(reference);
                        int total = Math.max(1, (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
                        for (int i = 0; i < total; i++)
                            RPCPacketDistributor.rpcToPlayer(
                                    player,
                                    FILE,
                                    new StructureFileChunk(
                                            request,
                                            canonical(reference),
                                            i,
                                            total,
                                            Arrays.copyOfRange(
                                                    bytes,
                                                    i * CHUNK_SIZE,
                                                    Math.min(bytes.length, (i + 1) * CHUNK_SIZE))));
                    } catch (Exception e) {
                        RPCPacketDistributor.rpcToPlayer(
                                player,
                                ERROR,
                                request,
                                "Cannot read server structure: " + reference);
                    }
                });
    }

    /**
     * 接收服务端补全列表。
     *
     * @param sender RPC 发送端
     * @param result 查询结果
     */
    @RPCPacket(CATALOG)
    public static void receiveList(RPCSender sender, StructureFileList result) {
        if (sender.isServer()) com.zhenshiz.betterbook.client.StructureFiles.receiveList(result);
    }

    /**
     * 接收服务端文件片段。
     *
     * @param sender RPC 发送端
     * @param chunk 文件片段
     */
    @RPCPacket(FILE)
    public static void receiveFile(RPCSender sender, StructureFileChunk chunk) {
        if (sender.isServer()) com.zhenshiz.betterbook.client.StructureFiles.receiveFile(chunk);
    }

    /**
     * 接收服务端读取错误。
     *
     * @param sender RPC 发送端
     * @param request 请求标识
     * @param error 错误提示
     */
    @RPCPacket(ERROR)
    public static void receiveError(RPCSender sender, String request, String error) {
        if (sender.isServer())
            com.zhenshiz.betterbook.client.StructureFiles.receiveError(request, error);
    }
}
