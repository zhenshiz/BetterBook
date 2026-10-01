package com.zhenshiz.betterbook.data;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.util.*;

/** Litematic v7 文件适配器；外部格式使用其原生字段及跨 long 连续位存储。 */
public final class BookSchematic {
    public static final int MAX_BLOCKS = 262144;
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    private BookSchematic() {}

    /**
     * 从已加载的服务端选区生成 Forgematica 原生结构 NBT。
     *
     * @param level 选区世界
     * @param first 第一个角点
     * @param second 第二个角点
     * @param name 结构名称
     * @param author 作者名称
     * @return v7、SubVersion 1 的 Litematic 根标签
     * @throws IllegalArgumentException 选区过大或区块未加载时抛出
     */
    public static CompoundTag capture(
            ServerLevel level, BlockPos first, BlockPos second, String name, String author) {
        var min = min(first, second);
        var max = max(first, second);
        var size = max.subtract(min).offset(1, 1, 1);
        int volume = volume(size);
        if (min.getY() < level.getMinBuildHeight() || max.getY() >= level.getMaxBuildHeight())
            throw new IllegalArgumentException("Selection exceeds world height");
        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++)
            for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++)
                if (!level.hasChunk(x, z))
                    throw new IllegalArgumentException("Selection contains unloaded chunks");
        var palette = new LinkedHashMap<BlockState, Integer>();
        palette.put(Blocks.AIR.defaultBlockState(), 0);
        int[] indices = new int[volume];
        var tiles = new ListTag();
        int i = 0, count = 0;
        for (int y = 0; y < size.getY(); y++)
            for (int z = 0; z < size.getZ(); z++)
                for (int x = 0; x < size.getX(); x++) {
                    var pos = min.offset(x, y, z);
                    var state = level.getBlockState(pos);
                    indices[i++] = palette.computeIfAbsent(state, s -> palette.size());
                    if (!state.isAir()) count++;
                    var be = level.getBlockEntity(pos);
                    if (be != null) {
                        var tag = be.saveWithFullMetadata(level.registryAccess());
                        putPosition(tag, new BlockPos(x, y, z));
                        tiles.add(tag);
                    }
                }
        var entities = new ListTag();
        for (var entity :
                level.getEntities(
                        (net.minecraft.world.entity.Entity) null,
                        new AABB(
                                Vec3.atLowerCornerOf(min),
                                Vec3.atLowerCornerOf(max.offset(1, 1, 1))),
                        e -> !(e instanceof Player) && !e.isPassenger())) {
            var tag = new CompoundTag();
            if (entity.save(tag)) {
                shiftEntity(tag, Vec3.atLowerCornerOf(min).scale(-1));
                entities.add(tag);
            }
        }
        var states = new ListTag();
        palette.keySet().forEach(s -> states.add(NbtUtils.writeBlockState(s)));
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long[] packed = new long[(int) (((long) volume * bits + 63) / 64)];
        for (i = 0; i < volume; i++) {
            long bit = (long) i * bits;
            int word = (int) (bit >> 6), offset = (int) (bit & 63);
            packed[word] |= (long) indices[i] << offset;
            if (offset + bits > 64) packed[word + 1] |= (long) indices[i] >>> (64 - offset);
        }
        var region = new CompoundTag();
        region.put("Position", position(BlockPos.ZERO));
        region.put("Size", position(size));
        region.put("BlockStatePalette", states);
        region.putLongArray("BlockStates", packed);
        region.put("TileEntities", tiles);
        region.put("Entities", entities);
        var blockTicks = new ListTag();
        var fluidTicks = new ListTag();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                var chunk = level.getChunk(cx, cz);
                if (chunk.getBlockTicks()
                        instanceof net.minecraft.world.ticks.LevelChunkTicks<?> ticks)
                    ticks.getAll()
                            .filter(t -> inside(t.pos(), min, max))
                            .forEach(
                                    t ->
                                            blockTicks.add(
                                                    tick(
                                                            t,
                                                            min,
                                                            level.getGameTime(),
                                                            "Block",
                                                            BuiltInRegistries.BLOCK
                                                                    .getKey(
                                                                            (net.minecraft.world
                                                                                            .level
                                                                                            .block
                                                                                            .Block)
                                                                                    t.type())
                                                                    .toString())));
                if (chunk.getFluidTicks()
                        instanceof net.minecraft.world.ticks.LevelChunkTicks<?> ticks)
                    ticks.getAll()
                            .filter(t -> inside(t.pos(), min, max))
                            .forEach(
                                    t ->
                                            fluidTicks.add(
                                                    tick(
                                                            t,
                                                            min,
                                                            level.getGameTime(),
                                                            "Fluid",
                                                            BuiltInRegistries.FLUID
                                                                    .getKey(
                                                                            (net.minecraft.world
                                                                                            .level
                                                                                            .material
                                                                                            .Fluid)
                                                                                    t.type())
                                                                    .toString())));
            }
        region.put("PendingBlockTicks", blockTicks);
        region.put("PendingFluidTicks", fluidTicks);
        var regions = new CompoundTag();
        regions.put("main", region);
        var meta = new CompoundTag();
        meta.putString("Name", name);
        meta.putString("Author", author);
        meta.putString("Description", "");
        meta.putInt("RegionCount", 1);
        meta.putInt("TotalVolume", volume);
        meta.putInt("TotalBlocks", count);
        meta.putLong("TimeCreated", System.currentTimeMillis());
        meta.putLong("TimeModified", System.currentTimeMillis());
        meta.put("EnclosingSize", position(size));
        var root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt(
                "MinecraftDataVersion",
                SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("Metadata", meta);
        root.put("Regions", regions);
        return root;
    }

    /**
     * 解码 Litematic v5–v7 或原版结构 NBT，保留区域偏移、方块状态与 NBT。
     *
     * @param root 解压后的结构标签
     * @return 展示用的未持久化结构快照
     * @throws IllegalArgumentException 文件版本、尺寸或调色板无效时抛出
     */
    public static Snapshot decode(CompoundTag root) {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        var tiles = new LinkedHashMap<BlockPos, CompoundTag>();
        var entities = new ArrayList<CompoundTag>();
        if (root.contains("size", Tag.TAG_LIST) && root.contains("blocks", Tag.TAG_LIST))
            return vanilla(root);
        int version = root.getInt("Version");
        if (version < 5 || version > 7 || !root.contains("Regions", Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Expected a Litematic v5–v7 file");
        var regions = root.getCompound("Regions");
        BlockPos boundsMin = null, boundsMax = null;
        long total = 0;
        for (String name : new TreeSet<>(regions.getAllKeys())) {
            var r = regions.getCompound(name);
            var origin = readPosition(r.getCompound("Position"));
            var signed = readPosition(r.getCompound("Size"));
            var size =
                    new BlockPos(
                            Math.abs(signed.getX()),
                            Math.abs(signed.getY()),
                            Math.abs(signed.getZ()));
            int count = volume(size);
            total += count;
            if (total > MAX_BLOCKS)
                throw new IllegalArgumentException("Structure exceeds " + MAX_BLOCKS + " blocks");
            var start =
                    origin.offset(
                            Math.min(0, signed.getX() + 1),
                            Math.min(0, signed.getY() + 1),
                            Math.min(0, signed.getZ() + 1));
            var end = start.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
            boundsMin = boundsMin == null ? start : min(boundsMin, start);
            boundsMax = boundsMax == null ? end : max(boundsMax, end);
            var tags = r.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            if (tags.isEmpty() || tags.size() > MAX_BLOCKS)
                throw new IllegalArgumentException("Invalid block palette");
            var palette = new ArrayList<BlockState>();
            for (Tag t : tags) {
                var stateTag = (CompoundTag) t;
                var id =
                        net.minecraft.resources.ResourceLocation.tryParse(
                                stateTag.getString("Name"));
                if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
                    throw new IllegalArgumentException(
                            "Missing block: " + stateTag.getString("Name"));
                palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), stateTag));
            }
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long mask = (1L << bits) - 1;
            long[] packed = r.getLongArray("BlockStates");
            if (packed.length != ((long) count * bits + 63) / 64)
                throw new IllegalArgumentException("Invalid block state array");
            for (int i = 0; i < count; i++) {
                long bit = (long) i * bits;
                int word = (int) (bit >> 6), offset = (int) (bit & 63);
                long value = packed[word] >>> offset;
                if (offset + bits > 64) value |= packed[word + 1] << (64 - offset);
                int index = (int) (value & mask);
                if (index >= palette.size())
                    throw new IllegalArgumentException("Invalid palette index");
                int x = i % size.getX(),
                        z = i / size.getX() % size.getZ(),
                        y = i / (size.getX() * size.getZ());
                blocks.put(start.offset(x, y, z), palette.get(index));
            }
            for (Tag t : r.getList("TileEntities", Tag.TAG_COMPOUND)) {
                var tag = ((CompoundTag) t).copy();
                var local = readPosition(tag);
                if (local.getX() < 0
                        || local.getY() < 0
                        || local.getZ() < 0
                        || local.getX() >= size.getX()
                        || local.getY() >= size.getY()
                        || local.getZ() >= size.getZ())
                    throw new IllegalArgumentException("Block entity outside region");
                var pos = start.offset(local);
                putPosition(tag, pos);
                tiles.put(pos, tag);
            }
            for (Tag t : r.getList("Entities", Tag.TAG_COMPOUND)) {
                if (entities.size() >= 1024)
                    throw new IllegalArgumentException("Too many entities");
                var tag = ((CompoundTag) t).copy();
                shiftEntity(tag, Vec3.atLowerCornerOf(origin));
                entities.add(tag);
            }
        }
        if (boundsMin == null) throw new IllegalArgumentException("Structure has no regions");
        var span = boundsMax.subtract(boundsMin).offset(1, 1, 1);
        if (span.getX() > 2048
                || span.getY() > 2048
                || span.getZ() > 2048
                || span.getX() < 1
                || span.getY() < 1
                || span.getZ() < 1)
            throw new IllegalArgumentException("Structure bounds too large");
        return new Snapshot(
                blocks,
                tiles,
                entities,
                new AABB(
                        Vec3.atLowerCornerOf(boundsMin),
                        Vec3.atLowerCornerOf(boundsMax.offset(1, 1, 1))));
    }

    private static Snapshot vanilla(CompoundTag root) {
        var sizeTag = root.getList("size", Tag.TAG_INT);
        if (sizeTag.size() != 3) throw new IllegalArgumentException("Invalid structure size");
        var size = new BlockPos(sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2));
        volume(size);
        var paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        if (paletteTag.isEmpty() && root.contains("palettes", Tag.TAG_LIST))
            paletteTag = (ListTag) root.getList("palettes", Tag.TAG_LIST).getFirst();
        var palette = new ArrayList<BlockState>();
        for (Tag t : paletteTag) {
            var state = (CompoundTag) t;
            var id = net.minecraft.resources.ResourceLocation.tryParse(state.getString("Name"));
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
                throw new IllegalArgumentException("Missing block: " + state.getString("Name"));
            palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), state));
        }
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        var tiles = new LinkedHashMap<BlockPos, CompoundTag>();
        var entities = new ArrayList<CompoundTag>();
        for (Tag t : root.getList("blocks", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) t;
            var p = tag.getList("pos", Tag.TAG_INT);
            if (p.size() != 3)
                throw new IllegalArgumentException("Invalid structure block position");
            var pos = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
            int index = tag.getInt("state");
            if (index < 0
                    || index >= palette.size()
                    || pos.getX() < 0
                    || pos.getY() < 0
                    || pos.getZ() < 0
                    || pos.getX() >= size.getX()
                    || pos.getY() >= size.getY()
                    || pos.getZ() >= size.getZ())
                throw new IllegalArgumentException("Invalid structure block");
            blocks.put(pos, palette.get(index));
            if (tag.contains("nbt", Tag.TAG_COMPOUND)) {
                var nbt = tag.getCompound("nbt").copy();
                putPosition(nbt, pos);
                tiles.put(pos, nbt);
            }
        }
        for (Tag t : root.getList("entities", Tag.TAG_COMPOUND)) {
            if (entities.size() >= 1024) throw new IllegalArgumentException("Too many entities");
            var tag = (CompoundTag) t;
            var nbt = tag.getCompound("nbt").copy();
            nbt.put("Pos", tag.getList("pos", Tag.TAG_DOUBLE).copy());
            entities.add(nbt);
        }
        return new Snapshot(
                blocks, tiles, entities, new AABB(Vec3.ZERO, Vec3.atLowerCornerOf(size)));
    }

    /** 展示快照，不替代原文件的持久化格式。 */
    public record Snapshot(
            Map<BlockPos, BlockState> blocks,
            Map<BlockPos, CompoundTag> tiles,
            List<CompoundTag> entities,
            AABB bounds) {}

    /**
     * 按文件格式压缩 NBT。
     *
     * @param tag 根标签
     * @return gzip NBT 字节
     * @throws IOException 压缩失败或文件过大时抛出
     */
    public static byte[] compress(CompoundTag tag) throws IOException {
        var out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        if (out.size() > MAX_BYTES) throw new IOException("Structure file exceeds 8 MiB");
        return out.toByteArray();
    }

    /**
     * 有限额地读取压缩 NBT。
     *
     * @param input 文件输入流
     * @return 根标签
     * @throws IOException NBT 无效或超出解压上限时抛出
     */
    public static CompoundTag read(InputStream input) throws IOException {
        return NbtIo.readCompressed(input, NbtAccounter.create(64L * 1024 * 1024));
    }

    private static boolean inside(BlockPos p, BlockPos min, BlockPos max) {
        return p.getX() >= min.getX()
                && p.getY() >= min.getY()
                && p.getZ() >= min.getZ()
                && p.getX() <= max.getX()
                && p.getY() <= max.getY()
                && p.getZ() <= max.getZ();
    }

    private static CompoundTag tick(
            net.minecraft.world.ticks.ScheduledTick<?> tick,
            BlockPos min,
            long now,
            String key,
            String id) {
        var tag = position(tick.pos().subtract(min));
        tag.putString(key, id);
        tag.putInt("Priority", tick.priority().getValue());
        tag.putLong("SubTick", tick.subTickOrder());
        tag.putInt("Time", (int) (tick.triggerTick() - now));
        return tag;
    }

    private static int volume(BlockPos size) {
        long x = size.getX(), y = size.getY(), z = size.getZ();
        if (x < 1 || y < 1 || z < 1 || x > 512 || y > 512 || z > 512 || x * y * z > MAX_BLOCKS)
            throw new IllegalArgumentException(
                    "Selection must contain 1–" + MAX_BLOCKS + " blocks (max 512 per axis)");
        return (int) (x * y * z);
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
    }

    private static CompoundTag position(BlockPos p) {
        var t = new CompoundTag();
        putPosition(t, p);
        return t;
    }

    private static void putPosition(CompoundTag t, BlockPos p) {
        t.putInt("x", p.getX());
        t.putInt("y", p.getY());
        t.putInt("z", p.getZ());
    }

    private static BlockPos readPosition(CompoundTag t) {
        return new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
    }

    private static void shiftEntity(CompoundTag t, Vec3 offset) {
        var p = t.getList("Pos", Tag.TAG_DOUBLE);
        if (p.size() != 3) throw new IllegalArgumentException("Entity is missing Pos");
        var list = new ListTag();
        list.add(DoubleTag.valueOf(p.getDouble(0) + offset.x));
        list.add(DoubleTag.valueOf(p.getDouble(1) + offset.y));
        list.add(DoubleTag.valueOf(p.getDouble(2) + offset.z));
        t.put("Pos", list);
        for (Tag child : t.getList("Passengers", Tag.TAG_COMPOUND))
            shiftEntity((CompoundTag) child, offset);
    }
}
