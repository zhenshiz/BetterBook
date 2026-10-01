package com.zhenshiz.betterbook.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;

import org.jsoup.nodes.Element;

/** HTML 物品节点使用原版 ItemStack 编解码器保存数量和数据组件。 */
public final class BookItem {
    private BookItem() {}

    /**
     * 解码展示物品；缺失模组或无效数据显示为空，原始 HTML 属性仍由文档保留。
     *
     * @param element 物品节点
     * @param registries 当前客户端或服务端的注册表
     * @return 物品栈，无法解码时为空栈
     */
    public static ItemStack read(Element element, HolderLookup.Provider registries) {
        String value = element.attr("data-stack");
        if (value.isBlank() || value.equals("{}")) return ItemStack.EMPTY;
        try {
            return ItemStack.CODEC
                    .parse(
                            registries.createSerializationContext(NbtOps.INSTANCE),
                            TagParser.parseTag(value))
                    .result()
                    .orElse(ItemStack.EMPTY);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException
                | IllegalArgumentException e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 序列化为节点属性，交由 HTML 库转义引号及特殊字符。
     *
     * @param stack 要展示的物品栈
     * @param registries 当前客户端或服务端的注册表
     * @return 原版物品 SNBT，空槽位为 {@code {}}
     */
    public static String write(ItemStack stack, HolderLookup.Provider registries) {
        return stack.isEmpty() ? "{}" : stack.save(registries).toString();
    }
}
