package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 物品节点作为原子组件参与插入、源码往返和删除。 */
class ItemNodeTest {
    @Test
    void itemNodeRoundTripsAndDeletesAsOneComponent() {
        var s = new BookSession(Book.empty(StandardSchema.create()));
        s.importHtml("<p>前文</p><div data-type='item' data-stack='{}'></div><p>后文</p>");
        assertEquals("div", s.document().blocks().get(1).element().tagName());
        assertTrue(s.document().blocks().get(1).atom());
        String original = s.document().html();
        s.draft(s.source());
        s.applySources();
        assertEquals(original, s.document().html());
        s.select(
                new BookSession.Selection(
                        new BookSession.Position(1, 0), new BookSession.Position(1, 1)));
        s.delete(1);
        assertTrue(s.document().body().select("div[data-type=item]").isEmpty());
        assertEquals(
                "前文后文",
                s.document().blocks().stream()
                        .map(RichDocument.Block::text)
                        .reduce("", String::concat));
        s.undo();
        assertEquals(original, s.document().html());
    }
}
