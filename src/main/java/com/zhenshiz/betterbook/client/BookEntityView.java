package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.client.scene.FBOWorldSceneRenderer;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.zhenshiz.betterbook.data.BookEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import org.jsoup.nodes.Element;

/** 原生 Scene 实体预览，沿用旋转、平移、缩放和渲染资源释放逻辑。 */
final class BookEntityView extends Scene {
    private final String source;
    private Entity entity;
    private Runnable onSelect = () -> {};

    BookEntityView(Element element, boolean editable) {
        source = element.outerHtml();
        addClass("book-entity-scene");
        setTickWorld(false);
        setRenderFacing(false);
        setRenderSelect(false);
        try {
            var world = new TrackedDummyWorld();
            entity = BookEntity.read(element).create(world);
            if (Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity) == null)
                throw new IllegalArgumentException("Missing entity renderer");
            world.addEntity(entity);
            createScene(world, true, com.lowdragmc.lowdraglib2.math.Size.of(480, 320));
            ((FBOWorldSceneRenderer) getRenderer())
                    .setClearColor(239 / 255f, 228 / 255f, 199 / 255f, 1);
            resetCamera();
        } catch (RuntimeException e) {
            setDraggable(false);
            setScalable(false);
            var error = new Label().setText("gui.betterbook.entity_unavailable");
            error.getLayout().widthPercent(100).heightPercent(100).paddingAll(8);
            error.textStyle(s -> s.textWrap(TextWrap.WRAP).textShadow(false));
            error.addClass("book-entity-error");
            addChild(error);
        }
        getStyle().tooltips(Component.translatable("gui.betterbook.entity_controls"));
        addEventListener(
                UIEvents.MOUSE_DOWN,
                event -> {
                    if (editable && event.button == 0) onSelect.run();
                    event.stopPropagation();
                });
        addEventListener(UIEvents.DOUBLE_CLICK, event -> event.stopPropagation());
        addEventListener(UIEvents.MOUSE_WHEEL, event -> event.stopPropagation());
    }

    boolean matches(Element element) {
        return source.equals(element.outerHtml());
    }

    void onSelect(Runnable callback) {
        onSelect = callback;
    }

    void resetCamera() {
        if (entity == null || getRenderer() == null) return;
        var box = entity.getBoundingBox();
        setCenter(box.getCenter().toVector3f());
        float diameter =
                (float)
                        Math.sqrt(
                                box.getXsize() * box.getXsize()
                                        + box.getYsize() * box.getYsize()
                                        + box.getZsize() * box.getZsize());
        setZoom(Math.max(.8f, diameter * 1.35f));
        setCameraYawAndPitch(70, 12);
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (getRenderer() instanceof FBOWorldSceneRenderer fbo) {
            int width = Math.max(1, Math.round(getContentWidth() * 2));
            int height = Math.max(1, Math.round(getPaddingHeight() * 2));
            if (width != fbo.getResolutionWidth() || height != fbo.getResolutionHeight())
                fbo.setFBOSize(width, height);
        }
    }
}
