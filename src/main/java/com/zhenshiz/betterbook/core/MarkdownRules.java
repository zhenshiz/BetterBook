package com.zhenshiz.betterbook.core;

import org.jsoup.nodes.Element;

import java.util.*;
import java.util.regex.Pattern;

/** 光标处的 Markdown 输入转换；转换单独形成一个撤销事务。 */
public final class MarkdownRules {
    private record Inline(Pattern pattern, String mark) {}

    private static final List<Inline> INLINE =
            List.of(
                    new Inline(Pattern.compile("(?<![\\\\*])\\*\\*([^*]+)\\*\\*$"), "bold"),
                    new Inline(Pattern.compile("(?<![\\\\~])~~([^~]+)~~$"), "strike"),
                    new Inline(Pattern.compile("(?<![\\\\|])\\|\\|([^|]+)\\|\\|$"), "hidden"),
                    new Inline(Pattern.compile("(?<![\\\\*])\\*([^*]+)\\*$"), "italic"),
                    new Inline(Pattern.compile("(?<![\\\\^])\\^([^^]+)\\^$"), "superscript"),
                    new Inline(Pattern.compile("(?<![\\\\~])~([^~]+)~$"), "subscript"),
                    new Inline(Pattern.compile("(?<![\\\\`])`([^`]+)`$"), "code"));

    private MarkdownRules() {}

    public static boolean apply(BookSession s) {
        if (!s.selection().empty()) return false;
        var pos = s.selection().head();
        var b = s.document().blocks().get(pos.block());
        if (b.atom()
                || b.code()
                || s.marksAtCursor().stream().anyMatch(m -> m.id().equals("betterbook:code")))
            return false;
        String text = b.text().substring(0, pos.offset());
        // While a fence is open, its body is literal text, even before conversion.
        var blocks = s.document().blocks();
        for (int i = pos.block() - 1; i >= 0; i--) {
            if (blocks.get(i).element().parent() != b.element().parent()) break;
            if (blocks.get(i).text().startsWith("```")) return multiline(s);
        }
        var image = Pattern.compile("(?<!\\\\)!\\[([^]]*)]\\(([^)]+)\\)$").matcher(text);
        if (image.find()) {
            String html =
                    new Element("img")
                            .attr("src", image.group(2))
                            .attr("alt", image.group(1))
                            .outerHtml();
            int start = image.start();
            s.transact(
                    () -> {
                        s.select(
                                new BookSession.Selection(
                                        new BookSession.Position(pos.block(), start), pos));
                        s.insert("");
                        s.insertHtml(html);
                    });
            return true;
        }
        var link = Pattern.compile("(?<![\\\\!])\\[([^]]+)]\\(([^)]+)\\)$").matcher(text);
        if (link.find()) {
            replaceMark(s, pos, link.start(), link.group(1), "link", Map.of("href", link.group(2)));
            return true;
        }
        for (var rule : INLINE) {
            var m = rule.pattern.matcher(text);
            if (m.find()) {
                replaceMark(s, pos, m.start(), m.group(1), rule.mark, Map.of());
                return true;
            }
        }
        if (text.matches("#{1,4} ")) {
            int level = text.length() - 1;
            s.transact(
                    () -> {
                        clearPrefix(s, pos);
                        s.blockTag("h" + level);
                    });
            return true;
        }
        if (text.matches("(?:[-+*]|\\d+\\.) ")) {
            boolean ordered = Character.isDigit(text.charAt(0));
            s.transact(
                    () -> {
                        clearPrefix(s, pos);
                        s.list(ordered ? "ol" : "ul", false);
                    });
            return true;
        }
        if (text.matches("(?:- )?\\[[ xX]] ")) {
            boolean checked = text.contains("x") || text.contains("X");
            s.transact(
                    () -> {
                        clearPrefix(s, pos);
                        var current =
                                s.document().blocks().get(s.selection().head().block()).element();
                        var li = BookSession.ancestor(current, "li");
                        if (li == null) {
                            s.list("ul", true);
                            li =
                                    BookSession.ancestor(
                                            s.document()
                                                    .blocks()
                                                    .get(s.selection().head().block())
                                                    .element(),
                                            "li");
                        }
                        li.attr("data-type", "taskItem")
                                .attr("data-checked", String.valueOf(checked));
                        li.parent().attr("data-type", "taskList");
                        s.document().invalidate();
                    });
            return true;
        }
        if (text.equals("> ")) {
            s.transact(
                    () -> {
                        clearPrefix(s, pos);
                        s.editElement(
                                e -> {
                                    var quote = new Element("blockquote");
                                    e.before(quote);
                                    quote.appendChild(e);
                                });
                    });
            return true;
        }
        if (text.equals("---")) {
            s.transact(
                    () -> {
                        clearPrefix(s, pos);
                        s.insertHtml("<hr><p></p>");
                    });
            return true;
        }
        return multiline(s);
    }

    private static void clearPrefix(BookSession s, BookSession.Position end) {
        s.select(new BookSession.Selection(new BookSession.Position(end.block(), 0), end));
        s.insert("");
    }

    private static void replaceMark(
            BookSession s,
            BookSession.Position end,
            int start,
            String value,
            String mark,
            Map<String, String> attrs) {
        s.transact(
                () -> {
                    s.select(
                            new BookSession.Selection(
                                    new BookSession.Position(end.block(), start), end));
                    s.insert(value);
                    s.select(
                            new BookSession.Selection(
                                    new BookSession.Position(end.block(), start),
                                    new BookSession.Position(end.block(), start + value.length())));
                    s.toggleMark("betterbook:" + mark, attrs);
                    s.select(BookSession.Selection.at(end.block(), start + value.length()));
                });
    }

    public static boolean multiline(BookSession s) {
        var blocks = s.document().blocks();
        int end = s.selection().head().block();
        for (int start = end; start >= Math.max(0, end - 100); start--) {
            var first = blocks.get(start);
            if (!first.element().is("p") || first.atom()) continue;
            String opening = first.text();
            String closing = blocks.get(end).text();
            if (start < end && opening.startsWith("```") && closing.equals("```")) {
                String code =
                        String.join(
                                "\n",
                                blocks.subList(start + 1, end).stream()
                                        .map(RichDocument.Block::text)
                                        .toList());
                var pre = new Element("pre").attr("language", opening.substring(3).trim());
                pre.appendElement("code")
                        .attr("class", "language-" + opening.substring(3).trim())
                        .text(code);
                replaceBlocks(s, start, end, pre.outerHtml());
                return true;
            }
            if (start < end
                    && opening.matches("::: (info|warning|important)( .*)?")
                    && closing.equals(":::")) {
                String[] args = opening.split(" ", 3);
                String type = args[1];
                var el =
                        new Element("div")
                                .attr("data-type", "admonition")
                                .attr("type", type)
                                .attr("data-admo-type", type);
                el.appendElement("div")
                        .attr("data-type", "admonition-title")
                        .text(args.length > 2 ? args[2] : type.toUpperCase(Locale.ROOT));
                var content = el.appendElement("div").attr("data-type", "admonition-content");
                for (int i = start + 1; i < end; i++)
                    content.appendChild(blocks.get(i).element().clone());
                if (content.children().isEmpty()) content.appendElement("p");
                replaceBlocks(s, start, end, el.outerHtml());
                return true;
            }
            if (start + 2 == end
                    && opening.startsWith("|")
                    && blocks.get(start + 1).text().matches("\\|[ :|\\-]+\\|")
                    && closing.length() > 2
                    && closing.startsWith("|")
                    && closing.endsWith("|")) {
                var headers =
                        opening.substring(
                                        1,
                                        opening.endsWith("|")
                                                ? opening.length() - 1
                                                : opening.length())
                                .split("\\|", -1);
                var cells = closing.substring(1, closing.length() - 1).split("\\|", -1);
                if (headers.length != cells.length) continue;
                var table =
                        new Element("table")
                                .attr("data-type", "custom-table")
                                .attr("data-with-header-row", "true");
                var tbody = table.appendElement("tbody");
                var header = tbody.appendElement("tr");
                var row = tbody.appendElement("tr");
                for (int i = 0; i < headers.length; i++) {
                    header.appendElement("th").text(headers[i].trim());
                    row.appendElement("td").text(cells[i].trim());
                }
                replaceBlocks(s, start, end, table.outerHtml());
                return true;
            }
        }
        return false;
    }

    private static void replaceBlocks(BookSession s, int start, int end, String html) {
        s.transact(
                () -> {
                    var blocks = s.document().blocks();
                    var el = blocks.get(start).element();
                    var replacement = RichDocument.parse(html, s.document().schema());
                    el.before(replacement.body().child(0).clone());
                    for (int i = end; i >= start; i--) blocks.get(i).element().remove();
                    s.document().invalidate();
                    s.select(BookSession.Selection.at(start, 0));
                });
    }
}
