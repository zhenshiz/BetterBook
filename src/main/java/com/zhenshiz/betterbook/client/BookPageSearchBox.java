package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.viscript_lib.gui.components.search.RegistrySearchBox;
import com.zhenshiz.betterbook.core.Book;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 使用 VSL 原生补全交互，按页码、页面标题或稳定 ID 查找本书页面。 */
final class BookPageSearchBox extends RegistrySearchBox<Book.Page> {
    BookPageSearchBox(List<Book.Page> pages, String target) {
        super(
                pages.stream().filter(p -> p.id().equals(target)).findFirst().orElse(null),
                () -> null,
                p ->
                        ResourceLocation.fromNamespaceAndPath(
                                "betterbook",
                                HexFormat.of().formatHex(p.id().getBytes(StandardCharsets.UTF_8))),
                p -> label(pages, p).getString(),
                (word, handler) -> {
                    String query = word.toLowerCase(Locale.ROOT);
                    for (var page : pages) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if ((label(pages, page).getString() + " " + page.id())
                                .toLowerCase(Locale.ROOT)
                                .contains(query)) handler.acceptResult(page);
                    }
                },
                UIElementProvider.text(p -> label(pages, p)));
        getSearchStyle().closeAfterSelect(true);
        getLayout().widthPercent(100).minWidth(0).height(20);
    }

    private static Component label(List<Book.Page> pages, Book.Page page) {
        return Component.translatable(
                "gui.betterbook.related_page_candidate", pages.indexOf(page) + 1, page.title());
    }
}
