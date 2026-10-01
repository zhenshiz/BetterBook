package com.zhenshiz.betterbook.mixin;

import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;
import com.viscript_lib.gui.editor.EditorFileNames;
import com.viscript_lib.gui.editor.EditorServerUploads;
import com.viscript_lib.network.c2s.EditorUploadC2SPackets;
import com.zhenshiz.betterbook.core.StandardSchema;
import com.zhenshiz.betterbook.core.TextCommand;
import com.zhenshiz.betterbook.data.BookFiles;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在 VSL 原生上传入口校验书籍发布权限，避免客户端文件成为未经授权的指令来源。 */
@Mixin(value = EditorUploadC2SPackets.class, remap = false)
public abstract class BookUploadPermissionMixin {
    @Inject(method = "receiveEditorUpload", at = @At("HEAD"), cancellable = true)
    private static void betterbook$checkUpload(
            RPCSender sender, CompoundTag request, CallbackInfo ci) {
        if (!EditorFileNames.normalizePathSegment(
                                request.getString(EditorServerUploads.TAG_MOD_ID), "")
                        .equals("betterbook")
                || !EditorFileNames.normalizePathSegment(
                                request.getString(EditorServerUploads.TAG_DOMAIN), "")
                        .equals("books")) return;
        var player = sender.asPlayer();
        if (sender.isServer() || player == null) {
            ci.cancel();
            return;
        }
        int required = TextCommand.EXECUTION_PERMISSION;
        if (!player.hasPermissions(required)) {
            ci.cancel();
            RPCPacketDistributor.rpcToPlayer(
                    player,
                    "viscript_lib:editor_upload_result",
                    Component.translatable("viscript_lib.editor.server_upload_result.error.title"),
                    Component.translatable("gui.betterbook.command_upload_permission", required));
            return;
        }
        try {
            BookFiles.decode(
                    request.getCompound(EditorServerUploads.TAG_DATA), StandardSchema.create());
        } catch (IllegalArgumentException e) {
            ci.cancel();
            RPCPacketDistributor.rpcToPlayer(
                    player,
                    "viscript_lib:editor_upload_result",
                    Component.translatable("viscript_lib.editor.server_upload_result.error.title"),
                    Component.translatable(
                            "gui.betterbook.server_book_save_failed", e.getMessage()));
        }
    }
}
