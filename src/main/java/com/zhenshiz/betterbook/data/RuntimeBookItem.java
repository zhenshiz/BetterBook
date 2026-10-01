package com.zhenshiz.betterbook.data;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

import java.util.List;

/** 空白时绑定文件，完成绑定后按服务端路径打开书籍。 */
public final class RuntimeBookItem extends Item {
    private final boolean bound;

    /**
     * 创建不可堆叠的空白手册或成书。
     *
     * @param bound 是否使用已有文件绑定直接阅读
     */
    public RuntimeBookItem(boolean bound) {
        super(new Properties().stacksTo(1));
        this.bound = bound;
    }

    /**
     * 判断物品是否可选择服务端文件；包括空白手册与创造栏取出的未绑定成书。
     *
     * @param stack 待检查物品
     * @param registries 当前注册表访问器
     * @return 空白手册或缺少文件引用的成书返回 true
     */
    public static boolean canBind(ItemStack stack, HolderLookup.Provider registries) {
        return stack.is(BookItems.BLANK_BOOK)
                || stack.is(BookItems.BOUND_BOOK)
                        && BookBinding.read(stack, registries).path.isBlank();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            if (canBind(stack, player.registryAccess())) ServerBooks.configure(serverPlayer, hand);
            else ServerBooks.openBound(serverPlayer, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (bound && context.registries() != null) {
            var binding = BookBinding.read(stack, context.registries());
            if (!binding.path.isBlank()) {
                if (!binding.author.isBlank())
                    tooltip.add(
                            Component.translatable("book.byAuthor", binding.author)
                                    .withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.literal(binding.path).withStyle(ChatFormatting.DARK_GRAY));
                return;
            }
        }
        tooltip.add(
                Component.translatable("gui.betterbook.blank_book_hint")
                        .withStyle(ChatFormatting.GRAY));
    }
}
