package com.zhenshiz.betterbook.client;

import static org.lwjgl.glfw.GLFW.*;

import com.lowdragmc.lowdraglib2.gui.ui.data.Cursor;
import com.lowdragmc.lowdraglib2.gui.ui.elements.codeeditor.CodeEditor;
import com.lowdragmc.lowdraglib2.gui.ui.event.CommandEvents;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.zhenshiz.betterbook.core.BookSession;

/** HTML 源码控件，撤销和选区由书籍会话统一保存。 */
public final class BookSourceEditor extends CodeEditor {
    private final BookSession session;
    private final Runnable save;

    public BookSourceEditor(BookSession session, Runnable save) {
        this.session = session;
        this.save = save;
        getTextAreaStyle().viewMode(com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode.VERTICAL);
        setLinesResponder(lines -> session.draft(String.join("\n", lines), selectionSnapshot()));
    }

    private BookSession.SourceSelection selectionSnapshot() {
        return new BookSession.SourceSelection(
                getCursorLine(),
                getCursorCol(),
                getSelStartLine(),
                getSelStartCol(),
                getSelEndLine(),
                getSelEndCol());
    }

    public void restoreSource() {
        setValue(session.source().split("\n", -1), false);
        var s = session.sourceSelection();
        setCursor(s.line(), s.column());
        setSelection(
                clampCursor(s.startLine(), s.startColumn()),
                clampCursor(s.endLine(), s.endColumn()));
    }

    private Cursor clampCursor(int line, int column) {
        var lines = getValue();
        int l = Math.clamp(line, 0, lines.length - 1);
        return new Cursor(l, Math.clamp(column, 0, lines[l].length()));
    }

    @Override
    public void pushHistory() {
        if (session != null) session.sourceSelection(selectionSnapshot());
    }

    @Override
    protected void onExecuteCommand(UIEvent event) {
        if (CommandEvents.UNDO.equals(event.command)) {
            session.undo();
            event.stopPropagation();
        } else if (CommandEvents.REDO.equals(event.command)) {
            session.redo();
            event.stopPropagation();
        } else super.onExecuteCommand(event);
    }

    private String selectedSourceText() {
        int a = getSelStartLine(), b = getSelStartCol(), c = getSelEndLine(), d = getSelEndCol();
        if (a > c || a == c && b > d) {
            int line = a, col = b;
            a = c;
            b = d;
            c = line;
            d = col;
        }
        var lines = getValue();
        var text = new StringBuilder();
        for (int i = a; i <= c; i++) {
            if (i > a) text.append('\n');
            text.append(lines[i], i == a ? b : 0, i == c ? d : lines[i].length());
        }
        return text.toString();
    }

    @Override
    public boolean ownsKey(UIEvent event) {
        return (event.modifiers & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0
                        && java.util.Set.of(
                                        GLFW_KEY_A,
                                        GLFW_KEY_C,
                                        GLFW_KEY_V,
                                        GLFW_KEY_X,
                                        GLFW_KEY_S,
                                        GLFW_KEY_Z,
                                        GLFW_KEY_LEFT,
                                        GLFW_KEY_RIGHT)
                                .contains(event.keyCode)
                || super.ownsKey(event);
    }

    @Override
    protected void onKeyDown(UIEvent event) {
        boolean modifier =
                event.isCtrlDown() || (event.modifiers & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0;
        if (modifier && (event.keyCode == GLFW_KEY_LEFT || event.keyCode == GLFW_KEY_RIGHT)
                || !modifier && (event.keyCode == GLFW_KEY_HOME || event.keyCode == GLFW_KEY_END)) {
            int index = visualRow(cursorPos());
            var row = wrappedRows.get(index);
            var anchor = new Cursor(getSelStartLine(), getSelStartCol());
            boolean end = event.keyCode == GLFW_KEY_RIGHT || event.keyCode == GLFW_KEY_END;
            rowHint = index;
            rowHintCursor = new Cursor(row.line(), end ? row.to() : row.from());
            setCursor(rowHintCursor.line(), rowHintCursor.col());
            if (event.isShiftDown() || (event.modifiers & GLFW_MOD_SHIFT) != 0)
                setSelection(anchor, cursorPos());
            else collapseSelectionToCursor();
            event.stopPropagation();
        } else if (modifier) {
            var clipboard = net.minecraft.client.Minecraft.getInstance().keyboardHandler;
            switch (event.keyCode) {
                case GLFW_KEY_Z -> {
                    if (event.isShiftDown() || (event.modifiers & GLFW_MOD_SHIFT) != 0)
                        session.redo();
                    else session.undo();
                }
                case GLFW_KEY_A -> selectAll();
                case GLFW_KEY_C -> clipboard.setClipboard(selectedSourceText());
                case GLFW_KEY_X -> {
                    clipboard.setClipboard(selectedSourceText());
                    insertText("");
                }
                case GLFW_KEY_V -> insertText(clipboard.getClipboard());
                case GLFW_KEY_S -> save.run();
                default -> {
                    super.onKeyDown(event);
                    return;
                }
            }
            event.stopPropagation();
        } else super.onKeyDown(event);
    }

    private record VisualRow(int line, int from, int to) {}

    private final java.util.List<VisualRow> wrappedRows = new java.util.ArrayList<>();
    private final java.util.List<float[]> advances = new java.util.ArrayList<>();
    private boolean wrapDirty = true, changingLines;
    private float wrappedWidth = -1, wrappedScale = -1, wrappedScroll;
    private net.minecraft.resources.ResourceLocation wrappedFont;
    private Cursor rowHintCursor;
    private int rowHint = -1;

    @Override
    public BookSourceEditor setValue(String[] value, boolean notify) {
        changingLines = true;
        try {
            super.setValue(value, notify);
        } finally {
            changingLines = false;
        }
        wrapDirty = true;
        wrappedScroll = 0;
        updateScrollers();
        return this;
    }

    @Override
    protected void onRawLinesUpdated() {
        changingLines = true;
        try {
            super.onRawLinesUpdated();
        } finally {
            changingLines = false;
        }
        wrapDirty = true;
        ensureCursorVisible();
    }

    private void wrapLines() {
        float width = Math.max(1, contentView.getContentWidth() - 1), scale = scale();
        var fontId = getTextAreaStyle().font();
        if (!wrapDirty
                && width == wrappedWidth
                && scale == wrappedScale
                && fontId.equals(wrappedFont)) return;
        wrappedRows.clear();
        advances.clear();
        rowHint = -1;
        rowHintCursor = null;
        var styled = getStyledLines();
        for (int line = 0; line < lines.size(); line++) {
            String text = lines.get(line);
            float[] prefix = new float[text.length() + 1];
            int offset = 0;
            for (var token : styled.get(line).text()) {
                for (int p = 0; p < token.text().length(); ) {
                    int end = p + Character.charCount(token.text().codePointAt(p));
                    var ch =
                            net.minecraft.network.chat.Component.literal(
                                            token.text().substring(p, end))
                                    .withStyle(style -> style.withFont(fontId))
                                    .withStyle(token.style());
                    float advance = getFont().getSplitter().stringWidth(ch) * scale;
                    for (int j = 1; j < end - p; j++) prefix[offset + j] = prefix[offset];
                    prefix[offset + end - p] = prefix[offset] + advance;
                    offset += end - p;
                    p = end;
                }
            }
            advances.add(prefix);
            int start = 0;
            for (int p = 0; p < text.length(); ) {
                int end = p + Character.charCount(text.codePointAt(p));
                if (p > start && prefix[end] - prefix[start] > width) {
                    wrappedRows.add(new VisualRow(line, start, p));
                    start = p;
                }
                p = end;
            }
            wrappedRows.add(new VisualRow(line, start, text.length()));
        }
        wrappedWidth = width;
        wrappedScale = scale;
        wrappedFont = fontId;
        wrapDirty = false;
    }

    private int visualRow(Cursor cursor) {
        wrapLines();
        if (rowHint >= 0 && rowHint < wrappedRows.size() && cursor.equals(rowHintCursor))
            return rowHint;
        int found = 0;
        for (int i = 0; i < wrappedRows.size(); i++) {
            var row = wrappedRows.get(i);
            if (row.line() == cursor.line() && row.from() <= cursor.col()) {
                found = i;
                if (cursor.col() < row.to()) break;
            }
        }
        return found;
    }

    private float rowX(VisualRow row, int column) {
        var prefix = advances.get(row.line());
        return prefix[Math.clamp(column, row.from(), row.to())] - prefix[row.from()];
    }

    private Cursor rowCursor(int index, float x) {
        var row = wrappedRows.get(index);
        String text = lines.get(row.line());
        int column = row.from();
        while (column < row.to()) {
            int next = column + Character.charCount(text.codePointAt(column));
            if (x < (rowX(row, column) + rowX(row, next)) / 2) break;
            column = next;
        }
        rowHint = index;
        rowHintCursor = new Cursor(row.line(), column);
        return rowHintCursor;
    }

    private float maxWrappedScroll() {
        return Math.max(0, wrappedRows.size() * lineHeight() - contentView.getContentHeight());
    }

    @Override
    protected void updateScrollers() {
        if (wrappedRows == null || changingLines || contentView.getContentWidth() <= 0) return;
        wrapLines();
        wrappedScroll = Math.clamp(wrappedScroll, 0, maxWrappedScroll());
        horizontalScroller.setDisplay(false);
        verticalScroller.setDisplay(maxWrappedScroll() > 0);
        verticalScroller.setScrollBarSize(
                Math.min(
                                1,
                                contentView.getContentHeight()
                                        / Math.max(1, wrappedRows.size() * lineHeight()))
                        * 100);
        verticalScroller.setValue(
                maxWrappedScroll() == 0 ? 0 : wrappedScroll / maxWrappedScroll(), false);
    }

    @Override
    protected void onVerticalScroll(float value) {
        wrapLines();
        wrappedScroll = Math.clamp(value, 0, 1) * maxWrappedScroll();
    }

    @Override
    protected float verticalClamp(float value) {
        wrapLines();
        return maxWrappedScroll() == 0 ? 0 : Math.signum(value) * lineHeight() / maxWrappedScroll();
    }

    @Override
    public float getScrollY() {
        return wrappedScroll;
    }

    @Override
    public float getScrollX() {
        return 0;
    }

    @Override
    protected void ensureCursorVisible() {
        if (wrappedRows == null || changingLines || contentView.getContentWidth() <= 0) return;
        wrapLines();
        float top = visualRow(cursorPos()) * lineHeight();
        if (top < wrappedScroll) wrappedScroll = top;
        if (top + lineHeight() > wrappedScroll + contentView.getContentHeight())
            wrappedScroll = top + lineHeight() - contentView.getContentHeight();
        updateScrollers();
    }

    @Override
    public Cursor getCursorUnderMouse(double mouseX, double mouseY) {
        wrapLines();
        int row =
                Math.clamp(
                        (int)
                                Math.floor(
                                        (mouseY - contentView.getContentY() + wrappedScroll)
                                                / lineHeight()),
                        0,
                        wrappedRows.size() - 1);
        return rowCursor(row, (float) mouseX - contentView.getContentX());
    }

    private void moveVisualRow(int delta) {
        int current = visualRow(cursorPos());
        float x = rowX(wrappedRows.get(current), getCursorCol());
        var cursor = rowCursor(Math.clamp(current + delta, 0, wrappedRows.size() - 1), x);
        setCursor(cursor.line(), cursor.col());
    }

    @Override
    protected void moveUp() {
        moveVisualRow(-1);
    }

    @Override
    protected void moveDown() {
        moveVisualRow(1);
    }

    @Override
    protected void page(int direction) {
        moveVisualRow(
                direction * Math.max(1, (int) (contentView.getContentHeight() / lineHeight())));
    }

    @Override
    public void drawContentView(com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext context) {
        updateScrollers();
        var start = new Cursor(getSelStartLine(), getSelStartCol());
        var end = new Cursor(getSelEndLine(), getSelEndCol());
        if (start.line() > end.line() || start.line() == end.line() && start.col() > end.col()) {
            var swap = start;
            start = end;
            end = swap;
        }
        float x = contentView.getContentX(), y = contentView.getContentY();
        int first = Math.max(0, (int) (wrappedScroll / lineHeight()));
        int last =
                Math.min(
                        wrappedRows.size() - 1,
                        first + (int) Math.ceil(contentView.getContentHeight() / lineHeight()));
        for (int i = first; i <= last; i++) {
            var row = wrappedRows.get(i);
            float top = y + i * lineHeight() - wrappedScroll;
            if (isFocused()
                    && hasSelection()
                    && row.line() >= start.line()
                    && row.line() <= end.line()) {
                int from =
                        row.line() == start.line() ? Math.max(row.from(), start.col()) : row.from();
                int to = row.line() == end.line() ? Math.min(row.to(), end.col()) : row.to();
                if (to > from)
                    context.graphics.fill(
                            (int) (x + rowX(row, from)),
                            (int) top,
                            (int) Math.ceil(x + rowX(row, to)),
                            (int) (top + getTextAreaStyle().fontSize()),
                            0x88669dcc);
            }
            context.pose.pushPose();
            context.pose.translate(x, top, 0);
            context.pose.scale(scale(), scale(), 1);
            com.lowdragmc.lowdraglib2.gui.LDLibFonts.drawText(
                    context.graphics,
                    getFont(),
                    styledLineComponent(row.line(), row.from(), row.to()),
                    0,
                    0,
                    -1,
                    getTextAreaStyle().textShadow());
            context.pose.popPose();
        }
        if (isFocused() && System.currentTimeMillis() % 1000 < 500) {
            int row = visualRow(cursorPos());
            float left = x + rowX(wrappedRows.get(row), getCursorCol());
            float top = y + row * lineHeight() - wrappedScroll;
            context.graphics.fill(
                    (int) left,
                    (int) top,
                    (int) left + 1,
                    (int) (top + getTextAreaStyle().fontSize()),
                    getTextAreaStyle().cursorColor());
        }
    }
}
