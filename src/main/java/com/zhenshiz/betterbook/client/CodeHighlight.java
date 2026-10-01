package com.zhenshiz.betterbook.client;

import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import java.util.*;

import javax.swing.text.Segment;

/** 使用 RSyntaxTextArea 的词法分析器生成颜色，不创建 Swing 窗口或编辑器。 */
public final class CodeHighlight {
    public static final int TEXT = 0xd9e2ec;
    public static final int PAPER_TEXT = 0x403226;

    private CodeHighlight() {}

    public static String language(String value) {
        String name = value.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("language-")) name = name.substring(9);
        if (name.startsWith("text/")) name = name.substring(5);
        return switch (name) {
            case "js", "mjs", "kubejs" -> "javascript";
            case "ts" -> "typescript";
            case "py" -> "python";
            case "kt", "kts" -> "kotlin";
            case "c++", "cxx", "hpp" -> "cpp";
            case "c#", "csharp" -> "cs";
            case "sh", "bash", "shell" -> "unix";
            case "yml" -> "yaml";
            case "md" -> "markdown";
            case "go" -> "golang";
            case "rs" -> "rust";
            case "rb" -> "ruby";
            case "lss" -> "css";
            case "jsonc" -> "jshintrc";
            case "ps1" -> "powershell";
            case "", "text", "txt", "plaintext" -> "plain";
            default -> name;
        };
    }

    /** 返回每个 UTF-16 偏移的颜色；跨行保留注释、字符串等词法状态。 */
    public static int[] colors(String text, String language) {
        return colors(text, language, false);
    }

    public static int[] colors(String text, String language, boolean paper) {
        var maker =
                TokenMakerFactory.getDefaultInstance().getTokenMaker("text/" + language(language));
        int[] result = new int[text.length()];
        Arrays.fill(result, paper ? PAPER_TEXT : TEXT);
        int offset = 0, state = TokenTypes.NULL;
        for (String line : text.split("\n", -1)) {
            var chars = line.toCharArray();
            var token = maker.getTokenList(new Segment(chars, 0, chars.length), state, offset);
            state = TokenTypes.NULL;
            while (token != null) {
                if (token.isPaintable()) {
                    int start = Math.clamp(token.getOffset(), 0, result.length);
                    int end = Math.clamp(token.getOffset() + token.length(), start, result.length);
                    Arrays.fill(result, start, end, color(token.getType(), paper));
                }
                state = token.getType();
                token = token.getNextToken();
            }
            offset += line.length() + 1;
        }
        return result;
    }

    private static int color(int token, boolean paper) {
        return switch (token) {
            case TokenTypes.COMMENT_EOL,
                    TokenTypes.COMMENT_MULTILINE,
                    TokenTypes.COMMENT_DOCUMENTATION,
                    TokenTypes.COMMENT_KEYWORD,
                    TokenTypes.COMMENT_MARKUP,
                    TokenTypes.MARKUP_COMMENT ->
                    paper ? 0x75684f : 0x7f9484;
            case TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.LITERAL_BOOLEAN ->
                    paper ? 0x754581 : 0xc792ea;
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE,
                    TokenTypes.LITERAL_CHAR,
                    TokenTypes.LITERAL_BACKQUOTE,
                    TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE ->
                    paper ? 0x47622e : 0xc3e88d;
            case TokenTypes.LITERAL_NUMBER_DECIMAL_INT,
                    TokenTypes.LITERAL_NUMBER_FLOAT,
                    TokenTypes.LITERAL_NUMBER_HEXADECIMAL ->
                    paper ? 0xa14e2d : 0xf78c6c;
            case TokenTypes.FUNCTION -> paper ? 0x315f78 : 0x82aaff;
            case TokenTypes.DATA_TYPE, TokenTypes.ANNOTATION, TokenTypes.PREPROCESSOR ->
                    paper ? 0x805e23 : 0xffcb6b;
            case TokenTypes.OPERATOR, TokenTypes.MARKUP_TAG_DELIMITER ->
                    paper ? 0x536674 : 0x89ddff;
            case TokenTypes.MARKUP_TAG_NAME -> paper ? 0x944343 : 0xf07178;
            case TokenTypes.MARKUP_TAG_ATTRIBUTE, TokenTypes.VARIABLE ->
                    paper ? 0x805e23 : 0xffcb6b;
            default -> paper ? PAPER_TEXT : TEXT;
        };
    }
}
