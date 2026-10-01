package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;

import org.jsoup.nodes.Element;

import java.util.*;

/** 书页中的配方画布；坐标采用逻辑像素，随书页等比缩放。 */
public final class BookRecipe implements IPersistedSerializable {
    @Persisted public int width = 180, height = 100;
    @Persisted public boolean jei;
    @Persisted public String recipe = "";
    @Persisted public List<Part> parts = new ArrayList<>();

    /** 自由布局中的展示组件，不绑定玩家背包槽位。 */
    public static final class Part implements IPersistedSerializable {
        @Persisted public String id = UUID.randomUUID().toString(), kind = "slot";
        @Persisted public int x, y, width = 18, height = 18, direction;
        @Persisted
        public String stack = "{}", image = "minecraft:textures/block/crafting_table_front.png";

        /**
         * 解码此组件的物品。
         *
         * @param registries 当前注册表
         * @return 展示用物品栈
         */
        public ItemStack item(HolderLookup.Provider registries) {
            return BookItem.read(new Element("div").attr("data-stack", stack), registries);
        }
    }

    /**
     * 从 HTML 中读取画布，损坏的数据由调用方展示错误，原属性保持不变。
     *
     * @param element 画布节点
     * @param registries 当前注册表
     * @return 已校验的画布
     * @throws IllegalArgumentException 无效尺寸、类型或数据时抛出
     */
    public static BookRecipe read(Element element, HolderLookup.Provider registries) {
        var recipe = new BookRecipe();
        String data = element.attr("data-recipe");
        if (!data.isBlank()) {
            if (data.length() > 1048576)
                throw new IllegalArgumentException("Recipe data is too large");
            try {
                recipe.deserializeNBT(registries, TagParser.parseTag(data));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid recipe data", e);
            }
        }
        recipe.validate();
        return recipe;
    }

    /**
     * 写入画布，使用 LDLib2 持久化和 HTML 属性转义。
     *
     * @param element 目标节点
     * @param registries 当前注册表
     */
    public void write(Element element, HolderLookup.Provider registries) {
        validate();
        element.attr("data-recipe", serializeNBT(registries).toString())
                .attr("data-recipe-width", Integer.toString(width))
                .attr("data-recipe-height", Integer.toString(height));
    }

    /**
     * 检查画布尺寸、组件类型和组件边界。
     *
     * @throws IllegalArgumentException 数据不满足画布约束时抛出
     */
    public void validate() {
        if (width < 72 || width > 512 || height < 36 || height > 512 || parts.size() > 128)
            throw new IllegalArgumentException("Invalid recipe canvas size");
        var ids = new HashSet<String>();
        for (var part : parts) {
            if (!ids.add(part.id)
                    || !Set.of("slot", "arrow", "image", "item").contains(part.kind)
                    || part.width < 8
                    || part.height < 8
                    || part.width > width
                    || part.height > height
                    || part.x < 0
                    || part.y < 0
                    || part.x + part.width > width
                    || part.y + part.height > height
                    || part.direction < 0
                    || part.direction > 3)
                throw new IllegalArgumentException("Invalid recipe component");
        }
    }

    /**
     * 添加默认组件，并将位置限制在画布内。
     *
     * @param kind 组件类型
     * @param x 水平坐标
     * @param y 垂直坐标
     * @return 新增组件
     */
    public Part add(String kind, int x, int y) {
        if (parts.size() >= 128) throw new IllegalArgumentException("Too many recipe components");
        var part = new Part();
        part.kind = kind;
        if (kind.equals("arrow")) {
            part.width = 24;
            part.height = 16;
        }
        if (kind.equals("image")) part.width = part.height = 24;
        place(part, x, y);
        parts.add(part);
        validate();
        return part;
    }

    /**
     * 将组件移动到画布内。
     *
     * @param part 要移动的组件
     * @param x 水平坐标
     * @param y 垂直坐标
     */
    public void place(Part part, int x, int y) {
        part.width = Math.clamp(part.width, 8, width);
        part.height = Math.clamp(part.height, 8, height);
        part.x = Math.clamp(x, 0, width - part.width);
        part.y = Math.clamp(y, 0, height - part.height);
    }

    /**
     * 查找组件。
     *
     * @param id 稳定组件标识
     * @return 对应组件，缺失时为空
     */
    public Optional<Part> part(String id) {
        return parts.stream().filter(p -> p.id.equals(id)).findFirst();
    }
}
