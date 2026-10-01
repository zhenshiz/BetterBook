package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.viscript_lib.gui.components.search.RegistrySearchBox;
import com.zhenshiz.betterbook.compat.BookJeiPlugin;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.Locale;

/** 沿用 VSL 注册 ID 补全交互，候选覆盖 JEI 的原版和模组自定义配方。 */
final class RecipeIdSearchBox extends RegistrySearchBox<ResourceLocation> {
    RecipeIdSearchBox(String selected) {
        // JEI 查询留在客户端线程；VSL 的异步搜索只读取 ID 快照。
        this(selected, ModList.get().isLoaded("jei") ? BookJeiPlugin.recipeIds() : List.of());
    }

    private RecipeIdSearchBox(String selected, List<ResourceLocation> ids) {
        super(
                selected.isBlank() ? null : ResourceLocation.tryParse(selected),
                () -> null,
                id -> id,
                ResourceLocation::toString,
                (word, handler) -> {
                    String query = word.toLowerCase(Locale.ROOT);
                    ids.stream()
                            .takeWhile(id -> !Thread.currentThread().isInterrupted())
                            .filter(id -> id.toString().contains(query))
                            .forEach(handler::acceptResult);
                },
                UIElementProvider.text(id -> Component.literal(id.toString())));
    }
}
