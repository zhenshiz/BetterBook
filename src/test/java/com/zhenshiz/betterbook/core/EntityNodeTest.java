package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class EntityNodeTest {
    @Test
    void entityNbtAndUnknownFieldsSurviveSourceCopyAndUndo() {
        var s = new BookSession(Book.empty(StandardSchema.create()));
        String nbt =
                "{VillagerData:{profession:\"minecraft:librarian\",type:\"minecraft:desert\",level:2},CustomName:'{\"text\":\"<&>\"}'}";
        var entity =
                new Element("div")
                        .attr("data-type", "entity")
                        .attr("data-entity-id", "minecraft:villager")
                        .attr("data-entity-nbt", nbt)
                        .attr("data-future-option", "kept");
        s.importHtml("<p>前文</p>" + entity.outerHtml() + "<p>后文</p>");
        assertTrue(s.document().blocks().get(1).atom());
        assertFalse(s.document().blocks().get(1).unknown());
        String original = s.document().html();
        s.draft(s.source());
        s.applySources();
        assertEquals(original, s.document().html());
        assertEquals(
                nbt,
                s.document().body().selectFirst("div[data-type=entity]").attr("data-entity-nbt"));
        s.select(
                new BookSession.Selection(
                        new BookSession.Position(1, 0), new BookSession.Position(1, 1)));
        s.delete(1);
        assertEquals("<p>前文</p><p></p><p>后文</p>", s.document().html());
        s.undo();
        assertEquals(original, s.document().html());
        s.selectAll();
        s.delete(-1);
        assertEquals("<p></p>", s.document().html());
    }
}
