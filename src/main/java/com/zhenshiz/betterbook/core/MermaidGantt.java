package com.zhenshiz.betterbook.core;

import static com.zhenshiz.betterbook.core.MermaidScene.*;

import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.ToIntFunction;
import java.util.regex.*;

/** 甘特图：日期、时长、after 依赖、分区及任务状态。 */
final class MermaidGantt {
    private record Task(
            String name,
            String id,
            String section,
            List<String> schedule,
            Set<String> tags,
            int previous,
            MermaidDiagrams.SourceLine line) {}

    private record Range(LocalDateTime start, LocalDateTime end) {}

    static MermaidScene parse(
            List<MermaidDiagrams.SourceLine> lines, ToIntFunction<String> measure) {
        String title = "", section = "", axis = "MM-dd";
        DateTimeFormatter format =
                DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
        boolean weekends = false;
        var tasks = new ArrayList<Task>();
        var ids = new HashMap<String, Integer>();
        for (int i = 1; i < lines.size(); i++) {
            var line = lines.get(i);
            String t = line.text();
            if (t.startsWith("title ")) {
                title = t.substring(6);
                continue;
            }
            if (t.startsWith("dateFormat ")) {
                format = dateFormat(t.substring(11).trim());
                continue;
            }
            if (t.startsWith("axisFormat ")) {
                axis =
                        t.substring(11)
                                .replace("%Y", "uuuu")
                                .replace("%m", "MM")
                                .replace("%d", "dd")
                                .replace("%H", "HH")
                                .replace("%M", "mm");
                continue;
            }
            if (t.startsWith("section ")) {
                section = label(t.substring(8));
                continue;
            }
            if (t.equals("excludes weekends")) {
                weekends = true;
                continue;
            }
            if (t.equals("todayMarker off")) continue;
            if (t.startsWith("tickInterval ") || t.startsWith("todayMarker "))
                throw line.error("当前甘特图使用自动刻度；支持 todayMarker off，不支持自定义刻度/今日标记");
            int colon = t.indexOf(':');
            if (colon < 0) throw line.error("任务使用 名称 : 状态, ID, 开始日期/after, 时长 格式");
            String name = label(t.substring(0, colon));
            var fields =
                    new ArrayList<>(
                            Arrays.stream(t.substring(colon + 1).split(","))
                                    .map(String::strip)
                                    .toList());
            var tags = new HashSet<String>();
            while (!fields.isEmpty()
                    && Set.of("done", "active", "crit", "milestone").contains(fields.getFirst()))
                tags.add(fields.removeFirst());
            String id = "task" + tasks.size();
            if (fields.size() >= 2
                    && !isDate(fields.getFirst(), format)
                    && !isDuration(fields.getFirst())
                    && !fields.getFirst().startsWith("after ")
                    && !fields.getFirst().startsWith("until ")) id = fields.removeFirst();
            if (fields.isEmpty() || fields.size() > 2) throw line.error("任务需要开始日期/依赖与时长，或一个时长");
            if (ids.putIfAbsent(id, tasks.size()) != null) throw line.error("重复任务 ID：" + id);
            tasks.add(
                    new Task(
                            name,
                            id,
                            section,
                            List.copyOf(fields),
                            Set.copyOf(tags),
                            tasks.size() - 1,
                            line));
        }
        if (tasks.isEmpty() || tasks.size() > 96)
            throw new IllegalArgumentException("甘特图需要 1–96 个任务");
        var ranges = new HashMap<Integer, Range>();
        var resolving = new HashSet<Integer>();
        for (int i = 0; i < tasks.size(); i++)
            resolve(i, tasks, ids, ranges, resolving, format, weekends);
        var first =
                ranges.values().stream()
                        .map(Range::start)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        var last =
                ranges.values().stream()
                        .map(Range::end)
                        .max(Comparator.naturalOrder())
                        .orElseThrow();
        long span = Math.max(1, ChronoUnit.MINUTES.between(first, last));
        float labelWidth =
                Math.min(
                        280,
                        Math.max(
                                100,
                                tasks.stream()
                                                .mapToInt(t -> measure.applyAsInt(t.name()))
                                                .max()
                                                .orElse(0)
                                        + 20));
        float chartWidth = 460, width = labelWidth + chartWidth + 20, y = 64;
        var out = new Builder("gantt", measure);
        out.text(title, width / 2, 9, true);
        DateTimeFormatter axisFormat;
        try {
            axisFormat = DateTimeFormatter.ofPattern(axis);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("axisFormat 不受支持");
        }
        float height =
                80 + tasks.size() * 28 + tasks.stream().map(Task::section).distinct().count() * 22;
        for (int i = 0; i <= 6; i++) {
            float x = labelWidth + i * chartWidth / 6;
            out.line(x, 52, x, height - 8, true, Marker.NONE, Marker.NONE);
            out.text(first.plusMinutes(span * i / 6).format(axisFormat), x, 38, true);
        }
        String previousSection = null;
        for (int i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            var range = ranges.get(i);
            if (!task.section().equals(previousSection)) {
                previousSection = task.section();
                if (!previousSection.isBlank()) {
                    out.box(8, y - 4, width - 16, 20, SHADE);
                    out.text(previousSection, 12, y, false);
                    y += 24;
                }
            }
            out.text(task.name(), 10, y + 6, false);
            float start =
                    labelWidth
                            + ChronoUnit.MINUTES.between(first, range.start())
                                    / (float) span
                                    * chartWidth;
            float end =
                    labelWidth
                            + ChronoUnit.MINUTES.between(first, range.end())
                                    / (float) span
                                    * chartWidth;
            int fill =
                    task.tags().contains("crit")
                            ? 0xffc99571
                            : task.tags().contains("done")
                                    ? 0xffc7c1a0
                                    : task.tags().contains("active") ? 0xffb3a375 : 0xffdfc99e;
            if (task.tags().contains("milestone"))
                out.path(
                        List.of(
                                new Point(start, y + 2),
                                new Point(start + 7, y + 9),
                                new Point(start, y + 16),
                                new Point(start - 7, y + 9),
                                new Point(start, y + 2)),
                        false,
                        Marker.NONE,
                        Marker.NONE);
            else out.box(start, y + 1, Math.max(3, end - start), 18, fill);
            y += 28;
        }
        return out.size(width, Math.max(height, y + 8)).build();
    }

    private static Range resolve(
            int index,
            List<Task> tasks,
            Map<String, Integer> ids,
            Map<Integer, Range> ranges,
            Set<Integer> resolving,
            DateTimeFormatter format,
            boolean weekends) {
        if (ranges.containsKey(index)) return ranges.get(index);
        var task = tasks.get(index);
        if (!resolving.add(index)) throw task.line().error("任务依赖存在循环");
        var fields = task.schedule();
        LocalDateTime start;
        String finish;
        if (fields.size() == 1) {
            if (task.previous() < 0) throw task.line().error("第一个任务需要明确开始日期");
            start = resolve(task.previous(), tasks, ids, ranges, resolving, format, weekends).end();
            finish = fields.getFirst();
        } else {
            String value = fields.getFirst();
            if (value.startsWith("after ")) {
                start = null;
                for (String id : value.substring(6).strip().split("\\s+")) {
                    Integer dependency = ids.get(id);
                    if (dependency == null) throw task.line().error("未定义依赖任务：" + id);
                    var end =
                            resolve(dependency, tasks, ids, ranges, resolving, format, weekends)
                                    .end();
                    if (start == null || end.isAfter(start)) start = end;
                }
            } else start = parseDate(value, format, task.line());
            finish = fields.getLast();
        }
        LocalDateTime end;
        if (finish.startsWith("until ")) {
            Integer dependency = ids.get(finish.substring(6).strip());
            if (dependency == null) throw task.line().error("未定义 until 任务");
            end = resolve(dependency, tasks, ids, ranges, resolving, format, weekends).start();
        } else if (isDuration(finish)) {
            var m =
                    Pattern.compile("(\\d+(?:\\.\\d+)?)([dw hms])".replace(" ", ""))
                            .matcher(finish);
            m.matches();
            double value = Double.parseDouble(m.group(1));
            char unit = m.group(2).charAt(0);
            double minutes =
                    value
                            * switch (unit) {
                                case 'w' -> 10080;
                                case 'd' -> 1440;
                                case 'h' -> 60;
                                case 'm' -> 1;
                                default -> 1d / 60;
                            };
            if (!Double.isFinite(minutes) || minutes > 5256000) throw task.line().error("任务时长超出范围");
            long duration = Math.round(minutes);
            if (weekends && (unit == 'd' || unit == 'w')) {
                long days = duration / 1440;
                if (days > 3660) throw task.line().error("任务过长");
                end = start;
                while (days > 0) {
                    end = end.plusDays(1);
                    if (end.getDayOfWeek() != DayOfWeek.SATURDAY
                            && end.getDayOfWeek() != DayOfWeek.SUNDAY) days--;
                }
                end = end.plusMinutes(duration % 1440);
            } else end = start.plusMinutes(duration);
        } else end = parseDate(finish, format, task.line());
        if (end.isBefore(start)) throw task.line().error("结束日期早于开始日期");
        var range = new Range(start, end);
        ranges.put(index, range);
        resolving.remove(index);
        return range;
    }

    private static boolean isDuration(String value) {
        return value.matches("\\d+(?:\\.\\d+)?[dwhms]");
    }

    private static boolean isDate(String value, DateTimeFormatter format) {
        try {
            parseDate(value, format, new MermaidDiagrams.SourceLine(value, 0, 0));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static DateTimeFormatter dateFormat(String value) {
        try {
            return DateTimeFormatter.ofPattern(value.replace("YYYY", "uuuu").replace("DD", "dd"))
                    .withResolverStyle(ResolverStyle.STRICT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("dateFormat 不受支持");
        }
    }

    private static LocalDateTime parseDate(
            String value, DateTimeFormatter format, MermaidDiagrams.SourceLine line) {
        try {
            var temporal = format.parseBest(value, LocalDateTime::from, LocalDate::from);
            return temporal instanceof LocalDateTime date
                    ? date
                    : ((LocalDate) temporal).atStartOfDay();
        } catch (DateTimeException e) {
            throw line.error("日期不符合 dateFormat：" + value);
        }
    }
}
