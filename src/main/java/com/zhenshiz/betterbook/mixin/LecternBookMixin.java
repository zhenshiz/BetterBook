package com.zhenshiz.betterbook.mixin;

import com.zhenshiz.betterbook.data.BookItems;

import net.minecraft.world.level.block.entity.LecternBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 让原版讲台将手册视为有效书籍，保留存档、容器和比较器语义。 */
@Mixin(LecternBlockEntity.class)
public abstract class LecternBookMixin {
    @Inject(method = "hasBook", at = @At("HEAD"), cancellable = true)
    private void betterbook$hasBook(CallbackInfoReturnable<Boolean> result) {
        if (((LecternBlockEntity) (Object) this).getBook().is(BookItems.BOUND_BOOK))
            result.setReturnValue(true);
    }
}
