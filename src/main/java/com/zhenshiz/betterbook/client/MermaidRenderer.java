package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import org.joml.Vector2f;

import java.util.*;

/** 使用 Minecraft 字体和原生绘制离线显示 Mermaid 流程图，无浏览器和纹理资源。 */
final class MermaidRenderer {
    // 与书页的米黄底、棕色正文和边缘保持一致。
    static final int BACKGROUND = 0xffefe4c7,
            FILL = 0xfff7edd5,
            BORDER = 0xff9f8055,
            TEXT = 0xff362a21,
            EDGE = 0xff79603e,
            FRAME = 0xffc4ad83,
            SOURCE_BACKGROUND = 0xfff5ecd8,
            HEADER = 0xffe3d3af,
            HEADER_HOVER = 0xffdac59c,
            HEADER_PRESSED = 0xffd1bb91,
            ERROR = 0xff9c3f2e;

    record Result(MermaidLayout.Diagram diagram, MermaidScene scene, String error) {
        boolean valid() {
            return diagram != null || scene != null;
        }

        float width() {
            return diagram != null ? diagram.width() : scene.width();
        }

        float height() {
            return diagram != null ? diagram.height() : scene.height();
        }
    }

    private static final Map<String, Result> CACHE =
            new LinkedHashMap<>(32, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Result> entry) {
                    return size() > 64;
                }
            };

    static Result get(String source) {
        return CACHE.computeIfAbsent(
                source,
                value -> {
                    try {
                        var font = Minecraft.getInstance().font;
                        String type = MermaidDiagrams.type(value);
                        if (type.equals("graph") || type.equals("flowchart"))
                            return new Result(
                                    MermaidLayout.build(MermaidFlowchart.parse(value), font::width),
                                    null,
                                    "");
                        return new Result(null, MermaidDiagrams.parse(value, font::width), "");
                    } catch (IllegalArgumentException exception) {
                        return new Result(null, null, exception.getMessage());
                    }
                });
    }

    static float diagramHeight(String source, float width) {
        var result = get(source);
        if (!result.valid()) return 92;
        return Math.clamp(
                result.height() * Math.min(1, Math.max(20, width - 12) / result.width()) + 12,
                92,
                300);
    }

    static float height(org.jsoup.nodes.Element element, float width, boolean editable) {
        var node = MermaidNode.read(element);
        return diagramHeight(node.source(), width)
                + (editable ? 24 + (node.sourceVisible() ? 124 : 0) : 0);
    }

    static void draw(
            GuiGraphics graphics, Result result, float x, float y, float width, float height) {
        if (!result.valid()) {
            var font = Minecraft.getInstance().font;
            var lines =
                    font.split(
                            net.minecraft.network.chat.Component.literal(result.error()),
                            Math.max(20, (int) width - 16));
            int at = 0;
            for (var line : lines) {
                graphics.drawString(font, line, (int) x + 8, (int) y + 8 + at * 11, ERROR, false);
                if (++at >= 6) break;
            }
            return;
        }
        if (result.scene() != null) {
            drawScene(graphics, result.scene(), x, y, width, height);
            return;
        }
        var diagram = result.diagram();
        float scale =
                Math.min(
                        1,
                        Math.min((width - 12) / diagram.width(), (height - 12) / diagram.height()));
        if (scale <= 0) return;
        graphics.pose().pushPose();
        graphics.pose()
                .translate(
                        x + (width - diagram.width() * scale) / 2,
                        y + (height - diagram.height() * scale) / 2,
                        0);
        graphics.pose().scale(scale, scale, 1);
        var sortedGroups = new ArrayList<>(diagram.graph().groups());
        sortedGroups.sort(
                Comparator.comparingInt((MermaidFlowchart.Group group) -> group.members().size())
                        .reversed());
        for (var group : sortedGroups) {
            var boxes =
                    diagram.boxes().stream()
                            .filter(b -> group.members().contains(b.node().id()))
                            .toList();
            if (boxes.isEmpty()) continue;
            long children =
                    diagram.graph().groups().stream()
                            .filter(g -> g != group && group.members().containsAll(g.members()))
                            .count();
            float margin = 8 + children * 8;
            float left =
                    (float) boxes.stream().mapToDouble(MermaidLayout.Box::x).min().orElse(0)
                            - margin;
            float top =
                    (float) boxes.stream().mapToDouble(MermaidLayout.Box::y).min().orElse(0)
                            - 22
                            - children * 18;
            float right =
                    (float) boxes.stream().mapToDouble(b -> b.x() + b.width()).max().orElse(0)
                            + margin;
            float bottom =
                    (float) boxes.stream().mapToDouble(b -> b.y() + b.height()).max().orElse(0)
                            + margin;
            graphics.fill((int) left, (int) top, (int) right, (int) bottom, 0x12a88956);
            line(
                    graphics,
                    List.of(
                            new Vector2f(left, top),
                            new Vector2f(right, top),
                            new Vector2f(right, bottom),
                            new Vector2f(left, bottom),
                            new Vector2f(left, top)),
                    FRAME,
                    1);
            graphics.drawString(
                    Minecraft.getInstance().font,
                    group.title(),
                    (int) left + 5,
                    (int) top + 5,
                    TEXT,
                    false);
            graphics.flush();
        }
        var labels = new ArrayList<Label>();
        for (var edge : diagram.graph().edges()) {
            var from = diagram.box(edge.from());
            var to = diagram.box(edge.to());
            var path = route(from, to, diagram.graph().direction());
            float thickness = edge.line() == MermaidFlowchart.Line.THICK ? 2.3f : 1;
            if (edge.line() == MermaidFlowchart.Line.DOTTED) {
                for (int i = 0; i + 1 < path.size(); i += 3)
                    line(graphics, path.subList(i, Math.min(i + 2, path.size())), EDGE, thickness);
            } else line(graphics, path, EDGE, thickness);
            if (edge.arrow()) arrow(graphics, path.get(path.size() - 2), path.getLast(), EDGE);
            if (edge.startArrow()) arrow(graphics, path.get(1), path.getFirst(), EDGE);
            if (!edge.label().isBlank()) {
                var point = path.get(path.size() / 2);
                labels.add(new Label(edge.label(), point.x, point.y));
            }
        }
        graphics.flush();
        for (var box : diagram.boxes())
            drawNode(graphics, box, diagram.graph().styles().get(box.node().id()));
        graphics.flush();
        var font = Minecraft.getInstance().font;
        for (var box : diagram.boxes()) {
            float top = box.centerY() - box.lines().size() * 11f / 2 + 1;
            for (int i = 0; i < box.lines().size(); i++) {
                String text = box.lines().get(i);
                graphics.drawString(
                        font,
                        text,
                        (int) (box.centerX() - font.width(text) / 2f),
                        (int) (top + i * 11),
                        diagram.graph().styles().containsKey(box.node().id())
                                        && diagram.graph().styles().get(box.node().id()).text()
                                                != null
                                ? diagram.graph().styles().get(box.node().id()).text()
                                : TEXT,
                        false);
            }
        }
        for (var label : labels) {
            int w = font.width(label.text());
            graphics.fill(
                    (int) (label.x() - w / 2f - 2),
                    (int) label.y() - 6,
                    (int) (label.x() + w / 2f + 2),
                    (int) label.y() + 6,
                    BACKGROUND);
            graphics.drawString(
                    font,
                    label.text(),
                    (int) (label.x() - w / 2f),
                    (int) label.y() - 4,
                    TEXT,
                    false);
        }
        graphics.flush();
        graphics.pose().popPose();
    }

    private static void drawScene(
            GuiGraphics graphics, MermaidScene scene, float x, float y, float width, float height) {
        float scale =
                Math.min(1, Math.min((width - 12) / scene.width(), (height - 12) / scene.height()));
        if (scale <= 0) return;
        graphics.pose().pushPose();
        graphics.pose()
                .translate(
                        x + (width - scene.width() * scale) / 2,
                        y + (height - scene.height() * scale) / 2,
                        0);
        graphics.pose().scale(scale, scale, 1);
        for (var item : scene.items()) {
            if (item instanceof MermaidScene.Box box) {
                var points = new ArrayList<Vector2f>();
                if (box.ellipse()) {
                    for (int i = 0; i < 40; i++) {
                        double angle = i * Math.PI * 2 / 40;
                        points.add(
                                new Vector2f(
                                        box.x()
                                                + box.width() / 2
                                                + (float) Math.cos(angle) * box.width() / 2,
                                        box.y()
                                                + box.height() / 2
                                                + (float) Math.sin(angle) * box.height() / 2));
                    }
                } else
                    points.addAll(
                            List.of(
                                    new Vector2f(box.x(), box.y()),
                                    new Vector2f(box.x() + box.width(), box.y()),
                                    new Vector2f(box.x() + box.width(), box.y() + box.height()),
                                    new Vector2f(box.x(), box.y() + box.height())));
                fillPolygon(graphics, points, box.fill());
                points.add(new Vector2f(points.getFirst()));
                line(graphics, points, box.border(), 1);
            } else if (item instanceof MermaidScene.Text text) {
                var font = Minecraft.getInstance().font;
                graphics.drawString(
                        font,
                        text.value(),
                        (int) (text.x() - (text.centered() ? font.width(text.value()) / 2f : 0)),
                        (int) text.y(),
                        text.color(),
                        false);
            } else if (item instanceof MermaidScene.Stroke stroke) {
                var points = stroke.points().stream().map(p -> new Vector2f(p.x(), p.y())).toList();
                if (stroke.dotted()) {
                    for (int i = 0; i + 1 < points.size(); i++) {
                        var a = points.get(i);
                        var b = points.get(i + 1);
                        float length = a.distance(b);
                        for (float step = 0; step < length; step += 7) {
                            line(
                                    graphics,
                                    List.of(
                                            new Vector2f(a).lerp(b, step / length),
                                            new Vector2f(a)
                                                    .lerp(b, Math.min(length, step + 3) / length)),
                                    stroke.color(),
                                    1);
                        }
                    }
                } else line(graphics, points, stroke.color(), 1);
                sceneMarker(
                        graphics, points.get(1), points.getFirst(), stroke.start(), stroke.color());
                sceneMarker(
                        graphics,
                        points.get(points.size() - 2),
                        points.getLast(),
                        stroke.end(),
                        stroke.color());
            } else if (item instanceof MermaidScene.Sector sector) {
                int steps = Math.max(2, (int) Math.ceil(sector.angle() * 32));
                for (int i = 0; i < steps; i++) {
                    double a = sector.from() + sector.angle() * i / steps,
                            b = sector.from() + sector.angle() * (i + 1) / steps;
                    fillPolygon(
                            graphics,
                            List.of(
                                    new Vector2f(sector.cx(), sector.cy()),
                                    new Vector2f(
                                            sector.cx() + (float) Math.cos(a) * sector.radius(),
                                            sector.cy() + (float) Math.sin(a) * sector.radius()),
                                    new Vector2f(
                                            sector.cx() + (float) Math.cos(b) * sector.radius(),
                                            sector.cy() + (float) Math.sin(b) * sector.radius())),
                            sector.color());
                }
            }
            graphics.flush();
        }
        graphics.pose().popPose();
    }

    private static void sceneMarker(
            GuiGraphics graphics,
            Vector2f previous,
            Vector2f tip,
            MermaidScene.Marker marker,
            int color) {
        if (marker == MermaidScene.Marker.NONE) return;
        var dir = new Vector2f(tip).sub(previous);
        if (dir.lengthSquared() < .001f) return;
        dir.normalize();
        var side = new Vector2f(-dir.y, dir.x);
        var base = new Vector2f(tip).sub(new Vector2f(dir).mul(8));
        switch (marker) {
            case ARROW -> arrow(graphics, previous, tip, color);
            case OPEN ->
                    line(
                            graphics,
                            List.of(
                                    new Vector2f(base).add(new Vector2f(side).mul(4)),
                                    tip,
                                    new Vector2f(base).sub(new Vector2f(side).mul(4))),
                            color,
                            1);
            case CROSS -> {
                line(
                        graphics,
                        List.of(
                                new Vector2f(tip)
                                        .add(new Vector2f(side).mul(4))
                                        .sub(new Vector2f(dir).mul(4)),
                                new Vector2f(tip)
                                        .sub(new Vector2f(side).mul(4))
                                        .add(new Vector2f(dir).mul(4))),
                        color,
                        1);
                line(
                        graphics,
                        List.of(
                                new Vector2f(tip)
                                        .sub(new Vector2f(side).mul(4))
                                        .sub(new Vector2f(dir).mul(4)),
                                new Vector2f(tip)
                                        .add(new Vector2f(side).mul(4))
                                        .add(new Vector2f(dir).mul(4))),
                        color,
                        1);
            }
            case TRIANGLE -> {
                var points =
                        List.of(
                                tip,
                                new Vector2f(base).add(new Vector2f(side).mul(5)),
                                new Vector2f(base).sub(new Vector2f(side).mul(5)),
                                tip);
                fillPolygon(graphics, points, MermaidScene.LIGHT);
                line(graphics, points, color, 1);
            }
            case DIAMOND, HOLLOW_DIAMOND -> {
                var center = new Vector2f(tip).sub(new Vector2f(dir).mul(5));
                var points =
                        List.of(
                                tip,
                                new Vector2f(center).add(new Vector2f(side).mul(4)),
                                new Vector2f(tip).sub(new Vector2f(dir).mul(10)),
                                new Vector2f(center).sub(new Vector2f(side).mul(4)),
                                tip);
                fillPolygon(
                        graphics,
                        points,
                        marker == MermaidScene.Marker.DIAMOND ? color : MermaidScene.LIGHT);
                line(graphics, points, color, 1);
            }
            case ONE, OPTIONAL_ONE, MANY, OPTIONAL_MANY -> {
                if (marker == MermaidScene.Marker.MANY
                        || marker == MermaidScene.Marker.OPTIONAL_MANY) {
                    line(
                            graphics,
                            List.of(
                                    new Vector2f(tip).add(new Vector2f(side).mul(5)),
                                    base,
                                    new Vector2f(tip).sub(new Vector2f(side).mul(5))),
                            color,
                            1);
                } else {
                    var at = new Vector2f(tip).sub(new Vector2f(dir).mul(3));
                    line(
                            graphics,
                            List.of(
                                    new Vector2f(at).add(new Vector2f(side).mul(5)),
                                    new Vector2f(at).sub(new Vector2f(side).mul(5))),
                            color,
                            1);
                }
                if (marker == MermaidScene.Marker.OPTIONAL_ONE
                        || marker == MermaidScene.Marker.OPTIONAL_MANY) {
                    var circle = new ArrayList<Vector2f>();
                    var center = new Vector2f(tip).sub(new Vector2f(dir).mul(12));
                    for (int i = 0; i <= 16; i++) {
                        double angle = i * Math.PI * 2 / 16;
                        circle.add(
                                new Vector2f(
                                        center.x + (float) Math.cos(angle) * 3,
                                        center.y + (float) Math.sin(angle) * 3));
                    }
                    line(graphics, circle, color, 1);
                } else {
                    var at = new Vector2f(tip).sub(new Vector2f(dir).mul(12));
                    line(
                            graphics,
                            List.of(
                                    new Vector2f(at).add(new Vector2f(side).mul(5)),
                                    new Vector2f(at).sub(new Vector2f(side).mul(5))),
                            color,
                            1);
                }
            }
            default -> {}
        }
    }

    private record Label(String text, float x, float y) {}

    private static List<Vector2f> route(
            MermaidLayout.Box from, MermaidLayout.Box to, MermaidFlowchart.Direction direction) {
        boolean horizontal =
                direction == MermaidFlowchart.Direction.LR
                        || direction == MermaidFlowchart.Direction.RL;
        float dx = to.centerX() - from.centerX(), dy = to.centerY() - from.centerY();
        Vector2f a, b, c, d;
        if (from == to || horizontal && Math.abs(dx) < 1 || !horizontal && Math.abs(dy) < 1) {
            if (horizontal) {
                a = new Vector2f(from.centerX(), from.y());
                d = new Vector2f(to.centerX(), to.y());
                b = new Vector2f(a.x - 15, Math.min(from.y(), to.y()) - 10);
                c = new Vector2f(d.x + 15, b.y);
            } else {
                a = new Vector2f(from.x() + from.width(), from.centerY());
                d = new Vector2f(to.x() + to.width(), to.centerY() - (from == to ? 8 : 0));
                b = new Vector2f(Math.max(a.x, d.x) + 10, a.y + 20);
                c = new Vector2f(b.x, d.y - 20);
            }
        } else if (horizontal) {
            a = boundary(from, dx, from.node().shape() == MermaidFlowchart.Shape.DIAMOND ? dy : 0);
            d = boundary(to, -dx, 0);
            float middle = (a.x + d.x) / 2;
            b = new Vector2f(middle, a.y);
            c = new Vector2f(middle, d.y);
        } else {
            a = boundary(from, from.node().shape() == MermaidFlowchart.Shape.DIAMOND ? dx : 0, dy);
            d = boundary(to, 0, -dy);
            float middle = (a.y + d.y) / 2;
            b = new Vector2f(a.x, middle);
            c = new Vector2f(d.x, middle);
        }
        var points = new ArrayList<Vector2f>();
        for (int i = 0; i <= 24; i++) {
            float t = i / 24f, u = 1 - t;
            points.add(
                    new Vector2f(
                            u * u * u * a.x
                                    + 3 * u * u * t * b.x
                                    + 3 * u * t * t * c.x
                                    + t * t * t * d.x,
                            u * u * u * a.y
                                    + 3 * u * u * t * b.y
                                    + 3 * u * t * t * c.y
                                    + t * t * t * d.y));
        }
        return points;
    }

    private static Vector2f boundary(MermaidLayout.Box box, float dx, float dy) {
        float x = Math.abs(dx) / (box.width() / 2), y = Math.abs(dy) / (box.height() / 2);
        float divisor =
                box.node().shape() == MermaidFlowchart.Shape.DIAMOND
                        ? x + y
                        : box.node().shape() == MermaidFlowchart.Shape.CIRCLE
                                ? (float) Math.sqrt(x * x + y * y)
                                : Math.max(x, y);
        float t = divisor == 0 ? 0 : 1 / divisor;
        return new Vector2f(box.centerX() + dx * t, box.centerY() + dy * t);
    }

    private static void drawNode(
            GuiGraphics graphics, MermaidLayout.Box box, MermaidFlowchart.NodeStyle style) {
        int fill = style != null && style.fill() != null ? style.fill() : FILL;
        int borderColor = style != null && style.border() != null ? style.border() : BORDER;
        float x = box.x(), y = box.y(), w = box.width(), h = box.height();
        var polygon = new ArrayList<Vector2f>();
        switch (box.node().shape()) {
            case DIAMOND ->
                    polygon.addAll(
                            List.of(
                                    new Vector2f(x + w / 2, y),
                                    new Vector2f(x + w, y + h / 2),
                                    new Vector2f(x + w / 2, y + h),
                                    new Vector2f(x, y + h / 2)));
            case HEXAGON ->
                    polygon.addAll(
                            List.of(
                                    new Vector2f(x + 10, y),
                                    new Vector2f(x + w - 10, y),
                                    new Vector2f(x + w, y + h / 2),
                                    new Vector2f(x + w - 10, y + h),
                                    new Vector2f(x + 10, y + h),
                                    new Vector2f(x, y + h / 2)));
            case CIRCLE -> {
                for (int i = 0; i < 40; i++) {
                    double angle = i * Math.PI * 2 / 40;
                    polygon.add(
                            new Vector2f(
                                    x + w / 2 + (float) Math.cos(angle) * w / 2,
                                    y + h / 2 + (float) Math.sin(angle) * h / 2));
                }
            }
            case ROUND, STADIUM -> {
                float radius =
                        box.node().shape() == MermaidFlowchart.Shape.STADIUM
                                ? Math.min(h / 2, w / 2)
                                : 6;
                float[] cx = {x + w - radius, x + w - radius, x + radius, x + radius},
                        cy = {y + radius, y + h - radius, y + h - radius, y + radius};
                for (int corner = 0; corner < 4; corner++)
                    for (int i = 0; i <= 6; i++) {
                        double angle = (-90 + corner * 90 + i * 15) * Math.PI / 180;
                        polygon.add(
                                new Vector2f(
                                        cx[corner] + (float) Math.cos(angle) * radius,
                                        cy[corner] + (float) Math.sin(angle) * radius));
                    }
            }
            default ->
                    polygon.addAll(
                            List.of(
                                    new Vector2f(x, y),
                                    new Vector2f(x + w, y),
                                    new Vector2f(x + w, y + h),
                                    new Vector2f(x, y + h)));
        }
        fillPolygon(graphics, polygon, fill);
        var border = new ArrayList<>(polygon);
        border.add(polygon.getFirst());
        line(graphics, border, borderColor, 1);
        if (box.node().shape() == MermaidFlowchart.Shape.SUBROUTINE) {
            line(graphics, List.of(new Vector2f(x + 6, y), new Vector2f(x + 6, y + h)), BORDER, 1);
            line(
                    graphics,
                    List.of(new Vector2f(x + w - 6, y), new Vector2f(x + w - 6, y + h)),
                    BORDER,
                    1);
        }
        if (box.node().shape() == MermaidFlowchart.Shape.DATABASE) {
            var ellipse = new ArrayList<Vector2f>();
            for (int i = 0; i <= 24; i++) {
                double angle = i * Math.PI * 2 / 24;
                ellipse.add(
                        new Vector2f(
                                x + w / 2 + (float) Math.cos(angle) * w / 2,
                                y + 5 + (float) Math.sin(angle) * 5));
            }
            line(graphics, ellipse, BORDER, 1);
        }
    }

    private static void fillPolygon(GuiGraphics graphics, List<Vector2f> points, int color) {
        int min = (int) Math.floor(points.stream().mapToDouble(p -> p.y).min().orElse(0));
        int max = (int) Math.ceil(points.stream().mapToDouble(p -> p.y).max().orElse(0));
        for (int y = min; y < max; y++) {
            float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY, scan = y + .5f;
            for (int i = 0; i < points.size(); i++) {
                var a = points.get(i);
                var b = points.get((i + 1) % points.size());
                if ((a.y <= scan && b.y > scan) || (b.y <= scan && a.y > scan)) {
                    float x = a.x + (scan - a.y) / (b.y - a.y) * (b.x - a.x);
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
            if (left < right)
                graphics.fill((int) Math.floor(left), y, (int) Math.ceil(right), y + 1, color);
        }
    }

    private static void line(GuiGraphics graphics, List<Vector2f> points, int color, float width) {
        DrawerHelper.drawLines(graphics, points, color, color, width);
    }

    private static void arrow(GuiGraphics graphics, Vector2f previous, Vector2f tip, int color) {
        var direction = new Vector2f(tip).sub(previous);
        if (direction.lengthSquared() < .001f) return;
        direction.normalize();
        var base = new Vector2f(tip).sub(new Vector2f(direction).mul(6));
        var side = new Vector2f(-direction.y, direction.x).mul(3);
        fillPolygon(
                graphics,
                List.of(tip, new Vector2f(base).add(side), new Vector2f(base).sub(side)),
                color);
    }
}
