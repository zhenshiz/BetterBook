package com.zhenshiz.betterbook.client;

import com.zhenshiz.betterbook.core.LatexNode;
import com.zhenshiz.betterbook.core.LatexSyntax;

import org.scilab.forge.jlatexmath.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.regex.Pattern;

/** 纯 Java 数学排版。缓存排版结果，GPU 纹理由各视图独立管理。 */
final class LatexRenderer {
    static final int SCALE = 2, MAX_DIMENSION = 2048;
    private static final Pattern COMMAND = Pattern.compile("\\\\([a-zA-Z]+)");
    private static final Set<String> DISALLOWED =
            Set.of(
                    "includegraphics",
                    "input",
                    "include",
                    "newcommand",
                    "renewcommand",
                    "newenvironment",
                    "renewenvironment",
                    "def",
                    "gdef",
                    "edef",
                    "xdef",
                    "let",
                    "csname",
                    "catcode",
                    "makeatletter",
                    "definecolor",
                    "DeclareMathSizes",
                    "magnification");
    private static final Map<LatexNode, Result> CACHE =
            new LinkedHashMap<>(64, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<LatexNode, Result> entry) {
                    return size() > 128;
                }
            };

    record Result(TeXIcon icon, String error) {
        boolean valid() {
            return icon != null;
        }

        float width() {
            return valid() ? icon.getIconWidth() / (float) SCALE : 120;
        }

        float height() {
            return valid() ? icon.getIconHeight() / (float) SCALE : 24;
        }

        BufferedImage image() {
            if (!valid()) throw new IllegalStateException(error);
            var image =
                    new BufferedImage(
                            icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                icon.paintIcon(null, graphics, 0, 0);
            } finally {
                graphics.dispose();
            }
            return image;
        }
    }

    static synchronized Result get(LatexNode node) {
        return CACHE.computeIfAbsent(node, LatexRenderer::parse);
    }

    private static Result parse(LatexNode node) {
        try {
            if (node.source().isBlank()) throw new IllegalArgumentException("Empty formula");
            var commands = COMMAND.matcher(node.source());
            while (commands.find()) {
                String command = commands.group(1);
                // 外部资源和全局宏不属于数学公式，避免读取本地文件或污染其他书页。
                if (DISALLOWED.contains(command) || command.startsWith("jlm"))
                    throw new IllegalArgumentException("Unsupported math command: \\" + command);
            }
            int depth = 0;
            for (int i = 0; i < node.source().length(); i++) {
                char c = node.source().charAt(i);
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '{' && ++depth > 64)
                    throw new IllegalArgumentException("Formula is too deeply nested");
                if (c == '}' && --depth < 0)
                    throw new IllegalArgumentException("Unbalanced braces");
            }
            if (depth != 0) throw new IllegalArgumentException("Unbalanced braces");
            var icon =
                    new TeXFormula(LatexSyntax.forRenderer(node.source()))
                            .createTeXIcon(TeXConstants.STYLE_DISPLAY, node.size() * SCALE);
            icon.setForeground(Color.decode(node.color()));
            if (icon.getIconWidth() > MAX_DIMENSION || icon.getIconHeight() > MAX_DIMENSION)
                throw new IllegalArgumentException("Formula is too large");
            return new Result(icon, "");
        } catch (RuntimeException e) {
            String message = Objects.toString(e.getMessage(), "Invalid formula");
            return new Result(null, message.length() > 180 ? message.substring(0, 180) : message);
        }
    }

    static float height(org.jsoup.nodes.Element element, float width) {
        var result = get(LatexNode.read(element));
        return Math.max(
                28, result.height() * Math.min(1, Math.max(1, width - 12) / result.width()) + 12);
    }
}
