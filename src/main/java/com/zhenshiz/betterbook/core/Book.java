package com.zhenshiz.betterbook.core;

import java.util.*;

/** 与客户端渲染无关的书籍内容。页面 ID 和顺序共用，译文按语言分别保存。 */
public final class Book {
    public record Page(String id, String title, RichDocument document) {
        public Page withDocument(RichDocument value) {
            return new Page(id, title, value);
        }

        public Page withTitle(String value) {
            return new Page(id, value, document);
        }
    }

    /** 一种语言的书名、作者和页面译文；未提供的页面使用默认语言。 */
    public record Translation(String title, String author, Map<String, Page> pages) {
        public Translation {
            pages = Collections.unmodifiableMap(new LinkedHashMap<>(pages));
        }
    }

    public String id = UUID.randomUUID().toString();
    public String defaultLanguage = "en_us";
    public String title = "";
    public String author = "";
    public final List<Page> pages = new ArrayList<>();
    public final Map<String, Translation> translations = new LinkedHashMap<>();

    public static Book empty(Schema schema) {
        var b = new Book();
        b.pages.add(
                new Page(UUID.randomUUID().toString(), "1", RichDocument.parse("<p></p>", schema)));
        return b;
    }

    /**
     * 将 Minecraft 语言代码规范化为小写下划线形式。
     *
     * @param value 语言代码，允许首尾空格及连字符
     * @return 规范化的语言代码
     * @throws IllegalArgumentException 代码为空、过长或包含无效字符时抛出
     */
    public static String normalizeLanguage(String value) {
        String normalized = value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        if (normalized.length() > 35 || !normalized.matches("[a-z0-9]{2,8}(?:_[a-z0-9]{2,8})*"))
            throw new IllegalArgumentException("Invalid language code: " + value);
        return normalized;
    }

    public Set<String> languages() {
        var result = new LinkedHashSet<String>();
        result.add(defaultLanguage);
        result.addAll(translations.keySet());
        return Collections.unmodifiableSet(result);
    }

    public String title(String language) {
        var translation = translations.get(language);
        return translation == null ? title : translation.title();
    }

    public String author(String language) {
        var translation = translations.get(language);
        return translation == null ? author : translation.author();
    }

    /**
     * 获取指定语言的页面，缺少译文时使用默认正文。
     *
     * @param index 共用顺序中的页面索引
     * @param language 已规范化的语言代码
     * @return 解析回退规则后的页面；修改文档须经由编辑事务
     * @throws IndexOutOfBoundsException 页面索引超出范围时抛出
     */
    public Page page(int index, String language) {
        var original = pages.get(index);
        var translation = translations.get(language);
        return translation == null
                ? original
                : translation.pages().getOrDefault(original.id(), original);
    }

    public boolean hasTranslation(int index, String language) {
        return language.equals(defaultLanguage)
                || translations.containsKey(language)
                        && translations.get(language).pages().containsKey(pages.get(index).id());
    }

    /**
     * 写入指定语言的页面，保持所有语言共用的页面 ID 和排序。
     *
     * @param index 共用顺序中的页面索引
     * @param language 已存在的规范化语言代码
     * @param value 替换内容，其 ID 必须与该索引的页面一致
     * @throws IllegalArgumentException 页面 ID 不一致时抛出
     * @throws IndexOutOfBoundsException 页面索引超出范围时抛出
     */
    public void putPage(int index, String language, Page value) {
        if (!pages.get(index).id().equals(value.id()))
            throw new IllegalArgumentException("Page ID changed");
        if (language.equals(defaultLanguage)) {
            pages.set(index, value);
        } else {
            var translation =
                    Objects.requireNonNull(
                            translations.get(language), "Unknown language: " + language);
            var entries = new LinkedHashMap<>(translation.pages());
            entries.put(value.id(), value);
            translations.put(
                    language, new Translation(translation.title(), translation.author(), entries));
        }
    }

    public void rename(String language, String title, String author) {
        if (language.equals(defaultLanguage)) {
            this.title = title;
            this.author = author;
        } else {
            var translation =
                    Objects.requireNonNull(
                            translations.get(language), "Unknown language: " + language);
            translations.put(language, new Translation(title, author, translation.pages()));
        }
    }

    /**
     * 从默认内容创建译文，文档由编辑会话在修改时复制。
     *
     * @param value 新语言代码
     * @throws IllegalArgumentException 代码无效或语言已存在时抛出
     */
    public void addLanguage(String value) {
        String language = normalizeLanguage(value);
        if (languages().contains(language))
            throw new IllegalArgumentException("Language already exists: " + language);
        var entries = new LinkedHashMap<String, Page>();
        pages.forEach(page -> entries.put(page.id(), page));
        translations.put(language, new Translation(title, author, entries));
    }

    /**
     * 切换缺少译文时的默认语言，并保留切换前每种语言的完整内容。
     *
     * @param language 已存在的规范化语言代码
     * @throws IllegalArgumentException 语言不存在时抛出
     */
    public void setDefaultLanguage(String language) {
        if (!languages().contains(language))
            throw new IllegalArgumentException("Unknown language: " + language);
        if (defaultLanguage.equals(language)) return;
        var complete = new LinkedHashMap<String, Translation>();
        for (String locale : languages()) {
            var entries = new LinkedHashMap<String, Page>();
            for (int i = 0; i < pages.size(); i++) {
                var p = page(i, locale);
                entries.put(p.id(), p);
            }
            complete.put(locale, new Translation(title(locale), author(locale), entries));
        }
        var selected = complete.remove(language);
        title = selected.title();
        author = selected.author();
        for (int i = 0; i < pages.size(); i++)
            pages.set(i, selected.pages().get(pages.get(i).id()));
        defaultLanguage = language;
        translations.clear();
        translations.putAll(complete);
    }

    public void removePage(int index) {
        String pageId = pages.remove(index).id();
        translations.replaceAll(
                (locale, translation) -> {
                    var entries = new LinkedHashMap<>(translation.pages());
                    entries.remove(pageId);
                    return new Translation(translation.title(), translation.author(), entries);
                });
    }

    public void duplicatePage(int index) {
        var source = pages.get(index);
        String newId = UUID.randomUUID().toString();
        pages.add(index + 1, new Page(newId, source.title(), source.document()));
        translations.replaceAll(
                (locale, translation) -> {
                    var entries = new LinkedHashMap<>(translation.pages());
                    var p = entries.get(source.id());
                    if (p != null) entries.put(newId, new Page(newId, p.title(), p.document()));
                    return new Translation(translation.title(), translation.author(), entries);
                });
    }

    boolean containsDocument(RichDocument document) {
        return pages.stream().anyMatch(p -> p.document() == document)
                || translations.values().stream()
                        .flatMap(t -> t.pages().values().stream())
                        .anyMatch(p -> p.document() == document);
    }

    /**
     * 创建指定语言的独立阅读副本。
     *
     * @param language 已规范化的语言代码；不存在时使用默认语言
     * @return 只含已解析正文的书籍副本，文档修改不会影响作者内容
     */
    public Book localized(String language) {
        var b = new Book();
        b.id = id;
        b.defaultLanguage = languages().contains(language) ? language : defaultLanguage;
        b.title = title(language);
        b.author = author(language);
        for (int i = 0; i < pages.size(); i++) {
            var p = page(i, language);
            var document = p.document().copy();
            // 作者保存的步骤选中位置不作为读者的初始进度。
            document.body().select("div[data-type=steps]").attr("currentstep", "0");
            b.pages.add(p.withDocument(document));
        }
        return b;
    }

    public Book copy() {
        var b = new Book();
        b.id = id;
        b.defaultLanguage = defaultLanguage;
        b.title = title;
        b.author = author;
        b.pages.addAll(pages);
        b.translations.putAll(translations);
        return b;
    }
}
