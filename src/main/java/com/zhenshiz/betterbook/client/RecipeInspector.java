package com.zhenshiz.betterbook.client;

import static com.zhenshiz.betterbook.client.Widgets.*;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.accessors.ItemStackAccessor;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.zhenshiz.betterbook.compat.BookJeiPlugin;
import com.zhenshiz.betterbook.core.BookSession;
import com.zhenshiz.betterbook.data.*;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.*;
import java.util.function.Consumer;

/** 配方画布的组件面板，使用原生配置器编辑物品，所有修改进入书页撤销历史。 */
final class RecipeInspector extends UIElement {
    private final BookSession session;
    private final Runnable removed;
    private final UIElement fields = new UIElement();
    private final Label error = new Label();
    private final Button removeRecipe;
    private int block = -1;
    private String partId = "", source = "";
    private boolean applying;
    private BookRecipe value;
    private ItemStack stack = ItemStack.EMPTY;

    RecipeInspector(BookSession session, Runnable removed) {
        this.session = session;
        this.removed = removed;
        setId("recipe-panel");
        getLayout().widthPercent(100).flex(1).minHeight(0).minWidth(0);
        var scroll = new ScrollerView();
        scroll.getLayout().widthPercent(100).flex(1).minHeight(0);
        fields.getLayout().widthPercent(100).minWidth(0).gapAll(5).paddingAll(3);
        scroll.addScrollViewChild(fields);
        error.setId("recipe-error");
        error.getLayout().widthPercent(100).minWidth(0);
        error.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        error.setDisplay(false);
        removeRecipe =
                button(
                        "recipe_remove",
                        "recipe-remove",
                        () -> {
                            if (block < 0) return;
                            session.delete(1);
                            removed.run();
                        });
        addChildren(scroll, error, removeRecipe);
        setDisplay(false);
    }

    int block() {
        return block;
    }

    void hide() {
        block = -1;
        partId = "";
        setDisplay(false);
    }

    void show(int block, String part) {
        boolean different = this.block != block || !partId.equals(part);
        this.block = block;
        partId = part;
        setDisplay(true);
        refresh(different);
    }

    void refresh(boolean force) {
        if (block < 0 || applying) return;
        String current = session.document().blocks().get(block).element().outerHtml();
        if (!force && current.equals(source)) return;
        source = current;
        fields.clearAllChildren();
        error.setDisplay(false);
        try {
            value =
                    BookRecipe.read(
                            session.document().blocks().get(block).element(),
                            Platform.getFrozenRegistry());
            var part = value.jei ? Optional.<BookRecipe.Part>empty() : value.part(partId);
            removeRecipe.setDisplay(part.isEmpty());
            if (part.isPresent()) {
                component(part.get());
                return;
            }
            var jei = new Toggle().setText("gui.betterbook.recipe_use_jei");
            jei.setId("recipe-jei");
            jei.toggleButton.setId("recipe-jei-toggle");
            jei.setValue(value.jei, false);
            jei.setOnToggleChanged(on -> edit(data -> data.jei = on));
            fields.addChild(jei);
            dimensions();
            if (value.jei) {
                var id = recipeInput();
                fields.addChildren(
                        new Label().setText("gui.betterbook.recipe_id"),
                        id,
                        button(
                                "apply",
                                "recipe-jei-apply",
                                () -> {
                                    try {
                                        if (!ModList.get().isLoaded("jei"))
                                            throw new IllegalArgumentException(
                                                    "JEI is not installed");
                                        String selected = id.getSelectedIdString();
                                        BookJeiPlugin.layout(selected);
                                        edit(data -> data.recipe = selected);
                                    } catch (Exception e) {
                                        fail(e);
                                    }
                                }));
            } else {
                fields.addChild(new Label().setText("gui.betterbook.recipe_components"));
                for (String kind : List.of("slot", "arrow", "image", "item")) {
                    var palette = button("recipe_" + kind, "recipe-palette-" + kind, () -> {});
                    palette.addEventListener(
                            UIEvents.MOUSE_DOWN,
                            e -> {
                                if (e.button != 0) return;
                                palette.startDrag(
                                        new BookRecipeView.Palette(kind),
                                        BookRecipeView.paletteTexture(kind));
                                e.stopPropagation();
                                e.hasHandler = true;
                            },
                            true);
                    fields.addChild(palette);
                }
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private TextField input(String id, String value) {
        var input = new TextField();
        input.setId(id);
        input.setValue(value, false);
        input.getLayout().widthPercent(100).minWidth(0).height(20);
        return input;
    }

    private RecipeIdSearchBox recipeInput() {
        var input = new RecipeIdSearchBox(value.recipe);
        input.setId("recipe-id");
        input.getSearchStyle().closeAfterSelect(true);
        input.getLayout().widthPercent(100).minWidth(0).height(20);
        return input;
    }

    private void dimensions() {
        var width = input("recipe-width", Integer.toString(value.width));
        var height = input("recipe-height", Integer.toString(value.height));
        fields.addChildren(
                new Label().setText("gui.betterbook.recipe_canvas_size"),
                row().addChildren(width, height),
                button(
                        "apply",
                        "recipe-size-apply",
                        () ->
                                edit(
                                        data -> {
                                            data.width = Integer.parseInt(width.getValue());
                                            data.height = Integer.parseInt(height.getValue());
                                            if (data.width < 72
                                                    || data.width > 512
                                                    || data.height < 36
                                                    || data.height > 512)
                                                throw new IllegalArgumentException(
                                                        "Canvas size: 72–512 × 36–512");
                                            data.parts.forEach(
                                                    part -> data.place(part, part.x, part.y));
                                        })));
        width.getLayout().widthPercent(50);
        height.getLayout().widthPercent(50);
    }

    private void component(BookRecipe.Part part) {
        fields.addChild(new Label().setText("gui.betterbook.recipe_" + part.kind));
        var x = input("recipe-part-x", Integer.toString(part.x));
        var y = input("recipe-part-y", Integer.toString(part.y));
        var w = input("recipe-part-width", Integer.toString(part.width));
        var h = input("recipe-part-height", Integer.toString(part.height));
        fields.addChildren(
                new Label().setText("gui.betterbook.recipe_position"),
                row().addChildren(x, y),
                new Label().setText("gui.betterbook.recipe_component_size"),
                row().addChildren(w, h),
                button(
                        "apply",
                        "recipe-part-layout-apply",
                        () ->
                                edit(
                                        data -> {
                                            var selected = data.part(partId).orElseThrow();
                                            selected.width = Integer.parseInt(w.getValue());
                                            selected.height = Integer.parseInt(h.getValue());
                                            data.place(
                                                    selected,
                                                    Integer.parseInt(x.getValue()),
                                                    Integer.parseInt(y.getValue()));
                                        })));
        for (var input : List.of(x, y, w, h)) input.getLayout().widthPercent(50);
        if (part.kind.equals("slot") || part.kind.equals("item")) {
            stack = part.item(Platform.getFrozenRegistry());
            var config =
                    (ConfiguratorGroup)
                            new ItemStackAccessor()
                                    .create(
                                            "gui.betterbook.item",
                                            () -> stack,
                                            next -> {
                                                String encoded =
                                                        BookItem.write(
                                                                next, Platform.getFrozenRegistry());
                                                if (encoded.equals(part.stack)) return;
                                                applying = true;
                                                try {
                                                    edit(
                                                            data ->
                                                                    data.part(partId)
                                                                                    .orElseThrow()
                                                                                    .stack =
                                                                            encoded);
                                                    stack = next.copy();
                                                    part.stack = encoded;
                                                } finally {
                                                    applying = false;
                                                }
                                            },
                                            true,
                                            null,
                                            null);
            config.setId("recipe-item-configurator");
            config.setCollapse(false);
            config.getLayout().widthPercent(100).minWidth(0);
            fields.addChild(config);
        }
        if (part.kind.equals("image")) {
            var image = input("recipe-image", part.image);
            fields.addChildren(
                    new Label().setText("gui.betterbook.recipe_image_path"),
                    image,
                    button(
                            "apply",
                            "recipe-image-apply",
                            () -> {
                                var id = ResourceLocation.tryParse(image.getValue().trim());
                                if (id == null
                                        || Minecraft.getInstance()
                                                .getResourceManager()
                                                .getResource(id)
                                                .isEmpty()) {
                                    fail(new IllegalArgumentException("Image resource not found"));
                                    return;
                                }
                                edit(data -> data.part(partId).orElseThrow().image = id.toString());
                            }));
        }
        if (part.kind.equals("arrow")) {
            var direction = new Selector<Integer>();
            direction.setId("recipe-arrow-direction");
            direction.setCandidates(List.of(0, 1, 2, 3));
            direction.setCandidateUIProvider(
                    com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider.text(
                            dir ->
                                    Component.translatable(
                                            "gui.betterbook.recipe_direction_" + dir)));
            direction.setValue(part.direction, false);
            direction.setOnValueChanged(
                    dir -> edit(data -> data.part(partId).orElseThrow().direction = dir));
            direction.getLayout().widthPercent(100).height(20);
            fields.addChild(direction);
        }
        fields.addChild(
                button(
                        "recipe_component_remove",
                        "recipe-part-remove",
                        () -> edit(data -> data.parts.removeIf(p -> p.id.equals(partId)))));
    }

    void replace(BookRecipe data) {
        if (block < 0) return;
        try {
            data.validate();
            session.transact(
                    () -> {
                        data.write(
                                session.document().blocks().get(block).element(),
                                Platform.getFrozenRegistry());
                        session.document().invalidate();
                    });
            refresh(false);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void edit(Consumer<BookRecipe> action) {
        if (block < 0) return;
        try {
            var data =
                    BookRecipe.read(
                            session.document().blocks().get(block).element(),
                            Platform.getFrozenRegistry());
            action.accept(data);
            replace(data);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void fail(Exception e) {
        error.setText(Component.translatable("gui.betterbook.recipe_error", e.getMessage()));
        error.setDisplay(true);
    }
}
