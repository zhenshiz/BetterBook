package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.gui.texture.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import com.zhenshiz.betterbook.data.BookRecipe;

import dev.vfyjxf.taffy.style.TaffyPosition;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

import org.jsoup.nodes.Element;

import java.util.*;
import java.util.function.*;

/** 可拖放的配方画布，编辑器与阅读器共用同一套物品槽及图片绘制。 */
final class BookRecipeView extends UIElement {
    record Palette(String kind) {}

    private record Move(BookRecipeView owner, String id, float offsetX, float offsetY) {}

    private record DragPreview(String id, float x, float y) {}

    private final String source;
    private BookRecipe data;
    private final boolean editable;
    private final Map<String, UIElement> parts = new HashMap<>();
    private Consumer<String> selected = id -> {};
    private Consumer<BookRecipe> changed = recipe -> {};
    private String selection = "";
    private DragPreview dragPreview;

    BookRecipeView(Element element, boolean editable) {
        this.editable = editable;
        source = element.outerHtml();
        addClass("book-recipe-canvas");
        if (editable) addClass("book-recipe-editable");
        try {
            data = BookRecipe.read(element, Platform.getFrozenRegistry());
            if (data.jei) {
                if (!ModList.get().isLoaded("jei"))
                    throw new IllegalArgumentException("JEI is not installed");
                var view = new BookJeiView(data.recipe, editable);
                view.getLayout().widthPercent(100).heightPercent(100);
                addChild(view);
            } else {
                for (var part : data.parts) {
                    var child = partView(part);
                    child.setId("recipe-part-" + part.id);
                    child.addClass("book-recipe-part");
                    parts.put(part.id, child);
                    addChild(child);
                    if (editable) {
                        child.addEventListener(
                                UIEvents.MOUSE_DOWN,
                                e -> {
                                    if (e.button != 0) return;
                                    selectPart(part.id);
                                    var point = logical(e);
                                    child.startDrag(
                                            new Move(
                                                    this,
                                                    part.id,
                                                    point[0] - part.x,
                                                    point[1] - part.y),
                                            null);
                                    e.stopPropagation();
                                    e.hasHandler = true;
                                },
                                true);
                        child.addEventListener(
                                UIEvents.DRAG_SOURCE_UPDATE,
                                e -> {
                                    if (e.dragHandler.draggingObject instanceof Move move
                                            && move.owner == this) {
                                        var point = logical(e);
                                        float nx =
                                                Math.clamp(
                                                        point[0] - move.offsetX,
                                                        0,
                                                        data.width - part.width);
                                        float ny =
                                                Math.clamp(
                                                        point[1] - move.offsetY,
                                                        0,
                                                        data.height - part.height);
                                        dragPreview = new DragPreview(part.id, nx, ny);
                                        layoutParts();
                                    }
                                });
                        child.addEventListener(
                                UIEvents.DRAG_END,
                                e -> {
                                    dragPreview = null;
                                    layoutParts();
                                });
                    }
                }
                if (data.parts.isEmpty() && editable) {
                    var hint = new Label().setText("gui.betterbook.recipe_drag_hint");
                    hint.addClass("book-recipe-hint");
                    hint.getLayout().widthPercent(100).heightPercent(100).paddingAll(8);
                    hint.textStyle(
                            s ->
                                    s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP)
                                            .textShadow(false));
                    addChild(hint);
                }
            }
        } catch (Exception e) {
            var label =
                    new Label()
                            .setText(
                                    net.minecraft.network.chat.Component.translatable(
                                            "gui.betterbook.recipe_error", e.getMessage()));
            label.addClass("book-entity-error");
            label.getLayout().widthPercent(100).heightPercent(100).paddingAll(8);
            label.textStyle(
                    s ->
                            s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP)
                                    .textShadow(false));
            addChild(label);
        }
        addEventListener(
                UIEvents.MOUSE_DOWN,
                e -> {
                    if (editable && e.button == 0) selectPart("");
                    e.stopPropagation();
                });
        addEventListener(UIEvents.DOUBLE_CLICK, UIEvent::stopPropagation);
        if (editable) addEventListener(UIEvents.DRAG_PERFORM, this::drop, true);
    }

    boolean matches(Element element) {
        return source.equals(element.outerHtml());
    }

    void callbacks(Consumer<String> select, Consumer<BookRecipe> change) {
        selected = select;
        changed = change;
    }

    String selectedPart() {
        return selection;
    }

    void highlight(String id) {
        selection = id;
        parts.forEach(
                (key, part) -> {
                    if (key.equals(id)) part.addClass("book-recipe-selected");
                    else part.removeClass("book-recipe-selected");
                });
    }

    void selectPart(String id) {
        highlight(id);
        selected.accept(id);
    }

    private float scale() {
        return data == null ? 1 : getSizeWidth() / data.width;
    }

    private float[] logical(UIEvent e) {
        var point = getLocalMouse(e.x, e.y);
        float scale = Math.max(.01f, scale());
        return new float[] {(point.x - getPositionX()) / scale, (point.y - getPositionY()) / scale};
    }

    private void drop(UIEvent e) {
        if (data == null || data.jei) return;
        var point = logical(e);
        Object drag = e.dragHandler.draggingObject;
        if (drag instanceof Palette palette) {
            var part = data.add(palette.kind, Math.round(point[0] - 9), Math.round(point[1] - 9));
            if (part.kind.equals("item"))
                part.stack =
                        com.zhenshiz.betterbook.data.BookItem.write(
                                Items.CRAFTING_TABLE.getDefaultInstance(),
                                Platform.getFrozenRegistry());
            selection = part.id;
        } else if (drag instanceof Move move && move.owner == this) {
            var part = data.part(move.id).orElseThrow();
            int x = Math.round(point[0] - move.offsetX), y = Math.round(point[1] - move.offsetY);
            if (x == part.x && y == part.y) return;
            data.place(part, x, y);
            selection = part.id;
        } else return;
        selected.accept(selection);
        changed.accept(data);
        e.stopPropagation();
        e.hasHandler = true;
    }

    private void layoutParts() {
        if (data == null) return;
        float scale = scale();
        for (var part : data.parts) {
            var child = parts.get(part.id);
            if (child == null) continue;
            // 布局重算也保留拖动中的预览位置，松手后才写入文档坐标。
            boolean dragging = dragPreview != null && dragPreview.id.equals(part.id);
            child.getLayout()
                    .positionType(TaffyPosition.ABSOLUTE)
                    .left((dragging ? dragPreview.x : part.x) * scale)
                    .top((dragging ? dragPreview.y : part.y) * scale)
                    .width(part.width * scale)
                    .height(part.height * scale);
            if (child instanceof ItemSlot) child.getLayout().paddingAll(scale);
        }
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        layoutParts();
    }

    private UIElement partView(BookRecipe.Part part) {
        if (part.kind.equals("slot")) {
            var slot = new ItemSlot();
            slot.setItem(part.item(Platform.getFrozenRegistry()), false);
            slot.addClass("book-item-slot");
            return slot;
        }
        var view = new UIElement();
        if (part.kind.equals("arrow")) view.getStyle().background(arrow(part.direction));
        if (part.kind.equals("item")) {
            var stack = part.item(Platform.getFrozenRegistry());
            view.getStyle().background(new ItemStackTexture(stack));
            if (!stack.isEmpty()) view.getStyle().tooltips(stack.getHoverName());
        }
        if (part.kind.equals("image") && ResourceLocation.tryParse(part.image) != null)
            view.getStyle().background(SpriteTexture.of(part.image));
        return view;
    }

    static IGuiTexture paletteTexture(String kind) {
        return switch (kind) {
            case "slot" -> ItemSlot.ITEM_SLOT_TEXTURE;
            case "arrow" -> arrow(0);
            case "item" -> new ItemStackTexture(Items.CRAFTING_TABLE);
            default -> SpriteTexture.of("minecraft:textures/block/crafting_table_front.png");
        };
    }

    private static IGuiTexture arrow(int direction) {
        return (graphics, mx, my, x, y, width, height, ticks) -> {
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(x + width / 2, y + height / 2, 0);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(direction * 90));
            float w = direction % 2 == 0 ? width : height, h = direction % 2 == 0 ? height : width;
            int half = Math.max(2, Math.round(w / 2)), thickness = Math.max(1, Math.round(h / 8));
            graphics.fill(-half, -thickness, half / 2, thickness, 0xff6d5535);
            for (int i = 0; i < half; i++) {
                int r = Math.max(1, Math.round((half - i) * h / Math.max(w, 1)));
                graphics.fill(i, -r, i + 1, r, 0xff6d5535);
            }
            pose.popPose();
        };
    }
}
