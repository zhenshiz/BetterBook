package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.viscript_lib.gui.editor.ViScriptEditorWindow;
import com.zhenshiz.betterbook.data.BookMenus;

import net.minecraft.world.entity.player.Player;

/** 构建服务端菜单对应的客户端编辑器窗口。 */
public final class BookClientMenus {
    private BookClientMenus() {}

    /**
     * 构建玩家菜单中的编辑界面，缩放按钮将窗口宽度设为屏幕的 80%。
     *
     * @param player 菜单所属的客户端玩家
     * @return 使用 VSL 原生窗口按钮的编辑界面
     */
    public static ModularUI editorUI(Player player) {
        var window =
                ViScriptEditorWindow.open(BookMenus.EDITOR, BookEditor::new)
                        .setMinimizedBoundsPercent(10, 0, 80, 100);
        return new ModularUI(UI.of(window), player)
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false);
    }
}
