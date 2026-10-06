package com.zhenshiz.betterbook.data;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.zhenshiz.betterbook.BetterBook;
import com.zhenshiz.betterbook.api.BetterBookStages;
import com.zhenshiz.betterbook.core.StageNames;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** 管理员阶段命令；权限、参数解析及翻译反馈与无反馈的公共 API 分离。 */
@EventBusSubscriber(modid = BetterBook.ID)
public final class StageCommands {
    private static final DynamicCommandExceptionType INVALID_STAGE =
            new DynamicCommandExceptionType(
                    stage -> Component.translatable("command.betterbook.stage.invalid", stage));

    private StageCommands() {}

    /**
     * 注册需要 2 级权限的阶段管理命令，目标支持玩家选择器。
     *
     * @param event 命令注册上下文
     */
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var stages = Commands.literal("stage").requires(source -> source.hasPermission(2));
        stages.then(mutation("add", true));
        stages.then(mutation("remove", false));
        stages.then(
                Commands.literal("list")
                        .then(
                                Commands.argument("player", EntityArgument.player())
                                        .executes(StageCommands::list)));
        stages.then(
                Commands.literal("clear")
                        .then(
                                Commands.argument("players", EntityArgument.players())
                                        .executes(StageCommands::clear)));
        event.getDispatcher().register(Commands.literal(BetterBook.ID).then(stages));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mutation(
            String command, boolean add) {
        return Commands.literal(command)
                .then(
                        Commands.argument("players", EntityArgument.players())
                                .then(
                                        Commands.argument(
                                                        "stage", StringArgumentType.greedyString())
                                                .executes(context -> change(context, add))));
    }

    private static int change(CommandContext<CommandSourceStack> context, boolean add)
            throws CommandSyntaxException {
        String input = StringArgumentType.getString(context, "stage");
        String stage;
        try {
            stage = StageNames.normalize(input);
        } catch (IllegalArgumentException e) {
            throw INVALID_STAGE.create(input);
        }
        int changed = 0;
        for (var player : EntityArgument.getPlayers(context, "players"))
            if (add ? BetterBookStages.add(player, stage) : BetterBookStages.remove(player, stage))
                changed++;
        int count = changed;
        context.getSource()
                .sendSuccess(
                        () ->
                                Component.translatable(
                                        add
                                                ? "command.betterbook.stage.added"
                                                : "command.betterbook.stage.removed",
                                        stage,
                                        count),
                        true);
        return changed;
    }

    private static int list(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        var stages = BetterBookStages.list(player);
        context.getSource()
                .sendSuccess(
                        () ->
                                stages.isEmpty()
                                        ? Component.translatable(
                                                "command.betterbook.stage.empty",
                                                player.getDisplayName())
                                        : Component.translatable(
                                                "command.betterbook.stage.list",
                                                player.getDisplayName(),
                                                stages.size(),
                                                String.join(", ", stages)),
                        false);
        return stages.size();
    }

    private static int clear(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        int removed = 0, changed = 0;
        for (var player : EntityArgument.getPlayers(context, "players")) {
            int count = BetterBookStages.clear(player);
            removed += count;
            if (count > 0) changed++;
        }
        int stages = removed, players = changed;
        context.getSource()
                .sendSuccess(
                        () ->
                                Component.translatable(
                                        "command.betterbook.stage.cleared", stages, players),
                        true);
        return changed;
    }
}
