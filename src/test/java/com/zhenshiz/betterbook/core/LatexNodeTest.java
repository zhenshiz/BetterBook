package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LatexNodeTest {
    @Test
    void sourceAttributesAndUnknownOptionsSurviveEditingAndUndo() {
        String source = "\\begin{pmatrix}a & b \\\\ c & d\\end{pmatrix} < x\n\\frac{1}{2}";
        var node = new LatexNode(source, 20, "#2468ab", "right");
        var element = node.element().attr("data-future-option", "kept");
        var session = new BookSession(Book.empty(StandardSchema.create()));
        session.importHtml("<p>前文</p>" + element.outerHtml() + "<p>后文</p>");
        var block = session.document().blocks().get(1);
        assertTrue(block.atom());
        assertFalse(block.unknown());
        assertEquals(node, LatexNode.read(block.element()));
        String original = session.document().html();
        session.draft(session.source());
        session.applySources();
        assertEquals(original, session.document().html());
        session.select(BookSession.Selection.at(1, 0));
        session.editElement(new LatexNode("\\sqrt{x}", 16, "#393024", "center")::writeTo);
        assertEquals(
                "kept", session.document().blocks().get(1).element().attr("data-future-option"));
        session.undo();
        assertEquals(original, session.document().html());
        session.redo();
        assertEquals(
                "\\sqrt{x}", LatexNode.read(session.document().blocks().get(1).element()).source());
        session.undo();
        session.select(
                new BookSession.Selection(
                        new BookSession.Position(1, 0), new BookSession.Position(1, 1)));
        session.delete(1);
        assertTrue(session.document().body().select(LatexNode.SELECTOR).isEmpty());
        session.undo();
        assertEquals(original, session.document().html());
    }

    @Test
    void boundedAttributesAndPlainTextValidation() {
        var node = LatexNode.read(new org.jsoup.nodes.Element("div").text("x^2"));
        assertEquals(new LatexNode("x^2", 16, "#393024", "center"), node);
        assertEquals(32, new LatexNode("x", 100, "invalid", "invalid").size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new LatexNode("x".repeat(4097), 16, "#000000", "left"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RichDocument.parse(
                                "<div data-type='latex'><img src='a'></div>",
                                StandardSchema.create()));
    }

    @Test
    void formulasRemainOpaqueWithoutTheExtension() {
        var schema = new Schema();
        schema.node(new Schema.NodeSpec("test:p", "p", Schema.Kind.TEXT, ""));
        var html = new LatexNode("\\frac{x}{y}", 16, "#393024", "center").element().outerHtml();
        var document = RichDocument.parse(html, schema);
        assertTrue(document.blocks().getFirst().unknown());
        assertEquals(html, document.html());
    }

    @Test
    void visualTemplatesPreserveNestedExpressions() {
        for (var template : LatexTemplates.ALL) {
            assertEquals(
                    template.defaults(),
                    template.match(template.build(template.defaults())).orElseThrow(),
                    template.id());
        }
        var fraction = LatexTemplates.ALL.getFirst();
        var values = java.util.List.of("\\frac{x_{i}}{y}+1", "\\sqrt{a^{2}+b^{2}}");
        assertEquals(values, fraction.match(fraction.build(values)).orElseThrow());
        assertTrue(fraction.match("\\frac{x}{y}+1").isEmpty());
        assertEquals("\\frac{$1}{x}", fraction.build(java.util.List.of("$1", "x")));
    }
}
