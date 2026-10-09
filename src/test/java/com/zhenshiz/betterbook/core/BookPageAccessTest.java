package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.List;

class BookPageAccessTest {
    private Book book() {
        var book = new Book();
        var document = RichDocument.parse("<p>Page</p>", StandardSchema.create());
        book.pages.add(new Book.Page("open", "Open", document));
        book.pages.add(new Book.Page("locked", "Locked", document, "Finish intro"));
        book.requiredStages.put("locked", List.of("intro"));
        return book;
    }

    @Test
    void accessChecksOnlyExistingRestrictedPagesAndRefreshesPlayerStageState() {
        var book = book();
        var stages = new HashSet<String>();
        var queried = new ArrayList<String>();
        var access = new BookPageAccess(book, stage -> {
            queried.add(stage);
            return stages.contains(stage);
        });
        assertTrue(access.canRead("open"));
        assertFalse(access.canRead("missing"));
        assertFalse(access.canRead(null));
        assertTrue(queried.isEmpty());
        assertTrue(access.contains("locked"));
        assertFalse(access.canRead("locked"));
        assertEquals(java.util.List.of("intro"), queried);
        stages.add("intro");
        assertTrue(access.canRead("locked"));
        stages.remove("intro");
        assertFalse(access.canRead("locked"));
        book.requiredStages.remove("locked");
        assertTrue(access.canRead("locked"));
    }

    @Test
    void lookupTracksReorderedAndDeletedPagesEvenIfStageMetadataIsStale() {
        var book = book();
        var access = new BookPageAccess(book, stage -> false);
        assertEquals(0, access.index("open"));
        assertEquals(1, access.index("locked"));
        assertEquals(-1, access.index("missing"));
        Collections.swap(book.pages, 0, 1);
        assertEquals(0, access.index("locked"));
        book.removePage(0);
        book.requiredStages.put("locked", List.of("stale"));
        assertFalse(access.contains("locked"));
        assertFalse(access.canRead("locked"));
        assertEquals(-1, access.index("locked"));
        assertEquals(List.of(), access.unlockHint("locked"));
        assertEquals(0, access.index("open"));
    }

    @Test
    void blankPublicStageEntriesAreReadableWithoutCallingTheStagePredicate() {
        var book = book();
        var access = new BookPageAccess(book, stage -> {
            fail("An unrestricted page must not query a stage: " + stage);
            return false;
        });
        for (String stage : new String[] {"", " \t\n", "\u2003", null}) {
            book.requiredStages.put("locked", stage == null ? null : List.of(stage));
            assertTrue(access.canRead("locked"));
            assertFalse(access.canRead("missing"));
        }
    }

    @Test
    void readerHintsAreResolvedInTheSelectedLanguageAndMayBeBlank() {
        var book = book();
        book.translations.put("zh_cn", new Book.Translation("书籍", "", Map.of()));
        var fallback = new BookPageAccess(book.localized("zh_cn"), stage -> false);
        assertEquals(List.of("Finish intro"), fallback.unlockHint("locked"));
        book.putPage(1, "zh_cn", book.page(1, "zh_cn").withUnlockHint("先完成介绍"));
        var translated = new BookPageAccess(book.localized("zh_cn"), stage -> false);
        assertEquals(List.of("先完成介绍"), translated.unlockHint("locked"));
        assertFalse(translated.canRead("locked"));
        assertEquals(List.of(), translated.unlockHint("open"));
        assertEquals(List.of(), translated.unlockHint("missing"));
        assertEquals(List.of("Finish intro"), new BookPageAccess(book, stage -> false).unlockHint("locked"));
    }

    @Test
    void layoutNavigationAndIconSettingsDoNotBypassStageRequirements() {
        var book = book();
        book.singlePage = true;
        book.allowPageTurning = false;
        book.lockedIcons = false;
        var access = new BookPageAccess(book, stage -> false);
        assertTrue(access.canRead("open"));
        assertFalse(access.canRead("locked"));
        assertEquals(1, access.index("locked"));
    }

    @Test
    void allRequiredStagesMustBePresentAndRemovingAnyOneLocksThePageAgain() {
        var book = book();
        book.requiredStages.put("locked", List.of("intro", "entered_end"));
        var stages = new HashSet<String>();
        var access = new BookPageAccess(book, stages::contains);
        assertFalse(access.canRead("locked"));
        stages.add("intro");
        assertFalse(access.canRead("locked"));
        stages.add("entered_end");
        assertTrue(access.canRead("locked"));
        stages.remove("intro");
        assertFalse(access.canRead("locked"));
    }
}
