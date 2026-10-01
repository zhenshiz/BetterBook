package com.zhenshiz.betterbook.core;

import java.util.*;
import java.util.regex.Pattern;

/** 离线解析 Mermaid graph/flowchart；不执行源码中的脚本、HTML 或外部资源。 */
public final class MermaidFlowchart {
    public static final int MAX_NODES = 96, MAX_EDGES = 192, MAX_LABEL = 256;

    public enum Direction {
        TD,
        BT,
        LR,
        RL
    }

    public enum Shape {
        RECTANGLE,
        ROUND,
        STADIUM,
        DIAMOND,
        CIRCLE,
        SUBROUTINE,
        HEXAGON,
        DATABASE
    }

    public enum Line {
        SOLID,
        DOTTED,
        THICK
    }

    public record Node(String id, String label, Shape shape) {}

    public record Edge(
            String from, String to, String label, Line line, boolean arrow, boolean startArrow) {}

    public record Group(String id, String title, List<String> members) {}

    public record NodeStyle(Integer fill, Integer border, Integer text) {}

    public record Graph(
            Direction direction,
            List<Node> nodes,
            List<Edge> edges,
            List<Group> groups,
            Map<String, NodeStyle> styles) {
        public Graph(Direction direction, List<Node> nodes, List<Edge> edges) {
            this(direction, nodes, edges, List.of(), Map.of());
        }
    }

    private record References(List<String> ids, int end) {}

    private record OpenGroup(String id, String title, Set<String> members) {}

    private record Statement(String text, int line) {}

    private record Reference(String id, int end) {}

    private record Connection(
            int end, String label, Line line, boolean arrow, boolean startArrow) {}

    private static final Pattern HEADER =
            Pattern.compile("(?:graph|flowchart)\\s+(TD|TB|BT|LR|RL)");
    private static final List<String> ARROWS =
            List.of("<-.->", "<-->", "<==>", "-.->", "-->", "==>", "---", "-.-", "===");
    private final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private int line;
    private final List<Group> groups = new ArrayList<>();
    private final Deque<OpenGroup> groupStack = new ArrayDeque<>();
    private final Map<String, NodeStyle> styles = new HashMap<>(), classStyles = new HashMap<>();
    private final Map<String, String> classes = new HashMap<>();

    private MermaidFlowchart() {}

    public static Graph parse(String source) {
        if (source.length() > MermaidNode.MAX_SOURCE)
            throw new IllegalArgumentException("Mermaid 源码过长");
        var statements = statements(source);
        if (statements.isEmpty())
            throw new IllegalArgumentException("请输入 graph TD 或 flowchart LR 流程图");
        var header = HEADER.matcher(statements.getFirst().text());
        if (!header.matches())
            throw new IllegalArgumentException(
                    "第 "
                            + statements.getFirst().line()
                            + " 行：支持 graph / flowchart，方向为 TD、TB、BT、LR、RL");
        var parser = new MermaidFlowchart();
        for (int i = 1; i < statements.size(); i++) {
            parser.line = statements.get(i).line();
            parser.statement(statements.get(i).text());
        }
        if (!parser.groupStack.isEmpty()) throw parser.error("subgraph 缺少 end");
        for (var binding : parser.classes.entrySet()) {
            if (!parser.classStyles.containsKey(binding.getValue()))
                throw parser.error("未定义 classDef：" + binding.getValue());
            parser.styles.putIfAbsent(binding.getKey(), parser.classStyles.get(binding.getValue()));
        }
        if (parser.classStyles.containsKey("default"))
            for (String id : parser.nodes.keySet())
                parser.styles.putIfAbsent(id, parser.classStyles.get("default"));
        if (parser.nodes.isEmpty()) throw new IllegalArgumentException("流程图至少需要一个节点");
        return new Graph(
                Direction.valueOf(header.group(1).equals("TB") ? "TD" : header.group(1)),
                List.copyOf(parser.nodes.values()),
                List.copyOf(parser.edges),
                List.copyOf(parser.groups),
                Map.copyOf(parser.styles));
    }

    private void statement(String text) {
        if (text.startsWith("subgraph ")) {
            String value = text.substring(9).strip(),
                    id = "group" + groups.size() + "_" + groupStack.size(),
                    title = value;
            var matcher = Pattern.compile("([\\p{L}\\p{N}_-]+)\\s*\\[(.*)]").matcher(value);
            if (matcher.matches()) {
                id = matcher.group(1);
                title = label(matcher.group(2));
            }
            if (groupStack.size() >= 16) throw error("子图嵌套最多 16 层");
            groupStack.push(new OpenGroup(id, title, new LinkedHashSet<>()));
            return;
        }
        if (text.equals("end")) {
            if (groupStack.isEmpty()) throw error("多余的 end");
            var group = groupStack.pop();
            groups.add(new Group(group.id(), group.title(), List.copyOf(group.members())));
            return;
        }
        if (text.startsWith("classDef ")) {
            var parts = text.substring(9).split("\\s+", 2);
            if (parts.length != 2) throw error("classDef 需要类名与颜色声明");
            for (String name : parts[0].split(",")) classStyles.put(name, style(parts[1]));
            return;
        }
        if (text.startsWith("class ")) {
            var parts = text.substring(6).split("\\s+", 2);
            if (parts.length != 2) throw error("class 需要节点与类名");
            for (String id : parts[0].split(",")) classes.put(id, parts[1]);
            return;
        }
        if (text.startsWith("style ")) {
            var parts = text.substring(6).split("\\s+", 2);
            if (parts.length != 2) throw error("style 需要节点与颜色声明");
            styles.put(parts[0], style(parts[1]));
            return;
        }
        var from = references(text, 0);
        int at = skip(text, from.end());
        while (at < text.length()) {
            var connection = connection(text, at);
            var to = references(text, skip(text, connection.end()));
            for (String a : from.ids())
                for (String b : to.ids()) {
                    if (edges.size() >= MAX_EDGES) throw error("最多 " + MAX_EDGES + " 条连线");
                    edges.add(
                            new Edge(
                                    a,
                                    b,
                                    connection.label(),
                                    connection.line(),
                                    connection.arrow(),
                                    connection.startArrow()));
                }
            from = to;
            at = skip(text, to.end());
        }
    }

    private References references(String text, int at) {
        var ids = new ArrayList<String>();
        while (true) {
            var ref = reference(text, skip(text, at));
            ids.add(ref.id());
            at = skip(text, ref.end());
            if (at >= text.length() || text.charAt(at) != '&') break;
            at++;
        }
        return new References(List.copyOf(ids), at);
    }

    private NodeStyle style(String text) {
        Integer fill = null, border = null, color = null;
        for (String declaration : text.split(",")) {
            var parts = declaration.strip().split(":", 2);
            if (parts.length != 2) throw error("样式需要 属性:颜色");
            if (parts[0].equals("stroke-width")) continue;
            String value = parts[1].strip();
            int rgb;
            if (value.matches("#[0-9a-fA-F]{3}")) {
                value =
                        "#"
                                + value.charAt(1)
                                + value.charAt(1)
                                + value.charAt(2)
                                + value.charAt(2)
                                + value.charAt(3)
                                + value.charAt(3);
            }
            if (!value.matches("#[0-9a-fA-F]{6}")) throw error("颜色使用 #RGB 或 #RRGGBB");
            rgb = 0xff000000 | Integer.parseInt(value.substring(1), 16);
            switch (parts[0]) {
                case "fill" -> fill = rgb;
                case "stroke" -> border = rgb;
                case "color" -> color = rgb;
                default -> throw error("不支持此样式属性：" + parts[0]);
            }
        }
        return new NodeStyle(fill, border, color);
    }

    private Reference reference(String text, int at) {
        int start = at;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (Character.isLetterOrDigit(c) || c == '_') at++;
            else if (c == '-'
                    && at + 1 < text.length()
                    && Character.isLetterOrDigit(text.charAt(at + 1))) at++;
            else break;
        }
        if (start == at) throw error("缺少节点 ID，或使用了未支持的语法");
        String id = text.substring(start, at);
        at = skip(text, at);
        Node defined = null;
        if (text.startsWith("@{", at)) {
            int end = closing(text, at + 2, "}");
            if (end < 0) throw error("节点属性未闭合");
            String attributes = text.substring(at + 2, end), title = id, shape = "rect";
            var matcher =
                    Pattern.compile("(shape|label)\\s*:\\s*(?:\"([^\"]*)\"|([^,]+))")
                            .matcher(attributes);
            int matched = 0;
            while (matcher.find()) {
                String value =
                        matcher.group(2) != null ? matcher.group(2) : matcher.group(3).strip();
                if (matcher.group(1).equals("label")) title = label(value);
                else shape = value;
                matched++;
            }
            if (matched == 0) throw error("节点属性需要 shape 或 label");
            Shape nodeShape =
                    switch (shape) {
                        case "rect", "process" -> Shape.RECTANGLE;
                        case "rounded", "event" -> Shape.ROUND;
                        case "stadium", "terminal" -> Shape.STADIUM;
                        case "diamond", "decision" -> Shape.DIAMOND;
                        case "circle" -> Shape.CIRCLE;
                        case "subproc", "subprocess" -> Shape.SUBROUTINE;
                        case "hex", "hexagon" -> Shape.HEXAGON;
                        case "cyl", "cylinder", "database" -> Shape.DATABASE;
                        default -> throw error("不支持此节点 shape：" + shape);
                    };
            defined = new Node(id, title, nodeShape);
            at = end + 1;
        } else if (at < text.length() && "[({".indexOf(text.charAt(at)) >= 0) {
            var open = new StringBuilder();
            int content = at;
            if (text.startsWith("([", at) || text.startsWith("[(", at)) content = at + 2;
            else if (text.startsWith("[[", at)
                    || text.startsWith("((", at)
                    || text.startsWith("{{", at)) content = at + 2;
            else content = at + 1;
            open.append(text, at, content);
            String close =
                    switch (open.toString()) {
                        case "([" -> "])";
                        case "[(" -> ")]";
                        case "[[" -> "]]";
                        case "((" -> "))";
                        case "{{" -> "}}";
                        case "[" -> "]";
                        case "(" -> ")";
                        default -> "}";
                    };
            int end = closing(text, content, close);
            if (end < 0) throw error("节点 " + id + " 的括号未闭合");
            Shape shape =
                    switch (open.toString()) {
                        case "{" -> Shape.DIAMOND;
                        case "{{" -> Shape.HEXAGON;
                        case "(" -> Shape.ROUND;
                        case "([" -> Shape.STADIUM;
                        case "((" -> Shape.CIRCLE;
                        case "[[" -> Shape.SUBROUTINE;
                        case "[(" -> Shape.DATABASE;
                        default -> Shape.RECTANGLE;
                    };
            defined = new Node(id, label(text.substring(content, end)), shape);
            at = end + close.length();
        }
        at = skip(text, at);
        if (text.startsWith(":::", at)) {
            int startClass = at + 3, endClass = startClass;
            while (endClass < text.length()
                    && (Character.isLetterOrDigit(text.charAt(endClass))
                            || text.charAt(endClass) == '_')) endClass++;
            if (endClass == startClass) throw error("缺少样式类名");
            classes.put(id, text.substring(startClass, endClass));
            at = endClass;
        }
        for (var group : groupStack) group.members().add(id);
        if (!nodes.containsKey(id) && nodes.size() >= MAX_NODES)
            throw error("最多 " + MAX_NODES + " 个节点");
        if (defined != null) nodes.put(id, defined);
        else nodes.putIfAbsent(id, new Node(id, id, Shape.RECTANGLE));
        return new Reference(id, at);
    }

    private Connection connection(String text, int at) {
        String token = null, label = "";
        for (String candidate : ARROWS)
            if (text.startsWith(candidate, at)) {
                token = candidate;
                break;
            }
        int end;
        if (token != null) {
            end = skip(text, at + token.length());
            if (end < text.length() && text.charAt(end) == '|') {
                int close = closing(text, end + 1, "|");
                if (close < 0) throw error("连线文字的 | 未闭合");
                label = label(text.substring(end + 1, close));
                end = close + 1;
            }
        } else {
            String prefix =
                    text.startsWith("--", at)
                            ? "--"
                            : text.startsWith("==", at)
                                    ? "=="
                                    : text.startsWith("-.", at) ? "-." : "";
            if (prefix.isEmpty()) throw error("不支持此连线或声明，使用 -->、---、-.-> 或 ==> ");
            int labelStart = skip(text, at + prefix.length());
            int close = -1;
            for (String candidate : ARROWS) {
                int found = text.indexOf(candidate, labelStart);
                if (found >= 0 && (close < 0 || found < close)) {
                    close = found;
                    token = candidate;
                }
            }
            if (close < 0) throw error("连线未结束");
            label = label(text.substring(labelStart, close));
            end = close + token.length();
        }
        return new Connection(
                end,
                label,
                token.contains("=") ? Line.THICK : token.contains(".") ? Line.DOTTED : Line.SOLID,
                token.endsWith(">"),
                token.startsWith("<"));
    }

    private static int closing(String text, int from, String close) {
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (text.startsWith(close, i)) return i;
        }
        return -1;
    }

    private static String label(String value) {
        value = value.trim();
        if (value.length() > 1
                && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("`") && value.endsWith("`")))
            value = value.substring(1, value.length() - 1);
        value = value.replaceAll("(?i)<br\\s*/?>", "\n").replace("\\n", "\n").replace("\\\"", "\"");
        if (value.length() > MAX_LABEL)
            throw new IllegalArgumentException("节点和连线文字最多 " + MAX_LABEL + " 个字符");
        return value;
    }

    private static List<Statement> statements(String source) {
        var result = new ArrayList<Statement>();
        var text = new StringBuilder();
        int depth = 0, line = 1, start = 1;
        char quote = 0;
        boolean blank = true;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote == 0 && c == '%' && i + 1 < source.length() && source.charAt(i + 1) == '%') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                c = '\n';
            }
            if (c == '\\' && i + 1 < source.length()) {
                text.append(c).append(source.charAt(++i));
                blank = false;
                continue;
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '`') quote = c;
            else if ("[({".indexOf(c) >= 0) depth++;
            else if ("])}".indexOf(c) >= 0) depth--;
            if (depth < 0) throw new IllegalArgumentException("第 " + line + " 行：多余的闭合括号");
            if ((c == '\n' || c == ';') && quote == 0 && depth == 0) {
                if (!blank) result.add(new Statement(text.toString().trim(), start));
                text.setLength(0);
                blank = true;
                start = line + (c == '\n' ? 1 : 0);
            } else {
                text.append(c);
                if (!Character.isWhitespace(c)) blank = false;
            }
            if (c == '\n') line++;
            if (blank) start = line;
        }
        if (depth != 0 || quote != 0)
            throw new IllegalArgumentException("第 " + start + " 行：节点括号或引号未闭合");
        if (!text.toString().isBlank()) result.add(new Statement(text.toString().trim(), start));
        return result;
    }

    private static int skip(String text, int at) {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        return at;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("第 " + line + " 行：" + message);
    }
}
