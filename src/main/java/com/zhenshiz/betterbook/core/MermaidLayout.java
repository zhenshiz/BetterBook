package com.zhenshiz.betterbook.core;

import java.util.*;
import java.util.function.ToIntFunction;

/** 分层流程图布局；强连通分量使循环和回边不会导致无限递归。 */
public final class MermaidLayout {
    public record Box(
            MermaidFlowchart.Node node,
            List<String> lines,
            float x,
            float y,
            float width,
            float height) {
        public float centerX() {
            return x + width / 2;
        }

        public float centerY() {
            return y + height / 2;
        }
    }

    public record Diagram(
            MermaidFlowchart.Graph graph, List<Box> boxes, float width, float height) {
        public Box box(String id) {
            return boxes.stream().filter(b -> b.node().id().equals(id)).findFirst().orElseThrow();
        }
    }

    private MermaidLayout() {}

    public static Diagram build(MermaidFlowchart.Graph graph, ToIntFunction<String> measure) {
        var adjacency = new LinkedHashMap<String, List<String>>();
        for (var node : graph.nodes()) adjacency.put(node.id(), new ArrayList<>());
        for (var edge : graph.edges()) adjacency.get(edge.from()).add(edge.to());
        var components = new Components(adjacency);
        for (String id : adjacency.keySet())
            if (!components.index.containsKey(id)) components.visit(id);
        int count = components.count;
        var next = new ArrayList<Set<Integer>>();
        int[] incoming = new int[count], ranks = new int[count];
        for (int i = 0; i < count; i++) next.add(new LinkedHashSet<>());
        for (var edge : graph.edges()) {
            int from = components.component.get(edge.from()),
                    to = components.component.get(edge.to());
            if (from != to && next.get(from).add(to)) incoming[to]++;
        }
        var queue = new ArrayDeque<Integer>();
        for (int i = 0; i < count; i++) if (incoming[i] == 0) queue.add(i);
        while (!queue.isEmpty()) {
            int from = queue.removeFirst();
            for (int to : next.get(from)) {
                ranks[to] = Math.max(ranks[to], ranks[from] + 1);
                if (--incoming[to] == 0) queue.add(to);
            }
        }
        var layers = new TreeMap<Integer, List<MermaidFlowchart.Node>>();
        for (var node : graph.nodes())
            layers.computeIfAbsent(
                            ranks[components.component.get(node.id())], k -> new ArrayList<>())
                    .add(node);
        // 同层按上一层的连接顺序排列，分支按源码顺序稳定展示。
        var positions = new HashMap<String, Integer>();
        for (var layer : layers.values()) {
            layer.sort(
                    Comparator.comparingDouble(
                            n ->
                                    graph.edges().stream()
                                            .filter(
                                                    e ->
                                                            e.to().equals(n.id())
                                                                    && positions.containsKey(
                                                                            e.from()))
                                            .mapToInt(e -> positions.get(e.from()))
                                            .average()
                                            .orElse(0)));
            for (int i = 0; i < layer.size(); i++) positions.put(layer.get(i).id(), i);
        }
        var sizes = new HashMap<String, float[]>();
        var textLines = new HashMap<String, List<String>>();
        for (var node : graph.nodes()) {
            var lines = wrap(node.label(), measure, 126);
            textLines.put(node.id(), lines);
            float width = Math.max(54, lines.stream().mapToInt(measure).max().orElse(0) + 22);
            float height = Math.max(30, lines.size() * 11 + 16);
            if (node.shape() == MermaidFlowchart.Shape.DIAMOND) {
                width += 26;
                height += 22;
            }
            if (node.shape() == MermaidFlowchart.Shape.HEXAGON) width += 16;
            if (node.shape() == MermaidFlowchart.Shape.CIRCLE)
                width = height = Math.max(width, height);
            sizes.put(node.id(), new float[] {width, height});
        }
        boolean horizontal =
                graph.direction() == MermaidFlowchart.Direction.LR
                        || graph.direction() == MermaidFlowchart.Direction.RL;
        float cross = 0;
        for (var layer : layers.values()) {
            float sum = -24;
            for (var node : layer) sum += sizes.get(node.id())[horizontal ? 1 : 0] + 24;
            cross = Math.max(cross, sum);
        }
        var boxes = new ArrayList<Box>();
        int groupDepth =
                graph.nodes().stream()
                        .mapToInt(
                                n ->
                                        (int)
                                                graph.groups().stream()
                                                        .filter(g -> g.members().contains(n.id()))
                                                        .count())
                        .max()
                        .orElse(0);
        float primary = 12 + groupDepth * 24;
        for (var layer : layers.values()) {
            float sum = -24, thickness = 0;
            for (var node : layer) {
                float[] size = sizes.get(node.id());
                sum += size[horizontal ? 1 : 0] + 24;
                thickness = Math.max(thickness, size[horizontal ? 0 : 1]);
            }
            float cursor = 12 + groupDepth * 12 + (cross - sum) / 2;
            for (var node : layer) {
                float[] size = sizes.get(node.id());
                float main = primary + (thickness - size[horizontal ? 0 : 1]) / 2;
                boxes.add(
                        new Box(
                                node,
                                textLines.get(node.id()),
                                horizontal ? main : cursor,
                                horizontal ? cursor : main,
                                size[0],
                                size[1]));
                cursor += size[horizontal ? 1 : 0] + 24;
            }
            primary += thickness + 38;
        }
        primary = primary - 38 + 12;
        float width = horizontal ? primary + groupDepth * 12 : cross + 24 + groupDepth * 24,
                height = horizontal ? cross + 24 + groupDepth * 24 : primary + groupDepth * 12;
        if (graph.direction() == MermaidFlowchart.Direction.BT
                || graph.direction() == MermaidFlowchart.Direction.RL) {
            var reversed = new ArrayList<Box>();
            for (var box : boxes)
                reversed.add(
                        new Box(
                                box.node(),
                                box.lines(),
                                horizontal ? width - box.x() - box.width() : box.x(),
                                horizontal ? box.y() : height - box.y() - box.height(),
                                box.width(),
                                box.height()));
            boxes = reversed;
        }
        return new Diagram(graph, List.copyOf(boxes), width, height);
    }

    private static List<String> wrap(String label, ToIntFunction<String> measure, int width) {
        var lines = new ArrayList<String>();
        for (String paragraph : label.split("\n", -1)) {
            var current = new StringBuilder();
            for (int at = 0; at < paragraph.length(); ) {
                int cp = paragraph.codePointAt(at);
                String piece = new String(Character.toChars(cp));
                if (!current.isEmpty() && measure.applyAsInt(current + piece) > width) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                current.append(piece);
                at += Character.charCount(cp);
            }
            lines.add(current.toString());
        }
        return List.copyOf(lines);
    }

    private static final class Components {
        private final Map<String, List<String>> graph;
        private final Map<String, Integer> index = new HashMap<>(),
                low = new HashMap<>(),
                component = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> active = new HashSet<>();
        private int cursor, count;

        Components(Map<String, List<String>> graph) {
            this.graph = graph;
        }

        void visit(String id) {
            index.put(id, cursor);
            low.put(id, cursor++);
            stack.push(id);
            active.add(id);
            for (String to : graph.get(id)) {
                if (!index.containsKey(to)) {
                    visit(to);
                    low.put(id, Math.min(low.get(id), low.get(to)));
                } else if (active.contains(to)) low.put(id, Math.min(low.get(id), index.get(to)));
            }
            if (low.get(id).equals(index.get(id))) {
                String node;
                do {
                    node = stack.pop();
                    active.remove(node);
                    component.put(node, count);
                } while (!node.equals(id));
                count++;
            }
        }
    }
}
