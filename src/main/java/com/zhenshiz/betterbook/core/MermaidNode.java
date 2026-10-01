package com.zhenshiz.betterbook.core;

import org.jsoup.nodes.Element;

/** Mermaid 块只保存源码和编辑区展开状态，阅读器不会显示编辑区。 */
public record MermaidNode(String source, boolean sourceVisible) {
    public static final String ID = "betterbook:mermaid";
    public static final String SELECTOR = "div[data-type=mermaid]";
    public static final int MAX_SOURCE = 16384;
    public static final String EXAMPLE =
            "graph TD\n  A[开始] --> B{判断}\n  B -- 是 --> C[执行]\n  B -- 否 --> D[结束]";

    public MermaidNode {
        if (source.length() > MAX_SOURCE)
            throw new IllegalArgumentException(
                    "Mermaid source exceeds " + MAX_SOURCE + " characters");
    }

    public static MermaidNode read(Element element) {
        return new MermaidNode(
                element.hasAttr("data-mermaid")
                        ? element.attr("data-mermaid")
                        : element.wholeText(),
                !element.attr("data-source-visible").equals("false"));
    }

    public Element element() {
        var element = new Element("div").attr("data-type", "mermaid");
        writeTo(element);
        return element;
    }

    public void writeTo(Element element) {
        element.attr("data-mermaid", source)
                .attr("data-source-visible", Boolean.toString(sourceVisible))
                .text(source);
    }

    public static void validate(Element element) {
        read(element);
        if (!element.children().isEmpty())
            throw new IllegalArgumentException("Mermaid source must be plain text");
    }
}
