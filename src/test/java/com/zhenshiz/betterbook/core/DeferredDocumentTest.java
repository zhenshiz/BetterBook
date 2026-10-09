package com.zhenshiz.betterbook.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeferredDocumentTest {
    @Test
    void aLongBookLocalizesWithoutParsingUnvisitedPagesOrOtherLanguages() {
        var parses = new AtomicInteger();
        var schema = new Schema();
        schema.node(new Schema.NodeSpec("test:text", "p", Schema.Kind.TEXT, "",
                element -> parses.incrementAndGet(), element -> {}, element -> {}));
        var book = new Book();
        for (int i = 0; i < 66; i++)
            book.pages.add(new Book.Page("p" + i, "Page " + i, RichDocument.deferred("<p>English</p>", schema)));
        book.addLanguage("zh_cn");
        book.putPage(0, "zh_cn", new Book.Page("p0", "第一页", RichDocument.deferred("<p>中文</p>", schema)));
        var reader = book.localized("zh_cn");
        assertEquals(0, parses.get());
        assertEquals("中文", reader.pages.getFirst().document().blocks().getFirst().text());
        assertEquals(1, parses.get());
        assertEquals("English", reader.pages.getLast().document().blocks().getFirst().text());
        assertEquals(2, parses.get());
        reader.pages.getLast().document().blocks();
        assertEquals(2, parses.get(), "Revisiting a page must reuse its parsed DOM");
        assertEquals("English", book.pages.getFirst().document().body().text());
        assertEquals(3, parses.get(), "Readers must not materialize or mutate the source book");
    }

    @Test
    void deferredCopiesAndStepsStayIndependentBeforeAndAfterMaterialization() {
        String html = "<div data-type='steps' currentstep='1'>"
                + "<div data-type='step-item'><p>One</p></div>"
                + "<div data-type='step-item'><p>Two</p></div></div>";
        var source = RichDocument.deferred(html, StandardSchema.create());
        var first = source.readingCopy();
        var copy = first.copy();
        assertEquals("0", copy.body().selectFirst("div[data-type=steps]").attr("currentstep"));
        first.body().selectFirst("div[data-type=steps]").attr("currentstep", "1");
        assertEquals("0", copy.body().selectFirst("div[data-type=steps]").attr("currentstep"));
        assertEquals("1", source.body().selectFirst("div[data-type=steps]").attr("currentstep"));
        assertEquals("0", source.readingCopy().body().selectFirst("div[data-type=steps]").attr("currentstep"));
        var edited = source.copy();
        edited.body().empty().appendElement("p").text("New text");
        edited.invalidate();
        assertTrue(source.html().contains("Two"));
        assertEquals("New text", edited.blocks().getFirst().text());
    }

    @Test
    void malformedDeferredContentStillFailsValidationOnUse() {
        var document = RichDocument.deferred("<table><tr><td>A</td></tr><tr><td>B</td><td>C</td></tr></table>",
                StandardSchema.create());
        assertThrows(IllegalArgumentException.class, document::body);
        assertThrows(IllegalArgumentException.class, document::html);
    }

    @Test
    void matchingReflectsAttributeAndAncestorChangesAndKeepsRegistrationOrder() {
        var schema = new Schema();
        schema.node(new Schema.NodeSpec("test:special", "p[data-special=yes]", Schema.Kind.TEXT, ""));
        schema.node(new Schema.NodeSpec("test:child", "div.active > p:not([hidden])", Schema.Kind.TEXT, ""));
        schema.node(new Schema.NodeSpec("test:fallback", "p", Schema.Kind.TEXT, ""));
        var parent = new org.jsoup.nodes.Element("div").addClass("active");
        var child = parent.appendElement("p");
        assertEquals("test:child", schema.node(child).id());
        child.attr("hidden", "");
        assertEquals("test:fallback", schema.node(child).id());
        child.removeAttr("hidden");
        parent.removeClass("active");
        assertEquals("test:fallback", schema.node(child).id());
        child.attr("data-special", "yes");
        assertEquals("test:special", schema.node(child).id());
        child.removeAttr("data-special");
        assertEquals("test:fallback", schema.node(child).id());
    }
}
