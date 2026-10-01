package com.zhenshiz.betterbook.core;

import java.util.Locale;

/** 将 LaTeX 颜色声明转为排版库接受的有作用域颜色，原始书页源码不变。 */
public final class LatexSyntax {
    private LatexSyntax() {}

    public static String forRenderer(String source) {
        return normalize(source, 0, source.length());
    }

    private static String normalize(String source, int from, int to) {
        var out = new StringBuilder();
        for (int i = from; i < to; ) {
            char c = source.charAt(i);
            if (c == '%') {
                int end = source.indexOf('\n', i);
                if (end < 0 || end >= to) end = to;
                out.append(source, i, end);
                i = end;
                continue;
            }
            if (c == '{') {
                int end = groupEnd(source, i, to);
                out.append('{').append(normalize(source, i + 1, end)).append('}');
                i = end + 1;
                continue;
            }
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            int end = i + 1;
            while (end < to && Character.isLetter(source.charAt(end))) end++;
            if (end == i + 1) { // 转义大括号、反斜线等不能作为分组解释。
                end = Math.min(to, i + 2);
                out.append(source, i, end);
                i = end;
                continue;
            }
            String command = source.substring(i + 1, end);
            if (!command.equals("color") && !command.equals("textcolor")) {
                out.append(source, i, end);
                i = end;
                continue;
            }
            int argument = whitespace(source, end, to);
            String model = "";
            if (argument < to && source.charAt(argument) == '[') {
                int close = source.indexOf(']', argument);
                if (close < 0 || close >= to)
                    throw new IllegalArgumentException("Unclosed color model");
                model = source.substring(argument + 1, close).trim();
                argument = whitespace(source, close + 1, to);
            }
            if (argument >= to || source.charAt(argument) != '{')
                throw new IllegalArgumentException("Color requires a braced value");
            int close = groupEnd(source, argument, to);
            String color = color(model, source.substring(argument + 1, close).trim());
            int remainder = close + 1;
            out.append("\\textcolor{").append(color).append("}");
            if (command.equals("textcolor")) {
                i = remainder; // textcolor 自身的正文分组由正常递归处理。
            } else {
                out.append('{').append(normalize(source, remainder, to)).append('}');
                return out.toString(); // color 声明只覆盖当前分组的剩余内容。
            }
        }
        return out.toString();
    }

    private static int whitespace(String source, int from, int to) {
        while (from < to && Character.isWhitespace(source.charAt(from))) from++;
        return from;
    }

    private static int groupEnd(String source, int start, int to) {
        int depth = 0;
        for (int i = start; i < to; i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '%') {
                int lineEnd = source.indexOf('\n', i);
                if (lineEnd < 0 || lineEnd >= to) break;
                i = lineEnd;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("Unbalanced color group");
    }

    private static String color(String model, String value) {
        if (model.isEmpty()) return value;
        if (model.equals("HTML")) {
            if (!value.matches("[0-9a-fA-F]{6}"))
                throw new IllegalArgumentException("HTML color requires six hex digits");
            return "#" + value;
        }
        if (!model.equals("RGB") && !model.equals("rgb"))
            throw new IllegalArgumentException("Unsupported color model: " + model);
        var components = value.split(",", -1);
        if (components.length != 3)
            throw new IllegalArgumentException("RGB color requires three components");
        int result = 0;
        for (String component : components) {
            double number = Double.parseDouble(component.trim());
            double max = model.equals("RGB") ? 255 : 1;
            if (!Double.isFinite(number)
                    || number < 0
                    || number > max
                    || model.equals("RGB") && number != Math.rint(number))
                throw new IllegalArgumentException("Color component out of range");
            result = result << 8 | (int) Math.round(number * 255 / max);
        }
        return String.format(Locale.ROOT, "#%06x", result);
    }
}
