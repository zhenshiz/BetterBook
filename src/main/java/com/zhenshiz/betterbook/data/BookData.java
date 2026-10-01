package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.zhenshiz.betterbook.core.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 单一运行时文件；共用页面顺序，所有语言对称保存，HTML 使用 UTF-8 字节数组。 */
public final class BookData implements IPersistedSerializable {
    public static final int FORMAT_VERSION = 2;
    @Persisted public int formatVersion = FORMAT_VERSION;
    @Persisted public String id = "";
    @Persisted public String defaultLanguage = "";
    @Persisted public List<String> pageOrder = new ArrayList<>();
    @Persisted public List<LanguageData> languages = new ArrayList<>();

    public static final class LanguageData implements IPersistedSerializable {
        @Persisted public String language = "";
        @Persisted public String title = "";
        @Persisted public String author = "";
        @Persisted public List<PageData> pages = new ArrayList<>();
    }

    public static final class PageData implements IPersistedSerializable {
        @Persisted public String id = "";
        @Persisted public String title = "";
        @Persisted public byte[] html = new byte[0];
    }

    public static BookData from(Book book) {
        var data = new BookData();
        data.id = book.id;
        data.defaultLanguage = book.defaultLanguage;
        book.pages.forEach(p -> data.pageOrder.add(p.id()));
        for (String language : book.languages()) {
            var entry = new LanguageData();
            entry.language = language;
            entry.title = book.title(language);
            entry.author = book.author(language);
            for (int i = 0; i < book.pages.size(); i++)
                if (book.hasTranslation(i, language))
                    entry.pages.add(encodePage(book.page(i, language)));
            data.languages.add(entry);
        }
        return data;
    }

    private static PageData encodePage(Book.Page p) {
        var entry = new PageData();
        entry.id = p.id();
        entry.title = p.title();
        entry.html = p.document().html().getBytes(StandardCharsets.UTF_8);
        return entry;
    }

    public Book toBook(Schema schema) {
        if (formatVersion != FORMAT_VERSION)
            throw new IllegalArgumentException("Unsupported book version: " + formatVersion);
        if (pageOrder.isEmpty()) throw new IllegalArgumentException("Book contains no pages");
        var book = new Book();
        book.id = id;
        book.defaultLanguage = Book.normalizeLanguage(defaultLanguage);
        var ids = new HashSet<String>();
        for (String pageId : pageOrder)
            if (pageId.isBlank() || !ids.add(pageId))
                throw new IllegalArgumentException("Invalid or duplicate page ID");
        var contents = new LinkedHashMap<String, Book.Translation>();
        for (var entry : languages) {
            String language = Book.normalizeLanguage(entry.language);
            if (contents.containsKey(language))
                throw new IllegalArgumentException("Duplicate language: " + language);
            var pages = new LinkedHashMap<String, Book.Page>();
            for (var p : entry.pages) {
                if (!ids.contains(p.id) || pages.containsKey(p.id))
                    throw new IllegalArgumentException("Invalid translated page ID: " + p.id);
                pages.put(
                        p.id,
                        new Book.Page(
                                p.id,
                                p.title,
                                RichDocument.parse(
                                        new String(p.html, StandardCharsets.UTF_8), schema)));
            }
            contents.put(language, new Book.Translation(entry.title, entry.author, pages));
        }
        var primary = contents.remove(book.defaultLanguage);
        if (primary == null || primary.pages().size() != pageOrder.size())
            throw new IllegalArgumentException("Default language must contain every page");
        book.title = primary.title();
        book.author = primary.author();
        for (String pageId : pageOrder) book.pages.add(primary.pages().get(pageId));
        book.translations.putAll(contents);
        return book;
    }
}
