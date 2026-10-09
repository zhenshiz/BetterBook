package com.zhenshiz.betterbook.core;

import org.jsoup.nodes.Element;

import java.util.*;
import java.util.function.Consumer;

/** 书籍级事务、源码草稿和选区历史；所有内容修改必须经由事务入口。 */
public final class BookSession {
    public record Position(int block, int offset) implements Comparable<Position> {
        @Override
        public int compareTo(Position p) {
            int c = Integer.compare(block, p.block);
            return c == 0 ? Integer.compare(offset, p.offset) : c;
        }
    }

    public record Selection(Position anchor, Position head, boolean all) {
        public Selection(Position anchor, Position head) {
            this(anchor, head, false);
        }

        public Position start() {
            return anchor.compareTo(head) < 0 ? anchor : head;
        }

        public Position end() {
            return anchor.compareTo(head) > 0 ? anchor : head;
        }

        public boolean empty() {
            return !all && anchor.equals(head);
        }

        public static Selection at(int b, int o) {
            var p = new Position(b, o);
            return new Selection(p, p);
        }
    }

    public record SourceSelection(
            int line, int column, int startLine, int startColumn, int endLine, int endColumn) {}

    private SourceSelection sourceSelection = new SourceSelection(0, 0, 0, 0, 0, 0);

    public SourceSelection sourceSelection() {
        return sourceSelection;
    }

    public void sourceSelection(SourceSelection value) {
        sourceSelection = value;
    }

    private record DraftKey(String language, String pageId) {}

    private record Snapshot(
            Book book,
            String language,
            int page,
            Selection selection,
            Map<DraftKey, String> drafts,
            List<RichDocument.Mark> stored,
            SourceSelection sourceSelection) {}

    private record Change(Snapshot before, Snapshot after) {}

    private Book book;
    private String language;
    private int page;
    private Selection selection = Selection.at(0, 0);
    private Map<DraftKey, String> drafts = new HashMap<>();
    private List<RichDocument.Mark> stored;
    private final Deque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private long revision;
    private boolean inTransaction;

    public BookSession(Book book) {
        this.book = book;
        language = book.defaultLanguage;
    }

    public Book book() {
        return book;
    }

    public int pageIndex() {
        return page;
    }

    public Book.Page page() {
        return book.page(page, language);
    }

    /**
     * 获取当前编辑语言。
     *
     * @return 已规范化的 Minecraft 语言代码
     */
    public String language() {
        return language;
    }

    /**
     * 应用源码草稿后切换编辑语言，并重置选区。
     *
     * @param value 已存在的语言代码
     * @throws IllegalArgumentException 语言不存在或源码校验失败时抛出
     */
    public void switchLanguage(String value) {
        String next = Book.normalizeLanguage(value);
        if (!book.languages().contains(next))
            throw new IllegalArgumentException("Unknown language: " + next);
        applySources();
        language = next;
        selection = Selection.at(0, 0);
        sourceSelection = new SourceSelection(0, 0, 0, 0, 0, 0);
        stored = null;
        changed();
    }

    /**
     * 创建默认语言的译文副本并开始编辑，整个操作可撤销。
     *
     * @param value 新语言代码
     * @throws IllegalArgumentException 语言代码无效、重复或源码校验失败时抛出
     */
    public void addLanguage(String value) {
        String next = Book.normalizeLanguage(value);
        applySources();
        transactMetadata(
                () -> {
                    book.addLanguage(next);
                    language = next;
                    selection = Selection.at(0, 0);
                    sourceSelection = new SourceSelection(0, 0, 0, 0, 0, 0);
                    stored = null;
                });
    }

    public void useLanguageAsDefault() {
        applySources();
        transactMetadata(() -> book.setDefaultLanguage(language));
    }

    private void putPage(Book.Page value) {
        book.putPage(page, language, value);
    }

    private DraftKey draftKey() {
        return new DraftKey(language, page().id());
    }

    public RichDocument document() {
        return page().document();
    }

    public Selection selection() {
        return selection;
    }

    public long revision() {
        return revision;
    }

    public void listen(Runnable listener) {
        listeners.add(listener);
    }

    public void unlisten(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        revision++;
        List.copyOf(listeners).forEach(Runnable::run);
    }

    public void select(Selection value) {
        selection = clamp(value);
        stored = null;
    }

    /** 选中整个文档，包括空组件的结构，后续替换或删除可一并移除这些组件。 */
    public void selectAll() {
        var blocks = document().blocks();
        select(
                new Selection(
                        new Position(0, 0),
                        new Position(blocks.size() - 1, blocks.getLast().length()),
                        true));
    }

    private Position clamp(Position p) {
        var b = document().blocks();
        int i = Math.clamp(p.block(), 0, b.size() - 1);
        int offset = Math.clamp(p.offset(), 0, b.get(i).length());
        String text = b.get(i).text();
        if (offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset)))
            offset--;
        return new Position(i, offset);
    }

    private Selection clamp(Selection s) {
        return new Selection(clamp(s.anchor()), clamp(s.head()), s.all());
    }

    public void switchPage(int value) {
        page = Math.clamp(value, 0, book.pages.size() - 1);
        selection = Selection.at(0, 0);
        stored = null;
        changed();
    }

    private Snapshot snapshot() {
        return new Snapshot(
                book, language, page, selection, new HashMap<>(drafts), stored, sourceSelection);
    }

    private void restore(Snapshot s) {
        book = s.book;
        language = s.language;
        page = s.page;
        selection = s.selection;
        drafts = new HashMap<>(s.drafts);
        stored = s.stored;
        sourceSelection = s.sourceSelection;
        changed();
    }

    /**
     * 执行一个可撤销事务，失败时恢复内容和选区。
     *
     * @param edit 修改当前会话的操作
     */
    public void transact(Runnable edit) {
        transact(edit, true);
    }

    private void transactMetadata(Runnable edit) {
        transact(edit, false);
    }

    private void transact(Runnable edit, boolean editDocument) {
        if (inTransaction) {
            edit.run();
            return;
        }
        var before = snapshot();
        book = book.copy();
        if (editDocument) putPage(page().withDocument(document().copy()));
        inTransaction = true;
        try {
            edit.run();
            if (editDocument) {
                var result = document();
                if (before.book.containsDocument(result))
                    putPage(page().withDocument(result.copy()));
                document().cleanup();
                document().validate();
            }
            selection = clamp(selection);
            undo.addLast(new Change(before, snapshot()));
            if (undo.size() > 100) undo.removeFirst();
            redo.clear();
        } catch (RuntimeException e) {
            book = before.book;
            language = before.language;
            page = before.page;
            selection = before.selection;
            drafts = before.drafts;
            stored = before.stored;
            sourceSelection = before.sourceSelection;
            throw e;
        } finally {
            inTransaction = false;
        }
        changed();
    }

    public void undo() {
        if (!undo.isEmpty()) {
            var change = undo.removeLast();
            redo.addLast(change);
            restore(change.before());
        }
    }

    public void redo() {
        if (!redo.isEmpty()) {
            var change = redo.removeLast();
            undo.addLast(change);
            restore(change.after());
        }
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean hasDraft() {
        return drafts.containsKey(draftKey());
    }

    public boolean hasAnyDraft() {
        return !drafts.isEmpty();
    }

    public String source() {
        var draft = drafts.get(draftKey());
        return draft != null ? draft : document().sourceHtml();
    }

    public void draft(String text) {
        draft(text, sourceSelection);
    }

    public void draft(String text, SourceSelection after) {
        if (text.equals(source())) return;
        transactMetadata(
                () -> {
                    drafts.put(draftKey(), text);
                    sourceSelection = after;
                });
    }

    public void applySources() {
        if (drafts.isEmpty()) return;
        var parsed = new HashMap<DraftKey, RichDocument>();
        for (var entry : drafts.entrySet())
            parsed.put(entry.getKey(), RichDocument.parse(entry.getValue(), document().schema()));
        transactMetadata(
                () -> {
                    for (int i = 0; i < book.pages.size(); i++) {
                        for (String locale : book.languages()) {
                            var p = book.page(i, locale);
                            var result = parsed.get(new DraftKey(locale, p.id()));
                            if (result != null) book.putPage(i, locale, p.withDocument(result));
                        }
                    }
                    drafts.clear();
                });
    }

    public void importHtml(String html) {
        var parsed = RichDocument.parse(html, document().schema());
        transactMetadata(
                () -> {
                    putPage(page().withDocument(parsed));
                    selection = Selection.at(0, 0);
                    drafts.remove(draftKey());
                });
    }

    public void renameBook(String title, String author) {
        transactMetadata(() -> book.rename(language, title, author));
    }

    /**
     * 设置整本书的阅读界面是否跟随 Minecraft GUI 比例，支持撤销与重做。
     *
     * @param follow 是否跟随玩家的 GUI 比例；关闭时按窗口尺寸自动缩放
     */
    public void setFollowGuiScale(boolean follow) {
        if (book.followGuiScale == follow) return;
        transactMetadata(() -> book.followGuiScale = follow);
    }

    /**
     * 设置整本书是否使用单页阅读布局，支持撤销与重做。
     *
     * @param value 是否使用单页布局
     */
    public void setSinglePage(boolean value) {
        if (book.singlePage == value) return;
        transactMetadata(() -> book.singlePage = value);
    }

    /**
     * 设置整本书是否允许通过翻页控件导航，支持撤销与重做。
     *
     * @param value 是否允许翻页
     */
    public void setAllowPageTurning(boolean value) {
        if (book.allowPageTurning == value) return;
        transactMetadata(() -> book.allowPageTurning = value);
    }

    /**
     * 设置整本书是否显示锁定页面图标，支持撤销与重做。
     *
     * @param value 是否显示锁定图标
     */
    public void setLockedIcons(boolean value) {
        if (book.lockedIcons == value) return;
        transactMetadata(() -> book.lockedIcons = value);
    }

    /**
     * 在一个可撤销事务中设置当前页面的共用阶段要求和当前语言提示。
     *
     * @param stages 阶段名称列表；空列表或全空白条目移除阶段要求
     * @param hint 当前语言的提示行列表；全空白译文提示回退到默认语言
     * @throws IllegalArgumentException 非空阶段名称不符合阶段命名规则时抛出
     */
    public void setPageAccess(List<String> stages, List<String> hint) {
        var normalized = StageNames.normalizeAll(stages);
        var text = hint == null ? List.<String>of() : List.copyOf(hint);
        String pageId = page().id();
        boolean sameStage =
                normalized.isEmpty()
                        ? !book.requiredStages.containsKey(pageId)
                        : normalized.equals(book.requiredStages.get(pageId));
        if (sameStage && page().unlockHint().equals(text)) return;
        transactMetadata(
                () -> {
                    if (normalized.isEmpty()) book.requiredStages.remove(pageId);
                    else book.requiredStages.put(pageId, normalized);
                    putPage(page().withUnlockHint(text));
                });
    }

    /**
     * 将原有单阶段设置转换为列表，并保留提示中的换行。
     *
     * @param stage 阶段名称字符串；空白字符串表示不限制阅读
     * @param hint 提示字符串，允许为空引用
     * @throws IllegalArgumentException 阶段名称为空引用或无效时抛出
     */
    public void setPageAccess(String stage, String hint) {
        if (stage == null) throw new IllegalArgumentException("Stage name is required");
        setPageAccess(List.of(stage), Book.hintLines(hint));
    }

    public void renamePage(String title) {
        transactMetadata(() -> putPage(page().withTitle(title)));
    }

    public void addPage(boolean duplicate) {
        transactMetadata(
                () -> {
                    if (duplicate) book.duplicatePage(page);
                    else
                        book.pages.add(
                                page + 1,
                                new Book.Page(
                                        UUID.randomUUID().toString(),
                                        Integer.toString(book.pages.size() + 1),
                                        RichDocument.parse("<p></p>", document().schema())));
                    page++;
                    selection = Selection.at(0, 0);
                });
    }

    public void removePage() {
        if (book.pages.size() == 1) return;
        transactMetadata(
                () -> {
                    String removed = page().id();
                    drafts.keySet().removeIf(key -> key.pageId().equals(removed));
                    book.removePage(page);
                    page = Math.min(page, book.pages.size() - 1);
                    selection = Selection.at(0, 0);
                });
    }

    public void movePage(int delta) {
        int target = page + delta;
        if (target < 0 || target >= book.pages.size()) return;
        transactMetadata(
                () -> {
                    Collections.swap(book.pages, page, target);
                    page = target;
                });
    }

    /** 一页的剪贴板内容；正文和已解析提示的快照不会被后续编辑改变。 */
    public record PageText(String title, String html, List<String> unlockHint) {
        public PageText {
            unlockHint = unlockHint == null ? List.of() : List.copyOf(unlockHint);
        }

        public PageText(String title, String html) {
            this(title, html, List.of());
        }
    }

    public record PageCopy(String defaultLanguage, Map<String, PageText> languages, List<String> stages) {
        public PageCopy {
            languages = Map.copyOf(languages);
            stages = StageNames.normalizeAll(stages);
        }

        public PageCopy(String defaultLanguage, Map<String, PageText> languages) {
            this(defaultLanguage, languages, List.of());
        }
    }

    public PageCopy copyPage() {
        applySources();
        var content = new LinkedHashMap<String, PageText>();
        for (String locale : book.languages()) {
            var p = book.page(page, locale);
            content.put(
                    locale,
                    new PageText(
                            p.title(), p.document().html(), book.unlockHint(p.id(), locale)));
        }
        return new PageCopy(
                book.defaultLanguage, content, book.requiredStages.getOrDefault(page().id(), List.of()));
    }

    public void pastePage(PageCopy copy) {
        applySources();
        var parsed = new LinkedHashMap<String, Book.Page>();
        String id = UUID.randomUUID().toString();
        copy.languages()
                .forEach(
                        (locale, text) ->
                                parsed.put(
                                        locale,
                                        new Book.Page(
                                                id,
                                                text.title(),
                                                RichDocument.parse(
                                                        text.html(), document().schema()),
                                                text.unlockHint())));
        transactMetadata(
                () -> {
                    var fallback = parsed.get(copy.defaultLanguage());
                    book.pages.add(page + 1, parsed.getOrDefault(book.defaultLanguage, fallback));
                    if (!copy.stages().isEmpty()) book.requiredStages.put(id, copy.stages());
                    page++;
                    for (String locale : parsed.keySet()) {
                        if (!book.languages().contains(locale))
                            book.translations.put(
                                    locale,
                                    new Book.Translation(book.title, book.author, Map.of()));
                        if (!locale.equals(book.defaultLanguage))
                            book.putPage(page, locale, parsed.get(locale));
                    }
                    selection = Selection.at(0, 0);
                });
    }

    /** 以拖放结果重排所有页面，一次拖放对应一个撤销步骤。 */
    public void reorderPages(List<String> order) {
        var current = book.pages.stream().map(Book.Page::id).toList();
        if (current.equals(order)) return;
        if (order.size() != current.size() || !new HashSet<>(order).equals(new HashSet<>(current)))
            throw new IllegalArgumentException("Page order must contain every page exactly once");
        applySources();
        transactMetadata(
                () -> {
                    String selected = page().id();
                    var byId = new HashMap<String, Book.Page>();
                    book.pages.forEach(p -> byId.put(p.id(), p));
                    book.pages.clear();
                    order.forEach(id -> book.pages.add(byId.get(id)));
                    page = order.indexOf(selected);
                });
    }

    public String selectedText() {
        var s = selection.start();
        var e = selection.end();
        var out = new StringBuilder();
        var blocks = document().blocks();
        for (int i = s.block(); i <= e.block(); i++) {
            if (i > s.block()) out.append('\n');
            String t = blocks.get(i).text();
            out.append(
                    t, i == s.block() ? s.offset() : 0, i == e.block() ? e.offset() : t.length());
        }
        return out.toString();
    }

    public List<RichDocument.Mark> marksAtCursor() {
        if (stored != null) return stored;
        var b = document().blocks().get(selection.head().block());
        int pos = 0, at = Math.max(0, selection.head().offset() - 1);
        for (var r : b.runs()) {
            pos += r.text().length();
            if (pos > at) return r.marks();
        }
        return List.of();
    }

    public record LinkRange(Selection selection, RichDocument.Mark mark) {}

    /**
     * 获取包含当前选区或光标的完整链接范围，跨行内格式合并相邻的同一链接。
     *
     * @return 完整链接范围；跨越链接边界或位于普通文字内时为空
     */
    public Optional<LinkRange> selectedLink() {
        var start = selection.start();
        var end = selection.end();
        if (start.block() != end.block()) return Optional.empty();
        var runs = document().blocks().get(start.block()).runs();
        LinkRange boundary = null;
        int offset = 0;
        for (int i = 0; i < runs.size(); ) {
            var mark = linkMark(runs.get(i));
            int from = offset;
            offset += runs.get(i++).text().length();
            if (mark == null) continue;
            while (i < runs.size() && mark.equals(linkMark(runs.get(i))))
                offset += runs.get(i++).text().length();
            var range =
                    new LinkRange(
                            new Selection(
                                    new Position(start.block(), from),
                                    new Position(start.block(), offset)),
                            mark);
            if (start.offset() >= from && start.offset() < offset && end.offset() <= offset)
                return Optional.of(range);
            if (selection.empty() && start.offset() == offset) boundary = range;
        }
        return Optional.ofNullable(boundary);
    }

    private static RichDocument.Mark linkMark(RichDocument.Run run) {
        return run.marks().stream()
                .filter(m -> m.id().equals("betterbook:link"))
                .findFirst()
                .orElse(null);
    }

    /**
     * 更新选区的链接地址；选区位于已有链接内时修改完整链接。保留文字、其他格式及当前选区。
     *
     * @param href 链接地址；空白地址表示移除链接，操作可整体撤销
     */
    public void updateLink(String href) {
        var existing = selectedLink();
        var range = existing.map(LinkRange::selection).orElse(selection);
        if (range.empty()) return;
        var spec = document().schema().mark("betterbook:link");
        if (spec == null) return;
        var attrs =
                new LinkedHashMap<>(
                        existing.map(l -> l.mark().attributes()).orElse(spec.attributes()));
        String address = href.trim();
        attrs.put("href", address);
        var link = new RichDocument.Mark(spec.id(), spec.tag(), attrs);
        transact(
                () -> {
                    var start = range.start();
                    var end = range.end();
                    var blocks = document().blocks();
                    for (int i = start.block(); i <= end.block(); i++) {
                        var block = blocks.get(i);
                        if (block.atom() || block.code()) continue;
                        int from = i == start.block() ? start.offset() : 0;
                        int to = i == end.block() ? end.offset() : block.length();
                        var out = new ArrayList<>(RichDocument.slice(block.runs(), 0, from));
                        for (var run : RichDocument.slice(block.runs(), from, to)) {
                            var marks = new ArrayList<>(run.marks());
                            marks.removeIf(
                                    m ->
                                            m.id().equals("betterbook:link")
                                                    || !address.isEmpty()
                                                            && m.id().equals(TextCommand.MARK));
                            if (!address.isEmpty()) marks.add(link);
                            out.add(new RichDocument.Run(run.text(), marks, run.opaque()));
                        }
                        out.addAll(RichDocument.slice(block.runs(), to, block.length()));
                        document().setRuns(block, out);
                    }
                    stored = null;
                });
    }

    public void insert(String text) {
        if (text.isEmpty() && selection.empty()) return;
        transact(() -> replace(text.replace("\r\n", "\n").replace('\r', '\n')));
    }

    /**
     * 在当前选区插入链接，整个操作可撤销。
     *
     * @param href 非空链接地址，支持外部地址和书内页面地址
     * @param label 显示文字，空白时使用地址
     * @throws IllegalArgumentException 地址为空时抛出
     */
    public void insertLink(String href, String label) {
        String address = href.trim();
        if (address.isEmpty()) throw new IllegalArgumentException("Link URL is required");
        String text = label.isBlank() ? address : label;
        if (!selection.empty() && text.equals(selectedText())) {
            updateLink(address);
            return;
        }
        transact(
                () -> {
                    var marks = new ArrayList<>(marksAtCursor());
                    marks.removeIf(
                            mark ->
                                    mark.id().equals("betterbook:link")
                                            || mark.id().equals(TextCommand.MARK));
                    var spec = document().schema().mark("betterbook:link");
                    var attributes = new LinkedHashMap<>(spec.attributes());
                    attributes.put("href", address);
                    marks.add(new RichDocument.Mark(spec.id(), spec.tag(), attributes));
                    stored = marks;
                    replace(text);
                    stored = null;
                });
    }

    private void replace(String text) {
        var s = selection.start();
        var e = selection.end();
        var d = document();
        var blocks = d.blocks();
        var first = blocks.get(s.block());
        var last = blocks.get(e.block());
        var marks = marksAtCursor();
        if (selection.all()) {
            var paragraph = d.body().empty().appendElement("p");
            d.setRuns(
                    new RichDocument.Block(paragraph, List.of(), false, false),
                    text.isEmpty() ? List.of() : List.of(new RichDocument.Run(text, marks)));
            selection = Selection.at(0, text.length());
            stored = null;
            return;
        }
        if (first.atom()) {
            var paragraph = new Element("p");
            if (s.offset() == 0) first.element().before(paragraph);
            else first.element().after(paragraph);
            if (!selection.empty() && s.offset() == 0) first.element().remove();
            d.invalidate();
            int index =
                    d.blocks().stream()
                            .map(RichDocument.Block::element)
                            .toList()
                            .indexOf(paragraph);
            first = new RichDocument.Block(paragraph, List.of(), false, false);
            s = new Position(index, 0);
        }
        var runs = new ArrayList<>(RichDocument.slice(first.runs(), 0, s.offset()));
        if (!text.isEmpty()) runs.add(new RichDocument.Run(text, marks));
        // Cell boundaries are structural: clear selected text without collapsing the grid.
        boolean cells =
                blocks.subList(selection.start().block(), e.block() + 1).stream()
                        .anyMatch(b -> b.element().is("td,th"));
        if (!last.atom() && (!cells || first.element() == last.element()))
            runs.addAll(RichDocument.slice(last.runs(), e.offset(), last.length()));
        for (int i = e.block(); i > selection.start().block(); i--) {
            var block = blocks.get(i);
            if (block.element().is("td,th"))
                d.setRuns(
                        block,
                        i == e.block()
                                ? RichDocument.slice(block.runs(), e.offset(), block.length())
                                : List.of());
            else block.element().remove();
        }
        d.setRuns(first, runs);
        selection = Selection.at(s.block(), s.offset() + text.length());
    }

    public void delete(int direction) {
        if (!selection.empty()) {
            insert("");
            return;
        }
        transact(
                () -> {
                    var p = selection.head();
                    var blocks = document().blocks();
                    var b = blocks.get(p.block());
                    if (b.code() && b.length() == 0) {
                        b.element().replaceWith(new Element("p"));
                        document().invalidate();
                        selection = Selection.at(p.block(), 0);
                        stored = null;
                        return;
                    }
                    if (direction < 0
                            && p.offset() == 0
                            && !b.element().is("td,th")
                            && ancestor(b.element(), "li") != null) {
                        indent(true);
                        return;
                    }
                    if (direction < 0 && p.offset() > 0) {
                        selection =
                                new Selection(
                                        new Position(
                                                p.block(),
                                                b.text().offsetByCodePoints(p.offset(), -1)),
                                        p);
                        replace("");
                    } else if (direction > 0 && p.offset() < b.length()) {
                        selection =
                                new Selection(
                                        p,
                                        new Position(
                                                p.block(),
                                                b.text().offsetByCodePoints(p.offset(), 1)));
                        replace("");
                    } else {
                        int next = p.block() + direction;
                        if (next < 0 || next >= blocks.size()) return;
                        var other = blocks.get(next);
                        if (b.element().is("td,th") || other.element().is("td,th")) {
                            selection = Selection.at(next, direction < 0 ? other.length() : 0);
                            return;
                        }
                        if (other.atom()) {
                            other.element().remove();
                            document().invalidate();
                            if (direction < 0) selection = Selection.at(p.block() - 1, p.offset());
                            return;
                        }
                        selection =
                                direction < 0
                                        ? new Selection(new Position(next, other.length()), p)
                                        : new Selection(p, new Position(next, 0));
                        replace("");
                    }
                });
    }

    public void toggleMark(String id, Map<String, String> attrs) {
        var spec = document().schema().mark(id);
        if (spec == null) return;
        var mark = new RichDocument.Mark(id, spec.tag(), new LinkedHashMap<>(attrs));
        var attributes = new LinkedHashMap<>(mark.attributes());
        attributes.putAll(spec.attributes());
        mark = new RichDocument.Mark(id, spec.tag(), attributes);
        final var value = mark;
        if (selection.empty()) {
            var list = new ArrayList<>(marksAtCursor());
            boolean exists = list.removeIf(m -> m.id().equals(id));
            if (!exists) {
                if (id.equals("betterbook:superscript"))
                    list.removeIf(m -> m.id().equals("betterbook:subscript"));
                if (id.equals("betterbook:subscript"))
                    list.removeIf(m -> m.id().equals("betterbook:superscript"));
                list.add(value);
            }
            stored = List.copyOf(list);
            return;
        }
        var s = selection.start();
        var e = selection.end();
        boolean all = true;
        for (int i = s.block(); i <= e.block(); i++)
            for (var r :
                    RichDocument.slice(
                            document().blocks().get(i).runs(),
                            i == s.block() ? s.offset() : 0,
                            i == e.block() ? e.offset() : Integer.MAX_VALUE))
                all &= r.marks().stream().anyMatch(m -> m.id().equals(id));
        final boolean remove = all;
        transact(
                () -> {
                    var blocks = document().blocks();
                    for (int i = s.block(); i <= e.block(); i++) {
                        var b = blocks.get(i);
                        if (b.atom() || b.code()) continue;
                        int a = i == s.block() ? s.offset() : 0,
                                z = i == e.block() ? e.offset() : b.length();
                        var out = new ArrayList<>(RichDocument.slice(b.runs(), 0, a));
                        for (var r : RichDocument.slice(b.runs(), a, z)) {
                            var marks = new ArrayList<>(r.marks());
                            marks.removeIf(
                                    m ->
                                            m.id().equals(id)
                                                    || id.equals("betterbook:superscript")
                                                            && m.id().equals("betterbook:subscript")
                                                    || id.equals("betterbook:subscript")
                                                            && m.id().equals(
                                                                            "betterbook:superscript"));
                            if (!remove) marks.add(value);
                            out.add(new RichDocument.Run(r.text(), marks, r.opaque()));
                        }
                        out.addAll(RichDocument.slice(b.runs(), z, b.length()));
                        document().setRuns(b, out);
                    }
                });
    }

    /**
     * 设置当前文字选区的颜色，保留其他格式、选区和组件结构；操作可撤销。
     *
     * @param rgb RGB 整数；null 表示恢复默认文字颜色，代码块和原子节点不参与着色
     */
    public void setTextColor(Integer rgb) {
        if (selection.empty()) return;
        var start = selection.start();
        var end = selection.end();
        transact(
                () -> {
                    var blocks = document().blocks();
                    for (int i = start.block(); i <= end.block(); i++) {
                        var block = blocks.get(i);
                        if (block.atom() || block.code()) continue;
                        int from = i == start.block() ? start.offset() : 0;
                        int to = i == end.block() ? end.offset() : block.length();
                        var runs = new ArrayList<>(RichDocument.slice(block.runs(), 0, from));
                        for (var run : RichDocument.slice(block.runs(), from, to)) {
                            var marks = new ArrayList<RichDocument.Mark>();
                            for (var mark : run.marks()) {
                                if (!mark.id().equals("betterbook:color")) {
                                    marks.add(mark);
                                    continue;
                                }
                                var attributes = new LinkedHashMap<>(mark.attributes());
                                String style =
                                        TextColor.write(attributes.getOrDefault("style", ""), null);
                                attributes.remove("style");
                                if (!style.isEmpty() || !attributes.isEmpty()) {
                                    attributes.put("style", style);
                                    marks.add(
                                            new RichDocument.Mark(
                                                    mark.id(), mark.tag(), attributes));
                                }
                            }
                            if (rgb != null)
                                marks.add(
                                        new RichDocument.Mark(
                                                "betterbook:color",
                                                "span",
                                                Map.of("style", TextColor.write("", rgb))));
                            runs.add(new RichDocument.Run(run.text(), marks, run.opaque()));
                        }
                        runs.addAll(RichDocument.slice(block.runs(), to, block.length()));
                        document().setRuns(block, runs);
                    }
                    stored = null;
                });
    }

    /**
     * 读取选中文字共有的指令配置。
     *
     * @return 全部所选文字使用同一绑定时返回绑定，否则为空
     */
    public Optional<TextCommand> selectedCommand() {
        if (selection.empty()) return Optional.empty();
        TextCommand command = null;
        var start = selection.start();
        var end = selection.end();
        var blocks = document().blocks();
        for (int i = start.block(); i <= end.block(); i++) {
            var block = blocks.get(i);
            for (var run :
                    RichDocument.slice(
                            block.runs(),
                            i == start.block() ? start.offset() : 0,
                            i == end.block() ? end.offset() : block.length())) {
                var action =
                        run.marks().stream()
                                .filter(m -> m.id().equals(TextCommand.MARK))
                                .map(m -> TextCommand.read(m.attributes()))
                                .flatMap(Optional::stream)
                                .findFirst()
                                .orElse(null);
                if (action == null || command != null && !command.equals(action))
                    return Optional.empty();
                command = action;
            }
        }
        return Optional.ofNullable(command);
    }

    /**
     * 为选中文字设置点击指令，保留文字、颜色和其他格式；应用时替换该选区的链接。
     *
     * @param command 新绑定；null 表示移除指令绑定，操作可撤销
     */
    public void setTextCommand(TextCommand command) {
        if (selection.empty()) return;
        var start = selection.start();
        var end = selection.end();
        transact(
                () -> {
                    var blocks = document().blocks();
                    for (int i = start.block(); i <= end.block(); i++) {
                        var block = blocks.get(i);
                        if (block.atom() || block.code()) continue;
                        int from = i == start.block() ? start.offset() : 0;
                        int to = i == end.block() ? end.offset() : block.length();
                        var runs = new ArrayList<>(RichDocument.slice(block.runs(), 0, from));
                        for (var run : RichDocument.slice(block.runs(), from, to)) {
                            var marks = new ArrayList<>(run.marks());
                            marks.removeIf(
                                    m ->
                                            m.id().equals(TextCommand.MARK)
                                                    || command != null
                                                            && m.id().equals("betterbook:link"));
                            if (command != null) marks.add(command.mark());
                            runs.add(new RichDocument.Run(run.text(), marks, run.opaque()));
                        }
                        runs.addAll(RichDocument.slice(block.runs(), to, block.length()));
                        document().setRuns(block, runs);
                    }
                    stored = null;
                });
    }

    public void exitBlock() {
        transact(
                () -> {
                    var el = document().blocks().get(selection.head().block()).element();
                    while (el.parent() != document().body()) el = el.parent();
                    var paragraph = new Element("p");
                    el.after(paragraph);
                    document().invalidate();
                    selection =
                            Selection.at(
                                    document().blocks().stream()
                                            .map(RichDocument.Block::element)
                                            .toList()
                                            .indexOf(paragraph),
                                    0);
                });
    }

    public void enter(boolean soft) {
        var b = document().blocks().get(selection.head().block());
        if (soft || b.code()) {
            insert("\n");
            return;
        }
        transact(
                () -> {
                    if (!selection.empty()) replace("");
                    var p = selection.head();
                    var d = document();
                    var block = d.blocks().get(p.block());
                    if (block.atom()) {
                        block.element().after(new Element("p"));
                        d.invalidate();
                        selection = Selection.at(p.block() + 1, 0);
                        return;
                    }
                    var li = ancestor(block.element(), "li");
                    if (li != null && block.text().isEmpty()) {
                        indent(true);
                        return;
                    }
                    String tag =
                            block.element().normalName().matches("h[1-6]")
                                    ? "p"
                                    : block.element().normalName();
                    if (tag.equals("td") || tag.equals("th")) {
                        replace("\n");
                        return;
                    }
                    var next = new Element(tag.equals("div") ? "p" : tag);
                    var tail = RichDocument.slice(block.runs(), p.offset(), block.length());
                    d.setRuns(block, RichDocument.slice(block.runs(), 0, p.offset()));
                    if (li != null) {
                        var item = new Element("li");
                        if (li.hasAttr("data-type"))
                            item.attr("data-type", "taskItem").attr("data-checked", "false");
                        li.after(item);
                        item.appendChild(next);
                    } else if (block.element().is("div[data-type=admonition-title]")
                            && block.element().nextElementSibling() != null)
                        block.element().nextElementSibling().prependChild(next);
                    else block.element().after(next);
                    d.setRuns(new RichDocument.Block(next, List.of(), false, false), tail);
                    selection =
                            Selection.at(
                                    d.blocks().stream()
                                            .map(RichDocument.Block::element)
                                            .toList()
                                            .indexOf(next),
                                    0);
                });
    }

    public static Element ancestor(Element e, String selector) {
        for (var p = e; p != null; p = p.parent()) if (p.is(selector)) return p;
        return null;
    }

    public void blockTag(String tag) {
        transact(
                () -> {
                    for (var b :
                            document()
                                    .blocks()
                                    .subList(
                                            selection.start().block(),
                                            selection.end().block() + 1)) {
                        if (!b.element().is("p,h1,h2,h3,h4,h5,h6,pre")) continue;
                        var runs = b.runs();
                        b.element().tagName(tag);
                        document()
                                .setRuns(
                                        new RichDocument.Block(b.element(), runs, false, false),
                                        runs);
                    }
                    document().invalidate();
                });
    }

    public void align(String align) {
        transact(
                () -> {
                    for (int i = selection.start().block(); i <= selection.end().block(); i++)
                        document()
                                .blocks()
                                .get(i)
                                .element()
                                .attr("style", "text-align: " + align + ";");
                    document().invalidate();
                });
    }

    public void insertHtml(String html) {
        var parsed = RichDocument.parse(html, document().schema());
        transact(
                () -> {
                    var b = document().blocks().get(selection.head().block());
                    var elements = new ArrayList<>(parsed.body().children());
                    Element after = b.element();
                    Element inserted = null;
                    if (after.is("td,th")) after = ancestor(after, "table");
                    for (var e : elements) {
                        after.after(e.clone());
                        after = after.nextElementSibling();
                        if (inserted == null) inserted = after;
                    }
                    if (!b.atom() && b.text().isEmpty() && !b.element().is("td,th"))
                        b.element().remove();
                    document().invalidate();
                    var list = document().blocks();
                    int index = 0;
                    for (int i = 0; i < list.size(); i++)
                        if (list.get(i).element() == inserted
                                || list.get(i).element().parents().contains(inserted)) {
                            index = i;
                            break;
                        }
                    selection = Selection.at(index, 0);
                });
    }

    /** 插入普通矩形表格，表格整体进入一次撤销历史。 */
    public void insertTable(int rows, int columns) {
        if (rows < 1 || rows > 64 || columns < 1 || columns > 32)
            throw new IllegalArgumentException("Table size must be 1–64 rows and 1–32 columns");
        var table = new Element("table").attr("data-type", "custom-table");
        var body = table.appendElement("tbody");
        for (int r = 0; r < rows; r++) {
            var row = body.appendElement("tr");
            for (int c = 0; c < columns; c++) row.appendElement("td");
        }
        insertHtml(table.outerHtml());
    }

    /** 修改当前单元格所在表格；删除最后一行或列时移除整个表格。 */
    public void editTable(String action) {
        transact(
                () -> {
                    var cell =
                            ancestor(
                                    document().blocks().get(selection.head().block()).element(),
                                    "td,th");
                    if (cell == null) return;
                    var row = cell.parent();
                    var table = ancestor(row, "table");
                    int column = cell.elementSiblingIndex();
                    var rows = table.select("tr");
                    var next = cell;
                    if (action.equals("delete")
                            || action.equals("row-") && rows.size() == 1
                            || action.equals("col-") && row.childrenSize() == 1) {
                        next = new Element("p");
                        table.replaceWith(next);
                    } else
                        switch (action) {
                            case "row+" -> {
                                var added = new Element("tr");
                                for (int c = 0; c < row.childrenSize(); c++)
                                    added.appendElement("td");
                                row.after(added);
                                next = added.child(column);
                            }
                            case "row-" -> {
                                var last = rows.getLast();
                                if (row == last) next = rows.get(rows.size() - 2).child(column);
                                last.remove();
                            }
                            case "col+" -> {
                                for (var r : rows)
                                    r.child(column)
                                            .after(new Element(r.child(column).normalName()));
                                next = row.child(column + 1);
                            }
                            case "col-" -> {
                                for (var r : rows) r.child(column).remove();
                                next = row.child(Math.min(column, row.childrenSize() - 1));
                            }
                            case "header" -> {
                                boolean header = !rows.getFirst().child(0).is("th");
                                table.attr("data-with-header-row", Boolean.toString(header));
                                for (var c : rows.getFirst().children())
                                    c.tagName(header ? "th" : "td");
                            }
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unknown table action: " + action);
                        }
                    document().invalidate();
                    selection =
                            Selection.at(
                                    document().blocks().stream()
                                            .map(RichDocument.Block::element)
                                            .toList()
                                            .indexOf(next),
                                    0);
                });
    }

    public void list(String tag, boolean task) {
        transact(
                () -> {
                    var blocks = document().blocks();
                    var first = blocks.get(selection.start().block()).element();
                    if (first.is("td,th")) return;
                    var existing = ancestor(first, "li");
                    if (existing != null) {
                        existing.parent().tagName(tag);
                        if (task) {
                            existing.parent().attr("data-type", "taskList");
                            for (var item : existing.parent().children())
                                item.attr("data-type", "taskItem").attr("data-checked", "false");
                        } else {
                            existing.parent().removeAttr("data-type");
                            for (var item : existing.parent().children()) {
                                item.removeAttr("data-type");
                                item.removeAttr("data-checked");
                            }
                        }
                        document().invalidate();
                        return;
                    }
                    var list = new Element(tag);
                    if (task) list.attr("data-type", "taskList");
                    first.before(list);
                    for (int i = selection.start().block(); i <= selection.end().block(); i++) {
                        var li = list.appendElement("li");
                        if (task) li.attr("data-type", "taskItem").attr("data-checked", "false");
                        li.appendChild(blocks.get(i).element());
                    }
                    document().invalidate();
                });
    }

    public void indent(boolean out) {
        transact(
                () -> {
                    var el = document().blocks().get(selection.head().block()).element();
                    var li = ancestor(el, "li");
                    if (li == null) return;
                    var list = li.parent();
                    if (out) {
                        var outer = ancestor(list.parent(), "li");
                        if (outer != null) outer.after(li);
                        else {
                            var following = new Element(list.normalName());
                            following.attributes().addAll(list.attributes());
                            for (var sibling = li.nextElementSibling(); sibling != null; ) {
                                var next = sibling.nextElementSibling();
                                following.appendChild(sibling);
                                sibling = next;
                            }
                            list.after(el);
                            Element tail = el;
                            for (var child : new ArrayList<>(li.children())) {
                                tail.after(child);
                                tail = child;
                            }
                            if (following.childrenSize() > 0) tail.after(following);
                            li.remove();
                        }
                    } else {
                        var prev = li.previousElementSibling();
                        if (prev == null) return;
                        var nested =
                                prev.children().stream()
                                        .filter(e -> e.normalName().equals(list.normalName()))
                                        .findFirst()
                                        .orElseGet(
                                                () -> {
                                                    var e = prev.appendElement(list.normalName());
                                                    e.attributes().addAll(list.attributes());
                                                    return e;
                                                });
                        nested.appendChild(li);
                    }
                    document().invalidate();
                    int index =
                            document().blocks().stream()
                                    .map(RichDocument.Block::element)
                                    .toList()
                                    .indexOf(el);
                    selection = Selection.at(Math.max(0, index), selection.head().offset());
                });
    }

    /**
     * 切换到包含指定正文块的步骤，并将光标放在块首。
     *
     * @param block 当前文档中的正文块索引
     */
    public void selectStep(int block) {
        transact(
                () -> {
                    selection = Selection.at(block, 0);
                    revealStepAncestors();
                });
    }

    /** 展开选区所在的步骤，使跨步骤键盘导航的光标可见。 */
    public void revealSelectedStep() {
        for (var element = document().blocks().get(selection.head().block()).element();
                element != null;
                element = element.parent()) {
            if (element.is("div[data-type=step-item]")
                    && !element.parent()
                            .attr("currentstep")
                            .equals(Integer.toString(element.elementSiblingIndex()))) {
                transact(this::revealStepAncestors);
                return;
            }
        }
    }

    private void revealStepAncestors() {
        for (var element = document().blocks().get(selection.head().block()).element();
                element != null;
                element = element.parent())
            if (element.is("div[data-type=step-item]"))
                element.parent()
                        .attr("currentstep", Integer.toString(element.elementSiblingIndex()));
        document().invalidate();
    }

    /**
     * 编辑选区所在步骤组，内容、顺序、当前步骤和光标共同进入撤销历史。
     *
     * @param action 操作名称：add、delete、up、down 或 current
     * @throws IllegalArgumentException 操作名称不受支持时抛出
     */
    public void editStep(String action) {
        if (ancestor(
                        document().blocks().get(selection.head().block()).element(),
                        "div[data-type=step-item]")
                == null) return;
        transact(
                () -> {
                    var item =
                            ancestor(
                                    document().blocks().get(selection.head().block()).element(),
                                    "div[data-type=step-item]");
                    var parent = item.parent();
                    Element target = item;
                    switch (action) {
                        case "add" -> {
                            target = new Element("div").attr("data-type", "step-item");
                            target.appendElement("div")
                                    .attr("data-type", "admonition-title")
                                    .text(Integer.toString(parent.childrenSize() + 1));
                            target.appendElement("div")
                                    .attr("data-type", "admonition-content")
                                    .appendElement("p");
                            item.after(target);
                        }
                        case "delete" -> {
                            if (parent.childrenSize() > 1) {
                                target =
                                        item.nextElementSibling() != null
                                                ? item.nextElementSibling()
                                                : item.previousElementSibling();
                                item.remove();
                            }
                        }
                        case "up" -> {
                            var previous = item.previousElementSibling();
                            if (previous != null) previous.before(item);
                        }
                        case "down" -> {
                            var next = item.nextElementSibling();
                            if (next != null) next.after(item);
                        }
                        case "current" -> {}
                        default ->
                                throw new IllegalArgumentException(
                                        "Unknown step action: " + action);
                    }
                    parent.attr("currentstep", Integer.toString(target.elementSiblingIndex()));
                    document().invalidate();
                    var blocks = document().blocks();
                    for (int index = 0; index < blocks.size(); index++)
                        if (ancestor(blocks.get(index).element(), "div[data-type=step-item]")
                                == target) {
                            selection = Selection.at(index, 0);
                            break;
                        }
                    revealStepAncestors();
                });
    }

    public void editElement(Consumer<Element> edit) {
        transact(
                () -> {
                    var element = document().blocks().get(selection.head().block()).element();
                    edit.accept(element);
                    document().invalidate();
                    int index =
                            document().blocks().stream()
                                    .map(RichDocument.Block::element)
                                    .toList()
                                    .indexOf(element);
                    if (index >= 0 && selection.empty())
                        selection = Selection.at(index, selection.head().offset());
                });
    }
}
