package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class BookAccessMetadataTest {
    @Test
    void stageAndHintListsSurviveCopyPasteAndUndoWithoutSharingMutableInputs() {
        var stages = new java.util.ArrayList<>(List.of(" INTRO ", "entered_end", "intro", ""));
        var hint = new java.util.ArrayList<>(List.of("完成介绍", "", "前往末地"));
        var session = new BookSession(book());
        session.setPageAccess(stages, hint);
        assertEquals(List.of("intro", "entered_end"), session.book().requiredStages.get("start"));
        assertEquals(List.of("完成介绍", "", "前往末地"), session.page().unlockHint());
        stages.clear();
        hint.clear();
        var clipboard = session.copyPage();
        session.setPageAccess(List.of("other"), List.of("Changed"));
        session.pastePage(clipboard);
        String id = session.page().id();
        assertEquals(List.of("intro", "entered_end"), session.book().requiredStages.get(id));
        assertEquals(List.of("完成介绍", "", "前往末地"), session.page().unlockHint());
        session.undo();
        assertFalse(session.book().requiredStages.containsKey(id));
        session.redo();
        assertEquals(List.of("intro", "entered_end"), session.book().requiredStages.get(id));
        assertThrows(UnsupportedOperationException.class, () -> clipboard.stages().add("extra"));
        assertThrows(UnsupportedOperationException.class, () -> session.page().unlockHint().add("extra"));
    }

    @Test
    void invalidItemInStageListDoesNotPartiallyApplyConditionsOrHints() {
        var session = new BookSession(book());
        session.setPageAccess(List.of("intro"), List.of("Original"));
        long revision = session.revision();
        assertThrows(IllegalArgumentException.class,
                () -> session.setPageAccess(List.of("entered_end", "bad stage"), List.of("Changed")));
        assertEquals(revision, session.revision());
        assertEquals(List.of("intro"), session.book().requiredStages.get("start"));
        assertEquals(List.of("Original"), session.page().unlockHint());
    }

    @Test
    void blankHintListsFallBackAsAWholeAndKeepIntentionalBlankLines() {
        var book = book();
        book.pages.set(0, book.pages.getFirst().withUnlockHint(List.of("First", "", "Third")));
        book.addLanguage("zh_cn");
        book.putPage(0, "zh_cn", book.page(0, "zh_cn").withUnlockHint(List.of("", " ")));
        assertEquals(List.of("First", "", "Third"), book.unlockHint("start", "zh_cn"));
        book.putPage(0, "zh_cn", book.page(0, "zh_cn").withUnlockHint(List.of("第一行", "", "第三行")));
        assertEquals(List.of("第一行", "", "第三行"), book.localized("zh_cn").pages.getFirst().unlockHint());
    }
    private Book book() {
        var book = new Book();
        book.pages.add(
                new Book.Page(
                        "start", "Start", RichDocument.parse("<p>Intro</p>", StandardSchema.create())));
        return book;
    }

    @Test
    void pageHelpersPreserveHintsAndTheOldConstructorSuppliesAnEmptyHint() {
        var page = book().pages.getFirst();
        assertEquals(List.of(), page.unlockHint());
        var hinted = page.withUnlockHint("Finish the introduction");
        assertEquals(page.id(), hinted.id());
        assertEquals(page.title(), hinted.title());
        assertSame(page.document(), hinted.document());
        assertEquals(hinted.unlockHint(), hinted.withTitle("Renamed").unlockHint());
        assertEquals(hinted.unlockHint(), hinted.withDocument(page.document().copy()).unlockHint());
    }

    @Test
    void hintsUseTheirOwnFallbackWhileTranslatedPageContentRemainsEditable() {
        var book = book();
        book.pages.set(0, book.pages.getFirst().withUnlockHint("Finish the introduction"));
        book.addLanguage("zh_cn");
        book.putPage(0, "zh_cn", book.page(0, "zh_cn").withTitle("首页").withUnlockHint(" \t"));
        book.translations.put("fr_fr", new Book.Translation("Livre", "", Map.of()));
        assertEquals(List.of(" \t"), book.page(0, "zh_cn").unlockHint());
        assertEquals("首页", book.page(0, "zh_cn").title());
        for (String locale : List.of("en_us", "zh_cn", "fr_fr", "de_de")) {
            assertEquals(List.of("Finish the introduction"), book.unlockHint("start", locale));
            assertEquals(List.of("Finish the introduction"), book.localized(locale).pages.getFirst().unlockHint());
        }
        assertEquals(List.of(), book.unlockHint("missing", "zh_cn"));
        book.putPage(0, "zh_cn", book.page(0, "zh_cn").withUnlockHint("先完成介绍"));
        assertEquals(List.of("先完成介绍"), book.unlockHint("start", "zh_cn"));
        assertEquals(List.of("Finish the introduction"), book.unlockHint("start", "en_us"));
        book.pages.set(0, book.pages.getFirst().withUnlockHint(""));
        assertEquals(List.of(), book.unlockHint("start", "fr_fr"));
    }

    @Test
    void changingDefaultLanguagePreservesResolvedHintsAndSharedStageMetadata() {
        var book = book();
        book.pages.set(0, book.pages.getFirst().withUnlockHint("English hint"));
        book.addLanguage("zh_cn");
        book.addLanguage("fr_fr");
        book.putPage(0, "zh_cn", book.page(0, "zh_cn").withUnlockHint(""));
        book.putPage(0, "fr_fr", book.page(0, "fr_fr").withUnlockHint("French hint"));
        book.requiredStages.put("start", List.of("intro"));
        book.setDefaultLanguage("fr_fr");
        assertEquals(List.of("French hint"), book.unlockHint("start", "fr_fr"));
        assertEquals(List.of("English hint"), book.unlockHint("start", "zh_cn"));
        assertEquals(List.of("English hint"), book.unlockHint("start", "en_us"));
        assertEquals(List.of("French hint"), book.unlockHint("start", "de_de"));
        assertEquals(Map.of("start", List.of("intro")), book.requiredStages);
        book.setDefaultLanguage("zh_cn");
        assertEquals(List.of("English hint"), book.pages.getFirst().unlockHint());
        assertEquals(List.of("French hint"), book.unlockHint("start", "fr_fr"));
    }

    @Test
    void promotingABlankHintTranslationMaterializesItsPreviousFallbackAndIsUndoable() {
        var session = new BookSession(book());
        session.setPageAccess("intro", "English fallback");
        session.addLanguage("zh_cn");
        session.setPageAccess("intro", " \t");
        assertEquals(List.of(" \t"), session.page().unlockHint());
        assertEquals(List.of("English fallback"), session.book().unlockHint("start", "zh_cn"));
        session.useLanguageAsDefault();
        assertEquals("zh_cn", session.book().defaultLanguage);
        assertEquals(List.of("English fallback"), session.book().pages.getFirst().unlockHint());
        assertEquals(List.of("English fallback"), session.book().unlockHint("start", "en_us"));
        assertEquals(Map.of("start", List.of("intro")), session.book().requiredStages);
        session.undo();
        assertEquals("en_us", session.book().defaultLanguage);
        assertEquals(List.of(" \t"), session.page().unlockHint());
        assertEquals(List.of("English fallback"), session.book().unlockHint("start", "zh_cn"));
        session.redo();
        assertEquals("zh_cn", session.book().defaultLanguage);
        assertEquals(List.of("English fallback"), session.page().unlockHint());
    }

    @Test
    void bookCopiesIsolateStageMapsAndReaderDocuments() {
        var book = book();
        book.requiredStages.put("start", List.of("intro"));
        book.pages.set(0, book.pages.getFirst().withUnlockHint("Hint"));
        for (var copy : List.of(book.copy(), book.localized("en_us"), book.localized("de_de"))) {
            assertEquals(book.requiredStages, copy.requiredStages);
            copy.requiredStages.put("start", List.of("changed"));
            assertEquals(List.of("intro"), book.requiredStages.get("start"));
        }
        var localized = book.localized("en_us");
        localized.pages.getFirst().document().body().text("Reader edit");
        assertEquals("<p>Intro</p>", book.pages.getFirst().document().html());
    }

    @Test
    void pageAccessIsOneMetadataTransactionAndInvalidStageDoesNotChangeHistory() {
        var original = book();
        var session = new BookSession(original);
        var document = session.document();
        session.setPageAccess("  INTRO/First:Part-1  ", "Finish intro");
        assertEquals(List.of("intro/first:part-1"), session.book().requiredStages.get("start"));
        assertEquals(List.of("Finish intro"), session.page().unlockHint());
        assertTrue(original.requiredStages.isEmpty());
        assertEquals(List.of(), original.pages.getFirst().unlockHint());
        assertSame(document, session.document());
        long revision = session.revision();
        var beforeInvalid = session.book();
        assertThrows(
                IllegalArgumentException.class, () -> session.setPageAccess("bad stage", "Changed"));
        assertThrows(
                IllegalArgumentException.class, () -> session.setPageAccess(null, "Changed"));
        assertSame(beforeInvalid, session.book());
        assertEquals(revision, session.revision());
        session.setPageAccess("INTRO/FIRST:PART-1", "Finish intro");
        assertEquals(revision, session.revision());
        session.undo();
        assertTrue(session.book().requiredStages.isEmpty());
        assertEquals(List.of(), session.page().unlockHint());
        assertFalse(session.canUndo());
        session.redo();
        assertEquals(List.of("Finish intro"), session.page().unlockHint());
        assertEquals(List.of("intro/first:part-1"), session.book().requiredStages.get("start"));
        session.setPageAccess(" \t\n", "Still a hint");
        assertTrue(session.book().requiredStages.isEmpty());
        assertEquals(List.of("Still a hint"), session.page().unlockHint());
        session.undo();
        assertEquals(List.of("intro/first:part-1"), session.book().requiredStages.get("start"));
    }

    @Test
    void accessEditsKeepLanguageHintsIndependentAndPreserveSourceDrafts() {
        var session = new BookSession(book());
        session.setPageAccess("intro", "English hint");
        session.addLanguage("zh_cn");
        session.draft("<p>中文草稿</p>");
        var document = session.document();
        session.setPageAccess("CHAPTER/ONE", "先读第一章");
        assertTrue(session.hasDraft());
        assertSame(document, session.document());
        assertEquals(List.of("English hint"), session.book().pages.getFirst().unlockHint());
        assertEquals(List.of("先读第一章"), session.page().unlockHint());
        session.undo();
        assertEquals(List.of("intro"), session.book().requiredStages.get("start"));
        assertEquals(List.of("English hint"), session.page().unlockHint());
        assertTrue(session.hasDraft());
        session.redo();
        session.switchLanguage("en_us");
        assertEquals(List.of("chapter/one"), session.book().requiredStages.get("start"));
        assertEquals(List.of("English hint"), session.page().unlockHint());
    }

    @Test
    void removingARequirementClearsEvenBlankEntriesWrittenDirectlyIntoThePublicMap() {
        for (String stage : new String[] {"", " \t", null}) {
            var book = book();
            book.requiredStages.put("start", stage == null ? null : List.of(stage));
            var session = new BookSession(book);
            session.setPageAccess("", "");
            assertFalse(session.book().requiredStages.containsKey("start"));
            session.undo();
            assertTrue(session.book().requiredStages.containsKey("start"));
            assertEquals(stage == null ? null : List.of(stage), session.book().requiredStages.get("start"));
            session.redo();
            assertFalse(session.book().requiredStages.containsKey("start"));
        }
    }

    @Test
    void failedGroupedTransactionRollsBackSettingsStagesHintsAndSelection() {
        var session = new BookSession(book());
        session.select(BookSession.Selection.at(0, 2));
        session.draft("<p>Pending</p>");
        var before = session.book();
        var selection = session.selection();
        long revision = session.revision();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        session.transact(
                                () -> {
                                    session.setSinglePage(true);
                                    session.setAllowPageTurning(false);
                                    session.setLockedIcons(false);
                                    session.setPageAccess("intro", "New hint");
                                    session.setPageAccess("bad stage", "Invalid");
                                }));
        assertSame(before, session.book());
        assertFalse(session.book().singlePage);
        assertTrue(session.book().allowPageTurning);
        assertTrue(session.book().lockedIcons);
        assertTrue(session.book().requiredStages.isEmpty());
        assertEquals(List.of(), session.page().unlockHint());
        assertEquals(selection, session.selection());
        assertEquals(revision, session.revision());
        assertEquals("<p>Pending</p>", session.source());
        session.undo();
        assertFalse(session.hasDraft());
        assertFalse(session.canUndo());
    }

    @Test
    void stageAndHintsFollowStableIdsThroughDuplicationReorderDeletionAndHistory() {
        var session = new BookSession(book());
        session.setPageAccess("intro", "English hint");
        session.addLanguage("zh_cn");
        session.setPageAccess("intro", "中文提示");
        session.addPage(true);
        String duplicate = session.page().id();
        assertNotEquals("start", duplicate);
        assertEquals(Map.of("start", List.of("intro"), duplicate, List.of("intro")), session.book().requiredStages);
        assertEquals(List.of("中文提示"), session.page().unlockHint());
        assertEquals(List.of("English hint"), session.book().page(1, "en_us").unlockHint());
        session.setPageAccess("second", "Second hint");
        assertEquals(List.of("intro"), session.book().requiredStages.get("start"));
        assertEquals(List.of("中文提示"), session.book().page(0, "zh_cn").unlockHint());
        session.reorderPages(List.of(duplicate, "start"));
        assertEquals(0, session.pageIndex());
        assertEquals(List.of("second"), session.book().requiredStages.get(duplicate));
        session.removePage();
        assertEquals(Map.of("start", List.of("intro")), session.book().requiredStages);
        assertFalse(session.book().translations.get("zh_cn").pages().containsKey(duplicate));
        session.undo();
        assertEquals(duplicate, session.page().id());
        assertEquals(List.of("Second hint"), session.page().unlockHint());
        assertEquals(List.of("second"), session.book().requiredStages.get(duplicate));
        session.redo();
        session.addPage(false);
        assertFalse(session.book().requiredStages.containsKey(session.page().id()));
        assertEquals(List.of(), session.page().unlockHint());
        session.removePage();
        session.removePage();
        assertEquals(Map.of("start", List.of("intro")), session.book().requiredStages);
    }

    @Test
    void clipboardFreezesStageAndHintMetadataWhenPastedIntoADifferentDefaultLanguage() {
        var session = new BookSession(book());
        session.setPageAccess("intro", "English hint");
        session.addLanguage("zh_cn");
        session.setPageAccess("intro", "");
        var copy = session.copyPage();
        assertEquals(List.of("intro"), copy.stages());
        assertEquals(List.of("English hint"), copy.languages().get("zh_cn").unlockHint());
        assertThrows(UnsupportedOperationException.class, () -> copy.languages().clear());
        session.setPageAccess("changed", "Changed after copying");
        session.useLanguageAsDefault();
        session.pastePage(copy);
        String pasted = session.page().id();
        assertEquals(List.of("intro"), session.book().requiredStages.get(pasted));
        assertEquals(List.of("English hint"), session.page().unlockHint());
        assertEquals(List.of("English hint"), session.book().page(1, "en_us").unlockHint());
        session.undo();
        assertFalse(session.book().requiredStages.containsKey(pasted));
        assertEquals(1, session.book().pages.size());
        session.redo();
        assertEquals(List.of("intro"), session.book().requiredStages.get(pasted));
        assertEquals(List.of("English hint"), session.page().unlockHint());
    }

    @Test
    void crossBookPasteUsesTheSourceFallbackWhenTheDestinationHasAnotherDefaultLanguage() {
        var source = new BookSession(book());
        source.setPageAccess("intro", "Source English fallback");
        source.addLanguage("zh_cn");
        source.setPageAccess("intro", "");
        var clipboard = source.copyPage();
        assertEquals(List.of("Source English fallback"), clipboard.languages().get("zh_cn").unlockHint());
        source.switchLanguage("en_us");
        source.setPageAccess("changed", "Changed source hint");

        var targetBook = book();
        targetBook.defaultLanguage = "zh_cn";
        targetBook.pages.set(0, targetBook.pages.getFirst().withUnlockHint("目标默认提示"));
        var target = new BookSession(targetBook);
        target.pastePage(clipboard);
        String pasted = target.page().id();
        assertNotEquals("start", pasted);
        assertEquals(List.of("Source English fallback"), target.page().unlockHint());
        assertEquals(List.of("Source English fallback"), target.book().unlockHint(pasted, "en_us"));
        assertEquals(List.of("Source English fallback"), target.book().localized("zh_cn").pages.get(1).unlockHint());
        assertEquals(List.of("intro"), target.book().requiredStages.get(pasted));
        assertEquals(List.of("目标默认提示"), target.book().pages.getFirst().unlockHint());
        target.undo();
        assertEquals(1, target.book().pages.size());
        assertFalse(target.book().requiredStages.containsKey(pasted));
        target.redo();
        assertEquals(List.of("Source English fallback"), target.page().unlockHint());
        assertEquals(List.of("intro"), target.book().requiredStages.get(pasted));
    }

    @Test
    void legacyClipboardConstructorsPasteWithoutStageOrHint() {
        var session = new BookSession(book());
        session.setPageAccess("intro", "Hint");
        var text = new BookSession.PageText("Copied", "<p>Legacy</p>");
        var copy = new BookSession.PageCopy("en_us", Map.of("en_us", text));
        session.pastePage(copy);
        assertEquals(List.of(), text.unlockHint());
        assertEquals(List.of(), copy.stages());
        assertEquals(List.of(), session.page().unlockHint());
        assertFalse(session.book().requiredStages.containsKey(session.page().id()));
        assertEquals("<p>Legacy</p>", session.document().html());
    }
}
