package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

class StageNamesTest {
    @Test
    void normalizesNamesWithoutDependingOnTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("intro/path:part-1.2", StageNames.normalize("  INTRO/Path:Part-1.2  "));
            assertEquals("_", StageNames.normalize("_"));
            assertEquals("1", StageNames.normalize("1"));
            assertEquals("a".repeat(128), StageNames.normalize("A".repeat(128)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsEmptyNamesInvalidFirstCharactersAndOversizedNames() {
        for (String invalid :
                List.of("", " \t\n ", ".intro", "/intro", ":intro", "-intro", "a".repeat(129)))
            assertThrows(
                    IllegalArgumentException.class, () -> StageNames.normalize(invalid), invalid);
        assertThrows(IllegalArgumentException.class, () -> StageNames.normalize(null));
    }

    @Test
    void rejectsSpacesControlCharactersAndCharactersOutsideTheContract() {
        for (String invalid :
                List.of("two words", "line\nbreak", "tab\tname", "a@b", "a\\b", "阶段", "İ"))
            assertThrows(
                    IllegalArgumentException.class, () -> StageNames.normalize(invalid), invalid);
    }
}
