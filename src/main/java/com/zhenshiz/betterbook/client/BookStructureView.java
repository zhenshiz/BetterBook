package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.client.scene.FBOWorldSceneRenderer;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.utils.data.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.zhenshiz.betterbook.data.BookSchematic;

import dev.vfyjxf.taffy.style.TaffyPosition;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

import org.jsoup.nodes.Element;

/** 使用原生 Scene 在书页中展示多方块结构，保留镜头拖拽、缩放及正交投影。 */
final class BookStructureView extends Scene {
    private final String source;
    private AABB bounds;
    private Runnable onSelect = () -> {};
    private final java.util.concurrent.CompletableFuture<net.minecraft.nbt.CompoundTag> loading;
    private final boolean ortho;
    private boolean loaded;
    private BookSchematic.Snapshot snapshot;
    private final Button project = new Button();
    private final Label status = new Label();

    BookStructureView(Element element, boolean editable) {
        source = element.outerHtml();
        addClass("book-structure-scene");
        setTickWorld(false);
        setRenderFacing(false);
        setRenderSelect(false);
        ortho = Boolean.parseBoolean(element.attr("data-structure-ortho"));
        loading = StructureFiles.load(element.attr("data-structure-file"));
        setDraggable(false);
        setScalable(false);
        status.setText("gui.betterbook.structure_loading");
        status.getLayout().widthPercent(100).heightPercent(100).paddingAll(8);
        status.textStyle(style -> style.textWrap(TextWrap.WRAP).textShadow(false));
        status.addClass("book-entity-error");
        addChild(status);
        if (!editable && Boolean.parseBoolean(element.attr("data-structure-projectable"))) {
            project.setText("gui.betterbook.projection_start");
            project.text.textStyle(style -> style.textColor(0xff362a21).textShadow(false));
            project.addClass("book-structure-project");
            project.getLayout()
                    .positionType(TaffyPosition.ABSOLUTE)
                    .right(5)
                    .bottom(5)
                    .height(20)
                    .paddingHorizontal(8);
            project.setActive(false);
            project.setOnClick(
                    event -> {
                        event.stopPropagation();
                        if (snapshot == null) return;
                        var error =
                                StructureProjectionClient.begin(
                                        snapshot,
                                        Boolean.parseBoolean(
                                                element.attr(
                                                        "data-structure-projection-editable")));
                        if (error != null) {
                            status.setText(error);
                            if (status.getParent() == null) addChild(status);
                        }
                    });
            addChild(project);
        }
        finishLoading();
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

    @Override
    public void screenTick() {
        super.screenTick();
        finishLoading();
    }

    private void finishLoading() {
        if (loaded || !loading.isDone()) return;
        loaded = true;
        try {
            snapshot = BookSchematic.decode(loading.join());
            removeChild(status);
            setDraggable(true);
            setScalable(true);
            bounds = snapshot.bounds();
            var world = new TrackedDummyWorld();
            snapshot.blocks()
                    .forEach(
                            (pos, state) -> {
                                if (state.isAir()) return;
                                var info = BlockInfo.fromBlockState(state);
                                var tag = snapshot.tiles().get(pos);
                                if (tag != null)
                                    info.setPostCreate(
                                            be ->
                                                    be.loadWithComponents(
                                                            tag, world.registryAccess()));
                                world.addBlock(pos, info);
                            });
            for (var tag : snapshot.entities()) {
                EntityType.loadEntityRecursive(
                        tag,
                        world,
                        entity -> {
                            entity.xOld = entity.xo = entity.getX();
                            entity.yOld = entity.yo = entity.getY();
                            entity.zOld = entity.zo = entity.getZ();
                            world.addEntity(entity);
                            return entity;
                        });
            }
            createScene(world, true, com.lowdragmc.lowdraglib2.math.Size.of(480, 360));
            ((FBOWorldSceneRenderer) getRenderer())
                    .setClearColor(239 / 255f, 228 / 255f, 199 / 255f, 1);
            setRenderedCore(
                    snapshot.blocks().entrySet().stream()
                            .filter(e -> !e.getValue().isAir())
                            .map(java.util.Map.Entry::getKey)
                            .toList(),
                    null,
                    false);
            useOrtho(ortho);
            resetCamera();
            project.setActive(true);
        } catch (Exception e) {
            setDraggable(false);
            setScalable(false);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            status.setText(
                    Component.translatable(
                            "gui.betterbook.structure_unavailable", cause.getMessage()));
            if (status.getParent() == null) addChild(status);
        }
    }

    boolean matches(Element element) {
        return source.equals(element.outerHtml());
    }

    void onSelect(Runnable callback) {
        onSelect = callback;
    }

    void resetCamera() {
        if (bounds == null || getRenderer() == null) return;
        setCenter(bounds.getCenter().toVector3f());
        float diameter =
                (float)
                        Math.sqrt(
                                bounds.getXsize() * bounds.getXsize()
                                        + bounds.getYsize() * bounds.getYsize()
                                        + bounds.getZsize() * bounds.getZsize());
        setZoom(Math.max(2, diameter * 1.35f));
        setOrthoRange(.55f);
        setCameraYawAndPitch(-135, 25);
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (getRenderer() instanceof FBOWorldSceneRenderer fbo) {
            int w = Math.max(1, Math.round(getContentWidth() * 2)),
                    h = Math.max(1, Math.round(getPaddingHeight() * 2));
            if (w != fbo.getResolutionWidth() || h != fbo.getResolutionHeight())
                fbo.setFBOSize(w, h);
        }
    }
}
