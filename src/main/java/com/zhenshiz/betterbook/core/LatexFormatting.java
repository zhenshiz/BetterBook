package com.zhenshiz.betterbook.core;

import java.util.List;

/** 源码中的局部格式；菜单预览与插入共用同一段 LaTeX。 */
public final class LatexFormatting {
    private LatexFormatting() {}

    public record Format(String id, String prefix, String suffix, String sample) {
        public String wrap(String text) {
            return prefix + text + suffix;
        }
    }

    private static Format declaration(String command) {
        return new Format(command, "{\\" + command + " ", "}", "abc");
    }

    public static final List<Format> SIZES =
            List.of(
                    declaration("tiny"),
                    declaration("scriptsize"),
                    declaration("small"),
                    declaration("normalsize"),
                    declaration("large"),
                    declaration("Large"),
                    declaration("LARGE"),
                    declaration("huge"),
                    declaration("Huge"));
    public static final List<Format> COLORS =
            List.of(
                            "Blue", "Brown", "Gray", "Green", "Orange", "Peach", "Purple", "Red",
                            "Tan", "Violet", "Yellow")
                    .stream()
                    .map(name -> new Format(name, "{\\color{" + name + "} ", "}", name))
                    .toList();

    public static Format rgb(int color) {
        return new Format(
                "custom",
                "{\\color[RGB]{"
                        + (color >> 16 & 255)
                        + ","
                        + (color >> 8 & 255)
                        + ","
                        + (color & 255)
                        + "} ",
                "}",
                "RGB");
    }
}
