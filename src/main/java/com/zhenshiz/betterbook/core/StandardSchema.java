package com.zhenshiz.betterbook.core;

import static com.zhenshiz.betterbook.core.Schema.Kind.*;

import java.util.Map;

/** 内置 HTML 词汇；客户端扩展和无 Minecraft 依赖的编解码测试共用此定义。 */
public final class StandardSchema {
    private StandardSchema() {}

    public static Schema create() {
        var schema = new Schema();
        register(schema);
        return schema;
    }

    public static void register(Schema s) {
        s.node(
                new Schema.NodeSpec(
                        "betterbook:admonition",
                        "div[data-type=admonition]",
                        CONTAINER,
                        "<div data-type='admonition' type='info' data-admo-type='info'><div"
                                + " data-type='admonition-title'>INFO</div><div"
                                + " data-type='admonition-content'><p></p></div></div>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:steps",
                        "div[data-type=steps]",
                        CONTAINER,
                        "<div data-type='steps' currentstep='0'><div data-type='step-item'><div"
                            + " data-type='admonition-title'>1</div><div"
                            + " data-type='admonition-content'><p></p></div></div><div"
                            + " data-type='step-item'><div data-type='admonition-title'>2</div><div"
                            + " data-type='admonition-content'><p></p></div></div></div>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:title", "div[data-type=admonition-title]", TEXT, ""));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:content",
                        "div[data-type=admonition-content],div[data-type=step-item]",
                        CONTAINER,
                        ""));
        s.node(new Schema.NodeSpec("betterbook:division", "div:not([data-type])", CONTAINER, ""));
        s.node(new Schema.NodeSpec("betterbook:paragraph", "p", TEXT, "<p></p>"));
        s.node(new Schema.NodeSpec("betterbook:heading", "h1,h2,h3,h4,h5,h6", TEXT, "<h1></h1>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:code_block",
                        "pre",
                        TEXT,
                        "<pre language='text'><code></code></pre>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:task_list",
                        "ul[data-type=taskList],ul.task-list",
                        CONTAINER,
                        "<ul data-type='taskList'><li data-type='taskItem'"
                                + " data-checked='false'><p></p></li></ul>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:bullet_list", "ul", CONTAINER, "<ul><li><p></p></li></ul>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:ordered_list", "ol", CONTAINER, "<ol><li><p></p></li></ol>"));
        s.node(new Schema.NodeSpec("betterbook:list_item", "li", CONTAINER, ""));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:quote",
                        "blockquote",
                        CONTAINER,
                        "<blockquote><p></p></blockquote>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:table",
                        "table",
                        CONTAINER,
                        "<table data-type='custom-table'"
                            + " data-with-header-row='true'><tbody><tr><th></th><th></th></tr><tr><td></td><td></td></tr></tbody></table>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:table_structure", "tbody,thead,tfoot,tr", CONTAINER, ""));
        s.node(new Schema.NodeSpec("betterbook:cell", "td,th", TEXT, ""));
        s.node(
                new Schema.NodeSpec(
                        LatexNode.ID,
                        LatexNode.SELECTOR,
                        ATOM,
                        new LatexNode("x^2", 16, "#393024", "center").element().outerHtml(),
                        e -> {},
                        e -> {},
                        LatexNode::validate));
        s.node(new Schema.NodeSpec(MermaidNode.ID, MermaidNode.SELECTOR, ATOM,
                new MermaidNode(MermaidNode.EXAMPLE, true).element().outerHtml(),
                e -> {}, e -> {}, MermaidNode::validate));
        s.node(new Schema.NodeSpec("betterbook:image", "img", ATOM, "<img src='' alt=''>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:item",
                        "div[data-type=item]",
                        ATOM,
                        "<div data-type='item' data-stack='{}'></div>"));
        s.node(new Schema.NodeSpec("betterbook:rule", "hr", ATOM, "<hr>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:entity",
                        "div[data-type=entity]",
                        ATOM,
                        "<div data-type='entity' data-entity-id='minecraft:villager'"
                                + " data-entity-nbt='{}'></div>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:structure",
                        "div[data-type=structure]",
                        ATOM,
                        "<div data-type='structure' data-structure-file=''"
                                + " data-structure-ortho='false'></div>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:recipe",
                        "div[data-type=recipe]",
                        ATOM,
                        "<div data-type='recipe' data-recipe-width='180'"
                                + " data-recipe-height='100'></div>"));
        s.node(
                new Schema.NodeSpec(
                        "betterbook:related_pages",
                        "div[data-type=related-pages]",
                        ATOM,
                        "<div data-type='related-pages'></div>"));
        mark(s, "bold", "strong,b", "strong");
        mark(s, "italic", "em,i", "em");
        mark(s, "strike", "s,del,strike", "s");
        mark(s, "superscript", "sup", "sup");
        mark(s, "subscript", "sub", "sub");
        mark(s, "code", "code", "code");
        mark(s, "link", "a[href]", "a");
        s.mark(
                new Schema.MarkSpec(
                        "betterbook:hidden",
                        "span[data-type=hidden-text],span.spoiler",
                        "span",
                        Map.of("data-type", "hidden-text")));
        mark(s, "command", "span[data-type=command]", "span");
        mark(s, "color", "span[style],span[data-type=textStyle]", "span");
    }

    private static void mark(Schema s, String id, String selector, String tag) {
        s.mark(new Schema.MarkSpec("betterbook:" + id, selector, tag, Map.of()));
    }
}
