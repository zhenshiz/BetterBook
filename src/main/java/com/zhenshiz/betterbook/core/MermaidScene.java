package com.zhenshiz.betterbook.core;

import java.util.*;
import java.util.function.ToIntFunction;

/** 各图表共用的绘制指令，解析/布局与 Minecraft 绘制完全分离。 */
public record MermaidScene(String type, float width, float height, List<Item> items) {
    public static final int INK = 0xff362a21,
            LINE = 0xff9f8055,
            PAPER = 0xffefe4c7,
            LIGHT = 0xfff7edd5,
            SHADE = 0xffe3d3af,
            ACCENT = 0xffb89a66,
            ERROR = 0xffa6543d;

    public sealed interface Item permits Box, Stroke, Text, Sector {}

    public record Point(float x, float y) {}

    public record Box(
            float x, float y, float width, float height, int fill, int border, boolean ellipse)
            implements Item {}

    public enum Marker {
        NONE,
        ARROW,
        OPEN,
        CROSS,
        TRIANGLE,
        DIAMOND,
        HOLLOW_DIAMOND,
        ONE,
        OPTIONAL_ONE,
        MANY,
        OPTIONAL_MANY
    }

    public record Stroke(List<Point> points, boolean dotted, Marker start, Marker end, int color)
            implements Item {}

    /** x 为左侧或中心，y 为顶端。 */
    public record Text(String value, float x, float y, boolean centered, int color)
            implements Item {}

    public record Sector(float cx, float cy, float radius, double from, double angle, int color)
            implements Item {}

    public static final class Builder {
        private final String type;
        public final ToIntFunction<String> measure;
        private final List<Item> items = new ArrayList<>();
        private float width, height;

        public Builder(String type, ToIntFunction<String> measure) {
            this.type = type;
            this.measure = measure;
        }

        public Builder size(float width, float height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public void box(float x, float y, float w, float h, int fill) {
            items.add(new Box(x, y, w, h, fill, LINE, false));
        }

        public void ellipse(float x, float y, float w, float h, int fill) {
            items.add(new Box(x, y, w, h, fill, LINE, true));
        }

        public void text(String value, float x, float y, boolean center) {
            text(value, x, y, center, INK);
        }

        public void text(String value, float x, float y, boolean center, int color) {
            var lines = label(value).split("\n", -1);
            for (int i = 0; i < lines.length; i++)
                items.add(new Text(lines[i], x, y + i * 11, center, color));
        }

        public void line(
                float x1, float y1, float x2, float y2, boolean dotted, Marker start, Marker end) {
            path(List.of(new Point(x1, y1), new Point(x2, y2)), dotted, start, end);
        }

        public void path(List<Point> points, boolean dotted, Marker start, Marker end) {
            items.add(new Stroke(List.copyOf(points), dotted, start, end, LINE));
        }

        public void sector(float x, float y, float radius, double start, double angle, int color) {
            items.add(new Sector(x, y, radius, start, angle, color));
        }

        public MermaidScene build() {
            if (!Float.isFinite(width)
                    || !Float.isFinite(height)
                    || width <= 0
                    || height <= 0
                    || width > 16000
                    || height > 16000) throw new IllegalArgumentException("图表尺寸超出范围");
            if (items.size() > 4096) throw new IllegalArgumentException("图表内容过多");
            return new MermaidScene(type, width, height, List.copyOf(items));
        }
    }

    public static String label(String value) {
        value = value.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1)
            value = value.substring(1, value.length() - 1);
        value = value.replaceAll("(?i)<br\\s*/?>", "\n").replace("\\n", "\n");
        if (value.length() > 512) throw new IllegalArgumentException("图表文字过长");
        return value;
    }
}
