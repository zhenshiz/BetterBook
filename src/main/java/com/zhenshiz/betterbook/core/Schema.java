package com.zhenshiz.betterbook.core;

import org.jsoup.nodes.Element;

import java.util.*;
import java.util.function.Consumer;

/** 定义文档节点及行内标记；声明顺序决定选择器匹配顺序。 */
public final class Schema {
    public enum Kind {
        CONTAINER,
        TEXT,
        ATOM
    }

    /**
     * 节点契约。编解码钩子仅修改传入的节点，不持有编辑会话状态。
     *
     * @param id 命名空间标识
     * @param selector HTML 选择器
     * @param kind 节点布局类型
     * @param template 插入时使用的 HTML
     * @param decode 解析后规范化钩子
     * @param encode 输出前规范化钩子
     * @param validate 验证属性及子节点，不合法时抛出 IllegalArgumentException
     */
    public record NodeSpec(
            String id,
            String selector,
            Kind kind,
            String template,
            Consumer<Element> decode,
            Consumer<Element> encode,
            Consumer<Element> validate) {
        public NodeSpec(String id, String selector, Kind kind, String template) {
            this(id, selector, kind, template, e -> {}, e -> {}, e -> {});
        }

        public NodeSpec(
                String id,
                String selector,
                Kind kind,
                String template,
                Consumer<Element> decode,
                Consumer<Element> encode) {
            this(id, selector, kind, template, decode, encode, e -> {});
        }
    }

    /**
     * 文本标记契约。
     *
     * @param id 命名空间标识
     * @param selector 输入标签选择器
     * @param tag 规范化输出标签
     * @param attributes 输出时始终保留的属性
     */
    public record MarkSpec(
            String id, String selector, String tag, Map<String, String> attributes) {}

    private final LinkedHashMap<String, NodeSpec> nodes = new LinkedHashMap<>();
    private final LinkedHashMap<String, MarkSpec> marks = new LinkedHashMap<>();

    /**
     * 注册节点，HTML 匹配按注册顺序执行。
     *
     * @param spec 节点契约
     * @throws IllegalArgumentException 标识不合法或重复时抛出
     */
    public void node(NodeSpec spec) {
        requireId(spec.id());
        if (nodes.putIfAbsent(spec.id(), spec) != null)
            throw new IllegalArgumentException("Duplicate node: " + spec.id());
    }

    /**
     * 注册行内格式标记。
     *
     * @param spec 格式标记契约
     * @throws IllegalArgumentException 标识不合法或重复时抛出
     */
    public void mark(MarkSpec spec) {
        requireId(spec.id());
        if (marks.putIfAbsent(spec.id(), spec) != null)
            throw new IllegalArgumentException("Duplicate mark: " + spec.id());
    }

    /**
     * 校验命名空间标识。
     *
     * @param id 使用 namespace:path 格式的字符串
     * @throws IllegalArgumentException 标识字符或结构无效时抛出
     */
    public static void requireId(String id) {
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Invalid namespaced ID: " + id);
    }

    /**
     * 返回第一个匹配 HTML 元素的节点定义。
     *
     * @param element 待匹配元素
     * @return 已注册定义，无匹配时为 null
     */
    public NodeSpec node(Element element) {
        for (var spec : nodes.values()) if (element.is(spec.selector())) return spec;
        return null;
    }

    /**
     * 返回第一个匹配 HTML 元素的格式定义。
     *
     * @param element 待匹配元素
     * @return 已注册定义，无匹配时为 null
     */
    public MarkSpec mark(Element element) {
        for (var spec : marks.values()) if (element.is(spec.selector())) return spec;
        return null;
    }

    public MarkSpec mark(String id) {
        return marks.get(id);
    }

    public Collection<NodeSpec> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Collection<MarkSpec> marks() {
        return Collections.unmodifiableCollection(marks.values());
    }
}
