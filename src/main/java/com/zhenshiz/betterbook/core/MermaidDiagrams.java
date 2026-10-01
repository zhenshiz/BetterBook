package com.zhenshiz.betterbook.core;

import static com.zhenshiz.betterbook.core.MermaidScene.*;

import java.util.*;
import java.util.function.ToIntFunction;
import java.util.regex.*;

/** 其他 Mermaid 图表的离线入口，未实现的声明明确报错，不作为节点吞掉。 */
public final class MermaidDiagrams {
    private MermaidDiagrams() {}

    public static String type(String source) {
        var lines = lines(source);
        if (lines.isEmpty()) throw new IllegalArgumentException("请输入 Mermaid 源码");
        return lines.getFirst().text().split("\\s+", 2)[0];
    }

    public static MermaidScene parse(String source, ToIntFunction<String> measure) {
        var input = lines(source);
        if (input.isEmpty()) throw new IllegalArgumentException("请输入 Mermaid 源码");
        return switch (type(source)) {
            case "sequenceDiagram" -> MermaidSequence.parse(input, measure);
            case "gantt" -> MermaidGantt.parse(input, measure);
            case "classDiagram", "stateDiagram", "stateDiagram-v2", "erDiagram" ->
                    MermaidRelations.parse(input, measure);
            case "pie" -> pie(input, measure);
            case "mindmap" -> mindmap(input, measure);
            case "timeline" -> timeline(input, measure);
            default ->
                    throw new IllegalArgumentException(
                            "不支持此图表类型；可用"
                                + " flowchart、sequenceDiagram、gantt、classDiagram、stateDiagram-v2、erDiagram、pie、mindmap、timeline");
        };
    }

    public record SourceLine(String text, int number, int indent) {
        public IllegalArgumentException error(String message) {
            return new IllegalArgumentException("第 " + number + " 行：" + message);
        }
    }

    public static List<SourceLine> lines(String source) {
        if (source.length() > MermaidNode.MAX_SOURCE)
            throw new IllegalArgumentException("Mermaid 源码过长");
        var output = new ArrayList<SourceLine>();
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            int indent = 0;
            while (indent < raw.length() && Character.isWhitespace(raw.charAt(indent))) indent++;
            String text = raw.strip();
            if (text.isEmpty() || text.startsWith("%%")) continue;
            output.add(new SourceLine(text, i + 1, indent));
        }
        if (output.size() > 384) throw new IllegalArgumentException("图表最多 384 行声明");
        return List.copyOf(output);
    }

    public static Matcher match(String pattern, SourceLine line) {
        var matcher = Pattern.compile(pattern).matcher(line.text());
        if (!matcher.matches()) throw line.error("不支持此声明：" + line.text());
        return matcher;
    }

    private static MermaidScene pie(List<SourceLine> lines, ToIntFunction<String> measure) {
        var out = new Builder("pie", measure);
        String title = "";
        var names = new ArrayList<String>();
        var values = new ArrayList<Double>();
        for (int i = 1; i < lines.size(); i++) {
            var line = lines.get(i);
            if (line.text().startsWith("title ")) {
                title = line.text().substring(6);
                continue;
            }
            var m = match("\"(.*)\"\\s*:\\s*(\\d+(?:\\.\\d+)?)", line);
            double value = Double.parseDouble(m.group(2));
            if (!Double.isFinite(value)) throw line.error("数值必须有限");
            names.add(label(m.group(1)));
            values.add(value);
        }
        if (names.isEmpty() || names.size() > 32)
            throw new IllegalArgumentException("饼图需要 1–32 个数据项");
        double total = values.stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0 || !Double.isFinite(total)) throw new IllegalArgumentException("饼图总数必须大于 0");
        int[] colors = {
            0xffb89963,
            0xffd7be8b,
            0xff8d7756,
            0xffeee0ba,
            0xffad8765,
            0xffc9ac7c,
            0xff9d956e,
            0xffddcf9e
        };
        float legendWidth = names.stream().mapToInt(measure).max().orElse(60) + 100;
        float height = Math.max(220, names.size() * 18 + 50);
        out.size(230 + legendWidth, height);
        out.text(title, 110, 8, true);
        double start = -Math.PI / 2;
        for (int i = 0; i < names.size(); i++) {
            double angle = values.get(i) / total * Math.PI * 2;
            out.sector(110, 124, 88, start, angle, colors[i % colors.length]);
            if (angle > .25)
                out.text(
                        String.format(Locale.ROOT, "%.0f%%", values.get(i) / total * 100),
                        110 + (float) Math.cos(start + angle / 2) * 57,
                        119 + (float) Math.sin(start + angle / 2) * 57,
                        true);
            start += angle;
            out.box(222, 40 + i * 18, 10, 10, colors[i % colors.length]);
            out.text(
                    names.get(i) + "  " + String.format(Locale.ROOT, "%.1f", values.get(i)),
                    238,
                    41 + i * 18,
                    false);
        }
        return out.build();
    }

    private record MindNode(String id, String text, int indent, int parent) {}

    private static MermaidScene mindmap(List<SourceLine> lines, ToIntFunction<String> measure) {
        var nodes = new ArrayList<MindNode>();
        var parents = new ArrayDeque<Integer>();
        for (int i = 1; i < lines.size(); i++) {
            var line = lines.get(i);
            while (!parents.isEmpty() && nodes.get(parents.peek()).indent() >= line.indent())
                parents.pop();
            int parent = parents.isEmpty() ? -1 : parents.peek();
            if (parent < 0 && !nodes.isEmpty()) throw line.error("思维导图只允许一个根节点，子节点请增加缩进");
            String text = line.text();
            var m =
                    Pattern.compile(
                                    "(?:[\\p{L}\\p{N}_-]+)?(?:\\(\\((.*)\\)\\)|\\[(.*)]|\\((.*)\\)|\\{(.*)})")
                            .matcher(text);
            if (m.matches())
                for (int j = 1; j <= 4; j++)
                    if (m.group(j) != null) {
                        text = m.group(j);
                        break;
                    }
            nodes.add(new MindNode("m" + i, label(text), line.indent(), parent));
            parents.push(nodes.size() - 1);
        }
        if (nodes.isEmpty() || nodes.size() > 96)
            throw new IllegalArgumentException("思维导图需要 1–96 个节点");
        var graphNodes =
                nodes.stream()
                        .map(
                                n ->
                                        new MermaidFlowchart.Node(
                                                n.id(), n.text(), MermaidFlowchart.Shape.ROUND))
                        .toList();
        var edges = new ArrayList<MermaidFlowchart.Edge>();
        for (var n : nodes)
            if (n.parent() >= 0)
                edges.add(
                        new MermaidFlowchart.Edge(
                                nodes.get(n.parent()).id(),
                                n.id(),
                                "",
                                MermaidFlowchart.Line.SOLID,
                                false,
                                false));
        return MermaidRelations.graphScene(
                "mindmap",
                new MermaidFlowchart.Graph(MermaidFlowchart.Direction.LR, graphNodes, edges),
                measure,
                Map.of());
    }

    private record Period(String label, String section, List<String> events) {}

    private static MermaidScene timeline(List<SourceLine> lines, ToIntFunction<String> measure) {
        var periods = new ArrayList<Period>();
        String title = "", section = "";
        for (int i = 1; i < lines.size(); i++) {
            var line = lines.get(i);
            String t = line.text();
            if (t.startsWith("title ")) {
                title = t.substring(6);
                continue;
            }
            if (t.startsWith("section ")) {
                section = t.substring(8);
                continue;
            }
            if (t.startsWith(":")) {
                if (periods.isEmpty()) throw line.error("事件前需要时间段");
                periods.getLast().events().add(label(t.substring(1)));
                continue;
            }
            String[] parts = t.split("\\s+:\\s*", -1);
            if (parts.length < 2) throw line.error("时间线使用 时间段 : 事件 格式");
            periods.add(
                    new Period(
                            label(parts[0]),
                            section,
                            new ArrayList<>(
                                    Arrays.stream(parts)
                                            .skip(1)
                                            .map(MermaidScene::label)
                                            .toList())));
        }
        if (periods.isEmpty() || periods.size() > 48)
            throw new IllegalArgumentException("时间线需要 1–48 个时间段");
        var out = new Builder("timeline", measure);
        float x = 12, maxHeight = 0;
        for (var p : periods) {
            float w =
                    Math.max(
                            110,
                            Math.max(
                                            measure.applyAsInt(p.label()),
                                            p.events().stream().mapToInt(measure).max().orElse(0))
                                    + 20);
            float h = 70 + p.events().size() * 26;
            maxHeight = Math.max(maxHeight, h);
            out.box(x, 48, w, 28, SHADE);
            out.text(p.label(), x + w / 2, 57, true);
            if (!p.section().isBlank()) out.text(p.section(), x + w / 2, 32, true);
            out.line(x + w / 2, 76, x + w / 2, h, false, Marker.NONE, Marker.NONE);
            for (int j = 0; j < p.events().size(); j++) {
                out.box(x, 87 + j * 26, w, 22, LIGHT);
                out.text(p.events().get(j), x + w / 2, 94 + j * 26, true);
            }
            x += w + 12;
        }
        out.line(12, 81, x - 12, 81, false, Marker.NONE, Marker.ARROW);
        out.text(title, x / 2, 10, true);
        return out.size(x, maxHeight + 20).build();
    }
}
