package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.Platform;
import com.viscript_lib.gui.editor.EditorFileFormat;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.nbt.*;

import java.io.*;
import java.nio.file.*;

/** 书籍读写；只有完整写入成功才替换原文件。 */
public final class BookFiles {
    public static final EditorFileFormat FORMAT =
            EditorFileFormat.of("betterbook", "books", "book");

    private BookFiles() {}

    public static CompoundTag encode(Book book) {
        var root = new CompoundTag();
        root.put("data", BookData.from(book).serializeNBT(Platform.getFrozenRegistry()));
        return root;
    }

    public static Book decode(CompoundTag tag, Schema schema) {
        return BookData.load(Platform.getFrozenRegistry(), tag.getCompound("data")).toBook(schema);
    }

    public static Book read(Path path, Schema schema) throws IOException {
        var tag = NbtIo.read(path);
        if (tag == null) throw new IOException("Empty book");
        return decode(tag, schema);
    }

    public static Book read(InputStream input, Schema schema) throws IOException {
        try (var in = new DataInputStream(input)) {
            return decode(NbtIo.read(in, NbtAccounter.create(64L * 1024 * 1024)), schema);
        }
    }

    /**
     * 读取服务端已校验的书籍，保留未查看页面的源码以避免阻塞客户端帧。
     *
     * @param input 完整书籍输入流，读取后关闭
     * @param schema 客户端节点定义
     * @return 正文按需解析的书籍，目录和阶段元数据已校验
     * @throws IOException 文件读取失败
     */
    public static Book readForReading(InputStream input, Schema schema) throws IOException {
        try (var in = new DataInputStream(input)) {
            var tag = NbtIo.read(in, NbtAccounter.create(64L * 1024 * 1024));
            return BookData.load(Platform.getFrozenRegistry(), tag.getCompound("data"))
                    .toReadingBook(schema);
        }
    }

    public static void write(Path path, CompoundTag tag) throws IOException {
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".betterbook-", ".tmp");
        try {
            NbtIo.write(tag, temp);
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
