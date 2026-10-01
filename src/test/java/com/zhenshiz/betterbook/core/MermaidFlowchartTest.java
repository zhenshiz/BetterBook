package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MermaidFlowchartTest {
    @Test
    void referenceDiagramPreservesChineseLabelsAndBranches() {
        var graph = MermaidFlowchart.parse(MermaidNode.EXAMPLE);
        assertEquals(MermaidFlowchart.Direction.TD, graph.direction());
        assertEquals(
                List.of("开始", "判断", "执行", "结束"),
                graph.nodes().stream().map(MermaidFlowchart.Node::label).toList());
        assertEquals(MermaidFlowchart.Shape.DIAMOND, graph.nodes().get(1).shape());
        assertEquals(
                List.of("", "是", "否"),
                graph.edges().stream().map(MermaidFlowchart.Edge::label).toList());
        assertEquals("B", graph.edges().get(2).from());
        assertEquals("D", graph.edges().get(2).to());
    }

    @Test
    void directionsChainedEdgesQuotedLabelsCommentsAndNodeShapes() {
        for (String direction : List.of("TD", "TB", "BT", "LR", "RL")) {
            var graph =
                    MermaidFlowchart.parse(
                            "%% intro\nflowchart "
                                    + direction
                                    + "; A[\"a ] <br/> b; %% c\"] -->|\"是\"| B((圆)) -.-> C([结束]);\n"
                                    + " B ==> D{{六角}}\n"
                                    + " E[[调用]] --- F[(数据)]\n"
                                    + " G(圆角)\n"
                                    + " A");
            assertEquals(7, graph.nodes().size());
            assertEquals(4, graph.edges().size());
            assertEquals("a ] \n b; %% c", graph.nodes().getFirst().label());
            assertEquals(MermaidFlowchart.Line.DOTTED, graph.edges().get(1).line());
            assertEquals(MermaidFlowchart.Line.THICK, graph.edges().get(2).line());
            assertFalse(graph.edges().getLast().arrow());
            assertEquals(MermaidFlowchart.Shape.DATABASE, graph.nodes().get(5).shape());
        }
        var graph = MermaidFlowchart.parse("graph TD\nA-->B-->C\nA <--> C\nB -- no --> A");
        assertEquals(4, graph.edges().size());
        assertTrue(graph.edges().get(2).startArrow());
    }

    @Test
    void invalidOrUnsupportedSyntaxReportsAnErrorInsteadOfInventingAGraph() {
        for (String source :
                List.of(
                        "",
                        "sequenceDiagram\nA->>B: hi",
                        "graph TD",
                        "graph TD\nA[",
                        "graph TD\nA-->B[\"oops]",
                        "graph TD\nA -> B",
                        "graph TD\nsubgraph title\nA",
                        "graph TD\nclick A \"https://example.com\""))
            assertThrows(
                    IllegalArgumentException.class, () -> MermaidFlowchart.parse(source), source);
        String tooManyNodes =
                "graph TD\n"
                        + String.join(
                                "\n",
                                java.util.stream.IntStream.range(0, MermaidFlowchart.MAX_NODES + 1)
                                        .mapToObj(i -> "n" + i)
                                        .toList());
        assertThrows(IllegalArgumentException.class, () -> MermaidFlowchart.parse(tooManyNodes));
        String tooManyEdges = "graph TD\n" + "A --> B\n".repeat(MermaidFlowchart.MAX_EDGES + 1);
        assertThrows(IllegalArgumentException.class, () -> MermaidFlowchart.parse(tooManyEdges));
    }

    @Test
    void layoutHandlesBranchesCyclesDisconnectedNodesAndAllDirections() {
        for (String direction : List.of("TD", "BT", "LR", "RL")) {
            var diagram =
                    MermaidLayout.build(
                            MermaidFlowchart.parse(MermaidNode.EXAMPLE.replace("TD", direction)),
                            s -> s.length() * 6);
            var start = diagram.box("A");
            var decision = diagram.box("B");
            if (direction.equals("TD")) assertTrue(start.centerY() < decision.centerY());
            if (direction.equals("BT")) assertTrue(start.centerY() > decision.centerY());
            if (direction.equals("LR")) assertTrue(start.centerX() < decision.centerX());
            if (direction.equals("RL")) assertTrue(start.centerX() > decision.centerX());
            assertWithinBounds(diagram);
            assertFalse(overlap(diagram.box("C"), diagram.box("D")));
        }
        var graph = MermaidFlowchart.parse("graph TD\nA-->B-->C-->A\nC-->D\nE((alone))\nD-->D");
        var diagram = MermaidLayout.build(graph, s -> s.length() * 6);
        assertEquals(5, diagram.boxes().size());
        assertWithinBounds(diagram);
        for (int i = 0; i < diagram.boxes().size(); i++)
            for (int j = i + 1; j < diagram.boxes().size(); j++)
                assertFalse(overlap(diagram.boxes().get(i), diagram.boxes().get(j)));
    }

    private static void assertWithinBounds(MermaidLayout.Diagram diagram) {
        assertTrue(Float.isFinite(diagram.width()));
        assertTrue(Float.isFinite(diagram.height()));
        for (var box : diagram.boxes()) {
            assertTrue(box.x() >= 0 && box.y() >= 0);
            assertTrue(box.x() + box.width() <= diagram.width());
            assertTrue(box.y() + box.height() <= diagram.height());
        }
    }

    private static boolean overlap(MermaidLayout.Box a, MermaidLayout.Box b) {
        return a.x() < b.x() + b.width()
                && a.x() + a.width() > b.x()
                && a.y() < b.y() + b.height()
                && a.y() + a.height() > b.y();
    }
}
