package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

/** 编辑画布与阅读器共用的纸张边缘和书脊阴影。 */
final class BookPaper extends UIElement {
    private final boolean left;

    BookPaper(boolean left) {
        this.left = left;
        addClass("book_paper");
        getLayout().paddingAll(4);
    }

    @Override
    public void drawBackgroundAdditional(GUIContext c) {
        int x = (int) getPositionX(),
                y = (int) getPositionY(),
                w = (int) getSizeWidth(),
                h = (int) getSizeHeight();
        c.graphics.fill(x, y + h - 3, x + w, y + h, 0xffd0bb94);
        c.graphics.fill(x, y, x + 1, y + h, 0xffc4ad83);
        c.graphics.fill(x + w - 1, y, x + w, y + h, 0xffc4ad83);
        for (int i = 0; i < 5; i++) {
            int edge = left ? x + w - 2 - i : x + 1 + i;
            c.graphics.fill(edge, y, edge + 1, y + h - 3, ((26 - i * 4) << 24) | 0x493324);
        }
    }
}
