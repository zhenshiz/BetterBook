package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StepsTest {
    private BookSession session() {
        var session = new BookSession(Book.empty(StandardSchema.create()));
        session.importHtml(
                "<div data-type='steps' currentstep='0'>" + item("One") + item("Two") + "</div>");
        return session;
    }

    private String item(String title) {
        return "<div data-type='step-item'><div data-type='admonition-title'>"
                + title
                + "</div><div data-type='admonition-content'><p>"
                + title
                + " body</p></div></div>";
    }

    @Test
    void selectingAddingAndUndoRestoreTheActiveStepAndSelection() {
        var s = session();
        s.selectStep(2);
        assertEquals(
                "1", s.document().body().selectFirst("div[data-type=steps]").attr("currentstep"));
        var before = s.document().html();
        var selection = s.selection();
        s.editStep("add");
        assertEquals(3, s.document().body().select("div[data-type=step-item]").size());
        assertEquals(
                "2", s.document().body().selectFirst("div[data-type=steps]").attr("currentstep"));
        assertEquals("3", s.document().blocks().get(s.selection().head().block()).text());
        s.undo();
        assertEquals(before, s.document().html());
        assertEquals(selection, s.selection());
        s.redo();
        assertEquals(3, s.document().body().select("div[data-type=step-item]").size());
    }

    @Test
    void reorderingAndDeletingKeepCurrentItemAndValidCursor() {
        var s = session();
        s.selectStep(2);
        s.editStep("up");
        assertEquals("Two", s.document().blocks().getFirst().text());
        assertEquals(
                "0", s.document().body().selectFirst("div[data-type=steps]").attr("currentstep"));
        s.editStep("delete");
        assertEquals("One", s.document().blocks().get(s.selection().head().block()).text());
        s.editStep("delete");
        assertEquals(1, s.document().body().select("div[data-type=step-item]").size());
        var html = s.document().html();
        assertEquals(html, RichDocument.parse(html, StandardSchema.create()).html());
        assertThrows(IllegalArgumentException.class, () -> s.editStep("bad"));
        assertEquals(html, s.document().html());
    }

    @Test
    void keyboardNavigationCanRevealHiddenStepsWithoutLosingSelection() {
        var s = session();
        s.select(BookSession.Selection.at(3, 4));
        s.revealSelectedStep();
        assertEquals(BookSession.Selection.at(3, 4), s.selection());
        assertEquals(
                "1", s.document().body().selectFirst("div[data-type=steps]").attr("currentstep"));
        s.undo();
        assertEquals(
                "0", s.document().body().selectFirst("div[data-type=steps]").attr("currentstep"));
    }
}
