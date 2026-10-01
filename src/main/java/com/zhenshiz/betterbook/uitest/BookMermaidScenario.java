package com.zhenshiz.betterbook.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import com.lowdragmc.lowdraglib2.uitest.input.Keys;
import com.zhenshiz.betterbook.client.*;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/** Mermaid 图表/源码共存、编辑焦点、恢复和只读模式的真实客户端回归。 */
@LDLRegisterClient(
        name = "book_mermaid",
        group = "betterbook",
        registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public final class BookMermaidScenario implements UIScenario {
    private static final String UPDATED =
            "flowchart LR\n  A[输入] --> B{合法?}\n  B -->|是| C([完成])\n  B -->|否| D[返回]";

    @Override
    public void configure(ScenarioOptions options) {
        options.defaultSettleMs(80).tags("betterbook", "mermaid", "editor");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.openModularUI("book editor", ctx -> BookClientMenus.editorUI(ctx.player()))
                .awaitModularUI()
                .step(
                        "create empty book",
                        ctx -> {
                            var editor =
                                    ctx.query().type(BookEditor.class).one().as(BookEditor.class);
                            editor.newBook();
                            ctx.put("editor", editor);
                        })
                .waitUntil(
                        "mermaid toolbar laid out",
                        ctx ->
                                ctx.exists("#insert-mermaid")
                                        && ctx.el("#insert-mermaid").bounds().width() > 0)
                .step("insert mermaid diagram", ctx -> click(ctx, "#insert-mermaid"))
                .waitUntil(
                        "inline source laid out",
                        ctx ->
                                ctx.exists("#mermaid-source")
                                        && ctx.el("#mermaid-source").bounds().height() > 0)
                .check("reference graph saved", ctx -> source(ctx).equals(MermaidNode.EXAMPLE))
                .check(
                        "diagram and source in same block",
                        ctx ->
                                ctx.exists("#mermaid-diagram")
                                        && ctx.exists("#mermaid-source-toggle"))
                .step(
                        "remember source editor",
                        ctx -> ctx.put("source-editor", ctx.el("#mermaid-source").element()))
                .step("park pointer", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("01_mermaid_editor");
        replace(s, UPDATED);
        s.check("source updates immediately", ctx -> source(ctx).equals(UPDATED))
                .check(
                        "editor reused across edits",
                        ctx -> ctx.el("#mermaid-source").element() == ctx.get("source-editor"))
                .check(
                        "graph direction and branch labels updated",
                        ctx -> {
                            var graph = MermaidFlowchart.parse(source(ctx));
                            return graph.direction() == MermaidFlowchart.Direction.LR
                                    && graph.edges().get(1).label().equals("是");
                        })
                .step(
                        "move to source end",
                        ctx -> {
                            var input = ctx.el("#mermaid-source").as(TextArea.class);
                            var lines = input.getValue();
                            input.setCursor(lines.length - 1, lines[lines.length - 1].length());
                            input.focus();
                        })
                .type(" ")
                .check(
                        "typed character stays inside Mermaid source",
                        ctx -> source(ctx).equals(UPDATED + " "))
                .check(
                        "typing does not split or replace the atom",
                        ctx ->
                                session(ctx).document().body().select(MermaidNode.SELECTOR).size()
                                                == 1
                                        && session(ctx).document().body().text().contains("输入"))
                .step("undo source transaction", ctx -> session(ctx).undo())
                .check("undo restored source", ctx -> source(ctx).equals(UPDATED))
                .step("redo source transaction", ctx -> session(ctx).redo())
                .check("redo restored typed source", ctx -> source(ctx).equals(UPDATED + " "))
                .step("collapse source", ctx -> click(ctx, "#mermaid-source-toggle"))
                .waitUntil(
                        "source collapsed",
                        ctx -> !ctx.el("#mermaid-source").element().isDisplayed())
                .check("collapse state saved", ctx -> !node(ctx).sourceVisible())
                .step("expand source", ctx -> click(ctx, "#mermaid-source-toggle"))
                .waitUntil(
                        "source expanded",
                        ctx -> ctx.el("#mermaid-source").element().isDisplayed());
        replace(s, "graph TD\n A[");
        s.check(
                        "invalid draft remains editable",
                        ctx -> source(ctx).equals("graph TD\n A[") && ctx.exists("#mermaid-source"))
                .step("park pointer", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("02_mermaid_error");
        replace(s, MermaidNode.EXAMPLE);
        s.check(
                        "correcting source restores graph",
                        ctx -> MermaidFlowchart.parse(source(ctx)).nodes().size() == 4)
                .step("park pointer", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("03_mermaid_restored")
                .step(
                        "save and reparse source",
                        ctx -> {
                            var session = session(ctx);
                            session.draft(session.source());
                            session.applySources();
                        })
                .check("book source round trip", ctx -> source(ctx).equals(MermaidNode.EXAMPLE))
                .openScreen(
                        "read diagram",
                        ctx ->
                                new BookReaderScreen(
                                        session(ctx).book(), null, session(ctx).language()))
                .awaitModularUI()
                .check("reader renders diagram", ctx -> ctx.exists("#mermaid-diagram"))
                .check(
                        "reader has no source controls",
                        ctx ->
                                !ctx.exists("#mermaid-source")
                                        && !ctx.exists("#mermaid-source-toggle"))
                .step("park pointer", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("04_mermaid_reader")
                .step(
                        "close workspace",
                        ctx ->
                                ((BookEditor) ctx.get("editor"))
                                        .book()
                                        .onClosed((BookEditor) ctx.get("editor")))
                .closeScreen();
    }

    private static void replace(ScenarioBuilder s, String value) {
        s.focus("#mermaid-source")
                .keyDown(Minecraft.ON_OSX ? GLFW.GLFW_KEY_LEFT_SUPER : GLFW.GLFW_KEY_LEFT_CONTROL)
                .key(GLFW.GLFW_KEY_A)
                .keyUp(Minecraft.ON_OSX ? GLFW.GLFW_KEY_LEFT_SUPER : GLFW.GLFW_KEY_LEFT_CONTROL)
                .key(Keys.BACKSPACE)
                .type(value);
    }

    private static void click(TestContext ctx, String selector) {
        var bounds = ctx.el(selector).bounds();
        ctx.input().moveTo(bounds.centerX(), bounds.centerY());
        ctx.input().mouseDown(bounds.centerX(), bounds.centerY(), 0);
        ctx.input().mouseUp(bounds.centerX(), bounds.centerY(), 0);
    }

    private static BookSession session(TestContext ctx) {
        return ((BookEditor) ctx.get("editor")).book().session;
    }

    private static MermaidNode node(TestContext ctx) {
        return MermaidNode.read(session(ctx).document().body().selectFirst(MermaidNode.SELECTOR));
    }

    private static String source(TestContext ctx) {
        return node(ctx).source();
    }
}
