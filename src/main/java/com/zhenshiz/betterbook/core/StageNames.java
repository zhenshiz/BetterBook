package com.zhenshiz.betterbook.core;

import java.util.*;

/** 与游戏运行时无关的阶段名称规范。 */
public final class StageNames {
    private StageNames() {}

    /**
     * 去除首尾空格并将阶段名称转为小写，验证长度和允许的字符。
     *
     * @param value 阶段名称字符串，长度为 1 到 128 个字符
     * @return 使用 <code>Locale.ROOT</code> 转换的规范名称
     * @throws IllegalArgumentException 名称为空或不符合允许的字符规则时抛出
     */
    public static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("Stage name is required");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_][a-z0-9_./:-]{0,127}"))
            throw new IllegalArgumentException("Invalid stage name: " + value);
        return normalized;
    }

    /**
     * 规范化阶段列表，忽略空白条目并按首次出现的顺序去重。
     *
     * @param values 阶段名称列表；空列表表示无需阶段
     * @return 不可变的规范名称列表
     * @throws IllegalArgumentException 列表为空引用或包含无效名称时抛出
     */
    public static List<String> normalizeAll(List<String> values) {
        if (values == null) throw new IllegalArgumentException("Stage list is required");
        var stages = new LinkedHashSet<String>();
        for (String value : values) {
            if (value != null && value.isBlank()) continue;
            stages.add(normalize(value));
        }
        return List.copyOf(stages);
    }
}
