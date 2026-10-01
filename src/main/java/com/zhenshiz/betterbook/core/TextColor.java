package com.zhenshiz.betterbook.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** HTML 行内文字颜色的读取与修改，保留其余样式声明。 */
public final class TextColor {
    private static final Pattern RGB =
            Pattern.compile(
                    "rgb\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    private TextColor() {}

    /**
     * 读取样式中的十六进制或 RGB 文字颜色。
     *
     * @param style CSS 声明列表
     * @return RGB 整数；没有有效颜色时为 null
     */
    public static Integer read(String style) {
        Integer result = null;
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0 || !declaration.substring(0, colon).trim().equalsIgnoreCase("color"))
                continue;
            String value = declaration.substring(colon + 1).trim();
            if (value.matches("#[0-9a-fA-F]{6}")) result = Integer.parseInt(value.substring(1), 16);
            else if (value.matches("#[0-9a-fA-F]{3}")) {
                int rgb = Integer.parseInt(value.substring(1), 16);
                result = ((rgb >> 8) * 17 << 16) | ((rgb >> 4 & 15) * 17 << 8) | (rgb & 15) * 17;
            } else {
                var match = RGB.matcher(value);
                if (match.matches()) {
                    try {
                        int r = Integer.parseInt(match.group(1)),
                                g = Integer.parseInt(match.group(2)),
                                b = Integer.parseInt(match.group(3));
                        if (r <= 255 && g <= 255 && b <= 255) result = r << 16 | g << 8 | b;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return result;
    }

    /**
     * 更新文字颜色而不修改其他 CSS 声明。
     *
     * @param style 原始声明列表
     * @param rgb RGB 整数；null 表示移除文字颜色
     * @return 更新后的 CSS 声明列表
     */
    public static String write(String style, Integer rgb) {
        var result = new StringBuilder();
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon >= 0 && declaration.substring(0, colon).trim().equalsIgnoreCase("color"))
                continue;
            if (!declaration.isBlank()) result.append(declaration.trim()).append(';');
        }
        if (rgb != null) result.append(String.format(Locale.ROOT, "color: #%06x;", rgb & 0xffffff));
        return result.toString();
    }
}
