package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.zhenshiz.betterbook.core.Book;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 物品只保存服务端文件引用和展示信息，正文在每次打开时读取。 */
public final class BookBinding implements IPersistedSerializable {
    @Persisted public String path = "", title = "", author = "";

    /**
     * 读取物品上的文件引用；未绑定时返回空字段。
     *
     * @param stack 目标物品
     * @param registries 当前注册表访问器
     * @return 绑定数据的独立副本
     */
    public static BookBinding read(ItemStack stack, HolderLookup.Provider registries) {
        var value = new BookBinding();
        value.deserializeNBT(
                registries,
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag()
                        .getCompound("betterbook"));
        return value;
    }

    /**
     * 更新文件引用、书名和作者，保留无关的自定义数据。
     *
     * @param stack 待更新物品
     * @param path 服务端相对路径
     * @param book 已加载书籍
     * @param registries 当前注册表访问器
     */
    public static void write(
            ItemStack stack, String path, Book book, HolderLookup.Provider registries) {
        var value = new BookBinding();
        value.path = path;
        value.title = book.title;
        value.author = book.author;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.put("betterbook", value.serializeNBT(registries));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(
                DataComponents.ITEM_NAME,
                book.title.isBlank()
                        ? Component.translatable("item.betterbook.bound_book")
                        : Component.literal(book.title));
    }
}
