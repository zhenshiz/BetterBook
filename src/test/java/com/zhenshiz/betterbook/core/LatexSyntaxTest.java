package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LatexSyntaxTest {
    @Test
    void declarationsRespectGroupsAndSubsequentColorChanges() {
        assertEquals("{\\textcolor{Blue}{ x}}+y", LatexSyntax.forRenderer("{\\color{Blue} x}+y"));
        assertEquals(
                "{\\huge \\textcolor{#ff0080}{ x}}+y",
                LatexSyntax.forRenderer("{\\huge \\color[RGB]{255,0,128} x}+y"));
        assertEquals(
                "\\textcolor{red}{x+\\textcolor{Blue}{y}}",
                LatexSyntax.forRenderer("\\color{red}x+\\color{Blue}y"));
        assertEquals(
                "\\frac{{\\textcolor{red}{x}}}{y}",
                LatexSyntax.forRenderer("\\frac{{\\color{red}x}}{y}"));
        assertEquals(
                "\\textcolor{#0080ff}{x}", LatexSyntax.forRenderer("\\textcolor[rgb]{0,0.5,1}{x}"));
        assertEquals(
                "{\\textcolor{#a1b2c3}{x}}", LatexSyntax.forRenderer("{\\color[HTML]{a1b2c3}x}"));
    }

    @Test
    void otherMathAndEscapedSymbolsArePreserved() {
        for (String source :
                new String[] {
                    "\\left\\{x\\right\\}",
                    "\\begin{pmatrix}a & b \\\\ c & d\\end{pmatrix}",
                    "{\\huge 123}12312",
                    "\\frac{x_{i}}{y}",
                    "% { comment\nx"
                }) assertEquals(source, LatexSyntax.forRenderer(source));
    }

    @Test
    void invalidColorModelsAndComponentsAreRejected() {
        for (String source :
                new String[] {
                    "{\\color[RGB]{256,0,0}x}",
                    "{\\color[RGB]{1.5,0,0}x}",
                    "{\\color[rgb]{0,-1,0}x}",
                    "{\\color[RGB]{1,2}x}",
                    "{\\color[RGB]{NaN,0,0}x}",
                    "{\\color[RGB]x}",
                    "{\\color[HTML]{123}x}"
                })
            assertThrows(
                    IllegalArgumentException.class, () -> LatexSyntax.forRenderer(source), source);
    }

    @Test
    void formattingCreatesScopedSourceAndSurvivesBookRoundTrip() {
        assertEquals("{\\color[RGB]{18,52,86} x}", LatexFormatting.rgb(0xff123456).wrap("x"));
        var size =
                LatexFormatting.SIZES.stream()
                        .filter(s -> s.id().equals("huge"))
                        .findFirst()
                        .orElseThrow();
        String source = size.wrap("123") + "12312";
        var node = new LatexNode(source, 16, "#393024", "center");
        assertEquals(
                node,
                LatexNode.read(
                        RichDocument.parse(node.element().outerHtml(), StandardSchema.create())
                                .blocks()
                                .getFirst()
                                .element()));
    }
}
