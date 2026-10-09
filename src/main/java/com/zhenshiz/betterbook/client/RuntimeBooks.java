package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerScreen;
import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.data.*;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.io.*;
import java.util.*;

/** 将服务端书籍分片汇总到内存，完整校验后打开真实编辑器或阅读器。 */
@EventBusSubscriber(modid = BetterBook.ID, value = Dist.CLIENT)
public final class RuntimeBooks {
    private static Download download;

    private static final class Download {
        final String id, path;
        final boolean editor;
        final int menu, total;
        int next;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Download(String id, String path, boolean editor, int menu, int total) {
            this.id = id;
            this.path = path;
            this.editor = editor;
            this.menu = menu;
            this.total = total;
        }
    }

    private RuntimeBooks() {}

    /**
     * 初始化有大小上限的新传输，替换此前未完成的传输。
     *
     * @param id 传输标识
     * @param path 服务端相对路径
     * @param editor 是否打开编辑器
     * @param menu 编辑器菜单编号
     * @param total 分片总数
     */
    public static void begin(String id, String path, boolean editor, int menu, int total) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            download =
                                    total > 0
                                                    && total
                                                            <= ServerBooks.MAX_BYTES
                                                                    / ServerBooks.CHUNK_SIZE
                                            ? new Download(id, path, editor, menu, total)
                                            : null;
                        });
    }

    /**
     * 按顺序汇总文件；丢失或越界分片取消传输，收齐后才打开界面。
     *
     * @param id 传输标识
     * @param index 从零开始的分片编号
     * @param bytes 当前分片内容
     */
    public static void chunk(String id, int index, byte[] bytes) {
        var mc = Minecraft.getInstance();
        mc.execute(
                () -> {
                    var pending = download;
                    if (pending == null || !pending.id.equals(id)) return;
                    if (index != pending.next
                            || bytes.length > ServerBooks.CHUNK_SIZE
                            || pending.bytes.size() + bytes.length > ServerBooks.MAX_BYTES) {
                        download = null;
                        return;
                    }
                    pending.bytes.writeBytes(bytes);
                    if (++pending.next != pending.total) return;
                    download = null;
                    try {
                        var input = new ByteArrayInputStream(pending.bytes.toByteArray());
                        var schema = BookExtensions.create().schema;
                        var book = pending.editor ? BookFiles.read(input, schema)
                                : BookFiles.readForReading(input, schema);
                        if (pending.editor) {
                            if (!(mc.screen instanceof ModularUIContainerScreen screen)
                                    || screen.getMenu().containerId != pending.menu) return;
                            var editor =
                                    screen.getMenu()
                                            .getModularUI()
                                            .select("*")
                                            .filter(BookEditor.class::isInstance)
                                            .map(BookEditor.class::cast)
                                            .findFirst()
                                            .orElseThrow();
                            editor.openServerBook(book, pending.path);
                        } else mc.setScreen(new BookReaderScreen(book, null));
                    } catch (Exception e) {
                        BetterBook.LOGGER.warn("Cannot open server book {}", pending.path, e);
                        if (mc.player != null)
                            mc.player.displayClientMessage(
                                    Component.translatable(
                                            "gui.betterbook.read_failed", e.getMessage()),
                                    false);
                    }
                });
    }

    private static BookBindingPanel panel(int menu) {
        if (!(Minecraft.getInstance().screen instanceof ModularUIContainerScreen screen)
                || screen.getMenu().containerId != menu) return null;
        return screen.getMenu()
                .getModularUI()
                .select("#book-binding-panel")
                .filter(BookBindingPanel.class::isInstance)
                .map(BookBindingPanel.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * 将服务端文件列表应用到仍然打开的同一绑定菜单。
     *
     * @param menu 菜单编号
     * @param catalog 以换行分隔的相对路径
     */
    public static void configure(int menu, String catalog) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            var panel = panel(menu);
                            if (panel != null)
                                panel.configure(
                                        menu,
                                        catalog.isBlank()
                                                ? List.of()
                                                : List.of(catalog.split("\n")));
                        });
    }

    /**
     * 向对应菜单显示绑定失败信息，忽略过期菜单。
     *
     * @param menu 菜单编号
     * @param error 本地化错误组件
     */
    public static void error(int menu, Component error) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            var panel = panel(menu);
                            if (panel != null) panel.error(error);
                        });
    }

    /**
     * 断开服务器时丢弃未完成的文件传输。
     *
     * @param event 客户端退出连接事件
     */
    @SubscribeEvent
    public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        download = null;
    }
}
