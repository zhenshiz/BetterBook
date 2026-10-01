package com.zhenshiz.betterbook.core;

import java.util.*;
import java.util.regex.Pattern;

/** 可视化参数表与 LaTeX 源码之间的无损转换。槽位也可以填写嵌套的公式。 */
public final class LatexTemplates {
    private LatexTemplates() {}

    public record Template(String id, String formula, List<String> fields, List<String> defaults) {
        public String build(List<String> values) {
            if (values.size() != fields.size())
                throw new IllegalArgumentException("Wrong slot count");
            // 单次替换，用户参数中的 $0 等文本不会被再次当作模板槽位。
            var matcher = Pattern.compile("\\$(\\d+)").matcher(formula);
            var result = new StringBuilder();
            while (matcher.find())
                matcher.appendReplacement(
                        result,
                        java.util.regex.Matcher.quoteReplacement(
                                values.get(Integer.parseInt(matcher.group(1)))));
            matcher.appendTail(result);
            return result.toString();
        }

        public Optional<List<String>> match(String source) {
            var matcher = Pattern.compile("\\$\\d+").matcher(formula);
            var literals = new ArrayList<String>();
            int end = 0;
            while (matcher.find()) {
                literals.add(formula.substring(end, matcher.start()));
                end = matcher.end();
            }
            literals.add(formula.substring(end));
            if (!source.startsWith(literals.getFirst())) return Optional.empty();
            int position = literals.getFirst().length();
            var values = new ArrayList<String>();
            for (int slot = 0; slot < fields.size(); slot++) {
                String boundary = literals.get(slot + 1);
                int next =
                        boundary.isEmpty() ? source.length() : boundary(source, position, boundary);
                if (next < 0) return Optional.empty();
                String value = source.substring(position, next);
                if (!balanced(value)) return Optional.empty();
                values.add(value);
                position = next + boundary.length();
            }
            return position == source.length()
                    ? Optional.of(List.copyOf(values))
                    : Optional.empty();
        }
    }

    private static int boundary(String source, int start, String literal) {
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            if (depth == 0 && source.startsWith(literal, i)) return i;
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth < 0) return -1;
        }
        return -1;
    }

    private static boolean balanced(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth < 0) return false;
        }
        return depth == 0;
    }

    public static final List<Template> ALL =
            List.of(
                    new Template(
                            "fraction",
                            "\\frac{$0}{$1}",
                            List.of("numerator", "denominator"),
                            List.of("x", "y")),
                    new Template(
                            "root",
                            "\\sqrt[$0]{$1}",
                            List.of("degree", "expression"),
                            List.of("2", "x")),
                    new Template(
                            "power", "{$0}^{$1}", List.of("base", "exponent"), List.of("x", "2")),
                    new Template(
                            "subscript",
                            "{$0}_{$1}",
                            List.of("base", "subscript"),
                            List.of("a", "n")),
                    new Template(
                            "limit",
                            "\\lim_{$0\\to $1} {$2}",
                            List.of("variable", "target", "expression"),
                            List.of("x", "0", "\\frac{\\sin x}{x}")),
                    new Template(
                            "trig", "\\sin\\left($0\\right)", List.of("expression"), List.of("x")),
                    new Template(
                            "integral",
                            "\\int_{$0}^{$1} {$2}\\,d{$3}",
                            List.of("lower", "upper", "expression", "variable"),
                            List.of("0", "1", "x^2", "x")),
                    new Template(
                            "sum",
                            "\\sum_{$0}^{$1} {$2}",
                            List.of("lower", "upper", "expression"),
                            List.of("i=1", "n", "i^2")),
                    new Template(
                            "brackets", "\\left($0\\right)", List.of("expression"), List.of("x+y")),
                    new Template(
                            "matrix",
                            "\\begin{pmatrix}$0 & $1 \\\\ $2 & $3\\end{pmatrix}",
                            List.of("cell_11", "cell_12", "cell_21", "cell_22"),
                            List.of("1", "0", "0", "1")));
}
