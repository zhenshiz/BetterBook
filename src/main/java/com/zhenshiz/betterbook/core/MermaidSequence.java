package com.zhenshiz.betterbook.core;

import static com.zhenshiz.betterbook.core.MermaidScene.*;

import java.util.*;
import java.util.function.ToIntFunction;
import java.util.regex.*;

/** 时序图：参与者、消息、注释、组合片段和激活条。 */
final class MermaidSequence {
    private record Party(String id, String name, boolean actor) {}

    private record Event(
            String kind,
            String from,
            String to,
            String text,
            boolean dotted,
            Marker marker,
            char activation,
            boolean bidirectional) {}

    private record Frame(String title, float top, float bottom, List<Float> dividers) {}

    private record OpenFrame(String title, float top, List<Float> dividers) {}

    private static final Pattern MESSAGE =
            Pattern.compile(
                    "([\\p{L}\\p{N}_-]+?)\\s*(<<-->>|<<->>|-->>|->>|--x|-x|--\\)|-\\)|-->|->)\\s*([+-]?)([\\p{L}\\p{N}_-]+)\\s*:\\s*(.*)");

    static MermaidScene parse(
            List<MermaidDiagrams.SourceLine> lines, ToIntFunction<String> measure) {
        var parties = new LinkedHashMap<String, Party>();
        var events = new ArrayList<Event>();
        boolean numbering = false;
        String title = "";
        for (int i = 1; i < lines.size(); i++) {
            var line = lines.get(i);
            String t = line.text();
            if (t.startsWith("participant ") || t.startsWith("actor ")) {
                var m =
                        MermaidDiagrams.match(
                                "(participant|actor)\\s+([\\p{L}\\p{N}_-]+)(?:\\s+as\\s+(.+))?",
                                line);
                parties.put(
                        m.group(2),
                        new Party(
                                m.group(2),
                                label(m.group(3) == null ? m.group(2) : m.group(3)),
                                m.group(1).equals("actor")));
                continue;
            }
            if (t.equals("autonumber")) {
                numbering = true;
                continue;
            }
            if (t.startsWith("title ")) {
                title = t.substring(6);
                continue;
            }
            var message = MESSAGE.matcher(t);
            if (message.matches()) {
                String from = message.group(1), to = message.group(4), arrow = message.group(2);
                parties.putIfAbsent(from, new Party(from, from, false));
                parties.putIfAbsent(to, new Party(to, to, false));
                events.add(
                        new Event(
                                "message",
                                from,
                                to,
                                label(message.group(5)),
                                arrow.contains("--"),
                                arrow.endsWith("x")
                                        ? Marker.CROSS
                                        : arrow.endsWith(")")
                                                ? Marker.OPEN
                                                : arrow.endsWith(">>") ? Marker.ARROW : Marker.NONE,
                                message.group(3).isEmpty() ? ' ' : message.group(3).charAt(0),
                                arrow.startsWith("<<")));
                continue;
            }
            if (t.toLowerCase(Locale.ROOT).startsWith("note ")) {
                var m =
                        MermaidDiagrams.match(
                                "(?i)note\\s+(over|left of|right"
                                    + " of)\\s+([\\p{L}\\p{N}_-]+)(?:\\s*,\\s*([\\p{L}\\p{N}_-]+))?\\s*:\\s*(.*)",
                                line);
                String from = m.group(2), to = m.group(3) == null ? from : m.group(3);
                parties.putIfAbsent(from, new Party(from, from, false));
                parties.putIfAbsent(to, new Party(to, to, false));
                events.add(
                        new Event(
                                "note " + m.group(1).toLowerCase(Locale.ROOT),
                                from,
                                to,
                                label(m.group(4)),
                                false,
                                Marker.NONE,
                                ' ',
                                false));
                continue;
            }
            if (t.startsWith("activate ") || t.startsWith("deactivate ")) {
                String id = t.substring(t.indexOf(' ') + 1).strip();
                if (!parties.containsKey(id)) throw line.error("未定义参与者 " + id);
                events.add(
                        new Event(
                                t.startsWith("activate ") ? "activate" : "deactivate",
                                id,
                                id,
                                "",
                                false,
                                Marker.NONE,
                                ' ',
                                false));
                continue;
            }
            if (t.matches("(?:loop|alt|opt|par|critical|break|rect)(?:\\s+.*)?")) {
                events.add(new Event("start", "", "", t, false, Marker.NONE, ' ', false));
                continue;
            }
            if (t.matches("(?:else|and|option)(?:\\s+.*)?")) {
                events.add(new Event("divider", "", "", t, false, Marker.NONE, ' ', false));
                continue;
            }
            if (t.equals("end")) {
                events.add(new Event("end", "", "", "", false, Marker.NONE, ' ', false));
                continue;
            }
            throw line.error("不支持此时序图声明");
        }
        if (parties.isEmpty() || parties.size() > 32 || events.size() > 192)
            throw new IllegalArgumentException("时序图需要 1–32 个参与者，最多 192 个事件");
        float spacing =
                Math.max(
                        130,
                        events.stream()
                                .mapToInt(e -> measure.applyAsInt(e.text()) + 30)
                                .max()
                                .orElse(0));
        spacing = Math.min(350, spacing);
        float margin = 100, width = margin * 2 + Math.max(1, parties.size() - 1) * spacing;
        var xs = new HashMap<String, Float>();
        int index = 0;
        for (var party : parties.values()) xs.put(party.id(), margin + index++ * spacing);
        float[] ys = new float[events.size()];
        float y = 82;
        var stack = new ArrayDeque<OpenFrame>();
        var frames = new ArrayList<Frame>();
        for (int i = 0; i < events.size(); i++) {
            var e = events.get(i);
            ys[i] = y;
            if (e.kind().equals("start")) stack.push(new OpenFrame(e.text(), y, new ArrayList<>()));
            if (e.kind().equals("divider")) {
                if (stack.isEmpty()) throw new IllegalArgumentException("else/and/option 需要组合片段");
                stack.peek().dividers().add(y);
            }
            if (e.kind().equals("end")) {
                if (stack.isEmpty()) throw new IllegalArgumentException("多余的 end");
                var frame = stack.pop();
                frames.add(new Frame(frame.title(), frame.top(), y, frame.dividers()));
            }
            y +=
                    e.kind().equals("message") && e.from().equals(e.to())
                            ? 50
                            : Math.max(28, 22 + e.text().split("\n", -1).length * 11);
        }
        if (!stack.isEmpty()) throw new IllegalArgumentException("组合片段缺少 end");
        float bottom = y + 24;
        var out = new Builder("sequenceDiagram", measure).size(width, bottom + 45);
        frames.sort(Comparator.comparingDouble(f -> -f.bottom() + f.top()));
        for (var f : frames) {
            out.box(12, f.top() - 8, width - 24, f.bottom() - f.top() + 30, 0x14b89a66);
            out.text(f.title(), 20, f.top() - 3, false);
            for (float divider : f.dividers())
                out.line(12, divider - 8, width - 12, divider - 8, true, Marker.NONE, Marker.NONE);
        }
        out.text(title, width / 2, 5, true);
        for (var p : parties.values()) {
            float x = xs.get(p.id());
            out.line(x, 64, x, bottom, true, Marker.NONE, Marker.NONE);
            if (p.actor()) {
                out.ellipse(x - 6, 19, 12, 12, LIGHT);
                out.line(x, 31, x, 48, false, Marker.NONE, Marker.NONE);
                out.line(x - 10, 37, x + 10, 37, false, Marker.NONE, Marker.NONE);
                out.line(x, 48, x - 9, 57, false, Marker.NONE, Marker.NONE);
                out.line(x, 48, x + 9, 57, false, Marker.NONE, Marker.NONE);
                out.text(p.name(), x, 61, true);
            } else {
                float w = Math.max(70, measure.applyAsInt(p.name()) + 20);
                out.box(x - w / 2, 24, w, 30, SHADE);
                out.text(p.name(), x, 34, true);
            }
            float w = Math.max(70, measure.applyAsInt(p.name()) + 20);
            out.box(x - w / 2, bottom, w, 30, SHADE);
            out.text(p.name(), x, bottom + 10, true);
        }
        // 先绘制激活条，消息和文字随后覆盖；快捷 - 作用于发送方，+ 作用于接收方。
        var active = new HashMap<String, Deque<Float>>();
        for (int i = 0; i < events.size(); i++) {
            var e = events.get(i);
            boolean message = e.kind().equals("message");
            float at = ys[i] + (message ? 18 : 0);
            if (e.kind().equals("activate") || message && e.activation() == '+')
                active.computeIfAbsent(e.to(), k -> new ArrayDeque<>()).push(at);
            if (e.kind().equals("deactivate") || message && e.activation() == '-') {
                String id = message ? e.from() : e.to();
                var bars = active.get(id);
                if (bars == null || bars.isEmpty())
                    throw new IllegalArgumentException("deactivate 没有对应的 activate：" + id);
                float top = bars.pop();
                out.box(xs.get(id) - 4, top, 8, Math.max(2, at - top), ACCENT);
            }
        }
        for (var entry : active.entrySet())
            while (!entry.getValue().isEmpty()) {
                float top = entry.getValue().pop();
                out.box(xs.get(entry.getKey()) - 4, top, 8, bottom - top, ACCENT);
            }
        int number = 1;
        for (int i = 0; i < events.size(); i++) {
            var e = events.get(i);
            float at = ys[i], a = xs.getOrDefault(e.from(), 0f), b = xs.getOrDefault(e.to(), 0f);
            if (e.kind().equals("message")) {
                String text = (numbering ? (number++) + ". " : "") + e.text();
                float lineY = at + 18;
                if (e.from().equals(e.to()))
                    out.path(
                            List.of(
                                    new Point(a, lineY),
                                    new Point(a + 45, lineY),
                                    new Point(a + 45, lineY + 22),
                                    new Point(a, lineY + 22)),
                            e.dotted(),
                            Marker.NONE,
                            e.marker());
                else
                    out.line(
                            a,
                            lineY,
                            b,
                            lineY,
                            e.dotted(),
                            e.bidirectional() ? Marker.ARROW : Marker.NONE,
                            e.marker());
                out.text(text, e.from().equals(e.to()) ? a + 25 : (a + b) / 2, at, true);
            } else if (e.kind().startsWith("note ")) {
                float
                        w =
                                Math.max(
                                        70,
                                        Arrays.stream(e.text().split("\n"))
                                                        .mapToInt(measure)
                                                        .max()
                                                        .orElse(0)
                                                + 16),
                        h = 14 + e.text().split("\n").length * 11;
                float x =
                        e.kind().equals("note left of")
                                ? a - w - 10
                                : e.kind().equals("note right of") ? a + 10 : (a + b) / 2 - w / 2;
                out.box(x, at, w, h, SHADE);
                out.text(e.text(), x + w / 2, at + 7, true);
            } else if (e.kind().equals("divider")) out.text(e.text(), 20, at - 1, false);
        }
        return out.build();
    }
}
