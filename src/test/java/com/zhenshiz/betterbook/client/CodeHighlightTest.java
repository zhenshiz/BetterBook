package com.zhenshiz.betterbook.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CodeHighlightTest {
    @Test
    void knownLanguagesAndAliasesHaveSyntaxColors() {
        for (String[] sample :
                new String[][] {
                    {"java", "public class Book { int value = 1; }"},
                    {"js", "const value = 1;"},
                    {"ts", "interface Book { value: number; }"},
                    {"py", "def book(): return 1"},
                    {"kt", "val book = 1"},
                    {"cpp", "int main() { return 0; }"},
                    {"lua", "local book = 1"},
                    {"json", "{\"book\": true}"},
                    {"yaml", "book: true"},
                    {"rust", "fn main() {}"}
                }) {
            var colors = CodeHighlight.colors(sample[1], sample[0]);
            assertTrue(
                    java.util.Arrays.stream(colors).anyMatch(c -> c != CodeHighlight.TEXT),
                    sample[0]);
        }
    }

    @Test
    void multilineCommentsAndUnknownLanguagesRemainReadable() {
        var colors = CodeHighlight.colors("/* start\ncomment */\nint value;", "java");
        assertEquals(colors[0], colors[9]);
        assertNotEquals(colors[9], colors[20]);
        assertTrue(
                java.util.Arrays.stream(
                                CodeHighlight.colors("plain 😀 code", "uninstalled-language"))
                        .allMatch(c -> c == CodeHighlight.TEXT));
    }

    @Test
    void paperPaletteKeepsSyntaxReadable() {
        for (String[] sample :
                new String[][] {
                    {
                        "java",
                        "// comment\n"
                            + "@Deprecated public class Book { String name = \"book\"; int n = 2; }"
                    },
                    {"js", "const enabled = true; console.log(\"book\", 2 + 3);"},
                    {"html", "<!-- note --><span class=\"book\">Text</span>"},
                    {"unknown", "plain 中文 😀"}
                }) {
            var colors = CodeHighlight.colors(sample[1], sample[0], true);
            assertEquals(sample[1].length(), colors.length);
            for (int color : colors) {
                double contrast = (luminance(0xf5ecd6) + .05) / (luminance(color) + .05);
                assertTrue(
                        contrast >= 4.5, sample[0] + " contrast for " + Integer.toHexString(color));
            }
            if (!sample[0].equals("unknown"))
                assertTrue(java.util.Arrays.stream(colors).distinct().count() >= 3, sample[0]);
        }
    }

    private static double luminance(int color) {
        double[] channels = {
            (color >> 16 & 255) / 255d, (color >> 8 & 255) / 255d, (color & 255) / 255d
        };
        for (int i = 0; i < channels.length; i++) {
            double c = channels[i];
            channels[i] = c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
        }
        return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
    }
}
