package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.zhenshiz.betterbook.core.*;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 单一运行时文件；共用页面顺序，所有语言对称保存，HTML 使用 UTF-8 字节数组。 */
public final class BookData implements IPersistedSerializable {
    public static final int FORMAT_VERSION = 3;
    @Persisted public int formatVersion = FORMAT_VERSION;
    @Persisted public String id = "";
    @Persisted public String defaultLanguage = "";
    @Persisted public boolean followGuiScale = true;
    @Persisted public boolean singlePage = false;
    @Persisted public boolean allowPageTurning = true;
    @Persisted public boolean lockedIcons = true;
    @Persisted public Map<String, List<String>> requiredStages = new LinkedHashMap<>();
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
        // 整段字节交给 LDLib2 的 Tag 访问器，避免为正文的每个字节创建追踪引用。
        // 磁盘上仍是相同的 NBT ByteArray，兼容已有 .book。
        @Persisted public ByteArrayTag html = new ByteArrayTag(new byte[0]);
        @Persisted public List<String> unlockHint = new ArrayList<>();
    }

    /**
     * 升级旧书的字段类型后使用 LDLib2 持久化访问器加载，供阅读器和编辑器共用。
     *
     * @param provider 注册表上下文
     * @param tag 书籍 data 标签；不修改传入标签
     * @return 已加载的当前版本书籍数据
     * @throws IllegalArgumentException 文件版本不受支持或旧阶段映射格式无效时抛出
     */
    public static BookData load(HolderLookup.Provider provider, CompoundTag tag) {
        var data = new BookData();
        data.deserializeNBT(provider, migrate(tag));
        return data;
    }

    // 仅转换旧版字段的类型；新列表仍交给 LDLib2 持久化访问器读写。
    private static CompoundTag migrate(CompoundTag source) {
        int version = source.contains("formatVersion") ? source.getInt("formatVersion") : 2;
        if (version == FORMAT_VERSION) return source;
        if (version != 2) throw new IllegalArgumentException("Unsupported book version: " + version);
        var data = source.copy();
        if (data.contains("requiredStages")) {
            var entries = NbtOps.INSTANCE.getStream(data.get("requiredStages")).getOrThrow().toList();
            if ((entries.size() & 1) != 0) throw new IllegalArgumentException("Invalid stage map");
            var migrated = new ArrayList<Tag>();
            for (int i = 0; i < entries.size(); i += 2) {
                migrated.add(entries.get(i));
                String stage = NbtOps.INSTANCE.getStringValue(entries.get(i + 1)).getOrThrow();
                migrated.add(NbtOps.INSTANCE.createList(stage.isBlank() ? java.util.stream.Stream.empty()
                        : java.util.stream.Stream.of(StringTag.valueOf(stage))));
            }
            data.put("requiredStages", NbtOps.INSTANCE.createList(migrated.stream()));
        }
        for (var language : data.getList("languages", Tag.TAG_COMPOUND))
            for (var pageTag : ((CompoundTag) language).getList("pages", Tag.TAG_COMPOUND)) {
                var page = (CompoundTag) pageTag;
                if (page.contains("unlockHint", Tag.TAG_STRING))
                    page.put("unlockHint", NbtOps.INSTANCE.createList(
                            Book.hintLines(page.getString("unlockHint")).stream().map(StringTag::valueOf)));
            }
        data.putInt("formatVersion", FORMAT_VERSION);
        return data;
    }

    public static BookData from(Book book) {
        var data = new BookData();
        data.id = book.id;
        data.defaultLanguage = book.defaultLanguage;
        data.followGuiScale = book.followGuiScale;
        data.singlePage = book.singlePage;
        data.allowPageTurning = book.allowPageTurning;
        data.lockedIcons = book.lockedIcons;
        book.requiredStages.forEach((id, stages) -> data.requiredStages.put(id, new ArrayList<>(StageNames.normalizeAll(stages))));
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
        entry.html = new ByteArrayTag(p.document().html().getBytes(StandardCharsets.UTF_8));
        entry.unlockHint = new ArrayList<>(p.unlockHint());
        return entry;
    }

    public Book toBook(Schema schema) {
        return toBook(schema, false);
    }

    /**
     * 加载完整目录及阶段元数据，正文按需解析；只用于服务端已校验书籍的客户端阅读。
     *
     * @param schema 客户端节点定义
     * @return 正文延迟解析的书籍
     */
    public Book toReadingBook(Schema schema) {
        return toBook(schema, true);
    }

    private Book toBook(Schema schema, boolean deferred) {
        if (formatVersion != FORMAT_VERSION)
            throw new IllegalArgumentException("Unsupported book version: " + formatVersion);
        if (pageOrder.isEmpty()) throw new IllegalArgumentException("Book contains no pages");
        var book = new Book();
        book.id = id;
        book.defaultLanguage = Book.normalizeLanguage(defaultLanguage);
        book.followGuiScale = followGuiScale;
        book.singlePage = singlePage;
        book.allowPageTurning = allowPageTurning;
        book.lockedIcons = lockedIcons;
        var ids = new HashSet<String>();
        for (String pageId : pageOrder)
            if (pageId.isBlank() || !ids.add(pageId))
                throw new IllegalArgumentException("Invalid or duplicate page ID");
        if (requiredStages == null)
            throw new IllegalArgumentException("Required stages must be a map");
        for (var entry : requiredStages.entrySet()) {
            if (!ids.contains(entry.getKey()))
                throw new IllegalArgumentException("Invalid staged page ID: " + entry.getKey());
            var stages = StageNames.normalizeAll(entry.getValue());
            if (!stages.isEmpty()) book.requiredStages.put(entry.getKey(), stages);
        }
        var contents = new LinkedHashMap<String, Book.Translation>();
        for (var entry : languages) {
            String language = Book.normalizeLanguage(entry.language);
            if (contents.containsKey(language))
                throw new IllegalArgumentException("Duplicate language: " + language);
            var pages = new LinkedHashMap<String, Book.Page>();
            for (var p : entry.pages) {
                if (!ids.contains(p.id) || pages.containsKey(p.id))
                    throw new IllegalArgumentException("Invalid translated page ID: " + p.id);
                String html = new String(p.html.getAsByteArray(), StandardCharsets.UTF_8);
                pages.put(
                        p.id,
                        new Book.Page(
                                p.id,
                                p.title,
                                deferred ? RichDocument.deferred(html, schema)
                                        : RichDocument.parse(html, schema),
                                p.unlockHint));
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
