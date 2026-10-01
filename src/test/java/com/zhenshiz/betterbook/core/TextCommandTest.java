package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class TextCommandTest {
    private BookSession session(String html) {
        var session = new BookSession(Book.empty(StandardSchema.create()));
        session.importHtml(html);
        return session;
    }

    @Test
    void commandsPreserveColorsFormattingSelectionAndUndo() {
        var s = session("<p>左<strong>加粗</strong><span style='color: #287b54'>颜色</span>右</p>");
        var selection =
                new BookSession.Selection(
                        new BookSession.Position(0, 5), new BookSession.Position(0, 1));
        s.select(selection);
        String original = s.document().html();
        var first = TextCommand.create("/give @s minecraft:diamond 1");
        s.setTextCommand(first);
        assertEquals(selection, s.selection());
        assertEquals(first, s.selectedCommand().orElseThrow());
        assertEquals("加粗", s.document().body().select("strong").text());
        assertEquals(
                "color: #287b54", s.document().body().selectFirst("span[style]").attr("style"));
        var runs = s.document().blocks().getFirst().runs();
        assertTrue(
                runs.getFirst().marks().stream().noneMatch(m -> m.id().equals(TextCommand.MARK)));
        assertTrue(runs.getLast().marks().stream().noneMatch(m -> m.id().equals(TextCommand.MARK)));
        String configured = s.document().html();
        s.setTextCommand(TextCommand.create("say changed"));
        s.undo();
        assertEquals(configured, s.document().html());
        s.undo();
        assertEquals(original, s.document().html());
        s.redo();
        s.setTextCommand(null);
        assertEquals(original, s.document().html());
    }

    @Test
    void replacingClickActionKeepsOneBehaviorAndRoundTrips() {
        var s =
                session(
                        "<p><a"
                            + " href='https://example.com'>链接</a></p><pre><code>code</code></pre><p>后文</p>");
        s.selectAll();
        s.setTextCommand(TextCommand.create("say clicked"));
        assertTrue(s.document().body().select("a").isEmpty());
        assertTrue(s.document().body().select("pre span").isEmpty());
        String html = s.document().html();
        s.draft(s.source());
        s.applySources();
        assertEquals(html, s.document().html());
        s.select(
                new BookSession.Selection(
                        new BookSession.Position(0, 0), new BookSession.Position(0, 2)));
        s.updateLink("book:another-page");
        assertTrue(s.selectedCommand().isEmpty());
        assertEquals("book:another-page", s.document().body().selectFirst("a").attr("href"));
    }

    @Test
    void mixedSelectionDoesNotPretendToHaveOneCommand() {
        var s = session("<p>左右</p>");
        s.select(
                new BookSession.Selection(
                        new BookSession.Position(0, 0), new BookSession.Position(0, 1)));
        s.setTextCommand(TextCommand.create("say left"));
        s.selectAll();
        assertTrue(s.selectedCommand().isEmpty());
        s.select(BookSession.Selection.at(0, 0));
        s.setTextCommand(TextCommand.create("say ignored"));
        assertEquals("左右", s.document().blocks().getFirst().text());
    }

    @Test
    void underlineDefaultsOnAndCanBeRemovedWithoutRemovingCommand() {
        var action = TextCommand.create("say test");
        assertTrue(action.underline());
        assertTrue(
                TextCommand.read(
                                Map.of(
                                        "data-command-id",
                                        action.id(),
                                        "data-command",
                                        action.command()))
                        .orElseThrow()
                        .underline());
        var plain = new TextCommand(action.id(), action.command(), action.permission(), false);
        var s = session("<p>点击文字</p>");
        s.selectAll();
        s.setTextCommand(plain);
        assertEquals(plain, s.selectedCommand().orElseThrow());
        String html = s.document().html();
        assertTrue(html.contains("data-underline=\"false\""));
        assertEquals(
                plain,
                TextCommand.read(
                                RichDocument.parse(html, StandardSchema.create())
                                        .blocks()
                                        .getFirst()
                                        .runs()
                                        .getFirst()
                                        .marks()
                                        .getFirst()
                                        .attributes())
                        .orElseThrow());
        s.setTextCommand(action);
        s.undo();
        assertEquals(plain, s.selectedCommand().orElseThrow());
        s.redo();
        assertEquals(action, s.selectedCommand().orElseThrow());
    }

    @Test
    void missingPermissionDefaultsToTwoAndInvalidBindingsStayInert() {
        var command = TextCommand.create(" /say Hello ");
        assertEquals("say Hello", command.command());
        assertEquals(
                2,
                TextCommand.read(
                                Map.of(
                                        "data-command-id",
                                        command.id(),
                                        "data-command",
                                        "say Hello"))
                        .orElseThrow()
                        .permission());
        for (String legacy : new String[] {"0", "1", "3", "4", "5", "-1", "invalid"}) {
            var restored =
                    TextCommand.read(
                                    Map.of(
                                            "data-command-id",
                                            command.id(),
                                            "data-command",
                                            "say Hello",
                                            "data-permission",
                                            legacy))
                            .orElseThrow();
            assertEquals(2, restored.permission());
            assertEquals("2", restored.mark().attributes().get("data-permission"));
        }
        assertTrue(
                TextCommand.read(
                                Map.of("data-command-id", "not-an-id", "data-command", "say Hello"))
                        .isEmpty());
        for (String invalid : new String[] {"", "/", "say one\nsay two", "x".repeat(2049)})
            assertThrows(IllegalArgumentException.class, () -> TextCommand.create(invalid));
        assertEquals(2, new TextCommand(command.id(), "say legacy", 4).permission());
        assertEquals(2, new TextCommand(command.id(), "say legacy", 0).permission());
    }
}
