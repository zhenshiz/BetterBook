package com.zhenshiz.betterbook.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import com.zhenshiz.betterbook.client.*;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.client.Minecraft;

/** 九类模板均经真实 UI 插入、原生绘图与放大窗口检查。 */
@LDLRegisterClient(
        name = "book_mermaid_types",
        group = "betterbook",
        registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public final class BookMermaidTypesScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions options) {
        options.defaultSettleMs(80).tags("betterbook", "mermaid", "types");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.openModularUI("book editor", ctx -> BookClientMenus.editorUI(ctx.player()))
                .awaitModularUI()
                .step(
                        "new book",
                        ctx -> {
                            var editor =
                                    ctx.query().type(BookEditor.class).one().as(BookEditor.class);
                            editor.newBook();
                            ctx.put("editor", editor);
                        })
                .waitUntil(
                        "toolbar laid out",
                        ctx ->
                                ctx.exists("#insert-mermaid")
                                        && ctx.el("#insert-mermaid").bounds().width() > 0)
                .step("insert diagram", ctx -> click(ctx, "#insert-mermaid"))
                .waitUntil(
                        "diagram laid out",
                        ctx ->
                                ctx.exists("#mermaid-diagram")
                                        && ctx.el("#mermaid-diagram").bounds().height() > 0);
        for (var template : MermaidTemplates.ALL) {
            s.step("open templates " + template.id(), ctx -> click(ctx, "#mermaid-templates"))
                    .waitUntil(
                            "template menu visible",
                            ctx -> ctx.exists("#mermaid-template-" + template.id()))
                    .step(
                            "choose " + template.id(),
                            ctx -> click(ctx, "#mermaid-template-" + template.id()))
                    .check(
                            "source for " + template.id(),
                            ctx -> source(ctx).equals(template.source()))
                    .check(
                            "valid layout for " + template.id(),
                            ctx -> {
                                if (template.id().equals("flowchart"))
                                    return MermaidLayout.build(
                                                            MermaidFlowchart.parse(source(ctx)),
                                                            Minecraft.getInstance().font::width)
                                                    .boxes()
                                                    .size()
                                            == 4;
                                var scene =
                                        MermaidDiagrams.parse(
                                                source(ctx), Minecraft.getInstance().font::width);
                                return !scene.items().isEmpty()
                                        && Float.isFinite(scene.width())
                                        && Float.isFinite(scene.height());
                            })
                    .step("first diagram click", ctx -> click(ctx, "#mermaid-diagram"))
                    .settleMs(30)
                    .step("second diagram click", ctx -> click(ctx, "#mermaid-diagram"))
                    .waitUntil("expanded diagram", ctx -> ctx.exists("#mermaid-expanded-diagram"))
                    .check(
                            "expanded canvas visible",
                            ctx -> ctx.el("#mermaid-expanded-diagram").bounds().height() > 100)
                    .step("park pointer", ctx -> ctx.input().moveTo(-10000, -10000))
                    .screenshot("type_" + template.id())
                    .step("close expanded", ctx -> click(ctx, "#mermaid-expanded-close"))
                    .waitUntil("expanded closed", ctx -> !ctx.exists("#mermaid-expanded"));
        }
        String grouped =
                "flowchart TD\n"
                    + "subgraph first[输入]\n"
                    + "A[一] & B[二] --> C{判断}\n"
                    + "end\n"
                    + "C --> D[完成]\n"
                    + "classDef good fill:#f7edd5,stroke:#9f8055,color:#362a21\n"
                    + "class D good";
        s.step(
                        "set grouped flowchart",
                        ctx ->
                                ctx.el("#mermaid-source")
                                        .as(TextArea.class)
                                        .setValue(grouped.split("\n", -1)))
                .check(
                        "grouped flowchart persisted",
                        ctx -> MermaidFlowchart.parse(source(ctx)).groups().size() == 1)
                .step("park pointer", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("type_subgraph")
                .openScreen(
                        "read grouped diagram",
                        ctx ->
                                new BookReaderScreen(
                                        session(ctx).book(), null, session(ctx).language()))
                .awaitModularUI()
                .check(
                        "reader hides every editing control",
                        ctx ->
                                !ctx.exists("#mermaid-source")
                                        && !ctx.exists("#mermaid-source-toggle")
                                        && !ctx.exists("#mermaid-templates"))
                .step(
                        "close workspace",
                        ctx ->
                                ((BookEditor) ctx.get("editor"))
                                        .book()
                                        .onClosed((BookEditor) ctx.get("editor")))
                .closeScreen();
    }

    private static void click(TestContext ctx, String selector) {
        var b = ctx.el(selector).bounds();
        ctx.input().moveTo(b.centerX(), b.centerY());
        ctx.input().mouseDown(b.centerX(), b.centerY(), 0);
        ctx.input().mouseUp(b.centerX(), b.centerY(), 0);
    }

    private static BookSession session(TestContext ctx) {
        return ((BookEditor) ctx.get("editor")).book().session;
    }

    private static String source(TestContext ctx) {
        return MermaidNode.read(session(ctx).document().body().selectFirst(MermaidNode.SELECTOR))
                .source();
    }
}
