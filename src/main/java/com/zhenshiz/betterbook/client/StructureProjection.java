package com.zhenshiz.betterbook.client;

import com.zhenshiz.betterbook.data.BookSchematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 仅用于客户端搭建引导的结构快照；旋转不修改书页或原始结构文件。 */
final class StructureProjection {
    static final int MAX_BLOCKS = 16384;
    // 检查放置属性，不把亮灭、供电、作物生长等运行状态当作搭建错误。
    private static final Set<String> PLACEMENT_PROPERTIES =
            Set.of(
                    "facing",
                    "axis",
                    "rotation",
                    "half",
                    "part",
                    "type",
                    "face",
                    "hinge",
                    "attachment",
                    "waterlogged",
                    "orientation");

    enum Status {
        MISSING,
        WRONG,
        MATCHED,
        UNLOADED
    }

    static final class Block {
        final BlockPos offset;
        final BlockState state;
        Status status = Status.UNLOADED;

        Block(BlockPos offset, BlockState state) {
            this.offset = offset;
            this.state = state;
        }
    }

    private final BookSchematic.Snapshot source;
    final boolean editable;
    List<Block> blocks = List.of();
    BlockPos anchor;
    boolean selecting = true;
    int turns, matched, wrong, unloaded;
    private int sizeX, sizeY, sizeZ;

    StructureProjection(BookSchematic.Snapshot source, boolean editable) {
        this.source = source;
        this.editable = editable;
        rebuild();
    }

    void rotate() {
        if (!editable) return;
        turns = (turns + 1) % 4;
        rebuild();
    }

    private void rebuild() {
        var bounds = source.bounds();
        var min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        int sx = (int) bounds.getXsize(), sz = (int) bounds.getZsize();
        sizeX = turns % 2 == 0 ? sx : sz;
        sizeZ = turns % 2 == 0 ? sz : sx;
        sizeY = (int) bounds.getYsize();
        Rotation rotation = Rotation.values()[turns];
        var result = new ArrayList<Block>();
        source.blocks()
                .forEach(
                        (pos, state) -> {
                            if (state.isAir()) return;
                            var p = pos.subtract(min);
                            var offset =
                                    switch (turns) {
                                        case 1 ->
                                                new BlockPos(sz - 1 - p.getZ(), p.getY(), p.getX());
                                        case 2 ->
                                                new BlockPos(
                                                        sx - 1 - p.getX(),
                                                        p.getY(),
                                                        sz - 1 - p.getZ());
                                        case 3 ->
                                                new BlockPos(p.getZ(), p.getY(), sx - 1 - p.getX());
                                        default -> p;
                                    };
                            result.add(new Block(offset, state.rotate(rotation)));
                        });
        blocks = List.copyOf(result);
        matched = wrong = 0;
        unloaded = blocks.size();
    }

    boolean fits(Level level) {
        return anchor != null
                && anchor.getY() >= level.getMinBuildHeight()
                && (long) anchor.getY() + sizeY <= level.getMaxBuildHeight()
                && level.getWorldBorder().isWithinBounds(anchor)
                && level.getWorldBorder().isWithinBounds(anchor.offset(sizeX - 1, 0, sizeZ - 1));
    }

    void refresh(Level level) {
        matched = wrong = unloaded = 0;
        if (anchor == null) return;
        for (var block : blocks) {
            var pos = anchor.offset(block.offset);
            if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos)) {
                block.status = Status.UNLOADED;
                unloaded++;
                continue;
            }
            var actual = level.getBlockState(pos);
            if (matches(block.state, actual)) {
                block.status = Status.MATCHED;
                matched++;
            } else if (actual.isAir() || actual.canBeReplaced()) {
                block.status = Status.MISSING;
            } else {
                block.status = Status.WRONG;
                wrong++;
            }
        }
    }

    static boolean matches(BlockState expected, BlockState actual) {
        if (!actual.is(expected.getBlock())) return false;
        for (var property : expected.getProperties()) {
            if ((PLACEMENT_PROPERTIES.contains(property.getName())
                            || property.getValueClass() == Direction.class
                            || property.getValueClass() == Direction.Axis.class)
                    && !expected.getValue(property).equals(actual.getValue(property))) return false;
        }
        return true;
    }
}
