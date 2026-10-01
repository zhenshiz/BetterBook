package com.zhenshiz.betterbook.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MermaidDiagramsTest {
    private static int measure(String text) {
        return text.codePointCount(0, text.length()) * 6;
    }

    @Test
    void allNineTemplatesBuildAndExposeTheirExpectedContent() {
        assertEquals(9, MermaidTemplates.ALL.size());
        for (var template : MermaidTemplates.ALL) {
            if (template.id().equals("flowchart")) {
                assertEquals(4, MermaidFlowchart.parse(template.source()).nodes().size());
                continue;
            }
            var scene = MermaidDiagrams.parse(template.source(), MermaidDiagramsTest::measure);
            assertFalse(scene.items().isEmpty(), template.id());
            assertTrue(scene.width() > 0 && Float.isFinite(scene.width()), template.id());
            assertTrue(scene.height() > 0 && Float.isFinite(scene.height()), template.id());
            for (var item : scene.items()) {
                if (item instanceof MermaidScene.Box box)
                    assertTrue(box.width() > 0 && box.height() > 0, template.id());
                if (item instanceof MermaidScene.Stroke line) {
                    assertTrue(line.points().size() >= 2);
                    for (var point : line.points())
                        assertTrue(
                                Float.isFinite(point.x()) && Float.isFinite(point.y()),
                                template.id());
                }
            }
        }
    }

    @Test
    void sequenceSupportsImplicitParticipantsNotesFramesAndActivation() {
        var source =
                "sequenceDiagram\n"
                        + "participant A as Alice\n"
                        + "actor B as Bob\n"
                        + "autonumber\n"
                        + "A->>+B: Request\n"
                        + "loop retries\n"
                        + "B-->>A: Response\n"
                        + "end\n"
                        + "Note right of A: note<br/>second line\n"
                        + "B--xA: cancelled\n"
                        + "deactivate B\n"
                        + "A->>A: self";
        var scene = MermaidDiagrams.parse(source, MermaidDiagramsTest::measure);
        assertTrue(texts(scene).contains("1. Request"));
        assertTrue(texts(scene).contains("loop retries"));
        assertTrue(texts(scene).contains("second line"));
        assertTrue(
                scene.items().stream()
                        .anyMatch(
                                i ->
                                        i instanceof MermaidScene.Stroke stroke
                                                && stroke.end() == MermaidScene.Marker.CROSS));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MermaidDiagrams.parse(
                                "sequenceDiagram\nA->>B: hi\nalt ok",
                                MermaidDiagramsTest::measure));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MermaidDiagrams.parse(
                                "sequenceDiagram\nA->>B: hi\ndeactivate B",
                                MermaidDiagramsTest::measure));
    }

    @Test
    void ganttAfterDependencyDatesDurationsAndMilestoneProduceOrderedBars() {
        var scene =
                MermaidDiagrams.parse(
                        "gantt\n"
                                + "dateFormat YYYY-MM-DD\n"
                                + "section build\n"
                                + "One :done, a, 2026-10-01, 2d\n"
                                + "Two :crit, b, after a, 3d\n"
                                + "Release :milestone, c, after b, 0d",
                        MermaidDiagramsTest::measure);
        var bars =
                scene.items().stream()
                        .filter(i -> i instanceof MermaidScene.Box box && box.height() == 18)
                        .map(i -> (MermaidScene.Box) i)
                        .toList();
        assertEquals(2, bars.size());
        assertEquals(bars.getFirst().x() + bars.getFirst().width(), bars.getLast().x(), .01);
        assertEquals(1.5, bars.getLast().width() / bars.getFirst().width(), .01);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MermaidDiagrams.parse(
                                "gantt\na :a, after b, 1d\nb :b, after a, 1d",
                                MermaidDiagramsTest::measure));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MermaidDiagrams.parse(
                                "gantt\na :a, after missing, 1d", MermaidDiagramsTest::measure));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MermaidDiagrams.parse(
                                "gantt\na :2026-10-02, 2026-10-01", MermaidDiagramsTest::measure));
    }

    @Test
    void relationshipTypesRetainMembersLabelsAndDifferentMarkers() {
        var classes =
                MermaidDiagrams.parse(
                        "classDiagram\n"
                                + "class A {\n"
                                + "+String field\n"
                                + "+method()\n"
                                + "}\n"
                                + "class B\n"
                                + "A <|-- B : inherits",
                        MermaidDiagramsTest::measure);
        assertTrue(texts(classes).contains("+String field"));
        assertTrue(texts(classes).contains("inherits"));
        assertTrue(
                classes.items().stream()
                        .anyMatch(
                                i ->
                                        i instanceof MermaidScene.Stroke stroke
                                                && stroke.start() == MermaidScene.Marker.TRIANGLE));
        var er =
                MermaidDiagrams.parse(
                        "erDiagram\nA ||--o{ B : contains", MermaidDiagramsTest::measure);
        assertTrue(
                er.items().stream()
                        .anyMatch(
                                i ->
                                        i instanceof MermaidScene.Stroke stroke
                                                && stroke.end()
                                                        == MermaidScene.Marker.OPTIONAL_MANY));
        var state =
                MermaidDiagrams.parse(
                        "stateDiagram-v2\n[*] --> A\nA --> B : run\nB --> [*]",
                        MermaidDiagramsTest::measure);
        assertEquals(
                2,
                state.items().stream()
                        .filter(i -> i instanceof MermaidScene.Box box && box.ellipse())
                        .count());
    }

    @Test
    void pieFractionsSumToFullCircleAndInvalidInputsAreRejected() {
        var scene =
                MermaidDiagrams.parse("pie\n\"a\" : 3\n\"b\" : 1", MermaidDiagramsTest::measure);
        var slices =
                scene.items().stream()
                        .filter(i -> i instanceof MermaidScene.Sector)
                        .map(i -> (MermaidScene.Sector) i)
                        .toList();
        assertEquals(
                Math.PI * 2, slices.stream().mapToDouble(MermaidScene.Sector::angle).sum(), .00001);
        assertEquals(3, slices.getFirst().angle() / slices.getLast().angle(), .00001);
        assertThrows(
                IllegalArgumentException.class,
                () -> MermaidDiagrams.parse("pie\n\"a\" : 0", MermaidDiagramsTest::measure));
        for (String source :
                List.of(
                        "unknownDiagram",
                        "classDiagram\nclass A {",
                        "stateDiagram-v2\nstate A {",
                        "mindmap\nA\nB",
                        "timeline\n: event",
                        "gantt\na :1d",
                        "gantt\na :2026-02-31, 1d",
                        "gantt\ntickInterval 2day\na :2026-10-01, 1d"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> MermaidDiagrams.parse(source, MermaidDiagramsTest::measure),
                    source);
    }

    @Test
    void flowchartGroupsFanOutAndColorClassesPreserveMembership() {
        var graph =
                MermaidFlowchart.parse(
                        "flowchart TD\n"
                                + "subgraph input[输入]\n"
                                + "A[一] & B[二] --> C[三]:::good\n"
                                + "end\n"
                                + "C --> D\n"
                                + "classDef good fill:#efe,stroke:#342,color:#123\n"
                                + "style A fill:#ffe4bb");
        assertEquals(3, graph.edges().size());
        assertEquals(List.of("A", "B", "C"), graph.groups().getFirst().members());
        assertEquals(0xffeeffee, graph.styles().get("C").fill());
        assertEquals(0xff112233, graph.styles().get("C").text());
        assertEquals(0xffffe4bb, graph.styles().get("A").fill());
        var scene = MermaidLayout.build(graph, MermaidDiagramsTest::measure);
        assertTrue(scene.box("A").y() >= 24);
        assertThrows(
                IllegalArgumentException.class,
                () -> MermaidFlowchart.parse("graph TD\nsubgraph a\nA"));
    }

    @Test
    void sequenceDashedBidirectionalAndActivationShortcutsKeepDeclaredParticipants() {
        var scene =
                MermaidDiagrams.parse(
                        "sequenceDiagram\n"
                            + "participant A as Alice\n"
                            + "participant B as Bob\n"
                            + "A->>+B: request\n"
                            + "B-->>-A: response\n"
                            + "A<<-->>B: mutual\n"
                            + "A-->B: plain",
                        MermaidDiagramsTest::measure);
        assertEquals(2, texts(scene).stream().filter("Alice"::equals).count());
        assertEquals(2, texts(scene).stream().filter("Bob"::equals).count());
        assertFalse(texts(scene).contains("B-"));
        var messages =
                scene.items().stream()
                        .filter(
                                i ->
                                        i instanceof MermaidScene.Stroke stroke
                                                && !stroke.points().isEmpty()
                                                && stroke.points().getFirst().y()
                                                        == stroke.points().getLast().y())
                        .map(i -> (MermaidScene.Stroke) i)
                        .toList();
        assertEquals(4, messages.size());
        assertTrue(messages.get(1).dotted());
        assertEquals(MermaidScene.Marker.ARROW, messages.get(2).start());
        assertEquals(MermaidScene.Marker.ARROW, messages.get(2).end());
        assertEquals(MermaidScene.Marker.NONE, messages.get(3).end());
        int bar = -1, message = -1;
        for (int i = 0; i < scene.items().size(); i++) {
            if (scene.items().get(i) instanceof MermaidScene.Box box
                    && box.fill() == MermaidScene.ACCENT) bar = i;
            if (scene.items().get(i) instanceof MermaidScene.Text text
                    && text.value().equals("request")) message = i;
        }
        assertTrue(bar >= 0 && bar < message, "activation bars must not paint over messages");
    }

    @Test
    void stateReturnTransitionsUseDistinctRoutesAndConnectToVisibleStartCircle() {
        var scene =
                MermaidDiagrams.parse(
                        "stateDiagram-v2\n[*] --> A\nA --> B : open\nB --> A : close\nA --> [*]",
                        MermaidDiagramsTest::measure);
        var edges =
                scene.items().stream()
                        .filter(
                                i ->
                                        i instanceof MermaidScene.Stroke stroke
                                                && stroke.end() == MermaidScene.Marker.ARROW)
                        .map(i -> (MermaidScene.Stroke) i)
                        .toList();
        assertEquals(4, edges.size());
        assertNotEquals(edges.get(1).points().get(1).y(), edges.get(2).points().get(1).y());
        var start =
                scene.items().stream()
                        .filter(
                                i ->
                                        i instanceof MermaidScene.Box box
                                                && box.ellipse()
                                                && box.fill() == MermaidScene.INK)
                        .map(i -> (MermaidScene.Box) i)
                        .findFirst()
                        .orElseThrow();
        var tip = edges.getFirst().points().getFirst();
        assertEquals(
                7,
                Math.hypot(
                        tip.x() - start.x() - start.width() / 2,
                        tip.y() - start.y() - start.height() / 2),
                .001);
    }

    private static List<String> texts(MermaidScene scene) {
        return scene.items().stream()
                .filter(i -> i instanceof MermaidScene.Text)
                .map(i -> ((MermaidScene.Text) i).value())
                .toList();
    }
}
