package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BookSettingsTest {
    @Test
    void scaleSettingDefaultsToCurrentBehaviorAndSurvivesCopies() {
        var book = Book.empty(StandardSchema.create());
        assertTrue(book.followGuiScale);
        book.addLanguage("zh_cn");
        book.followGuiScale = false;
        assertFalse(book.copy().followGuiScale);
        assertFalse(book.localized("en_us").followGuiScale);
        assertFalse(book.localized("zh_cn").followGuiScale);
        assertFalse(book.localized("de_de").followGuiScale);
        book.localized("zh_cn").followGuiScale = true;
        assertFalse(book.followGuiScale);
    }

    @Test
    void settingIsSharedAcrossLanguagesAndRestoredByHistory() {
        var session = new BookSession(Book.empty(StandardSchema.create()));
        session.addLanguage("zh_cn");
        String html = session.document().html();
        session.setFollowGuiScale(false);
        session.switchLanguage("en_us");
        assertFalse(session.book().followGuiScale);
        session.undo();
        assertTrue(session.book().followGuiScale);
        session.redo();
        assertFalse(session.book().followGuiScale);
        assertEquals(html, session.document().html());
        session.renameBook("Title", "Author");
        assertFalse(session.book().followGuiScale);
        session.undo();
        assertFalse(session.book().followGuiScale);
        long revision = session.revision();
        session.setFollowGuiScale(false);
        assertEquals(revision, session.revision());
    }

    @Test
    void readerSettingsKeepLegacyDefaultsAndSurviveEveryBookCopy() {
        var book = Book.empty(StandardSchema.create());
        assertFalse(book.singlePage);
        assertTrue(book.allowPageTurning);
        assertTrue(book.lockedIcons);
        book.addLanguage("zh_cn");
        book.singlePage = true;
        book.allowPageTurning = false;
        book.lockedIcons = false;
        book.followGuiScale = false;
        for (var copy :
                java.util.List.of(
                        book.copy(),
                        book.localized("en_us"),
                        book.localized("zh_cn"),
                        book.localized("de_de"))) {
            assertTrue(copy.singlePage);
            assertFalse(copy.allowPageTurning);
            assertFalse(copy.lockedIcons);
            assertFalse(copy.followGuiScale);
            copy.singlePage = false;
            assertTrue(book.singlePage);
        }
        book.setDefaultLanguage("zh_cn");
        assertTrue(book.singlePage);
        assertFalse(book.allowPageTurning);
        assertFalse(book.lockedIcons);
        assertFalse(book.followGuiScale);
    }

    @Test
    void readerSettingsAreSharedMetadataWithIndependentUndoSteps() {
        var session = new BookSession(Book.empty(StandardSchema.create()));
        session.addLanguage("zh_cn");
        var document = session.document();
        session.draft("<p>Pending source</p>");
        session.setSinglePage(true);
        session.setAllowPageTurning(false);
        session.setLockedIcons(false);
        assertSame(document, session.document());
        assertTrue(session.hasDraft());
        assertTrue(session.book().singlePage);
        assertFalse(session.book().allowPageTurning);
        assertFalse(session.book().lockedIcons);
        long revision = session.revision();
        session.setSinglePage(true);
        session.setAllowPageTurning(false);
        session.setLockedIcons(false);
        assertEquals(revision, session.revision());
        session.switchLanguage("en_us");
        assertTrue(session.book().singlePage);
        assertFalse(session.book().allowPageTurning);
        assertFalse(session.book().lockedIcons);
        // Switching language applies the source draft as its own history entry.
        session.undo();
        session.undo();
        assertTrue(session.book().lockedIcons);
        assertFalse(session.book().allowPageTurning);
        session.undo();
        assertTrue(session.book().allowPageTurning);
        assertTrue(session.book().singlePage);
        session.undo();
        assertFalse(session.book().singlePage);
        session.redo();
        session.redo();
        session.redo();
        assertTrue(session.book().singlePage);
        assertFalse(session.book().allowPageTurning);
        assertFalse(session.book().lockedIcons);
        assertTrue(session.hasDraft());
    }
}
