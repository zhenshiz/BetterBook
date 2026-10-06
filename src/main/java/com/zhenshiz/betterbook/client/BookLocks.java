package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.zhenshiz.betterbook.core.BookPageAccess;

import net.minecraft.network.chat.Component;

/** 阅读器的锁图标及当前语言的解锁提示。 */
final class BookLocks {
    static final SpriteTexture ICON = SpriteTexture.of("betterbook:textures/gui/lock.png");

    private BookLocks() {}

    static Component hint(BookPageAccess access, String pageId) {
        String hint = access.unlockHint(pageId);
        return hint.isBlank() ? Component.translatable("gui.betterbook.page_unlock_hint")
                : Component.literal(hint);
    }

    static Component message(BookPageAccess access, String pageId) {
        return Component.translatable("gui.betterbook.link_locked", hint(access, pageId));
    }
}
