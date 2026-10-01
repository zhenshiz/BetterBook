package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class MermaidNodeTest {
    @Test
    void opaqueSourceAndCollapseStateSurviveEditingSaveAndUndo() {
        var node = new MermaidNode("flowchart LR\n  A[\"x < y & z\"] --> B{判断}", true);
        var element = node.element().attr("data-future-option", "kept");
        var session = new BookSession(Book.empty(StandardSchema.create()));
        session.importHtml("<p>之前</p>" + element.outerHtml() + "<p>之后</p>");
        assertTrue(session.document().blocks().get(1).atom());
        assertFalse(session.document().blocks().get(1).unknown());
        assertEquals(node, MermaidNode.read(session.document().blocks().get(1).element()));
        String original = session.document().html();
        session.draft(session.source());
        session.applySources();
        assertEquals(original, session.document().html());
        session.select(BookSession.Selection.at(1, 0));
        var replacement = new MermaidNode("graph TD\nA --> B", false);
        session.editElement(replacement::writeTo);
        assertEquals(replacement, MermaidNode.read(session.document().blocks().get(1).element()));
        assertEquals(
                "kept", session.document().blocks().get(1).element().attr("data-future-option"));
        session.undo();
        assertEquals(original, session.document().html());
        session.redo();
        assertEquals(replacement, MermaidNode.read(session.document().blocks().get(1).element()));
    }

    @Test
    void malformedGraphsAreStoredButEmbeddedElementsAndOversizedSourcesAreRejected() {
        assertEquals(
                "graph TD\nA[",
                MermaidNode.read(new MermaidNode("graph TD\nA[", true).element()).source());
        assertEquals(
                "graph TD\nA", MermaidNode.read(new Element("div").text("graph TD\nA")).source());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MermaidNode("x".repeat(MermaidNode.MAX_SOURCE + 1), true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RichDocument.parse(
                                "<div data-type=mermaid><img src=x></div>",
                                StandardSchema.create()));
        var document =
                RichDocument.parse(
                        new MermaidNode(MermaidNode.EXAMPLE, true).element().outerHtml(),
                        new Schema());
        assertTrue(document.blocks().getFirst().unknown());
        assertEquals(
                new MermaidNode(MermaidNode.EXAMPLE, true).element().outerHtml(), document.html());
    }
}
