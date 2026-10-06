package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;

/** 复用原版纸张纹理的九宫格；翻转 UV 而非几何，避免左页被背面剔除。 */
final class VanillaBookPage extends UIElement {
    private final boolean left;
    private final boolean single;

    VanillaBookPage(boolean left) {
        this(left, false);
    }

    VanillaBookPage(boolean left, boolean single) {
        this.left = left;
        this.single = single;
        addClasses("book_paper", "book-reader-page");
    }

    @Override
    public void drawBackgroundTexture(GUIContext context) {
        int x = Math.round(getPositionX()), y = Math.round(getPositionY());
        int width = Math.round(getSizeWidth()), height = Math.round(getSizeHeight());
        if (width < 24 || height < 24) return;
        // 裁去原图装订侧的外封皮，两页在书脊处连续相接。
        int[] u = single ? new int[] {20, 33, 154, 166}
                : left ? new int[] {166, 154, 33, 26} : new int[] {26, 33, 154, 166};
        int[] v = {0, 12, 168, 180};
        int[] dx = single ? new int[] {0, 13, width - 12, width}
                : left ? new int[] {0, 12, width - 7, width} : new int[] {0, 7, width - 12, width};
        int[] dy = {0, 12, height - 12, height};
        context.graphics.flush();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                context.graphics.blit(
                        BookViewScreen.BOOK_LOCATION,
                        x + dx[col],
                        y + dy[row],
                        dx[col + 1] - dx[col],
                        dy[row + 1] - dy[row],
                        (float) u[col],
                        (float) v[row],
                        u[col + 1] - u[col],
                        v[row + 1] - v[row],
                        256,
                        256);
            }
        }
    }
}
