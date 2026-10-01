package com.zhenshiz.betterbook.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.data.Cursor;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import com.lowdragmc.lowdraglib2.uitest.input.Keys;
import com.zhenshiz.betterbook.client.*;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/** 真实客户端验证分类、局部源码格式、颜色组件和保存阅读。 */
@LDLRegisterClient(
        name = "book_latex",
        group = "betterbook",
        registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public final class BookLatexScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions options) {
        options.defaultSettleMs(80).tags("betterbook", "latex", "editor");
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
                        "toolbar laid out",
                        ctx ->
                                ctx.exists("#insert-latex")
                                        && ctx.el("#insert-latex").bounds().width() > 0)
                .step("open formula editor", ctx -> click(ctx, "#insert-latex"))
                .waitUntil("formula editor visible", ctx -> ctx.exists("#latex-editor"))
                .check(
                        "ten top categories",
                        ctx -> ctx.el("#latex-categories").element().getChildren().size() == 10)
                .check(
                        "old symbol row and size buttons removed",
                        ctx -> !ctx.exists("#latex-symbol-0") && !ctx.exists("#latex-size"))
                .step("open common symbols", ctx -> click(ctx, "#latex-category-symbols"))
                .waitUntil("common symbols popup", ctx -> ctx.exists("#latex-palette-symbols"))
                .check(
                        "symbols have grouped sections",
                        ctx ->
                                ctx.exists("#latex-entry-symbols_binary_times")
                                        && ctx.exists("#latex-entry-symbols_arrows_rightarrow"))
                .step("park cursor", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("01_latex_symbols")
                .key(Keys.ESCAPE)
                .check(
                        "Esc dismisses only the palette",
                        ctx -> !ctx.exists("#latex-palette-symbols") && ctx.exists("#latex-editor"))
                .step("open fraction category", ctx -> click(ctx, "#latex-category-fraction"))
                .waitUntil("fractions popup", ctx -> ctx.exists("#latex-palette-fraction"))
                .step(
                        "choose fraction",
                        ctx -> click(ctx, "#latex-entry-fraction_fractions_fraction"));
        replace(s, "#latex-slot-0", "x+1");
        replace(s, "#latex-slot-1", "y");
        s.check("valid visual formula", ctx -> ctx.el("#latex-confirm").isActive())
                .check(
                        "draft not yet inserted",
                        ctx -> session(ctx).document().body().select(LatexNode.SELECTOR).isEmpty())
                .step("park cursor", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("02_latex_visual_editor")
                .step("commit fraction", ctx -> click(ctx, "#latex-confirm"))
                .waitUntil("fraction committed", ctx -> !ctx.exists("#latex-editor"))
                .check("fraction saved", ctx -> source(ctx).equals("\\frac{x+1}{y}"))
                .step("reopen formula", ctx -> click(ctx, "#insert-latex"))
                .waitUntil("parameters restored", ctx -> ctx.exists("#latex-slot-0"))
                .check(
                        "numerator restored",
                        ctx -> ctx.el("#latex-slot-0").as(TextField.class).getValue().equals("x+1"))
                .step("open size menu", ctx -> click(ctx, "#latex-size-menu"))
                .waitUntil("size popup visible", ctx -> ctx.exists("#latex-size-popup"))
                .check(
                        "nine syntax size choices",
                        ctx ->
                                ctx.exists("#latex-size-tiny")
                                        && ctx.exists("#latex-size-Huge")
                                        && ctx.exists("#latex-size-huge"))
                .step("park cursor", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("03_latex_size_menu")
                .step("huge numerator", ctx -> click(ctx, "#latex-size-huge"))
                .check(
                        "visual size wraps numerator only",
                        ctx ->
                                ctx.el("#latex-slot-0")
                                        .as(TextField.class)
                                        .getValue()
                                        .equals("{\\huge x+1}"))
                .step("show source", ctx -> click(ctx, "#latex-mode-source"))
                .check(
                        "visual formatting is literal LaTeX",
                        ctx -> draft(ctx).equals("\\frac{{\\huge x+1}}{y}"));
        replace(s, "#latex-source", "12312312");
        s.step(
                        "select first three digits",
                        ctx ->
                                ctx.el("#latex-source")
                                        .as(TextArea.class)
                                        .setSelection(new Cursor(0, 0), new Cursor(0, 3)))
                .step("open source size menu", ctx -> click(ctx, "#latex-size-menu"))
                .step("format source selection", ctx -> click(ctx, "#latex-size-huge"))
                .check(
                        "size applies only to selection",
                        ctx -> draft(ctx).equals("{\\huge 123}12312"))
                .step("open color menu", ctx -> click(ctx, "#latex-color-menu"))
                .waitUntil("color menu visible", ctx -> ctx.exists("#latex-color-popup"))
                .step("park cursor", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("04_latex_color_menu")
                .step("apply named color", ctx -> click(ctx, "#latex-color-Red"))
                .check(
                        "named color nests within selected size",
                        ctx -> draft(ctx).equals("{\\huge {\\color{Red} 123}}12312"))
                .step("reopen colors", ctx -> click(ctx, "#latex-color-menu"))
                .step("open custom RGB picker", ctx -> click(ctx, "#latex-color-custom"))
                .waitUntil("LDLib2 picker visible", ctx -> ctx.exists("#latex-custom-color-picker"))
                .check(
                        "native color selector used",
                        ctx ->
                                ctx.el("#latex-custom-color-picker").element()
                                        instanceof ColorSelector)
                .step(
                        "set RGB in color selector",
                        ctx ->
                                ctx.el("#latex-custom-color-picker")
                                        .as(ColorSelector.class)
                                        .setValue(0xff123456))
                .step("park cursor", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("05_latex_custom_color")
                .step("insert custom color", ctx -> click(ctx, "#latex-custom-color-apply"))
                .check(
                        "RGB syntax is inserted into selection",
                        ctx ->
                                draft(ctx)
                                        .equals(
                                                "{\\huge {\\color{Red} {\\color[RGB]{18,52,86}"
                                                        + " 123}}}12312"))
                .check("RGB formula parses", ctx -> ctx.el("#latex-confirm").isActive())
                .check(
                        "format controls only offer color, size and clear",
                        ctx ->
                                ctx.exists("#latex-color-menu")
                                        && ctx.exists("#latex-size-menu")
                                        && ctx.exists("#latex-clear")
                                        && !ctx.exists("#latex-font-menu")
                                        && !ctx.exists("#latex-environment-menu"))
                .step("cancel formatting draft", ctx -> click(ctx, "#latex-cancel"))
                .check(
                        "cancel leaves saved formula unchanged",
                        ctx -> source(ctx).equals("\\frac{x+1}{y}"))
                .step("undo insertion", ctx -> session(ctx).undo())
                .check(
                        "undo removes formula",
                        ctx -> session(ctx).document().body().select(LatexNode.SELECTOR).isEmpty())
                .step("redo insertion", ctx -> session(ctx).redo())
                .check("redo restores source", ctx -> source(ctx).equals("\\frac{x+1}{y}"))
                .step(
                        "name formula view",
                        ctx ->
                                ctx.query()
                                        .where(
                                                e ->
                                                        e.getClass()
                                                                .getSimpleName()
                                                                .equals("BookLatexView"))
                                        .one()
                                        .element()
                                        .setId("latex-book-node"))
                .step("first click formula", ctx -> click(ctx, "#latex-book-node"))
                .settleMs(30)
                .step("second click formula", ctx -> click(ctx, "#latex-book-node"))
                .waitUntil("double click editor opened", ctx -> ctx.exists("#latex-editor"))
                .step("show matrix category", ctx -> click(ctx, "#latex-category-matrix"))
                .step("park cursor", ctx -> ctx.input().moveTo(-10000, -10000))
                .screenshot("06_latex_matrix_palette")
                .step("choose matrix", ctx -> click(ctx, "#latex-entry-matrix_matrices_bmatrix"))
                .step("show source", ctx -> click(ctx, "#latex-mode-source"));
        replace(s, "#latex-source", "{\\huge {\\color[RGB]{18,52,86}x}}+y");
        s.step("commit scoped style", ctx -> click(ctx, "#latex-confirm"))
                .waitUntil("styled formula committed", ctx -> !ctx.exists("#latex-editor"))
                .check(
                        "source persisted without flattening styles",
                        ctx -> source(ctx).equals("{\\huge {\\color[RGB]{18,52,86}x}}+y"))
                .check(
                        "style menu did not change base node size/color",
                        ctx -> {
                            var node =
                                    LatexNode.read(
                                            session(ctx)
                                                    .document()
                                                    .body()
                                                    .selectFirst(LatexNode.SELECTOR));
                            return node.size() == 16 && node.color().equals("#393024");
                        })
                .openScreen(
                        "read styled formula",
                        ctx ->
                                new BookReaderScreen(
                                        session(ctx).book(), null, session(ctx).language()))
                .awaitModularUI()
                .check(
                        "reader has the shared formula view",
                        ctx ->
                                ctx.query()
                                                .where(
                                                        e ->
                                                                e.getClass()
                                                                        .getSimpleName()
                                                                        .equals("BookLatexView"))
                                                .count()
                                        == 1)
                .screenshot("07_latex_reader")
                .step(
                        "close workspace",
                        ctx ->
                                ((BookEditor) ctx.get("editor"))
                                        .book()
                                        .onClosed(((BookEditor) ctx.get("editor"))))
                .closeScreen();
    }

    private static void replace(ScenarioBuilder s, String selector, String value) {
        s.focus(selector)
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

    private static String draft(TestContext ctx) {
        return String.join("\n", ctx.el("#latex-source").as(TextArea.class).getValue());
    }

    private static BookSession session(TestContext ctx) {
        return ((BookEditor) ctx.get("editor")).book().session;
    }

    private static String source(TestContext ctx) {
        return LatexNode.read(session(ctx).document().body().selectFirst(LatexNode.SELECTOR))
                .source();
    }
}
