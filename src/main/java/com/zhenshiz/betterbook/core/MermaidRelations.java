package com.zhenshiz.betterbook.core;

import static com.zhenshiz.betterbook.core.MermaidScene.*;

import java.util.*;
import java.util.function.ToIntFunction;
import java.util.regex.*;

/** 类、状态、ER 图共享关系布局；类成员和实体属性保留在卡片正文。 */
final class MermaidRelations {
    private record Relation(
            String from, String to, String label, boolean dotted, Marker start, Marker end) {}

    static MermaidScene parse(
            List<MermaidDiagrams.SourceLine> lines, ToIntFunction<String> measure) {
        String type = lines.getFirst().text();
        boolean state = type.startsWith("stateDiagram"), er = type.equals("erDiagram");
        var names = new LinkedHashMap<String, String>();
        var fields = new HashMap<String, List<String>>();
        var relations = new ArrayList<Relation>();
        String block = null;
        MermaidFlowchart.Direction direction = MermaidFlowchart.Direction.TD;
        for (int i = 1; i < lines.size(); i++) {
            var line = lines.get(i);
            String t = line.text();
            if (block != null) {
                if (t.equals("}")) {
                    block = null;
                    continue;
                }
                if (t.contains("{") || t.contains("}")) throw line.error("属性/成员块括号必须单独闭合");
                fields.computeIfAbsent(block, k -> new ArrayList<>()).add(label(t));
                continue;
            }
            if (t.startsWith("direction ")) {
                try {
                    direction =
                            MermaidFlowchart.Direction.valueOf(t.substring(10).replace("TB", "TD"));
                } catch (IllegalArgumentException e) {
                    throw line.error("不支持此方向");
                }
                continue;
            }
            if (state) {
                if (t.startsWith("state ")) {
                    var alias =
                            Pattern.compile("state\\s+\"([^\"]+)\"\\s+as\\s+([\\p{L}\\p{N}_-]+)")
                                    .matcher(t);
                    if (alias.matches()) {
                        names.put(alias.group(2), label(alias.group(1)));
                        continue;
                    }
                    var simple = Pattern.compile("state\\s+([\\p{L}\\p{N}_-]+)").matcher(t);
                    if (simple.matches()) {
                        names.putIfAbsent(simple.group(1), simple.group(1));
                        continue;
                    }
                    throw line.error("状态声明支持 state ID 或 state \"名称\" as ID；复合状态尚未实现");
                }
                var edge =
                        Pattern.compile(
                                        "(\\[\\*]|[\\p{L}\\p{N}_-]+)\\s*-->\\s*(\\[\\*]|[\\p{L}\\p{N}_-]+)(?:\\s*:\\s*(.*))?")
                                .matcher(t);
                if (edge.matches()) {
                    String a = edge.group(1).equals("[*]") ? "__start" : "" + edge.group(1),
                            b = edge.group(2).equals("[*]") ? "__end" : "" + edge.group(2);
                    names.putIfAbsent(a, a.startsWith("__") ? "" : a);
                    names.putIfAbsent(b, b.startsWith("__") ? "" : b);
                    relations.add(
                            new Relation(
                                    a,
                                    b,
                                    edge.group(3) == null ? "" : label(edge.group(3)),
                                    false,
                                    Marker.NONE,
                                    Marker.ARROW));
                    continue;
                }
                var description = Pattern.compile("([\\p{L}\\p{N}_-]+)\\s*:\\s*(.*)").matcher(t);
                if (description.matches()) {
                    names.putIfAbsent(description.group(1), description.group(1));
                    fields.computeIfAbsent(description.group(1), k -> new ArrayList<>())
                            .add(label(description.group(2)));
                    continue;
                }
                throw line.error("不支持此状态图声明");
            }
            var declaration =
                    Pattern.compile(
                                    (er ? "" : "class\\s+")
                                            + "([\\p{L}\\p{N}_-]+)(?:\\[\"([^\"]+)\"])?\\s*(\\{)?")
                            .matcher(t);
            if (declaration.matches()) {
                String id = declaration.group(1);
                names.put(id, declaration.group(2) == null ? id : label(declaration.group(2)));
                if (declaration.group(3) != null) block = id;
                continue;
            }
            if (!er) {
                var annotation = Pattern.compile("<<([^>]+)>>\\s+([\\p{L}\\p{N}_-]+)").matcher(t);
                if (annotation.matches()) {
                    names.putIfAbsent(annotation.group(2), annotation.group(2));
                    fields.computeIfAbsent(annotation.group(2), k -> new ArrayList<>())
                            .addFirst("«" + annotation.group(1) + "»");
                    continue;
                }
                var member = Pattern.compile("([\\p{L}\\p{N}_-]+)\\s*:\\s*(.*)").matcher(t);
                if (member.matches()) {
                    names.putIfAbsent(member.group(1), member.group(1));
                    fields.computeIfAbsent(member.group(1), k -> new ArrayList<>())
                            .add(label(member.group(2)));
                    continue;
                }
            }
            String pattern =
                    er
                            ? "([\\p{L}\\p{N}_-]+)\\s*([|o}{]{2})(--|\\.\\.)([|o}{]{2})\\s*([\\p{L}\\p{N}_-]+)\\s*:\\s*(.*)"
                            : "([\\p{L}\\p{N}_-]+)(?:\\s+\"([^\"]*)\")?\\s*(<\\|--|--\\|>|<\\|\\.\\.|\\.\\.\\|>|\\*--|--\\*|o--|--o|<--|-->|<\\.\\.|\\.\\.>|--|\\.\\.)(?:\\s+\"([^\"]*)\")?\\s*([\\p{L}\\p{N}_-]+)(?:\\s*:\\s*(.*))?";
            var edge = MermaidDiagrams.match(pattern, line);
            String a = edge.group(1), b = er ? edge.group(5) : edge.group(5);
            names.putIfAbsent(a, a);
            names.putIfAbsent(b, b);
            if (er)
                relations.add(
                        new Relation(
                                a,
                                b,
                                label(edge.group(6)),
                                edge.group(3).equals(".."),
                                cardinality(edge.group(2)),
                                cardinality(edge.group(4))));
            else {
                String arrow = edge.group(3),
                        text = edge.group(6) == null ? "" : label(edge.group(6));
                if (edge.group(2) != null || edge.group(4) != null)
                    text =
                            (edge.group(2) == null ? "" : edge.group(2))
                                    + "  "
                                    + text
                                    + "  "
                                    + (edge.group(4) == null ? "" : edge.group(4));
                relations.add(
                        new Relation(
                                a,
                                b,
                                text.strip(),
                                arrow.contains(".."),
                                marker(arrow, true),
                                marker(arrow, false)));
            }
        }
        if (block != null) throw new IllegalArgumentException("成员/属性块缺少 }");
        if (names.isEmpty() || names.size() > 96 || relations.size() > 192)
            throw new IllegalArgumentException("关系图需要 1–96 个节点，最多 192 条关系");
        var graphNodes = new ArrayList<MermaidFlowchart.Node>();
        for (var entry : names.entrySet()) {
            String body = String.join("\n", fields.getOrDefault(entry.getKey(), List.of()));
            graphNodes.add(
                    new MermaidFlowchart.Node(
                            entry.getKey(),
                            entry.getValue() + (body.isEmpty() ? "" : "\n" + body),
                            state && entry.getKey().startsWith("__")
                                    ? MermaidFlowchart.Shape.CIRCLE
                                    : state
                                            ? MermaidFlowchart.Shape.ROUND
                                            : MermaidFlowchart.Shape.RECTANGLE));
        }
        var edges =
                relations.stream()
                        .map(
                                e ->
                                        new MermaidFlowchart.Edge(
                                                e.from(),
                                                e.to(),
                                                e.label(),
                                                e.dotted()
                                                        ? MermaidFlowchart.Line.DOTTED
                                                        : MermaidFlowchart.Line.SOLID,
                                                false,
                                                false))
                        .toList();
        var styles = new HashMap<Integer, Marker[]>();
        for (int i = 0; i < relations.size(); i++)
            styles.put(i, new Marker[] {relations.get(i).start(), relations.get(i).end()});
        return graphScene(
                type,
                new MermaidFlowchart.Graph(direction, List.copyOf(graphNodes), edges),
                measure,
                styles);
    }

    private static Marker cardinality(String text) {
        if (text.contains("{") || text.contains("}"))
            return text.contains("o") ? Marker.OPTIONAL_MANY : Marker.MANY;
        return text.contains("o") ? Marker.OPTIONAL_ONE : Marker.ONE;
    }

    private static Marker marker(String arrow, boolean start) {
        String end = start ? arrow : new StringBuilder(arrow).reverse().toString();
        if (end.startsWith("<|") || end.startsWith(">|")) return Marker.TRIANGLE;
        if (end.startsWith("*")) return Marker.DIAMOND;
        if (end.startsWith("o")) return Marker.HOLLOW_DIAMOND;
        if (end.startsWith("<") || end.startsWith(">")) return Marker.ARROW;
        return Marker.NONE;
    }

    static MermaidScene graphScene(
            String type,
            MermaidFlowchart.Graph graph,
            ToIntFunction<String> measure,
            Map<Integer, Marker[]> styles) {
        var layout = MermaidLayout.build(graph, measure);
        var diagram =
                new MermaidLayout.Diagram(
                        graph,
                        layout.boxes().stream()
                                .map(
                                        b ->
                                                new MermaidLayout.Box(
                                                        b.node(),
                                                        b.lines(),
                                                        b.x() + 32,
                                                        b.y() + 32,
                                                        b.width(),
                                                        b.height()))
                                .toList(),
                        layout.width() + 64,
                        layout.height() + 64);
        var out = new Builder(type, measure).size(diagram.width(), diagram.height());
        int index = 0;
        var edgeLabels = new ArrayList<Text>();
        var parallel = new HashMap<String, Integer>();
        for (var edge : graph.edges()) {
            var a = diagram.box(edge.from());
            var b = diagram.box(edge.to());
            Marker[] markers =
                    styles.getOrDefault(index++, new Marker[] {Marker.NONE, Marker.NONE});
            int lane = parallel.merge(edge.from() + "\0" + edge.to(), 1, Integer::sum) - 1;
            List<Point> path = route(a, b, lane);
            out.path(path, edge.line() == MermaidFlowchart.Line.DOTTED, markers[0], markers[1]);
            if (!edge.label().isBlank()) {
                Point left = path.get((path.size() - 1) / 2), right = path.get(path.size() / 2);
                edgeLabels.add(
                        new Text(
                                edge.label(),
                                (left.x() + right.x()) / 2,
                                (left.y() + right.y()) / 2 - 4,
                                true,
                                INK));
            }
        }
        for (var box : diagram.boxes()) {
            if (box.node().id().startsWith("__")) {
                out.ellipse(
                        box.centerX() - 7,
                        box.centerY() - 7,
                        14,
                        14,
                        box.node().id().equals("__start") ? INK : LIGHT);
                continue;
            }
            out.box(box.x(), box.y(), box.width(), box.height(), LIGHT);
            float top = box.centerY() - box.lines().size() * 11f / 2 + 1;
            for (int i = 0; i < box.lines().size(); i++)
                out.text(
                        box.lines().get(i),
                        i == 0 ? box.centerX() : box.x() + 10,
                        top + i * 11 + (i > 0 && !type.equals("mindmap") ? 4 : 0),
                        i == 0);
            if (box.lines().size() > 1 && !type.equals("mindmap"))
                out.line(
                        box.x(),
                        top + 10,
                        box.x() + box.width(),
                        top + 10,
                        false,
                        Marker.NONE,
                        Marker.NONE);
        }
        for (var text : edgeLabels) {
            float w = measure.applyAsInt(text.value());
            out.box(text.x() - w / 2 - 2, text.y() - 2, w + 4, 13, PAPER);
            out.text(text.value(), text.x(), text.y(), true);
        }
        return out.build();
    }

    /** 往返关系分别从上/下（或左/右）绕行，避免箭头和标签完全重叠。 */
    private static List<Point> route(MermaidLayout.Box a, MermaidLayout.Box b, int lane) {
        float dx = b.centerX() - a.centerX(), dy = b.centerY() - a.centerY();
        float gap = 22 + lane * 14;
        if (a == b) {
            return List.of(
                    new Point(a.centerX(), a.y()),
                    new Point(a.centerX(), a.y() - gap),
                    new Point(a.x() + a.width() + gap, a.y() - gap),
                    new Point(a.x() + a.width() + gap, a.centerY()),
                    new Point(a.x() + a.width(), a.centerY()));
        }
        if (Math.abs(dy) < 1) {
            boolean above = dx > 0;
            float y =
                    above
                            ? Math.min(a.y(), b.y()) - gap
                            : Math.max(a.y() + a.height(), b.y() + b.height()) + gap;
            Point start = boundary(a, 0, above ? -1 : 1), end = boundary(b, 0, above ? -1 : 1);
            return List.of(start, new Point(start.x(), y), new Point(end.x(), y), end);
        }
        if (Math.abs(dx) < 1 && lane > 0) {
            float x = Math.max(a.x() + a.width(), b.x() + b.width()) + gap;
            Point start = boundary(a, 1, 0), end = boundary(b, 1, 0);
            return List.of(start, new Point(x, start.y()), new Point(x, end.y()), end);
        }
        return List.of(boundary(a, dx, dy), boundary(b, -dx, -dy));
    }

    private static Point boundary(MermaidLayout.Box box, float dx, float dy) {
        float t =
                box.node().id().startsWith("__")
                        ? 7 / (float) Math.hypot(dx, dy)
                        : 1
                                / Math.max(
                                        Math.abs(dx) / (box.width() / 2),
                                        Math.abs(dy) / (box.height() / 2));
        return new Point(box.centerX() + dx * t, box.centerY() + dy * t);
    }
}
