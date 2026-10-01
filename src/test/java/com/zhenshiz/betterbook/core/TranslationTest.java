package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class TranslationTest {
    private Book chineseBook() {
        var b = Book.empty(StandardSchema.create());
        b.defaultLanguage = "zh_cn";
        b.title = "手册";
        b.author = "作者";
        b.pages.set(
                0,
                new Book.Page(
                        "start", "首页", RichDocument.parse("<p>中文</p>", StandardSchema.create())));
        return b;
    }

    @Test
    void editingAndUndoAreIsolatedByLanguage() {
        var s = new BookSession(chineseBook());
        s.addLanguage("EN-us");
        assertEquals("en_us", s.language());
        assertEquals("<p>中文</p>", s.document().html());
        s.importHtml("<p>Hello <strong>world</strong></p>");
        s.renameBook("Guide", "Author");
        s.renamePage("Home");
        assertEquals("手册", s.book().title);
        assertEquals("首页", s.book().pages.getFirst().title());
        assertEquals("<p>中文</p>", s.book().pages.getFirst().document().html());
        s.switchLanguage("zh_cn");
        s.undo();
        assertEquals("en_us", s.language());
        assertEquals("首页", s.page().title());
        s.redo();
        assertEquals("Home", s.page().title());
        s.undo();
        s.undo();
        s.undo();
        s.undo();
        assertEquals(Set.of("zh_cn"), s.book().languages());
        assertEquals("zh_cn", s.language());
    }

    @Test
    void sourceBufferAndInvalidSourceDoNotLeakBetweenLanguages() {
        var s = new BookSession(chineseBook());
        s.addLanguage("en_us");
        s.draft("<p>English draft</p>");
        s.switchLanguage("zh_cn");
        assertEquals("<p>中文</p>", s.source());
        s.draft("<p>中文草稿</p>");
        s.switchLanguage("en_us");
        assertEquals("<p>English draft</p>", s.source());
        s.draft("<p><");
        assertThrows(IllegalArgumentException.class, () -> s.switchLanguage("zh_cn"));
        assertEquals("en_us", s.language());
        assertEquals("<p><", s.source());
        assertEquals("<p>English draft</p>", s.document().html());
        assertEquals("<p>中文草稿</p>", s.book().pages.getFirst().document().html());
        s.undo();
        assertFalse(s.hasDraft());
        s.switchLanguage("zh_cn");
        assertEquals("<p>中文草稿</p>", s.document().html());
    }

    @Test
    void pageOrderIdsAndCopiesStayShared() {
        var s = new BookSession(chineseBook());
        s.addLanguage("en_us");
        s.importHtml("<p><a href='book:start'>Home</a></p>");
        s.renamePage("Home");
        s.addPage(true);
        String copyId = s.page().id();
        assertNotEquals("start", copyId);
        assertEquals("Home", s.page().title());
        assertEquals("首页", s.book().pages.get(1).title());
        s.insert("New ");
        assertFalse(s.book().page(0, "en_us").document().html().contains("New"));
        s.movePage(-1);
        assertEquals(copyId, s.book().pages.getFirst().id());
        s.switchPage(1);
        s.removePage();
        assertEquals(1, s.book().translations.get("en_us").pages().size());
        assertTrue(s.document().html().contains("book:start"));
        s.undo();
        assertEquals("start", s.page().id());
        assertEquals(2, s.book().translations.get("en_us").pages().size());
    }

    @Test
    void fallbackIsPerPageAndChangingDefaultPreservesResolvedText() {
        var b = chineseBook();
        b.pages.add(
                new Book.Page(
                        "next", "下一页", RichDocument.parse("<p>第二页</p>", StandardSchema.create())));
        b.translations.put(
                "en_us",
                new Book.Translation(
                        "Guide",
                        "Author",
                        Map.of(
                                "start",
                                new Book.Page(
                                        "start",
                                        "Home",
                                        RichDocument.parse(
                                                "<p>Hello</p>", StandardSchema.create())))));
        b.translations.put("fr_fr", new Book.Translation("Livre", "Auteur", Map.of()));
        assertEquals("Guide", b.localized("en_us").title);
        assertEquals("<p>第二页</p>", b.localized("en_us").pages.get(1).document().html());
        assertEquals("手册", b.localized("de_de").title);
        assertEquals("<p>中文</p>", b.localized("de_de").pages.getFirst().document().html());
        assertFalse(b.hasTranslation(1, "en_us"));
        b.setDefaultLanguage("en_us");
        assertEquals("Guide", b.title);
        assertEquals("<p>Hello</p>", b.localized("de_de").pages.getFirst().document().html());
        assertEquals("<p>中文</p>", b.localized("fr_fr").pages.getFirst().document().html());
        assertEquals("首页", b.page(0, "zh_cn").title());
        assertEquals("start", b.localized("en_us").pages.getFirst().id());
        b.localized("en_us").pages.getFirst().document().body().text("changed reader");
        assertEquals("<p>Hello</p>", b.pages.getFirst().document().html());
    }

    @Test
    void languageCodesAreNormalizedAndValidated() {
        var b = chineseBook();
        assertThrows(IllegalArgumentException.class, () -> b.addLanguage("../en_us"));
        b.addLanguage("en_us");
        assertThrows(IllegalArgumentException.class, () -> b.addLanguage("EN-US"));
        assertEquals("zh_cn", Book.normalizeLanguage("ZH-CN"));
    }

    @Test
    void pageClipboardAndDragOrderAreUndoableAcrossTranslations() {
        var session = new BookSession(chineseBook());
        session.addLanguage("en_us");
        session.importHtml("<p><a href='book:start'>English</a></p>");
        var copy = session.copyPage();
        session.importHtml("<p>Changed after copying</p>");
        session.pastePage(copy);
        String newId = session.page().id();
        assertNotEquals("start", newId);
        assertTrue(session.document().html().contains("English"));
        assertEquals("<p>中文</p>", session.book().page(1, "zh_cn").document().html());
        session.reorderPages(List.of(newId, "start"));
        assertEquals(newId, session.page().id());
        assertEquals(0, session.pageIndex());
        assertTrue(session.document().html().contains("book:start"));
        session.undo();
        assertEquals(1, session.pageIndex());
        session.undo();
        assertEquals(1, session.book().pages.size());
        session.redo();
        assertEquals(newId, session.page().id());
        assertThrows(
                IllegalArgumentException.class, () -> session.reorderPages(List.of(newId, newId)));
    }
}
