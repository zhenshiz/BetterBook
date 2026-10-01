package com.zhenshiz.betterbook.data;

import com.zhenshiz.betterbook.BetterBook;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** 将成书存入原版讲台，阅读时为每名玩家独立打开服务端文件。 */
@EventBusSubscriber(modid = BetterBook.ID)
public final class BookLecterns {
    private BookLecterns() {}

    /**
     * 处理已绑定成书的放置与公共阅读，沿用原版讲台的存储、音效及掉落。
     *
     * @param event 方块右键事件；已被领地或保护模组取消的事件不会进入此处理器
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void interact(PlayerInteractEvent.RightClickBlock event) {
        if (event.getUseBlock() == net.neoforged.neoforge.common.util.TriState.FALSE) return;
        var level = event.getLevel();
        var pos = event.getPos();
        var state = level.getBlockState(pos);
        if (!state.is(Blocks.LECTERN)
                || !(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) return;
        if (state.getValue(LecternBlock.HAS_BOOK)) {
            if (!lectern.getBook().is(BookItems.BOUND_BOOK)) return;
            if (event.getEntity() instanceof ServerPlayer player) {
                ServerBooks.openBound(player, lectern.getBook());
                lectern.setChanged();
                player.awardStat(Stats.INTERACT_WITH_LECTERN);
            }
        } else {
            if (event.getUseItem() == net.neoforged.neoforge.common.util.TriState.FALSE) return;
            var stack = event.getItemStack();
            if (!stack.is(BookItems.BOUND_BOOK)
                    || RuntimeBookItem.canBind(stack, level.registryAccess())
                    || !event.getEntity().mayBuild()) return;
            if (!LecternBlock.tryPlaceBook(event.getEntity(), level, pos, state, stack)) return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }
}
