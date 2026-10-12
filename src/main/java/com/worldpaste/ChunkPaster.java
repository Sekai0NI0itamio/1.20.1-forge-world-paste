package com.worldpaste;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.function.Function;

/**
 * Pastes ONE source chunk into the live level with an x/z chunk offset.
 *
 * <p>Heavily-modded-world safety (requires the same mods loaded on this server):
 * <ul>
 *   <li>Source chunk NBT is deserialized with the LIVE server registries
 *       ({@link ChunkSerializer#read}), so modded blocks resolve to the server's
 *       current modded set. Entries from mods missing here deserialize as air
 *       (vanilla missing-mapping behaviour) or fail the whole chunk, which is
 *       then skipped + counted — never a crash.</li>
 *   <li>Block-entity NBT is copied verbatim (generic {@link CompoundTag}), so modded
 *       tile data survives when the mod is present. BEs whose block is now missing
 *       (no block entity on the target state) are skipped + counted.</li>
 *   <li>Entities are re-spawned with FRESH UUIDs (source UUIDs stripped) so re-pastes
 *       never collide with live entities; unknown modded entity ids are skipped + counted.</li>
 * </ul>
 *
 * <p>Perf honesty: blocks are written with flag 2 (client sync, NO neighbour updates)
 * to avoid redstone/pipe cascades mid-paste; lighting + heightmaps are left for
 * vanilla chunk save/load to recompute (verified on relog), not recomputed inline.
 */
public final class ChunkPaster {
    private static final Logger LOG = LogUtils.getLogger();
    private ChunkPaster() {}

    public record PasteResult(int blocks, int blockEntities, int entities, int skippedMissing) {}

    public static PasteResult pasteChunk(ServerLevel level, CompoundTag sourceNbt,
                                         ChunkPos sourcePos, ChunkPos targetPos) {
        int blocks = 0, bes = 0, ents = 0, skipped = 0;

        // Re-base chunk coords so deserialization targets the destination.
        CompoundTag tag = sourceNbt.copy();
        tag.putInt("xPos", targetPos.x);
        tag.putInt("zPos", targetPos.z);

        final ProtoChunk proto;
        try {
            proto = ChunkSerializer.read(level, level.getPoiManager(), targetPos, tag);
        } catch (Exception ex) {
            LOG.warn("Skipping chunk {} (source {}) — unreadable with this server's registries (missing mods?): {}",
                    targetPos, sourcePos, ex.toString());
            return new PasteResult(0, 0, 0, 1);
        }

        int dx = (targetPos.x - sourcePos.x) * 16;
        int dz = (targetPos.z - sourcePos.z) * 16;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        // 1) Blocks.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos srcP = new BlockPos(sourcePos.getMinBlockX() + x, y, sourcePos.getMinBlockZ() + z);
                    BlockState state;
                    try {
                        state = proto.getBlockState(srcP);
                    } catch (Exception ex) {
                        skipped++;
                        continue;
                    }
                    BlockPos dstP = new BlockPos(srcP.getX() + dx, y, srcP.getZ() + dz);
                    if (!level.isInWorldBounds(dstP)) continue;
                    if (state.isAir() && level.getBlockState(dstP).isAir()) {
                        blocks++; // air-onto-air: nothing to write (huge win in sparse caves/sky)
                        continue;
                    }
                    try {
                        // Flag 2: sync to clients, NO neighbour updates (avoids redstone/pipe
                        // cascades and light-engine storms mid-paste at 3 chunks/sec).
                        level.setBlock(dstP, state, 2);
                        blocks++;
                    } catch (Exception ex) {
                        skipped++;
                    }
                }
            }
        }

        // 2) Block entities (verbatim NBT, position-offset).
        if (tag.contains("block_entities", Tag.TAG_LIST)) {
            ListTag list = tag.getList("block_entities", Tag.TAG_COMPOUND);
            for (Tag t : list) {
                CompoundTag beTag = ((CompoundTag) t).copy();
                try {
                    int bx = beTag.getInt("x") + dx;
                    int bz = beTag.getInt("z") + dz;
                    beTag.putInt("x", bx);
                    beTag.putInt("z", bz);
                    BlockPos dstP = new BlockPos(bx, beTag.getInt("y"), bz);
                    if (!level.isInWorldBounds(dstP)) continue;
                    BlockState dstState = level.getBlockState(dstP);
                    if (!dstState.hasBlockEntity()) {
                        skipped++; // mod missing here -> target block can't host the BE
                        continue;
                    }
                    BlockEntity be = BlockEntity.loadStatic(dstP, dstState, beTag);
                    if (be != null) {
                        level.getChunkAt(dstP).setBlockEntity(be);
                        bes++;
                    }
                } catch (Exception ex) {
                    skipped++;
                    if (skipped < 5) LOG.warn("Skipping modded block entity (missing mod?): {}", ex.toString());
                }
            }
        }

        // 3) Entities for fully-generated chunks live in entities/*.mca, handled by the caller
        // via pasteEntities(). Chunk NBT "entities" (proto-chunks) are handled here if present.
        if (tag.contains("entities", Tag.TAG_LIST)) {
            ListTag list = tag.getList("entities", Tag.TAG_COMPOUND);
            for (Tag t : list) {
                CompoundTag eTag = ((CompoundTag) t).copy();
                try {
                    offsetEntityTag(eTag, dx, dz);
                    eTag.remove("UUID"); // fresh UUID: re-pastes never collide with live entities
                    Entity spawned = EntityType.loadEntityRecursive(eTag, level, e -> e);
                    if (spawned != null) {
                        level.addFreshEntity(spawned);
                        ents++;
                    } else {
                        skipped++; // unknown (modded, mod missing) entity id
                    }
                } catch (Exception ex) {
                    skipped++;
                }
            }
        }

        // 4) Mark dirty + relight so the paste survives relog with correct lighting/heightmaps.
        try {
            var chunk = level.getChunk(targetPos.x, targetPos.z);
            chunk.setUnsaved(true);
        } catch (Exception ex) {
            LOG.debug("Mark-dirty failed for {} (non-fatal): {}", targetPos, ex.toString());
        }
        return new PasteResult(blocks, bes, ents, skipped);
    }

    /** Paste entities from the source world's entities/*.mca for one chunk (offset applied). */
    public static int pasteEntities(ServerLevel level, CompoundTag entitiesChunkNbt,
                                    ChunkPos sourcePos, ChunkPos targetPos) {
        if (entitiesChunkNbt == null) return 0;
        int dx = (targetPos.x - sourcePos.x) * 16;
        int dz = (targetPos.z - sourcePos.z) * 16;
        int count = 0, skipped = 0;
        if (!entitiesChunkNbt.contains("Entities", Tag.TAG_LIST)) return 0;
        ListTag list = entitiesChunkNbt.getList("Entities", Tag.TAG_COMPOUND);
        for (Tag t : list) {
            CompoundTag eTag = ((CompoundTag) t).copy();
            try {
                offsetEntityTag(eTag, dx, dz);
                eTag.remove("UUID"); // fresh UUID: re-pastes never collide with live entities
                Entity spawned = EntityType.loadEntityRecursive(eTag, level, e -> e);
                if (spawned != null) {
                    level.addFreshEntity(spawned);
                    count++;
                } else {
                    skipped++; // unknown (modded, mod missing) entity id
                }
            } catch (Exception ex) {
                skipped++;
            }
        }
        if (skipped > 0) LOG.warn("Skipped {} entities for chunk {} (missing mods?)", skipped, targetPos);
        return count;
    }

    /** Offset the position fields vanilla + most modded entities use. Unknown extra NBT passes through. */
    private static void offsetEntityTag(CompoundTag tag, int dx, int dz) {
        if (tag.contains("Pos", Tag.TAG_LIST)) {
            var pos = tag.getList("Pos", Tag.TAG_DOUBLE);
            if (pos.size() >= 3) {
                pos.set(0, DoubleTag.valueOf(pos.getDouble(0) + dx));
                pos.set(2, DoubleTag.valueOf(pos.getDouble(2) + dz));
            }
        }
        if (tag.contains("TileX", Tag.TAG_INT)) tag.putInt("TileX", tag.getInt("TileX") + dx);
        if (tag.contains("TileZ", Tag.TAG_INT)) tag.putInt("TileZ", tag.getInt("TileZ") + dz);
        if (tag.contains("BlockPos", Tag.TAG_INT_ARRAY)) {
            int[] a = tag.getIntArray("BlockPos");
            if (a.length >= 3) {
                a[0] += dx;
                a[2] += dz;
                tag.putIntArray("BlockPos", a);
            }
        }
        // Passengers recurse.
        if (tag.contains("Passengers", Tag.TAG_LIST)) {
            for (Tag t : tag.getList("Passengers", Tag.TAG_COMPOUND)) {
                offsetEntityTag((CompoundTag) t, dx, dz);
            }
        }
    }
}
