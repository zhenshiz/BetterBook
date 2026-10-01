package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.zhenshiz.betterbook.data.*;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 服务端结构文件的异步客户端缓存；接收内容仅存在内存中，断线时清空。 */
public final class StructureFiles {
    private static final Map<String, CompletableFuture<List<String>>> LISTS = new HashMap<>();
    private static final Map<String, Download> DOWNLOADS = new HashMap<>();
    private static final Map<String, CompletableFuture<CompoundTag>> CACHE =
            new LinkedHashMap<>(16, .75f, true);

    private static final class Download {
        final String reference;
        final CompletableFuture<CompoundTag> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next, total;

        Download(String reference) {
            this.reference = reference;
        }
    }

    private StructureFiles() {}

    /**
     * 查询当前连接服务端的结构目录。
     *
     * @return 在客户端线程完成的补全结果；断线、错误或超时则异常完成
     */
    public static CompletableFuture<List<String>> list() {
        if (Minecraft.getInstance().getConnection() == null)
            return CompletableFuture.failedFuture(new IOException("Not connected to a server"));
        String request = UUID.randomUUID().toString();
        var result = new CompletableFuture<List<String>>();
        LISTS.put(request, result);
        result.orTimeout(30, TimeUnit.SECONDS)
                .whenComplete(
                        (value, error) ->
                                Minecraft.getInstance().execute(() -> LISTS.remove(request)));
        RPCPacketDistributor.rpcToServer(ServerStructureFiles.LIST, request);
        return result;
    }

    /**
     * 按需请求结构文件，同时请求相同文件会共享下载。
     *
     * @param reference 书页中的文件引用
     * @return 压缩文件解码后的 NBT；失败时异常完成
     */
    public static CompletableFuture<CompoundTag> load(String reference) {
        String canonical = ServerStructureFiles.canonical(reference);
        if (!canonical.startsWith("server:") || canonical.length() > 263)
            return CompletableFuture.failedFuture(
                    new IOException("Choose a server structure file"));
        if (Minecraft.getInstance().getConnection() == null)
            return CompletableFuture.failedFuture(new IOException("Not connected to a server"));
        var cached = CACHE.get(canonical);
        if (cached != null) return cached;
        String request = UUID.randomUUID().toString();
        var download = new Download(canonical);
        DOWNLOADS.put(request, download);
        CACHE.put(canonical, download.result);
        download.result
                .orTimeout(30, TimeUnit.SECONDS)
                .whenComplete(
                        (value, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    DOWNLOADS.remove(request);
                                                    if (error != null)
                                                        CACHE.remove(canonical, download.result);
                                                    var iterator = CACHE.entrySet().iterator();
                                                    while (CACHE.size() > 16
                                                            && iterator.hasNext()) {
                                                        if (iterator.next().getValue().isDone())
                                                            iterator.remove();
                                                    }
                                                }));
        RPCPacketDistributor.rpcToServer(ServerStructureFiles.READ, request, canonical);
        return download.result;
    }

    /**
     * 完成对应的补全请求。
     *
     * @param result 服务端查询结果
     */
    public static void receiveList(StructureFileList result) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            var future = LISTS.remove(result.request);
                            if (future == null) return;
                            if (result.error.isEmpty()) future.complete(List.copyOf(result.files));
                            else future.completeExceptionally(new IOException(result.error));
                        });
    }

    /**
     * 汇集请求中的文件片段；不接受未知请求或乱序片段。
     *
     * @param chunk 服务端文件片段
     */
    public static void receiveFile(StructureFileChunk chunk) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            var download = DOWNLOADS.get(chunk.transfer);
                            if (download == null) return;
                            try {
                                if (!download.reference.equals(chunk.name)
                                        || chunk.index != download.next
                                        || chunk.total < 1
                                        || chunk.total
                                                > (BookSchematic.MAX_BYTES
                                                                + ServerStructureFiles.CHUNK_SIZE
                                                                - 1)
                                                        / ServerStructureFiles.CHUNK_SIZE
                                        || chunk.data.length > ServerStructureFiles.CHUNK_SIZE
                                        || (download.next > 0 && chunk.total != download.total))
                                    throw new IOException("Invalid structure download");
                                download.total = chunk.total;
                                download.bytes.writeBytes(chunk.data);
                                if (download.bytes.size() > BookSchematic.MAX_BYTES)
                                    throw new IOException("Structure file exceeds 8 MiB");
                                if (++download.next == download.total) {
                                    var tag =
                                            BookSchematic.read(
                                                    new ByteArrayInputStream(
                                                            download.bytes.toByteArray()));
                                    BookSchematic.decode(tag);
                                    download.result.complete(tag);
                                }
                            } catch (Exception e) {
                                download.result.completeExceptionally(e);
                            }
                        });
    }

    /**
     * 完成读取失败的请求，允许之后重试。
     *
     * @param request 请求标识
     * @param error 服务端错误提示
     */
    public static void receiveError(String request, String error) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            var download = DOWNLOADS.get(request);
                            if (download != null)
                                download.result.completeExceptionally(new IOException(error));
                        });
    }

    /** 清除断线前的缓存和请求，防止切换服务器后复用同名文件。 */
    public static void clear() {
        var error = new IOException("Disconnected from server");
        new ArrayList<>(LISTS.values()).forEach(future -> future.completeExceptionally(error));
        new ArrayList<>(DOWNLOADS.values())
                .forEach(download -> download.result.completeExceptionally(error));
        LISTS.clear();
        DOWNLOADS.clear();
        CACHE.clear();
    }
}
