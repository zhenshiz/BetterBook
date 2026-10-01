package com.zhenshiz.betterbook.client;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

/** 独立进程读取系统图片剪贴板，避免 AWT 与 Minecraft 的窗口线程冲突。 */
public final class ClipboardImageReader {
    private ClipboardImageReader() {}

    public static void main(String[] args) {
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                var image = (Image) clipboard.getData(DataFlavor.imageFlavor);
                var icon = new javax.swing.ImageIcon(image);
                int width = icon.getIconWidth(), height = icon.getIconHeight();
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096)
                    throw new IllegalArgumentException("Unsupported clipboard image dimensions");
                var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                var graphics = result.createGraphics();
                try {
                    graphics.drawImage(image, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                ImageIO.write(result, "png", System.out);
                System.out.flush();
            }
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
