package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.zhenshiz.betterbook.data.BookItem;

import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;

import org.jsoup.nodes.Element;

/** 编辑与阅读共用的展示槽位；LocalSlot 不参与玩家背包交换。 */
final class BookItemView extends UIElement {
    private final ItemSlot slot = new ItemSlot();

    BookItemView(Element element, boolean editable) {
        addClass("book-item");
        getLayout()
                .flexDirection(FlexDirection.ROW)
                .alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER)
                .gapAll(6);
        slot.addClass("book-item-slot");
        slot.getLayout().width(24).height(24).paddingAll(4).flexShrink(0);
        slot.setItem(BookItem.read(element, Platform.getFrozenRegistry()), false);
        if (editable) slot.getStyle().tooltips("gui.betterbook.item_edit");
        addChildren(
                new UIElement().addClass("book-item-line").layout(l -> l.width(12).height(1)),
                slot,
                new UIElement().addClass("book-item-line").layout(l -> l.width(12).height(1)));
    }

    void onSelect(Runnable action) {
        slot.addEventListener(
                UIEvents.MOUSE_DOWN,
                e -> {
                    if (e.button == 0) {
                        action.run();
                        e.hasHandler = true;
                    }
                });
    }
}
