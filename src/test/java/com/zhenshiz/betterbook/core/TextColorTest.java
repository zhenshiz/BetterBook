package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextColorTest {
    private BookSession session(String html) {
        var session = new BookSession(Book.empty(StandardSchema.create()));
        session.importHtml(html);
        return session;
    }

    private Integer color(RichDocument.Run run) {
        Integer result = null;
        for (var mark : run.marks()) {
            if (!mark.id().equals("betterbook:color")) continue;
            var value = TextColor.read(mark.attributes().getOrDefault("style", ""));
            if (value != null) result = value;
        }
        return result;
    }

    @Test
    void applyAndReplaceColorPreserveSelectionAndOtherMarks() {
        var s = session("<p>左<strong>加粗</strong>普通<em>斜体</em>右</p>");
        var selection =
                new BookSession.Selection(
                        new BookSession.Position(0, 7), new BookSession.Position(0, 1));
        s.select(selection);
        String before = s.document().html();
        s.setTextColor(0xbe4b32);
        assertEquals(selection, s.selection());
        var runs = s.document().blocks().getFirst().runs();
        assertNull(color(runs.getFirst()));
        assertNull(color(runs.getLast()));
        assertTrue(
                RichDocument.slice(runs, 1, 7).stream().allMatch(r -> color(r).equals(0xbe4b32)));
        assertEquals("加粗", s.document().body().select("strong").text());
        assertEquals("斜体", s.document().body().select("em").text());
        String red = s.document().html();
        s.setTextColor(0x287b54);
        assertTrue(
                RichDocument.slice(s.document().blocks().getFirst().runs(), 1, 7).stream()
                        .allMatch(r -> color(r).equals(0x287b54)));
        assertFalse(s.document().html().contains("be4b32"));
        s.undo();
        assertEquals(red, s.document().html());
        s.undo();
        assertEquals(before, s.document().html());
        s.redo();
        assertEquals(red, s.document().html());
        s.setTextColor(null);
        assertEquals(before, s.document().html());
    }

    @Test
    void crossBlockColorKeepsLinksCodeAndRoundTrips() {
        var s = session("<p><a href='book:page'>链接</a></p><pre><code>plain</code></pre><p>末尾</p>");
        s.selectAll();
        s.setTextColor(0x2244cc);
        assertEquals("book:page", s.document().body().selectFirst("a").attr("href"));
        assertEquals("plain", s.document().blocks().get(1).text());
        assertTrue(s.document().body().select("pre span").isEmpty());
        String html = s.document().html();
        s.draft(s.source());
        s.applySources();
        assertEquals(html, s.document().html());
        assertEquals(0x2244cc, color(s.document().blocks().getFirst().runs().getFirst()));
    }

    @Test
    void resetRemovesNestedColorWithoutLosingOtherAttributes() {
        var s =
                session(
                        "<p><span style='background-color: #ffeedd; color: #abc' title='note'><span"
                            + " style='color: rgb(1, 2, 3)'>text</span></span></p>");
        assertEquals(0x010203, color(s.document().blocks().getFirst().runs().getFirst()));
        s.selectAll();
        s.setTextColor(null);
        assertNull(color(s.document().blocks().getFirst().runs().getFirst()));
        assertEquals("note", s.document().body().selectFirst("span").attr("title"));
        assertEquals(
                "background-color: #ffeedd;",
                s.document().body().selectFirst("span").attr("style"));
        String html = s.document().html();
        assertEquals(html, RichDocument.parse(html, StandardSchema.create()).html());
        assertEquals(
                "text",
                RichDocument.parse(html, StandardSchema.create()).blocks().getFirst().text());
    }

    @Test
    void cssColorsParseWithoutReadingBackgroundColor() {
        assertEquals(0xaabbcc, TextColor.read("color: #abc"));
        assertEquals(0x123456, TextColor.read("COLOR : #123456;"));
        assertEquals(0xff8000, TextColor.read("color: rgb(255, 128, 0)"));
        assertNull(TextColor.read("background-color: #abcdef"));
        assertNull(TextColor.read("color: rgb(999, 2, 3)"));
        assertNull(TextColor.read("color: invalid"));
    }
}
