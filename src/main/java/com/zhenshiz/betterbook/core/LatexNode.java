package com.zhenshiz.betterbook.core;

import org.jsoup.nodes.Element;

/** 公式的持久化模型；服务端仅存储源码，不加载客户端排版库。 */
public record LatexNode(String source, int size, String color, String align) {
    public static final String ID = "betterbook:latex";
    public static final String SELECTOR = "div[data-type=latex]";
    public static final int MAX_SOURCE = 4096;

    public LatexNode {
        if (source.length() > MAX_SOURCE)
            throw new IllegalArgumentException(
                    "LaTeX source exceeds " + MAX_SOURCE + " characters");
        size = Math.clamp(size, 8, 32);
        if (!color.matches("#[0-9a-fA-F]{6}")) color = "#393024";
        if (!java.util.Set.of("left", "center", "right").contains(align)) align = "center";
    }

    public static LatexNode read(Element element) {
        int size = 16;
        try {
            size = Integer.parseInt(element.attr("data-size"));
        } catch (NumberFormatException ignored) {
        }
        return new LatexNode(
                element.hasAttr("data-latex") ? element.attr("data-latex") : element.wholeText(),
                size,
                element.attr("data-color"),
                element.attr("data-align"));
    }

    public Element element() {
        var element = new Element("div").attr("data-type", "latex");
        writeTo(element);
        return element;
    }

    public void writeTo(Element element) {
        element.attr("data-latex", source)
                .attr("data-size", Integer.toString(size))
                .attr("data-color", color)
                .attr("data-align", align)
                .text(source);
    }

    public static void validate(Element element) {
        read(element);
        if (!element.children().isEmpty())
            throw new IllegalArgumentException("LaTeX source must be plain text");
    }
}
