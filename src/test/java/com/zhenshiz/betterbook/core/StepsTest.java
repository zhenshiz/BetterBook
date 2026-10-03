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

    @Test
    void readerStartsAtFirstStepWithoutChangingAuthorOrOtherReaders() {
        var author = session();
        author.selectStep(2);
        String saved = author.document().html();
        var firstReader = new BookSession(author.book().localized(author.language()));
        assertEquals(
                "0",
                firstReader.document().body().selectFirst("div[data-type=steps]").attr("currentstep"));
        firstReader.selectStep(2);
        assertEquals(
                "1",
                firstReader.document().body().selectFirst("div[data-type=steps]").attr("currentstep"));
        var nextReader = author.book().localized(author.language());
        assertEquals(
                "0",
                nextReader.pages.getFirst().document().body()
                        .selectFirst("div[data-type=steps]").attr("currentstep"));
        assertEquals(saved, author.document().html());
        assertEquals(
                "1",
                firstReader.document().body().selectFirst("div[data-type=steps]").attr("currentstep"));
    }

    @Test
    void readerResetsEveryGroupIncludingNestedTranslatedAndFallbackSteps() {
        var author = session();
        author.selectStep(2);
        var book = author.book();
        book.pages.add(new Book.Page("fallback", "Fallback", author.document().copy()));
        book.addLanguage("zh_cn");
        var nested =
                RichDocument.parse(
                        "<div data-type='steps' currentstep='1'>"
                                + item("一")
                                + "<div data-type='step-item'><div data-type='admonition-title'>二</div>"
                                + "<div data-type='admonition-content'><div data-type='steps' currentstep='1'>"
                                + item("内一")
                                + item("内二")
                                + "</div></div></div></div>",
                        StandardSchema.create());
        var translated = new Book.Page(book.pages.getFirst().id(), "步骤", nested);
        book.translations.put(
                "zh_cn",
                new Book.Translation("手册", "", java.util.Map.of(translated.id(), translated)));
        String savedTranslation = nested.html();
        var reader = book.localized("zh_cn");
        assertEquals(
                2, reader.pages.getFirst().document().body().select("div[data-type=steps]").size());
        for (var page : reader.pages)
            for (var group : page.document().body().select("div[data-type=steps]"))
                assertEquals("0", group.attr("currentstep"));
        assertEquals(savedTranslation, nested.html());
        assertEquals(
                "1",
                book.pages.get(1).document().body().selectFirst("div[data-type=steps]").attr("currentstep"));
    }
}
