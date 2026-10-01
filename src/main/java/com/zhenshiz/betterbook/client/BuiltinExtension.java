package com.zhenshiz.betterbook.client;

import static org.lwjgl.glfw.GLFW.*;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import com.zhenshiz.betterbook.api.*;
import com.zhenshiz.betterbook.core.*;

import java.util.Map;

/** 所有首版元素均经公开扩展接口注册。 */
@LDLRegister(registry = BookExtension.REGISTRY, name = "betterbook:standard", priority = 100)
public final class BuiltinExtension implements BookExtension {
    @Override
    public void register(ExtensionContext c) {
        StandardSchema.register(c.schema);
        c.nodeViews.put(
                LatexNode.ID,
                ctx -> new BookLatexView(ctx.element(), ctx.editable(), ctx.update()));
        c.inspectors.put(
                LatexNode.ID, (session, host) -> BookLatexEditor.insertOrEdit(host, session));
        c.nodeViews.put(MermaidNode.ID,
                ctx -> new BookMermaidView(ctx.element(), ctx.editable(), ctx.update()));
        c.nodeViews.put("betterbook:item", ctx -> new BookItemView(ctx.element(), ctx.editable()));
        c.nodeViews.put(
                "betterbook:entity", ctx -> new BookEntityView(ctx.element(), ctx.editable()));
        c.nodeViews.put(
                "betterbook:structure",
                ctx -> new BookStructureView(ctx.element(), ctx.editable()));
        c.nodeViews.put(
                "betterbook:recipe", ctx -> new BookRecipeView(ctx.element(), ctx.editable()));
        c.nodeViews.put(
                "betterbook:related_pages",
                ctx -> new BookRelatedPagesView(ctx.element(), ctx.editable()));
        for (var mark : c.schema.marks()) {
            if (mark.id().equals("betterbook:color") || mark.id().equals(TextCommand.MARK))
                continue;
            c.command(
                    new ExtensionContext.Command(
                            mark.id(),
                            "gui.betterbook." + mark.id().split(":")[1],
                            s -> !s.hasDraft(),
                            s -> s.toggleMark(mark.id(), Map.of())),
                    false);
        }
        for (var node : c.schema.nodes())
            if (!node.template().isEmpty())
                c.command(
                        new ExtensionContext.Command(
                                "betterbook:insert_" + node.id().split(":")[1],
                                "gui.betterbook." + node.id().split(":")[1],
                                s -> !s.hasDraft(),
                                s -> s.insertHtml(node.template())),
                        true);
        c.command(
                new ExtensionContext.Command(
                        "betterbook:paragraph",
                        "gui.betterbook.paragraph",
                        s -> !s.hasDraft(),
                        s -> s.blockTag("p")),
                false);
        for (int level = 1; level <= 4; level++) {
            int value = level;
            c.command(
                    new ExtensionContext.Command(
                            "betterbook:h" + level,
                            "gui.betterbook.heading",
                            s -> !s.hasDraft(),
                            s -> s.blockTag("h" + value)),
                    false);
        }
        for (String align : java.util.List.of("left", "center", "right"))
            c.command(
                    new ExtensionContext.Command(
                            "betterbook:align_" + align,
                            "gui.betterbook." + align,
                            s -> !s.hasDraft(),
                            s -> s.align(align)),
                    false);
        c.command(
                new ExtensionContext.Command(
                        "betterbook:bullet_list",
                        "gui.betterbook.bullet_list",
                        s -> !s.hasDraft(),
                        s -> s.list("ul", false)),
                false);
        c.command(
                new ExtensionContext.Command(
                        "betterbook:ordered_list",
                        "gui.betterbook.ordered_list",
                        s -> !s.hasDraft(),
                        s -> s.list("ol", false)),
                false);
        c.command(
                new ExtensionContext.Command(
                        "betterbook:task_list",
                        "gui.betterbook.task_list",
                        s -> !s.hasDraft(),
                        s -> s.list("ul", true)),
                false);
        c.shortcuts.add(new ExtensionContext.Shortcut(GLFW_KEY_B, true, false, "betterbook:bold"));
        c.shortcuts.add(
                new ExtensionContext.Shortcut(GLFW_KEY_I, true, false, "betterbook:italic"));
        c.inputRules.add(MarkdownRules::apply);
    }
}
