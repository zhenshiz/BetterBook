package com.zhenshiz.betterbook.compat;

import com.zhenshiz.betterbook.BetterBook;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.resources.ResourceLocation;

import java.util.*;

/** 通过 JEI 注册的分类和配方 ID 创建原生布局，保留模组自定义绘制与动画。 */
@JeiPlugin
public final class BookJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(BetterBook.ID, "book_recipes");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime value) {
        runtime = value;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /**
     * 是否已有可用的 JEI 配方运行时。
     *
     * @return 运行时就绪时为真
     */
    public static boolean ready() {
        return runtime != null;
    }

    /**
     * 在客户端线程获取当前 JEI 可见配方的注册 ID 快照。
     *
     * @return 按 ID 排序且去重的不可变列表；运行时未就绪时为空
     */
    public static List<ResourceLocation> recipeIds() {
        if (runtime == null) return List.of();
        var ids = new TreeSet<ResourceLocation>(Comparator.comparing(ResourceLocation::toString));
        for (var category :
                runtime.getRecipeManager().createRecipeCategoryLookup().get().toList()) {
            collectIds(category, ids);
        }
        return List.copyOf(ids);
    }

    private static <T> void collectIds(IRecipeCategory<T> category, Set<ResourceLocation> ids) {
        try (var recipes =
                runtime.getRecipeManager().createRecipeLookup(category.getRecipeType()).get()) {
            recipes.forEach(
                    recipe -> {
                        var id = category.getRegistryName(recipe);
                        if (id != null) ids.add(id);
                    });
        } catch (RuntimeException e) {
            BetterBook.LOGGER.warn(
                    "Cannot list book recipes in category {}",
                    category.getRecipeType().getUid(),
                    e);
        }
    }

    /**
     * 查找 JEI 中可见、具有指定 ID 的配方并创建独立布局。
     *
     * @param id 配方注册 ID
     * @return 独立的 JEI 绘制布局
     * @throws IllegalArgumentException JEI 未就绪、ID 无效或分类无法创建布局时抛出
     */
    public static IRecipeLayoutDrawable<?> layout(String id) {
        if (runtime == null) throw new IllegalArgumentException("JEI is not ready");
        var location = ResourceLocation.tryParse(id);
        if (location == null) throw new IllegalArgumentException("Invalid recipe ID");
        for (var category :
                runtime.getRecipeManager().createRecipeCategoryLookup().get().toList()) {
            var result = find(category, location);
            if (result.isPresent()) return result.get();
        }
        throw new IllegalArgumentException("Recipe is not available in JEI: " + id);
    }

    private static <T> Optional<IRecipeLayoutDrawable<T>> find(
            IRecipeCategory<T> category, ResourceLocation id) {
        var manager = runtime.getRecipeManager();
        try (var recipes = manager.createRecipeLookup(category.getRecipeType()).get()) {
            var iterator = recipes.iterator();
            while (iterator.hasNext()) {
                T recipe = iterator.next();
                if (id.equals(category.getRegistryName(recipe)))
                    return manager.createRecipeLayoutDrawable(
                            category,
                            recipe,
                            runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup(),
                            (g, x, y, w, h) -> {},
                            0);
            }
        } catch (RuntimeException e) {
            BetterBook.LOGGER.warn(
                    "Cannot resolve book recipe {} in category {}",
                    id,
                    category.getRecipeType().getUid(),
                    e);
        }
        return Optional.empty();
    }
}
