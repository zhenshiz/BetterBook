package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RecipeNodeTest {
    @Test
    void recipeIsAtomicAndSurvivesSourceUndoAndDeletion() {
        var session = new BookSession(Book.empty(StandardSchema.create()));
        session.importHtml(
                "<p>before</p><div data-type='recipe' data-recipe-width='180'"
                    + " data-recipe-height='100'"
                    + " data-recipe='{recipe:&quot;minecraft:iron_pickaxe&quot;,jei:1b}'></div><p>after</p>");
        String original = session.document().html();
        assertTrue(session.document().blocks().get(1).atom());
        session.draft(session.source());
        session.applySources();
        assertEquals(original, session.document().html());
        session.select(
                new BookSession.Selection(
                        new BookSession.Position(1, 0), new BookSession.Position(1, 1)));
        session.delete(-1);
        assertTrue(session.document().body().select("div[data-type=recipe]").isEmpty());
        session.undo();
        assertEquals(original, session.document().html());
        session.selectAll();
        session.delete(1);
        assertEquals("<p></p>", session.document().html());
    }
}
