package com.zhenshiz.betterbook.core;

import java.util.Objects;
import java.util.function.Predicate;

/** 根据共用阶段要求判断页面访问权限，读取书籍的当前页面顺序和提示。 */
public final class BookPageAccess {
    private final Book book;
    private final Predicate<String> hasStage;

    /**
     * 创建页面访问查询，阶段状态在每次权限查询时重新判断。
     *
     * @param book 要查询的书籍；阅读副本提供当前语言的已解析提示
     * @param hasStage 判断读者是否拥有指定阶段名称的谓词
     */
    public BookPageAccess(Book book, Predicate<String> hasStage) {
        this.book = Objects.requireNonNull(book);
        this.hasStage = Objects.requireNonNull(hasStage);
    }

    /**
     * 判断页面存在且阶段要求为空白或未设置，或读者拥有其要求的阶段。
     *
     * @param pageId 共用的页面 ID 字符串
     * @return 页面存在且允许阅读时为 <code>true</code>
     */
    public boolean canRead(String pageId) {
        if (!contains(pageId)) return false;
        String stage = book.requiredStages.get(pageId);
        return stage == null || stage.isBlank() || hasStage.test(stage);
    }

    /**
     * 判断共用页面列表是否包含指定 ID。
     *
     * @param pageId 共用的页面 ID 字符串
     * @return 页面存在时为 <code>true</code>
     */
    public boolean contains(String pageId) {
        return index(pageId) >= 0;
    }

    /**
     * 获取页面在共用顺序中的当前位置。
     *
     * @param pageId 共用的页面 ID 字符串
     * @return 从零开始的页面索引；页面不存在时为 <code>-1</code>
     */
    public int index(String pageId) {
        for (int i = 0; i < book.pages.size(); i++)
            if (book.pages.get(i).id().equals(pageId)) return i;
        return -1;
    }

    /**
     * 获取书籍当前默认语言的已解析解锁提示。
     *
     * @param pageId 共用的页面 ID 字符串
     * @return 提示字符串；提示为空或页面不存在时允许返回空字符串
     */
    public String unlockHint(String pageId) {
        return book.unlockHint(pageId, book.defaultLanguage);
    }
}
