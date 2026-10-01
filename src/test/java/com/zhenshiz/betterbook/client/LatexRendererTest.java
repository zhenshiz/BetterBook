package com.zhenshiz.betterbook.client;

import static org.junit.jupiter.api.Assertions.*;

import com.zhenshiz.betterbook.core.*;

import org.junit.jupiter.api.Test;

class LatexRendererTest {
    @Test
    void allVisualTemplatesRenderWithRealFontsAndTransparentBackground() {
        for (var template : LatexTemplates.ALL) {
            var node = new LatexNode(template.build(template.defaults()), 16, "#2468ab", "center");
            var result = LatexRenderer.get(node);
            assertTrue(result.valid(), template.id() + ": " + result.error());
            var image = result.image();
            boolean transparent = false, colored = false;
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getRGB(x, y);
                    transparent |= pixel >>> 24 == 0;
                    colored |= pixel >>> 24 > 200 && (pixel & 0xffffff) == 0x2468ab;
                }
            assertTrue(transparent, template.id());
            assertTrue(colored, template.id());
        }
        var symbols =
                LatexRenderer.get(
                        new LatexNode(
                                "\\alpha+\\beta+\\gamma+\\theta+\\pi+\\infty+\\leq",
                                16,
                                "#000000",
                                "left"));
        assertTrue(symbols.valid(), symbols.error());
    }

    @Test
    void badInputAndExternalResourceCommandsDoNotRender() {
        for (String source :
                new String[] {
                    "",
                    "\\frac{x",
                    "\\notARealCommand",
                    "\\input{secret}",
                    "\\includegraphics{/tmp/secret.png}",
                    "\\newcommand{\\foo}{x}",
                    "{".repeat(65) + "x" + "}".repeat(65)
                }) {
            var result = LatexRenderer.get(new LatexNode(source, 16, "#000000", "left"));
            assertFalse(result.valid(), source);
            assertFalse(result.error().isBlank());
        }
    }

    @Test
    void layoutUsesRenderedHeightAndScalesWideFormulas() {
        var node = new LatexNode("\\frac{\\frac{1}{x}}{\\sqrt{x}}", 24, "#000000", "center");
        var result = LatexRenderer.get(node);
        assertTrue(result.valid(), result.error());
        assertEquals(result.height() + 12, LatexRenderer.height(node.element(), 600), .01);
        assertTrue(
                LatexRenderer.height(node.element(), 30)
                        < LatexRenderer.height(node.element(), 600));
    }

    @Test
    void paletteAndFormattingMenusOnlyOfferRenderableMath() {
        var ids = new java.util.HashSet<String>();
        assertEquals(10, LatexPalette.CATEGORIES.size());
        for (var category : LatexPalette.CATEGORIES) {
            assertRenders(category.icon());
            for (var section : category.sections())
                for (var entry : section.entries()) {
                    assertTrue(ids.add(entry.id()), entry.id());
                    assertRenders(entry.source());
                }
        }
        for (var formats : java.util.List.of(LatexFormatting.SIZES, LatexFormatting.COLORS))
            for (var format : formats) assertRenders(format.wrap(format.sample()));
    }

    private static void assertRenders(String source) {
        var result = LatexRenderer.get(new LatexNode(source, 16, "#393024", "center"));
        assertTrue(result.valid(), source + ": " + result.error());
    }

    @Test
    void sourceSizesAndColorsAreLocalAndActuallyChangePixels() {
        var plain = LatexRenderer.get(new LatexNode("123", 16, "#393024", "center"));
        var huge = LatexRenderer.get(new LatexNode("{\\huge 123}", 16, "#393024", "center"));
        assertTrue(huge.valid(), huge.error());
        assertTrue(huge.width() > plain.width() * 1.5);
        var formula =
                LatexRenderer.get(
                        new LatexNode("{\\color[RGB]{255,0,128}x}+y", 16, "#393024", "center"));
        assertTrue(formula.valid(), formula.error());
        var image = formula.image();
        boolean styled = false, base = false;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) < 200) continue;
                styled |= (pixel & 0xffffff) == 0xff0080;
                base |= (pixel & 0xffffff) == 0x393024;
            }
        assertTrue(styled, "RGB scoped color must change pixels");
        assertTrue(base, "Text outside the color group keeps its original color");
    }
}
