package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class EditingTest {
    private BookSession session(String html) {
        var b = Book.empty(StandardSchema.create());
        var s = new BookSession(b);
        s.importHtml(html);
        return s;
    }

    @Test
    void quoteSupportsParagraphsSoftBreaksMarksAndUndo() {
        var s =
                session(
                        "<blockquote>第一行<strong>粗体</strong><br>第二行<p><em>第三段</em></p></blockquote>");
        assertEquals(2, s.document().blocks().size());
        assertEquals("第一行粗体\n第二行", s.document().blocks().getFirst().text());
        var original = s.document().html();
        s.select(BookSession.Selection.at(1, 3));
        s.enter(false);
        s.insert("第四段");
        s.enter(true);
        s.insert("软换行");
        assertEquals(3, s.document().body().select("blockquote > p").size());
        assertEquals("第四段\n软换行", s.document().blocks().getLast().text());
        s.select(
                new BookSession.Selection(
                        new BookSession.Position(2, 0), new BookSession.Position(2, 3)));
        s.toggleMark("betterbook:bold", Map.of());
        assertEquals("粗体 第四段", s.document().body().select("blockquote strong").text());
        var html = s.document().html();
        assertEquals(html, RichDocument.parse(html, StandardSchema.create()).html());
        s.undo();
        assertEquals("粗体", s.document().body().select("blockquote strong").text());
        for (int i = 0; i < 4; i++) s.undo();
        assertEquals(original, s.document().html());
    }

    @Test
    void formattingAndUndoRestoreSelection() {
        var s = session("<p>中文 hello</p>");
        var selection =
                new BookSession.Selection(
                        new BookSession.Position(0, 0), new BookSession.Position(0, 2));
        s.select(selection);
        s.toggleMark("betterbook:bold", Map.of());
        assertEquals("<p><strong>中文</strong> hello</p>", s.document().html());
        s.undo();
        assertEquals(selection, s.selection());
        assertEquals("<p>中文 hello</p>", s.document().html());
        s.redo();
        assertTrue(s.document().html().contains("<strong>"));
    }

    @Test
    void updateWholeMixedFormatLinkAndUndo() {
        var s =
                session(
                        "<p>前<a href='https://old.example'"
                                + " target='_blank'>中<strong>文😀</strong>链接</a>后</p>");
        var selection =
                new BookSession.Selection(
                        new BookSession.Position(0, 2), new BookSession.Position(0, 3));
        s.select(selection);
        assertEquals(
                new BookSession.Selection(
                        new BookSession.Position(0, 1), new BookSession.Position(0, 7)),
                s.selectedLink().orElseThrow().selection());
        var before = s.document().html();
        s.updateLink(" https://new.example/path ");
        assertEquals("前中文😀链接后", s.document().blocks().getFirst().text());
        assertTrue(
                s.document().body().select("a").stream()
                        .allMatch(
                                a ->
                                        a.attr("href").equals("https://new.example/path")
                                                && a.attr("target").equals("_blank")));
        assertEquals("文😀", s.document().body().selectFirst("strong").text());
        assertEquals(selection, s.selection());
        s.undo();
        assertEquals(before, s.document().html());
        assertEquals(selection, s.selection());
        s.redo();
        s.updateLink("");
        assertTrue(s.document().body().select("a").isEmpty());
        assertEquals("文😀", s.document().body().selectFirst("strong").text());
        s.undo();
        assertTrue(
                s.document().body().select("a").stream()
                        .allMatch(a -> a.attr("href").equals("https://new.example/path")));
    }

    @Test
    void createCrossParagraphLinkPreservesUnselectedTextAndCode() {
        var s =
                session(
                        "<p>before text</p><pre><code>literal</code></pre><p><em>after</em>"
                                + " text</p>");
        s.select(
                new BookSession.Selection(
                        new BookSession.Position(0, 7), new BookSession.Position(2, 5)));
        s.updateLink("book:target");
        assertEquals(2, s.document().body().select("a").size());
        assertEquals(
                "before text\nliteral\nafter text",
                s.document().blocks().stream()
                        .map(RichDocument.Block::text)
                        .collect(java.util.stream.Collectors.joining("\n")));
        assertTrue(s.document().body().select("pre a").isEmpty());
        assertEquals("after", s.document().body().selectFirst("em").text());
        s.updateLink("https://replacement.example");
        assertEquals(2, s.document().body().select("a").size());
        assertTrue(
                s.document().body().select("a").stream()
                        .allMatch(a -> a.attr("href").equals("https://replacement.example")));
    }

    @Test
    void adjacentLinksStayIndependentAtCursorBoundary() {
        var s =
                session(
                        "<p><a href='https://first.example'>first</a><a"
                                + " href='book:second'>second</a> plain</p>");
        s.select(BookSession.Selection.at(0, 5));
        assertEquals("book:second", s.selectedLink().orElseThrow().mark().attributes().get("href"));
        s.updateLink("https://second.example");
        assertEquals(
                "https://first.example", s.document().body().select("a").getFirst().attr("href"));
        assertEquals(
                "https://second.example", s.document().body().select("a").getLast().attr("href"));
        s.select(BookSession.Selection.at(0, 13));
        assertTrue(s.selectedLink().isEmpty());
        var html = s.document().html();
        s.updateLink("https://unused.example");
        assertEquals(html, s.document().html());
    }

    @Test
    void markdownUndoKeepsTypedSyntax() {
        var s = session("<p></p>");
        for (char c : "**粗体**".toCharArray()) {
            s.insert(String.valueOf(c));
            MarkdownRules.apply(s);
        }
        assertEquals("<p><strong>粗体</strong></p>", s.document().html());
        s.undo();
        assertEquals("**粗体**", s.document().blocks().getFirst().text());
    }

    @Test
    void unknownNodesAndWhitespaceRoundTrip() {
        String input =
                "<p>a<strong>b</strong><custom-inline x='1'>未知</custom-inline></p><pre><code>  a\n"
                        + "    b\n"
                        + "</code></pre><unknown-box value='2'>原样</unknown-box>";
        var doc = RichDocument.parse(input, StandardSchema.create());
        var html = doc.html();
        assertEquals(html, RichDocument.parse(html, StandardSchema.create()).html());
        assertTrue(html.contains("  a\n    b\n"));
        assertTrue(html.contains("unknown-box"));
    }

    @Test
    void crossBlockDeleteAndUnicode() {
        var s = session("<p>A😀B</p><p>中文</p>");
        s.select(
                new BookSession.Selection(
                        new BookSession.Position(0, 1), new BookSession.Position(1, 1)));
        s.insert("X");
        assertEquals("<p>AX文</p>", s.document().html());
        s.undo();
        s.select(BookSession.Selection.at(0, 3));
        s.delete(-1);
        assertEquals("AB", s.document().blocks().getFirst().text());
    }

    @Test
    void pagesKeepStableIdentity() {
        var s = session("<p>one</p>");
        String id = s.page().id();
        s.addPage(true);
        assertNotEquals(id, s.page().id());
        s.movePage(-1);
        s.renamePage("renamed");
        assertEquals(id, s.book().pages.get(1).id());
        s.undo();
        assertNotEquals("renamed", s.page().title());
    }

    @Test
    void sourceFailurePreservesLastValidDocument() {
        var s = session("<p>safe</p>");
        s.draft("<p><strong>broken</p>");
        assertThrows(IllegalArgumentException.class, s::applySources);
        assertEquals("<p>safe</p>", s.document().html());
        assertTrue(s.hasDraft());
    }

    @Test
    void tableAndTaskImport() {
        var s =
                session(
                        "<ul data-type='taskList'><li data-type='taskItem'"
                            + " data-checked='true'><label><input"
                            + " checked></label><div><p>ok</p></div></li></ul><table><tbody><tr><td>a<br>b</td></tr></tbody></table>");
        assertEquals(2, s.document().blocks().size());
        assertEquals("a\nb", s.document().blocks().get(1).text());
        assertTrue(s.document().html().contains("data-checked=\"true\""));
    }

    @Test
    void canTurnOffStoredMark() {
        var s = session("<p><strong>a</strong></p>");
        s.select(BookSession.Selection.at(0, 1));
        s.toggleMark("betterbook:bold", Map.of());
        s.insert("b");
        assertEquals("<p><strong>a</strong>b</p>", s.document().html());
    }

    @Test
    void deletingEmptyCodeReturnsToParagraphWithoutMergingNeighbors() {
        for (int direction : new int[] {-1, 1}) {
            for (String[] surroundings :
                    new String[][] {
                        {"", ""},
                        {"<p><strong>上一段</strong></p>", ""},
                        {"", "<p><em>下一段</em></p>"},
                        {"<p><strong>上一段</strong></p>", "<p><em>下一段</em></p>"},
                        {"<blockquote><p>引用</p>", "</blockquote>"},
                        {"<ul><li><p>列表</p>", "</li></ul>"}
                    }) {
                var s =
                        session(
                                surroundings[0]
                                        + "<pre language='javascript'><code></code></pre>"
                                        + surroundings[1]);
                var blocks = s.document().blocks();
                int index =
                        java.util.stream.IntStream.range(0, blocks.size())
                                .filter(i -> blocks.get(i).code())
                                .findFirst()
                                .orElseThrow();
                s.select(BookSession.Selection.at(index, 0));
                String before = s.document().html();
                s.delete(direction);
                assertEquals(surroundings[0] + "<p></p>" + surroundings[1], s.document().html());
                assertEquals(BookSession.Selection.at(index, 0), s.selection());
                s.undo();
                assertEquals(before, s.document().html());
                assertEquals(BookSession.Selection.at(index, 0), s.selection());
                s.redo();
                s.insert("正文");
                assertEquals(surroundings[0] + "<p>正文</p>" + surroundings[1], s.document().html());
            }
        }
    }

    @Test
    void lastCodeCharacterAndEmptyCodeAreSeparateUndoableDeletions() {
        for (int direction : new int[] {-1, 1}) {
            var s = session("<p>before</p><pre language='java'><code>😀</code></pre><p>after</p>");
            s.select(BookSession.Selection.at(1, direction < 0 ? 2 : 0));
            String before = s.document().html();
            s.delete(direction);
            assertTrue(s.document().blocks().get(1).code());
            assertEquals("", s.document().blocks().get(1).text());
            String emptyCode = s.document().html();
            s.delete(direction);
            assertEquals("<p>before</p><p></p><p>after</p>", s.document().html());
            s.undo();
            assertEquals(emptyCode, s.document().html());
            s.undo();
            assertEquals(before, s.document().html());
        }
    }

    @Test
    void backspaceExitsListWithoutReordering() {
        var s = session("<ul><li><p>one</p></li><li><p>two</p></li><li><p>three</p></li></ul>");
        s.select(BookSession.Selection.at(1, 0));
        s.delete(-1);
        assertEquals(
                "<ul><li><p>one</p></li></ul><p>two</p><ul><li><p>three</p></li></ul>",
                s.document().html());
        s.undo();
        assertEquals(BookSession.Selection.at(1, 0), s.selection());
    }

    @Test
    void tableInsideListDoesNotOutdentCellsOnBackspace() {
        var s =
                session(
                        "<ul><li><p>Item</p><table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table></li></ul>");
        String before = s.document().html();
        for (int block = 1; block <= 4; block++) {
            s.select(BookSession.Selection.at(block, 0));
            s.delete(-1);
            assertEquals(before, s.document().html());
            s.document().validate();
        }
    }

    @Test
    void tableOperationsAndLastCellRemovalRestoreWithUndo() {
        var s = session("<p></p>");
        s.insertTable(2, 3);
        s.select(BookSession.Selection.at(4, 0));
        s.editTable("row+");
        assertEquals(3, s.document().body().select("tr").size());
        s.editTable("col+");
        assertEquals(12, s.document().body().select("td").size());
        s.editTable("header");
        assertEquals(4, s.document().body().select("th").size());
        s.editTable("row-");
        s.editTable("row-");
        assertEquals(1, s.document().body().select("tr").size());
        for (int i = 0; i < 3; i++) s.editTable("col-");
        assertEquals(1, s.document().body().select("td,th").size());
        var before = s.document().html();
        s.editTable("col-");
        assertTrue(s.document().body().select("table").isEmpty());
        assertEquals("<p></p>", s.document().html());
        s.undo();
        assertEquals(before, s.document().html());
        s.editTable("row-");
        assertEquals("<p></p>", s.document().html());
    }

    @Test
    void removeRowAlwaysPreservesHeaderAndRemovesTheBottomRow() {
        var s =
                session(
                        "<table><tr><th>A</th><th>B</th></tr><tr><td>C</td><td>D</td></tr><tr><td>E</td><td>F</td></tr></table>");
        s.select(BookSession.Selection.at(1, 0));
        String before = s.document().html();
        s.editTable("row-");
        assertEquals("A B C D", s.document().body().select("td,th").text());
        assertEquals(2, s.document().body().select("th").size());
        assertEquals(BookSession.Selection.at(1, 0), s.selection());
        s.undo();
        assertEquals(before, s.document().html());
        s.select(BookSession.Selection.at(5, 0));
        s.editTable("row-");
        assertEquals(BookSession.Selection.at(3, 0), s.selection());
        assertEquals("D", s.document().blocks().get(3).text());
        s.document().validate();
    }

    @Test
    void sourceFormattingKeepsStructureAndTextUnchanged() {
        var s =
                session(
                        "<p>one<strong>two</strong>three</p><blockquote><p>甲</p><p>乙<br>丙</p></blockquote><pre><code>"
                            + "  x\n"
                            + "    y\n"
                            + "</code></pre><ul class='task-list'><li><input type='checkbox'"
                            + " checked>task</li></ul><table><tr><td>A</td><td>B</td></tr></table><unknown-widget>"
                            + " X <b>Y</b> Z </unknown-widget>");
        String compact = s.document().html();
        String pretty = s.source();
        assertTrue(
                pretty.contains("</p>\n<blockquote>\n  <p>甲</p>\n  <p>乙<br>丙</p>\n</blockquote>"));
        assertTrue(pretty.contains("one<strong>two</strong>three"));
        assertTrue(pretty.contains("  x\n    y\n"));
        s.draft(pretty + "\n");
        s.applySources();
        assertEquals(compact, s.document().html());
        assertEquals(pretty, s.source());
    }

    @Test
    void insertedLinkHasFallbackLabelAndOneUndo() {
        var s = session("<p>左右</p>");
        s.select(BookSession.Selection.at(0, 1));
        s.insertLink("https://example.invalid", "代码文档");
        assertEquals("左代码文档右", s.document().blocks().getFirst().text());
        assertEquals("代码文档", s.document().body().selectFirst("a").text());
        s.undo();
        assertEquals("<p>左右</p>", s.document().html());
        s.insertLink("book:contents", "");
        assertEquals("book:contents", s.document().body().selectFirst("a").text());
        String before = s.document().html();
        assertThrows(IllegalArgumentException.class, () -> s.insertLink(" ", "text"));
        assertEquals(before, s.document().html());
    }

    @Test
    void enterAfterHeadingCreatesNormalParagraph() {
        for (int level = 1; level <= 4; level++) {
            var s = session("<h" + level + ">标题</h" + level + ">");
            s.select(BookSession.Selection.at(0, 2));
            s.enter(false);
            s.insert("正文");
            assertEquals("p", s.document().blocks().get(1).element().normalName());
            assertEquals("h" + level, s.document().blocks().getFirst().element().normalName());
            s.select(BookSession.Selection.at(0, 2));
            s.enter(true);
            assertEquals("标题\n", s.document().blocks().getFirst().text());
        }
    }

    @Test
    void selectAllRemovesComponentStructureAndCanBeUndone() {
        for (String html :
                java.util.List.of(
                        "<div data-type='admonition'><div data-type='admonition-title'></div><div"
                                + " data-type='admonition-content'><p></p></div></div>",
                        "<table><tr><td></td></tr></table>",
                        "<p>before</p><table><tr><th>A</th><th>B</th></tr><tr><td>C</td><td>D</td></tr></table><p>after</p>",
                        "<div data-type='steps' currentstep='0'><div data-type='step-item'><div"
                            + " data-type='admonition-title'>one</div><div"
                            + " data-type='admonition-content'><p>text</p></div></div><div"
                            + " data-type='step-item'><div"
                            + " data-type='admonition-title'>two</div><div"
                            + " data-type='admonition-content'><p>hidden</p></div></div></div>")) {
            for (int direction : new int[] {-1, 1}) {
                var s = session(html);
                String before = s.document().html();
                s.selectAll();
                assertFalse(s.selection().empty());
                s.delete(direction);
                assertEquals("<p></p>", s.document().html());
                s.undo();
                assertEquals(before, s.document().html());
                assertTrue(s.selection().all());
                s.redo();
                assertEquals("<p></p>", s.document().html());
                s.insert("正文");
                assertEquals("<p>正文</p>", s.document().html());
            }
        }
    }

    @Test
    void replacingAllTextClearsContainersButTypingInEmptyCellDoesNot() {
        var s = session("<table><tr><td></td></tr></table>");
        s.insert("cell");
        assertEquals("cell", s.document().body().selectFirst("td").text());
        s.selectAll();
        s.insert("replacement");
        assertEquals("<p>replacement</p>", s.document().html());
    }

    @Test
    void cellDeleteKeepsRectangle() {
        var s = session("<table><tr><td>abc</td><td>def</td></tr></table>");
        s.select(
                new BookSession.Selection(
                        new BookSession.Position(0, 1), new BookSession.Position(1, 2)));
        s.insert("");
        assertEquals(2, s.document().body().select("td").size());
        assertEquals("a", s.document().blocks().getFirst().text());
        assertEquals("f", s.document().blocks().get(1).text());
    }

    @Test
    void insertAtomSelectsAtom() {
        var s = session("<p></p>");
        s.insertHtml("<img src='test'><p>tail</p>");
        assertTrue(s.document().blocks().get(s.selection().head().block()).element().is("img"));
    }

    @Test
    void paragraphCellsAndEmptyContainersAreEditable() {
        var s = session("<table><tr><td><p>a</p><p>b</p></td></tr></table>");
        assertEquals("a\nb", s.document().blocks().getFirst().text());
        assertFalse(
                RichDocument.parse("<div data-type='steps'></div>", StandardSchema.create())
                        .blocks()
                        .isEmpty());
    }

    @Test
    void blockConversionPreservesCodeNewlines() {
        var s = session("<p>a<br> b</p>");
        s.blockTag("pre");
        assertEquals("a\n b", s.document().blocks().getFirst().text());
        assertTrue(s.document().html().contains("<code"));
        s.blockTag("p");
        assertEquals("a\n b", s.document().blocks().getFirst().text());
    }

    @Test
    void realMcmodwikiSampleRoundTrips() throws Exception {
        String html =
                new String(
                        getClass().getResourceAsStream("/mcmodwiki-nonmc.html").readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        var doc = RichDocument.parse(html, StandardSchema.create());
        String output = doc.html();
        assertEquals(output, RichDocument.parse(output, StandardSchema.create()).html());
        assertTrue(doc.body().selectFirst("img").attr("src").startsWith("data:image/"));
        assertEquals(3, doc.body().select("div[data-type=admonition]").size());
        assertEquals(2, doc.body().select("div[data-type=step-item]").size());
        assertTrue(doc.blocks().stream().noneMatch(RichDocument.Block::unknown));
    }

    @Test
    void fencesProtectMarkdownAndPreserveWhitespace() {
        var s = session("<p></p>");
        for (String line : new String[] {"```javascript", "  **literal**", "```"}) {
            for (char c : line.toCharArray()) {
                s.insert(String.valueOf(c));
                MarkdownRules.apply(s);
            }
            if (!line.equals("```")) s.enter(false);
        }
        assertTrue(s.document().blocks().getFirst().code());
        assertEquals("  **literal**", s.document().blocks().getFirst().text());
    }

    @Test
    void fullMarkdownTableConvertsAtClosingRow() {
        var s = session("<p></p>");
        String[] lines = {"| A | B |", "| --- | --- |", "| one | two |"};
        for (int i = 0; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                s.insert(String.valueOf(c));
                MarkdownRules.apply(s);
            }
            if (i < 2) s.enter(false);
        }
        assertEquals(4, s.document().body().select("td,th").size());
        assertEquals("two", s.document().blocks().getLast().text());
    }

    @Test
    void invalidPluginContentRollsBack() {
        var schema = StandardSchema.create();
        schema.node(
                new Schema.NodeSpec(
                        "example:test",
                        "example-test",
                        Schema.Kind.ATOM,
                        "",
                        e -> {},
                        e -> {},
                        e -> {
                            if (e.attr("value").isBlank())
                                throw new IllegalArgumentException("value required");
                        }));
        var s = new BookSession(Book.empty(schema));
        assertThrows(
                IllegalArgumentException.class,
                () -> s.insertHtml("<example-test></example-test>"));
        assertEquals("<p></p>", s.document().html());
    }

    @Test
    void opaqueDescendantsSurviveUnrelatedTransactions() {
        var s =
                session(
                        "<p>text</p><custom-box><ul></ul><table><tr><td"
                                + " colspan='2'>opaque</td></tr></table></custom-box>");
        String original = s.document().body().selectFirst("custom-box").outerHtml();
        s.select(BookSession.Selection.at(0, 0));
        s.insert("a");
        assertEquals(original, s.document().body().selectFirst("custom-box").outerHtml());
    }

    @Test
    void editReorderKeepsCursorOnElement() {
        var s = session("<p>one</p><p>two</p>");
        s.select(BookSession.Selection.at(1, 2));
        s.editElement(e -> e.previousElementSibling().before(e));
        assertEquals(BookSession.Selection.at(0, 2), s.selection());
        s.undo();
        assertEquals(BookSession.Selection.at(1, 2), s.selection());
    }

    @Test
    void enterAtParentListItemSelectsNewSibling() {
        var s = session("<ul><li><p>one</p><ul><li><p>nested</p></li></ul></li></ul>");
        s.select(BookSession.Selection.at(0, 3));
        s.enter(false);
        s.insert("next");
        assertEquals("nested", s.document().blocks().get(1).text());
        assertEquals("next", s.document().blocks().get(2).text());
    }
}
