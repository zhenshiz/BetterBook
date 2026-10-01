package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.TagParser;

import org.jsoup.nodes.Element;

import java.util.*;

/** 书内关联按钮组；目标保存为稳定页面 ID，页码和默认名称在显示时解析。 */
public final class BookRelatedPages implements IPersistedSerializable {
    @Persisted public String title = "";
    @Persisted public int columns = 4;
    @Persisted public boolean showNames;
    @Persisted public List<Entry> entries = new ArrayList<>();

    /** 一个页面入口的图标及可选名称。 */
    public static final class Entry implements IPersistedSerializable {
        @Persisted public String id = UUID.randomUUID().toString();
        @Persisted public String target = "", name = "", icon = "item", stack = "{}", image = "";
    }

    /**
     * 读取并检查关联组，允许目标尚未配置或已被删除。
     *
     * @param element 关联组节点
     * @param registries 当前注册表
     * @return 已校验的关联组
     * @throws IllegalArgumentException 属性损坏或超过大小限制
     */
    public static BookRelatedPages read(Element element, HolderLookup.Provider registries) {
        var data = new BookRelatedPages();
        String encoded = element.attr("data-related-pages");
        if (encoded.length() > 1048576)
            throw new IllegalArgumentException("Related pages data is too large");
        if (!encoded.isBlank()) {
            try {
                data.deserializeNBT(registries, TagParser.parseTag(encoded));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid related pages data", e);
            }
        }
        data.validate();
        return data;
    }

    /**
     * 使用 LDLib2 编码保存到 HTML 属性。
     *
     * @param element 目标节点
     * @param registries 当前注册表
     */
    public void write(Element element, HolderLookup.Provider registries) {
        validate();
        element.attr("data-related-pages", serializeNBT(registries).toString());
    }

    /**
     * 检查组尺寸、文本上限、图标类型及按钮 ID 唯一性。
     *
     * @throws IllegalArgumentException 数据超出限制
     */
    public void validate() {
        if (columns < 2 || columns > 6 || title.length() > 100 || entries.size() > 64)
            throw new IllegalArgumentException("Invalid related pages group");
        var ids = new HashSet<String>();
        for (var e : entries) {
            if (e.id.isBlank()
                    || e.id.length() > 128
                    || !ids.add(e.id)
                    || e.target.length() > 256
                    || e.name.length() > 100
                    || !Set.of("item", "image").contains(e.icon)
                    || e.stack.length() > 65536
                    || e.image.length() > 512)
                throw new IllegalArgumentException("Invalid related page button");
        }
    }

    /**
     * 按稳定 ID 查找按钮。
     *
     * @param id 按钮 ID
     * @return 对应按钮，缺失时为空
     */
    public Optional<Entry> entry(String id) {
        return entries.stream().filter(e -> e.id.equals(id)).findFirst();
    }

    /**
     * 计算当前宽度可容纳的列数。
     *
     * @param width 书页可用宽度
     * @return 至少一列，最多为配置列数
     */
    public int visibleColumns(float width) {
        return Math.max(1, Math.min(columns, (int) ((width - 16) / 36)));
    }

    /**
     * 计算关联组所需高度，留出标题、按钮名称和空白点击区域。
     *
     * @param width 书页可用宽度
     * @return 逻辑像素高度
     */
    public float height(float width) {
        return (title.isBlank() ? 8 : 28)
                + Math.max(1, (int) Math.ceil((double) entries.size() / visibleColumns(width)))
                        * (showNames ? 54 : 38)
                + 12;
    }
}
