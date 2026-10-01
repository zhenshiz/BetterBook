package com.zhenshiz.betterbook.data;

import com.zhenshiz.betterbook.BetterBook;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 注册结构选区工具，以及绑定服务端文件的空白书和成书。 */
public final class BookItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BetterBook.ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BetterBook.ID);
    public static final DeferredItem<RuntimeBookItem> BLANK_BOOK =
            ITEMS.register("blank_book", () -> new RuntimeBookItem(false));
    public static final DeferredItem<RuntimeBookItem> BOUND_BOOK =
            ITEMS.register("bound_book", () -> new RuntimeBookItem(true));
    public static final DeferredItem<Item> STRUCTURE_WAND =
            ITEMS.register(
                    "structure_wand",
                    () ->
                            new Item(new Item.Properties().stacksTo(1)) {
                                @Override
                                public void appendHoverText(
                                        net.minecraft.world.item.ItemStack stack,
                                        TooltipContext context,
                                        java.util.List<net.minecraft.network.chat.Component>
                                                tooltip,
                                        net.minecraft.world.item.TooltipFlag flag) {
                                    tooltip.add(
                                            net.minecraft.network.chat.Component.translatable(
                                                    "gui.betterbook.structure_wand_hint"));
                                    tooltip.add(
                                            net.minecraft.network.chat.Component.literal(
                                                    "/betterbook structure export <name>"));
                                }
                            });

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
            TABS.register(
                    "betterbook",
                    () ->
                            CreativeModeTab.builder()
                                    .title(Component.translatable("itemGroup.betterbook"))
                                    .icon(() -> new ItemStack(BookItems.BOUND_BOOK.get()))
                                    .withTabsBefore(CreativeModeTabs.TOOLS_AND_UTILITIES)
                                    .displayItems(
                                            (parameters, output) -> {
                                                output.accept(BLANK_BOOK);
                                                output.accept(BOUND_BOOK);
                                                output.accept(STRUCTURE_WAND);
                                            })
                                    .build());

    private BookItems() {}

    /**
     * 注册物品及包含全部三个物品的独立创造标签页。
     *
     * @param bus 模组事件总线
     */
    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        TABS.register(bus);
    }
}
