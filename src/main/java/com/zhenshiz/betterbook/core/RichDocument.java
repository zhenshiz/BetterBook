package com.zhenshiz.betterbook.core;

import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;

import java.util.*;

/** HTML 节点树及编辑投影。布局和命令引用同一文本块次序。 */
public final class RichDocument {
    public record Mark(String id, String tag, Map<String, String> attributes) {
        public Mark {
            attributes = Map.copyOf(attributes);
        }
    }

    public record Run(String text, List<Mark> marks, String opaque) {
        public Run {
            marks = List.copyOf(marks);
        }

        public Run(String text, List<Mark> marks) {
            this(text, marks, null);
        }
    }

    public record Block(Element element, List<Run> runs, boolean atom, boolean unknown) {
        public String text() {
            var b = new StringBuilder();
            runs.forEach(r -> b.append(r.text()));
            return b.toString();
        }

        public int length() {
            return runs.stream().mapToInt(r -> r.text().length()).sum();
        }

        public boolean code() {
            return element.normalName().equals("pre");
        }
    }

    private final Document dom;
    private final Schema schema;
    private List<Block> blocks;

    private RichDocument(Document dom, Schema schema) {
        this.dom = dom;
        this.schema = schema;
        dom.outputSettings().prettyPrint(false).charset(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 解析 HTML 片段，保留未知标签。
     *
     * @param html UTF-16 HTML 字符串
     * @param schema 当前会话的节点定义
     * @return 可编辑文档
     * @throws IllegalArgumentException HTML 存在语法错误时抛出
     */
    public static RichDocument parse(String html, Schema schema) {
        var parser = Parser.htmlParser().setTrackErrors(30);
        var dom = parser.parseInput("", "");
        var nodes = parser.parseFragmentInput(html, dom.body(), "");
        if (!parser.getErrors().isEmpty())
            throw new IllegalArgumentException(parser.getErrors().getFirst().toString());
        dom.body().insertChildren(0, nodes);
        var result = new RichDocument(dom, schema);
        result.normalize(dom.body());
        if (result.blocks().isEmpty()) {
            dom.body().appendElement("p");
            result.invalidate();
        }
        result.validate();
        return result;
    }

    public RichDocument copy() {
        return new RichDocument(dom.clone(), schema);
    }

    public Element body() {
        return dom.body();
    }

    public Schema schema() {
        return schema;
    }

    public void invalidate() {
        blocks = null;
    }

    public String html() {
        var copy = dom.clone();
        encodeTree(copy.body());
        return copy.body().html();
    }

    /**
     * 输出按块结构缩进的源码，保留文本块内部的空白与未知节点。
     *
     * @return 使用两空格缩进的 HTML 片段
     */
    public String sourceHtml() {
        var copy = dom.clone();
        encodeTree(copy.body());
        var out = new StringBuilder();
        for (var node : copy.body().childNodes()) formatSource(node, 0, out);
        if (!out.isEmpty() && out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
        return out.toString();
    }

    private void formatSource(Node node, int depth, StringBuilder out) {
        if (node instanceof TextNode text && text.isBlank()) return;
        String indent = "  ".repeat(depth);
        if (node instanceof Element element
                && structuralContent(element)
                && element.childrenSize() > 0) {
            String close = "</" + element.normalName() + ">";
            String empty = element.clone().empty().outerHtml();
            out.append(indent).append(empty, 0, empty.length() - close.length()).append('\n');
            for (var child : element.childNodes()) formatSource(child, depth + 1, out);
            out.append(indent).append(close).append('\n');
        } else out.append(indent).append(node.outerHtml()).append('\n');
    }

    private boolean structuralContent(Element element) {
        var spec = schema.node(element);
        return (element == dom.body() || spec != null && spec.kind() == Schema.Kind.CONTAINER)
                && element.childNodes().stream()
                        .noneMatch(
                                n ->
                                        n instanceof TextNode text && !text.isBlank()
                                                || n instanceof Element child
                                                        && (schema.mark(child) != null
                                                                || child.is("br")));
    }

    private void encodeTree(Element element) {
        var spec = schema.node(element);
        if (spec == null && !element.is("body")) return;
        if (spec == null || spec.kind() == Schema.Kind.CONTAINER)
            for (var child : new ArrayList<>(element.children())) encodeTree(child);
        if (spec != null) spec.encode().accept(element);
        if (element.is("li[data-type=taskItem]")) {
            var children = new ArrayList<>(element.childNodes());
            element.empty();
            var label = element.appendElement("label");
            var input = label.appendElement("input").attr("type", "checkbox");
            if (element.attr("data-checked").equals("true")) input.attr("checked", "checked");
            label.appendElement("span");
            var content = element.appendElement("div");
            children.forEach(content::appendChild);
        }
    }

    private void normalize(Element root) {
        if (structuralContent(root))
            for (var node : new ArrayList<>(root.childNodes()))
                if (node instanceof TextNode text && text.isBlank()) node.remove();
        for (var child : new ArrayList<>(root.children())) {
            var spec = schema.node(child);
            if (spec == null) continue;
            spec.decode().accept(child);
            if (child.is("ul.task-list")) child.attr("data-type", "taskList");
            if (child.is("li") && root.is("ul[data-type=taskList]")) {
                child.attr("data-type", "taskItem");
                if (!child.hasAttr("data-checked"))
                    child.attr(
                            "data-checked",
                            Boolean.toString(child.selectFirst("input[checked]") != null));
                for (var direct : new ArrayList<>(child.children()))
                    if (direct.is("label,input")) direct.remove();
                for (var div : new ArrayList<>(child.children()))
                    if (div.normalName().equals("div") && !div.hasAttr("data-type")) div.unwrap();
            }
            if (child.is("td,th")) {
                for (var paragraph : new ArrayList<>(child.children()))
                    if (paragraph.is("p,div:not([data-type])")) {
                        if (paragraph.nextSibling() != null) paragraph.after(new Element("br"));
                        paragraph.unwrap();
                    }
            }
            if (child.is("pre")) {
                var code = child.selectFirst("code");
                if (!child.hasAttr("language") && code != null)
                    for (String cls : code.classNames())
                        if (cls.startsWith("language-")) child.attr("language", cls.substring(9));
            }
            if (child.is("table")) child.attr("data-type", "custom-table");
            if (child.is("pre") && child.selectFirst("code") == null) {
                String text = child.wholeText();
                child.empty().appendElement("code").text(text);
            }
            if (spec.kind() == Schema.Kind.CONTAINER) normalize(child);
        }
        if (root == dom.body()
                || root.is(
                        "li,blockquote,div[data-type=admonition-content],div:not([data-type])")) {
            Element paragraph = null;
            for (var n : new ArrayList<>(root.childNodes())) {
                boolean inline =
                        n instanceof TextNode
                                || n instanceof Element e && (schema.mark(e) != null || e.is("br"));
                if (inline) {
                    if (n instanceof TextNode t && t.isBlank() && paragraph == null) {
                        n.remove();
                        continue;
                    }
                    if (paragraph == null) {
                        paragraph = new Element("p");
                        n.before(paragraph);
                    }
                    paragraph.appendChild(n);
                } else paragraph = null;
            }
            if (root.childNodeSize() == 0) root.appendElement("p");
        }
    }

    public List<Block> blocks() {
        if (blocks == null) {
            var list = new ArrayList<Block>();
            walk(dom.body(), list);
            blocks = List.copyOf(list);
        }
        return blocks;
    }

    private void walk(Element parent, List<Block> result) {
        for (var el : parent.children()) {
            var spec = schema.node(el);
            if (spec == null || spec.kind() == Schema.Kind.ATOM) {
                result.add(
                        new Block(
                                el,
                                List.of(new Run("\uFFFC", List.of(), el.outerHtml())),
                                true,
                                spec == null));
            } else if (spec.kind() == Schema.Kind.TEXT) {
                var runs = new ArrayList<Run>();
                if (el.is("pre")) runs.add(new Run(el.wholeText(), List.of()));
                else readRuns(el, List.of(), runs);
                result.add(new Block(el, List.copyOf(runs), false, false));
            } else walk(el, result);
        }
    }

    private void readRuns(Element el, List<Mark> marks, List<Run> out) {
        for (var child : el.childNodes()) {
            if (child instanceof TextNode text) out.add(new Run(text.getWholeText(), marks));
            else if (child instanceof Element e) {
                if (e.is("br")) {
                    out.add(new Run("\n", marks));
                    continue;
                }
                var spec = schema.mark(e);
                if (spec == null) {
                    out.add(new Run("\uFFFC", marks, e.outerHtml()));
                    continue;
                }
                var attrs = new LinkedHashMap<String, String>();
                e.attributes().forEach(a -> attrs.put(a.getKey(), a.getValue()));
                attrs.putAll(spec.attributes());
                var next = new ArrayList<>(marks);
                next.add(new Mark(spec.id(), spec.tag(), attrs));
                readRuns(e, next, out);
            }
        }
    }

    public static List<Run> slice(List<Run> runs, int from, int to) {
        var result = new ArrayList<Run>();
        int pos = 0;
        for (var run : runs) {
            int end = pos + run.text().length();
            int a = Math.max(from, pos), b = Math.min(to, end);
            if (a < b)
                result.add(
                        new Run(run.text().substring(a - pos, b - pos), run.marks(), run.opaque()));
            pos = end;
        }
        return result;
    }

    public void setRuns(Block block, List<Run> runs) {
        var el = block.element();
        el.empty();
        Element target = el;
        if (block.code())
            target = el.appendElement("code").attr("class", "language-" + el.attr("language"));
        for (var run : runs) {
            Element p = target;
            if (!block.code())
                for (var m : run.marks()) {
                    p = p.appendElement(m.tag());
                    m.attributes().forEach(p::attr);
                }
            if (run.opaque() != null) p.append(run.opaque());
            else if (block.code()) p.appendChild(new TextNode(run.text()));
            else {
                String[] lines = run.text().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) p.appendElement("br");
                    p.appendChild(new TextNode(lines[i]));
                }
            }
        }
        invalidate();
    }

    private List<Element> knownElements() {
        var result = new ArrayList<Element>();
        collectKnown(body(), result);
        return result;
    }

    private void collectKnown(Element parent, List<Element> result) {
        for (var child : parent.children()) {
            var spec = schema.node(child);
            if (spec == null) continue;
            result.add(child);
            if (spec.kind() == Schema.Kind.CONTAINER) collectKnown(child, result);
        }
    }

    public void validate() {
        var known = knownElements();
        for (var el : known) {
            var spec = schema.node(el);
            if (spec != null) spec.validate().accept(el);
        }
        for (var table : known.stream().filter(e -> e.is("table")).toList()) {
            var rows = table.select("tr");
            int columns = rows.isEmpty() ? 0 : rows.first().childrenSize();
            for (var row : rows)
                if (row.childrenSize() != columns
                        || row.children().stream()
                                .anyMatch(
                                        e ->
                                                !e.is("td,th")
                                                        || e.hasAttr("colspan")
                                                                && !e.attr("colspan").equals("1")
                                                        || e.hasAttr("rowspan")
                                                                && !e.attr("rowspan").equals("1")))
                    throw new IllegalArgumentException(
                            "Tables must be rectangular, without merged cells");
        }
    }

    public void cleanup() {
        var known = knownElements();
        Collections.reverse(known);
        for (var e : known)
            if (e.is(
                            "ul,ol,li,blockquote,div[data-type=admonition-content],div[data-type=step-item]")
                    && e.children().isEmpty()) e.remove();
        invalidate();
        if (blocks().isEmpty()) {
            body().appendElement("p");
            invalidate();
        }
    }
}
