package com.zhenshiz.betterbook.data;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.jsoup.nodes.Element;

/** 书籍中的实体外观配置；实体只由客户端在独立预览世界中创建。 */
public record BookEntity(ResourceLocation id, CompoundTag nbt) {
    /**
     * 校验实体标识与 SNBT，空 NBT 按空复合标签处理。
     *
     * @param id 注册的实体资源标识
     * @param nbt SNBT 复合标签
     * @return 可保存的实体配置
     * @throws IllegalArgumentException 实体未注册、无法直接创建或 SNBT 不合法时抛出
     */
    public static BookEntity parse(String id, String nbt) {
        var location = ResourceLocation.tryParse(id.trim());
        var type =
                location == null
                        ? null
                        : BuiltInRegistries.ENTITY_TYPE.getOptional(location).orElse(null);
        if (type == null || type == EntityType.PLAYER)
            throw new IllegalArgumentException("Invalid entity ID: " + id);
        try {
            return new BookEntity(
                    location, nbt.isBlank() ? new CompoundTag() : TagParser.parseTag(nbt));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * 从 HTML 原子节点读取配置。
     *
     * @param element 实体节点
     * @return 校验后的实体配置
     */
    public static BookEntity read(Element element) {
        return parse(element.attr("data-entity-id"), element.attr("data-entity-nbt"));
    }

    /**
     * 更新 HTML 属性，由 Jsoup 完成引号转义。
     *
     * @param element 要更新的实体节点
     */
    public void writeTo(Element element) {
        element.attr("data-entity-id", id.toString()).attr("data-entity-nbt", nbt.toString());
    }

    /**
     * 创建并加载外观数据，随后将展示位置和旋转归一化。
     *
     * @param previewWorld 隔离的预览世界，不能传入玩家所在的真实世界
     * @return 尚未加入世界的展示实体
     * @throws IllegalArgumentException 实体无法创建时抛出
     */
    public Entity create(Level previewWorld) {
        var entity = BuiltInRegistries.ENTITY_TYPE.get(id).create(previewWorld);
        if (entity == null) throw new IllegalArgumentException("Cannot create entity: " + id);
        var data = entity.saveWithoutId(new CompoundTag());
        data.merge(nbt.copy());
        entity.load(data);
        entity.moveTo(0, 0, 0, 0, 0);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.xOld = entity.xo = 0;
        entity.yOld = entity.yo = 0;
        entity.zOld = entity.zo = 0;
        entity.yRotO = entity.xRotO = 0;
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = living.yBodyRotO = 0;
            living.yHeadRot = living.yHeadRotO = 0;
        }
        return entity;
    }
}
