package com.zhenshiz.betterbook.client;

import com.zhenshiz.betterbook.api.ExtensionContext;
import com.zhenshiz.betterbook.core.*;
import com.zhenshiz.betterbook.core.TextColor;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;

import org.jsoup.nodes.Element;

import java.util.*;

/** 一次布局同时生成绘制字形、命中位置和光标坐标。 */
public final class DocumentLayout {
    private static final float INLINE_CODE_GAP = 2;

    private static float recipeHeight(org.jsoup.nodes.Element element, float width) {
        try {
            int w = Integer.parseInt(element.attr("data-recipe-width")),
                    h = Integer.parseInt(element.attr("data-recipe-height"));
            return width * Math.clamp(h, 36, 512) / Math.clamp(w, 72, 512);
        } catch (NumberFormatException e) {
            return width * 100 / 180;
        }
    }

    private static float relatedHeight(org.jsoup.nodes.Element element, float width) {
        try {
            return com.zhenshiz.betterbook.data.BookRelatedPages.read(
                            element, com.lowdragmc.lowdraglib2.Platform.getFrozenRegistry())
                    .height(width);
        } catch (IllegalArgumentException e) {
            return 48;
        }
    }

    public record Glyph(
            int block,
            int from,
            int to,
            float x,
            float y,
            float width,
            float height,
            float scale,
            Component text,
            boolean hidden,
            boolean inlineCode,
            String href) {}

    public record Caret(BookSession.Position position, float x, float y, float height) {}

    public record Box(
            int block, float x, float y, float width, float height, String kind, String label) {}

    private record Fragment(List<Glyph> glyphs, List<Caret> carets, float height) {}

    private record Key(String html, String alignment, boolean checked, float width) {}

    private record Frame(
            Element element, int block, float x, float y, float width, String kind, String label) {
        Box close(float bottom) {
            return new Box(block, x, y, width, bottom - y, kind, label);
        }
    }

    public final List<Glyph> glyphs = new ArrayList<>();
    public final List<Caret> carets = new ArrayList<>();
    public final List<Box> boxes = new ArrayList<>();
    private final Map<Key, Fragment> cache =
            new LinkedHashMap<>(128, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, Fragment> e) {
                    return size() > 256;
                }
            };
    public float height;
    private ExtensionContext extensions;

    public void build(
            RichDocument doc,
            float width,
            boolean editable,
            ImageCache images,
            ExtensionContext extensions) {
        build(doc, width, editable, images, extensions, Set.of());
    }

    public void build(
            RichDocument doc,
            float width,
            boolean editable,
            ImageCache images,
            ExtensionContext extensions,
            Set<Integer> collapsedCode) {
        this.extensions = extensions;
        glyphs.clear();
        carets.clear();
        boxes.clear();
        var frames = new ArrayList<Frame>();
        var backgrounds = new ArrayList<Box>();
        float y = 6;
        var blocks = doc.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            var b = blocks.get(i);
            var el = b.element();
            boolean visible = true;
            for (var parent = el.parent(); parent != null; parent = parent.parent())
                if (parent.is("div[data-type=step-item]")
                        && parent.elementSiblingIndex() != activeStep(parent.parent()))
                    visible = false;
            if (!visible) continue;
            var ancestors = new ArrayList<Element>();
            for (var parent = el.parent();
                    parent != null && parent != doc.body();
                    parent = parent.parent()) ancestors.addFirst(parent);
            var containers = ancestors.stream().filter(p -> frameKind(p) != null).toList();
            int shared = 0;
            while (shared < frames.size()
                    && shared < containers.size()
                    && frames.get(shared).element() == containers.get(shared)) shared++;
            while (frames.size() > shared) {
                backgrounds.add(frames.removeLast().close(y));
                y += 7;
            }
            float parentIndent = 0, rightInset = 0;
            int frameIndex = 0;
            for (var parent : ancestors) {
                String kind = frameKind(parent);
                if (kind != null) {
                    if (frameIndex >= shared) {
                        var frame =
                                new Frame(
                                        parent,
                                        i,
                                        6 + parentIndent,
                                        y,
                                        Math.max(20, width - 12 - parentIndent - rightInset),
                                        kind,
                                        parent.attr("data-admo-type"));
                        frames.add(frame);
                        y += kind.equals("steps") ? stepNavigation(frame, blocks, editable) : 7;
                    }
                    frameIndex++;
                    parentIndent += kind.equals("quote") ? 12 : 9;
                    rightInset += 8;
                } else if (parent.is("ul,ol")) parentIndent += 18;
            }
            var table = BookSession.ancestor(el, "table");
            if (table != null) {
                float tableY = y;
                if (editable) y += 18;
                int last = i;
                while (last + 1 < blocks.size()
                        && BookSession.ancestor(blocks.get(last + 1).element(), "table") == table)
                    last++;
                int cursor = i;
                while (cursor <= last) {
                    var row = BookSession.ancestor(blocks.get(cursor).element(), "tr");
                    int rowEnd = cursor;
                    while (rowEnd + 1 <= last
                            && BookSession.ancestor(blocks.get(rowEnd + 1).element(), "tr") == row)
                        rowEnd++;
                    int cols = rowEnd - cursor + 1;
                    float cw = Math.max(12, (width - 12 - parentIndent - rightInset) / cols);
                    float rh = 18;
                    for (int j = cursor; j <= rowEnd; j++) {
                        float x = 6 + parentIndent + (j - cursor) * cw;
                        float h = place(blocks.get(j), j, x + 3, y + 3, cw - 6);
                        rh = Math.max(rh, h + 6);
                    }
                    for (int j = cursor; j <= rowEnd; j++)
                        boxes.add(
                                new Box(
                                        j,
                                        6 + parentIndent + (j - cursor) * cw,
                                        y,
                                        cw,
                                        rh,
                                        "cell",
                                        ""));
                    y += rh;
                    cursor = rowEnd + 1;
                }
                boxes.add(
                        new Box(
                                i,
                                6 + parentIndent,
                                tableY,
                                width - 12 - parentIndent - rightInset,
                                y - tableY,
                                "table-area",
                                ""));
                if (editable) {
                    float toolsWidth = Math.min(150, width - 12 - parentIndent - rightInset);
                    boxes.add(
                            new Box(
                                    i,
                                    width - 6 - rightInset - toolsWidth,
                                    tableY,
                                    toolsWidth,
                                    18,
                                    "table-tools",
                                    ""));
                }
                y += 7;
                i = last;
                continue;
            }
            int listDepth = (int) ancestors.stream().filter(p -> p.is("ul,ol")).count();
            float x = 6 + parentIndent, w = Math.max(20, width - 12 - parentIndent - rightInset);
            var titleFrame =
                    el.is("div[data-type=admonition-title]")
                                    && !frames.isEmpty()
                                    && frames.getLast().element() == el.parent()
                            ? frames.getLast()
                            : null;
            if (titleFrame != null && titleFrame.kind().equals("admonition")) {
                x += 15;
                w = Math.max(8, w - 15);
            }
            var li = BookSession.ancestor(el, "li");
            if (li != null && li.children().getFirst() == el) {
                String label =
                        li.hasAttr("data-checked")
                                ? (li.attr("data-checked").equals("true") ? "[x]" : "[ ]")
                                : li.parent().is("ol")
                                        ? (li.elementSiblingIndex() + 1) + "."
                                        : listDepth == 1 ? "•" : listDepth == 2 ? "◦" : "▪";
                boxes.add(
                        new Box(
                                i,
                                x - 18,
                                li.hasAttr("data-checked") ? y + (fontSize(el) - 12) / 2 : y,
                                18,
                                12,
                                li.hasAttr("data-checked") ? "task" : "bullet",
                                label));
            }
            if (b.atom()) {
                float h =
                        el.is(MermaidNode.SELECTOR)
                                ? MermaidRenderer.height(el, w, editable)
                                : el.is(LatexNode.SELECTOR)
                                ? LatexRenderer.height(el, w)
                                : el.is("hr")
                                ? 12
                                : el.is("img")
                                        ? images.height(el, w)
                                        : el.is("div[data-type=entity]")
                                                ? 160
                                                : el.is("div[data-type=structure]")
                                                        ? 180
                                                        : el.is("div[data-type=recipe]")
                                                                ? recipeHeight(el, w)
                                                                : el.is(
                                                                                "div[data-type=related-pages]")
                                                                        ? relatedHeight(el, w)
                                                                        : 32;
                float iw = el.is("img") ? Math.min(w, images.width(el, w)) : w;
                boxes.add(
                        new Box(
                                i,
                                x,
                                y,
                                iw,
                                h,
                                el.is("img") ? "image" : el.is("hr") ? "rule" : "unknown",
                                el.tagName()));
                carets.add(new Caret(new BookSession.Position(i, 0), x, y, h));
                carets.add(new Caret(new BookSession.Position(i, 1), x + iw, y, h));
                y += h + 8;
                if (el.is("img") && !el.attr("alt").isBlank()) {
                    float captionHeight =
                            Minecraft.getInstance()
                                            .font
                                            .split(Component.literal(el.attr("alt")), (int) w)
                                            .size()
                                    * 11;
                    boxes.add(new Box(i, x, y, w, captionHeight, "caption", el.attr("alt")));
                    y += captionHeight + 4;
                }
                continue;
            }
            if (b.code()) {
                boolean collapsed = collapsedCode.contains(i);
                float top = y;
                int lines = b.text().split("\n", -1).length;
                float gutter =
                        Math.max(
                                16,
                                Minecraft.getInstance().font.width(Integer.toString(lines)) + 9);
                float h =
                        collapsed
                                ? 0
                                : place(b, i, x + gutter + 4, y + 26, Math.max(8, w - gutter - 9));
                boxes.add(new Box(i, x, top, w, collapsed ? 21 : h + 31, "code", ""));
                boxes.add(new Box(i, x, top, w, 21, "code-header", b.element().attr("language")));
                boxes.add(new Box(i, x + w - 39, top + 1, 19, 19, "code-copy", ""));
                boxes.add(
                        new Box(
                                i,
                                x + w - 20,
                                top + 1,
                                19,
                                19,
                                "code-fold",
                                collapsed ? ">" : "v"));
                if (collapsed)
                    carets.add(new Caret(new BookSession.Position(i, 0), x + 4, top + 4, 13));
                else {
                    int offset = 0, number = 1;
                    for (String line : b.text().split("\n", -1)) {
                        var caret = caret(new BookSession.Position(i, offset));
                        boxes.add(
                                new Box(
                                        i,
                                        x + 2,
                                        caret.y(),
                                        gutter - 4,
                                        13,
                                        "code-line",
                                        Integer.toString(number++)));
                        offset += line.length() + 1;
                    }
                }
                y += (collapsed ? 21 : h + 31) + 7;
                continue;
            }
            float h = place(b, i, x, y, w);
            if (titleFrame != null) {
                String kind =
                        titleFrame.kind().equals("admonition")
                                ? "admonition-header"
                                : "step-header";
                boxes.add(
                        new Box(
                                i,
                                titleFrame.x() + 1,
                                titleFrame.y() + 1,
                                titleFrame.width() - 2,
                                y + h + 3 - titleFrame.y(),
                                kind,
                                titleFrame.label()));
                if (kind.equals("admonition-header"))
                    boxes.add(
                            new Box(
                                    i,
                                    titleFrame.x() + 8,
                                    y,
                                    10,
                                    10,
                                    "admonition-icon",
                                    titleFrame.label()));
                y += 5;
            }
            y += h + 7;
        }
        while (!frames.isEmpty()) {
            backgrounds.add(frames.removeLast().close(y));
            y += 7;
        }
        // 外层容器先画，避免覆盖嵌套卡片、表格和正文装饰。
        backgrounds.sort(Comparator.comparing(Box::width).reversed());
        boxes.addAll(0, backgrounds);
        height = y + 6;
    }

    private static String frameKind(Element element) {
        if (element.is("blockquote")) return "quote";
        if (element.is("div[data-type=admonition]")) return "admonition";
        if (element.is("div[data-type=steps]")) return "steps";
        if (element.is("div[data-type=step-item]")) return "step-card";
        return null;
    }

    private static int activeStep(Element steps) {
        return Math.clamp(
                integer(steps.attr("currentstep"), 0), 0, Math.max(0, steps.childrenSize() - 1));
    }

    private float stepNavigation(Frame frame, List<RichDocument.Block> blocks, boolean editable) {
        var items = frame.element().children();
        float inset = Math.min(9, (frame.width() - 18) / 2);
        float inner = frame.width() - inset * 2;
        boolean separateActions = editable && inner < 70;
        boolean stackedActions = editable && inner < 44;
        float navigationWidth = editable && !separateActions ? inner - 52 : inner;
        float navigationTop = frame.y() + 8 + (separateActions ? stackedActions ? 50 : 25 : 0);
        int columns = Math.max(1, Math.min(items.size(), (int) (navigationWidth / 26)));
        float gap = columns == 1 ? 0 : (navigationWidth - 18) / (columns - 1);
        if (editable) {
            float right = frame.x() + frame.width() - inset;
            boxes.add(
                    new Box(
                            frame.block(),
                            right - (stackedActions ? 18 : 44),
                            frame.y() + 8,
                            18,
                            18,
                            "step-add",
                            "+"));
            if (items.size() > 1)
                boxes.add(
                        new Box(
                                frame.block(),
                                right - 18,
                                frame.y() + 8 + (stackedActions ? 25 : 0),
                                18,
                                18,
                                "step-remove",
                                "−"));
        }
        for (int index = 0; index < items.size(); index++) {
            int col = index % columns, row = index / columns;
            float x = frame.x() + inset + col * gap, y = navigationTop + row * 25;
            var item = items.get(index);
            int block = frame.block();
            for (int b = 0; b < blocks.size(); b++)
                if (BookSession.ancestor(blocks.get(b).element(), "div[data-type=step-item]")
                        == item) {
                    block = b;
                    break;
                }
            if (col > 0)
                boxes.add(
                        new Box(
                                block,
                                x - gap + 21,
                                y + 8,
                                Math.max(0, gap - 24),
                                1,
                                "step-connector",
                                ""));
            boxes.add(
                    new Box(
                            block,
                            x,
                            y,
                            18,
                            18,
                            index == activeStep(frame.element()) ? "step-active" : "step-tab",
                            Integer.toString(index + 1)));
        }
        return navigationTop
                - frame.y()
                + (float) Math.ceil((double) items.size() / columns) * 25
                + 4;
    }

    private float place(RichDocument.Block b, int index, float x, float y, float width) {
        var task = BookSession.ancestor(b.element(), "li");
        var key =
                new Key(
                        b.element().outerHtml(),
                        alignment(b.element()),
                        task != null && task.attr("data-checked").equals("true"),
                        width);
        var f = cache.computeIfAbsent(key, k -> fragment(b, width));
        for (var g : f.glyphs)
            glyphs.add(
                    new Glyph(
                            index,
                            g.from,
                            g.to,
                            g.x + x,
                            g.y + y,
                            g.width,
                            g.height,
                            g.scale,
                            g.text,
                            g.hidden,
                            g.inlineCode,
                            g.href));
        for (var c : f.carets)
            carets.add(
                    new Caret(
                            new BookSession.Position(index, c.position.offset()),
                            c.x + x,
                            c.y + y,
                            c.height));
        return f.height;
    }

    private static float fontSize(Element element) {
        float size =
                switch (element.normalName()) {
                    case "h1" -> 18;
                    case "h2" -> 16;
                    case "h3" -> 14;
                    case "h4" -> 12;
                    case "h5" -> 11;
                    default -> 9;
                };
        return element.is("div[data-type=admonition-title],th") ? 10 : size;
    }

    private Fragment fragment(RichDocument.Block b, float width) {
        var out = new ArrayList<Glyph>();
        var measured = new ArrayList<Glyph>();
        var points = new ArrayList<Caret>();
        String tag = b.element().normalName();
        float size = fontSize(b.element());
        float lineHeight = size + 4, x = 0, y = 0;
        int offset = 0;
        var font = Minecraft.getInstance().font;
        points.add(new Caret(new BookSession.Position(0, 0), 0, 0, lineHeight));
        Style[] syntax = b.code() ? codeStyles(b) : null;
        for (var run : b.runs()) {
            var task = BookSession.ancestor(b.element(), "li");
            var style =
                    task != null && task.attr("data-checked").equals("true")
                            ? Style.EMPTY.withStrikethrough(true).withColor(0x84745c)
                            : Style.EMPTY;
            boolean hidden = false, inlineCode = false;
            String href = "";
            float scale = size / 9f, shift = 0;
            if (tag.matches("h[1-6]") || b.element().is("th,div[data-type=admonition-title]"))
                style = style.withBold(true);
            for (var mark : run.marks()) {
                switch (mark.id()) {
                    case "betterbook:bold" -> style = style.withBold(true);
                    case "betterbook:italic" -> style = style.withItalic(true);
                    case "betterbook:strike" -> style = style.withStrikethrough(true);
                    case "betterbook:superscript" -> {
                        scale *= .75f;
                        shift = -2;
                    }
                    case "betterbook:subscript" -> {
                        scale *= .75f;
                        shift = 4;
                    }
                    case "betterbook:hidden" -> hidden = true;
                    case "betterbook:link" -> {
                        href = mark.attributes().getOrDefault("href", "");
                        style = style.withUnderlined(true);
                    }
                    case "betterbook:code" -> inlineCode = true;
                    case TextCommand.MARK -> {
                        var command = TextCommand.read(mark.attributes());
                        if (command.isPresent())
                            style =
                                    style.withUnderlined(command.get().underline())
                                            .withClickEvent(
                                                    new ClickEvent(
                                                            ClickEvent.Action.RUN_COMMAND,
                                                            command.get().id()));
                    }
                    case "betterbook:color" -> {
                        var color = TextColor.read(mark.attributes().getOrDefault("style", ""));
                        if (color != null) style = style.withColor(color);
                    }
                    default -> {}
                }
                var custom = extensions.markStyles.get(mark.id());
                if (custom != null) style = custom.apply(mark, style);
            }
            for (int p = 0; p < run.text().length(); ) {
                int next = p + Character.charCount(run.text().codePointAt(p));
                String ch = run.text().substring(p, next);
                var component =
                        Component.literal(ch.equals("\uFFFC") ? "□" : ch.equals("\t") ? "    " : ch)
                                .setStyle(
                                        syntax != null
                                                        && offset < syntax.length
                                                        && syntax[offset] != null
                                                ? syntax[offset]
                                                : style);
                float w = ch.equals("\n") ? 0 : font.width(component) * scale;
                measured.add(
                        new Glyph(
                                0,
                                offset,
                                offset + next - p,
                                0,
                                shift,
                                w,
                                lineHeight,
                                scale,
                                component,
                                hidden,
                                inlineCode,
                                href));
                offset += next - p;
                p = next;
            }
        }
        Map<Integer, Float> wordWidths =
                b.code() ? Map.of() : lineSegmentWidths(b.text(), measured);
        for (int i = 0; i < measured.size(); i++) {
            var glyph = measured.get(i);
            if (glyph.text().getString().equals("\n")) {
                x = 0;
                y += lineHeight;
                points.add(new Caret(new BookSession.Position(0, glyph.to()), x, y, lineHeight));
                continue;
            }
            float advance = glyph.width();
            float before = inlineCodeGap(measured, i, -1);
            float after = inlineCodeGap(measured, i, 1);
            if (!b.code()
                    && x > 0
                    && x + advance > width
                    && Character.isWhitespace(glyph.text().getString().codePointAt(0))) advance = 0;
            float segment = wordWidths.getOrDefault(glyph.from(), 0f);
            boolean wrapWord = !b.code() && segment <= width && x + segment > width;
            // 每行代码片段为两端背景留位，格式变化不会在片段内部插入间距。
            float rightGap = glyph.inlineCode() ? INLINE_CODE_GAP : 0;
            if (x > 0 && (wrapWord || x + before + advance + rightGap > width)) {
                x = 0;
                y += lineHeight;
                if (glyph.inlineCode()) before = INLINE_CODE_GAP;
                points.add(new Caret(new BookSession.Position(0, glyph.from()), x, y, lineHeight));
            }
            x += before;
            if (before > 0)
                points.add(new Caret(new BookSession.Position(0, glyph.from()), x, y, lineHeight));
            out.add(
                    new Glyph(
                            0,
                            glyph.from(),
                            glyph.to(),
                            x,
                            y + glyph.y(),
                            advance,
                            lineHeight,
                            glyph.scale(),
                            glyph.text(),
                            glyph.hidden(),
                            glyph.inlineCode(),
                            glyph.href()));
            x += advance + after;
            points.add(new Caret(new BookSession.Position(0, glyph.to()), x, y, lineHeight));
        }
        String align = alignment(b.element());
        if (align.contains("center") || align.contains("right")) {
            var widths = new HashMap<Float, Float>();
            for (var p : points) widths.merge(p.y, p.x, Math::max);
            float factor = align.contains("center") ? .5f : 1;
            for (int i = 0; i < points.size(); i++) {
                var p = points.get(i);
                points.set(
                        i,
                        new Caret(
                                p.position,
                                p.x + Math.max(0, width - widths.get(p.y)) * factor,
                                p.y,
                                p.height));
            }
            for (int i = 0; i < out.size(); i++) {
                var g = out.get(i);
                float row = (float) Math.floor((g.y + 2) / lineHeight) * lineHeight;
                float shift = Math.max(0, width - widths.getOrDefault(row, width)) * factor;
                out.set(
                        i,
                        new Glyph(
                                g.block,
                                g.from,
                                g.to,
                                g.x + shift,
                                g.y,
                                g.width,
                                g.height,
                                g.scale,
                                g.text,
                                g.hidden,
                                g.inlineCode,
                                g.href));
            }
        }
        return new Fragment(out, points, y + lineHeight);
    }

    private static Map<Integer, Float> lineSegmentWidths(String text, List<Glyph> glyphs) {
        float[] prefix = new float[text.length() + 1];
        for (int i = 0; i < glyphs.size(); i++) {
            var glyph = glyphs.get(i);
            for (int offset = glyph.from() + 1; offset < glyph.to(); offset++)
                prefix[offset] = prefix[glyph.from()];
            prefix[glyph.to()] =
                    prefix[glyph.from()]
                            + glyph.width()
                            + inlineCodeGap(glyphs, i, -1)
                            + inlineCodeGap(glyphs, i, 1);
        }
        var iterator = java.text.BreakIterator.getLineInstance(Locale.ROOT);
        iterator.setText(text);
        var widths = new HashMap<Integer, Float>();
        for (int start = iterator.first(), end = iterator.next();
                end != java.text.BreakIterator.DONE;
                start = end, end = iterator.next()) {
            // 末尾空白保留可编辑坐标，但不用于判断整个单词能否放进当前行。
            int visibleEnd = end;
            while (visibleEnd > start && Character.isWhitespace(text.codePointBefore(visibleEnd)))
                visibleEnd -= Character.charCount(text.codePointBefore(visibleEnd));
            widths.put(start, prefix[visibleEnd] - prefix[start]);
        }
        return widths;
    }

    private static float inlineCodeGap(List<Glyph> glyphs, int index, int direction) {
        var glyph = glyphs.get(index);
        if (!glyph.inlineCode() || glyph.text().getString().equals("\n")) return 0;
        int neighbor = index + direction;
        return neighbor < 0
                        || neighbor >= glyphs.size()
                        || !glyphs.get(neighbor).inlineCode()
                        || glyphs.get(neighbor).text().getString().equals("\n")
                ? INLINE_CODE_GAP
                : 0;
    }

    private static String alignment(Element element) {
        for (var el = element; el != null; el = el.parent())
            if (el.attr("style").contains("text-align")) return el.attr("style");
        return "";
    }

    private static Style[] codeStyles(RichDocument.Block block) {
        int[] colors = CodeHighlight.colors(block.text(), block.element().attr("language"), true);
        return Arrays.stream(colors)
                .mapToObj(
                        color ->
                                Style.EMPTY
                                        .withColor(color)
                                        .withFont(
                                                net.minecraft.resources.ResourceLocation
                                                        .withDefaultNamespace("uniform")))
                .toArray(Style[]::new);
    }

    public Caret caret(BookSession.Position position) {
        Caret found = carets.isEmpty() ? new Caret(position, 6, 6, 13) : carets.getFirst();
        for (var c : carets) if (c.position.equals(position)) found = c;
        return found;
    }

    public BookSession.Position hit(float x, float y) {
        Caret best =
                carets.isEmpty()
                        ? new Caret(new BookSession.Position(0, 0), 0, 0, 13)
                        : carets.getFirst();
        double score = Double.MAX_VALUE;
        for (var c : carets) {
            float dy = y < c.y ? c.y - y : y > c.y + c.height ? y - c.y - c.height : 0;
            double d = dy * 1000 + Math.abs(c.x - x);
            if (d < score) {
                score = d;
                best = c;
            }
        }
        return best.position;
    }

    public static int integer(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
