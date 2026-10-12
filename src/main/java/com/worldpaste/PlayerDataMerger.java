package com.worldpaste;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Merges source {@code playerdata/*.dat} (vanilla + ForgeCaps/modded NBT preserved verbatim).
 *
 * <p>Default policy: NEVER silently overwrite a live player. On the first pass we
 * back up + KEEP the live file and record a conflict. The operator then resolves
 * each conflict explicitly via {@code /copy players <overwrite|keep|rename-copy|delete-source>}.
 */
public final class PlayerDataMerger {
    private static final Logger LOG = LogUtils.getLogger();
    private PlayerDataMerger() {}

    public record Conflict(String uuid, Path sourceFile, Path liveFile) {}
    public record MergeReport(int copied, int keptLive, List<Conflict> conflicts, int skipped) {}

    /** First pass: copy non-conflicting players, back up + keep live on conflict. */
    public static MergeReport mergeKeepLive(MinecraftServer server, Path sourcePlayerDir) throws IOException {
        Path liveDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Files.createDirectories(liveDir);
        Path backupDir = liveDir.resolveSibling("playerdata_backup_worldpaste");
        Files.createDirectories(backupDir);

        List<Conflict> conflicts = new ArrayList<>();
        int copied = 0, kept = 0, skipped = 0;
        if (!Files.isDirectory(sourcePlayerDir)) return new MergeReport(0, 0, conflicts, 0);

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sourcePlayerDir, "*.dat")) {
            for (Path src : ds) {
                String name = src.getFileName().toString();
                String uuid = name.substring(0, name.length() - 4);
                Path live = liveDir.resolve(name);
                try {
                    // Validate NBT (also preserves modded ForgeCaps tags verbatim on copy).
                    CompoundTag tag = NbtIo.readCompressed(src.toFile());
                    if (tag == null) {
                        skipped++;
                        continue;
                    }
                    if (Files.exists(live)) {
                        String stamp = "." + System.currentTimeMillis() + ".bak";
                        Files.copy(live, backupDir.resolve(name + stamp),
                                StandardCopyOption.REPLACE_EXISTING);
                        Files.copy(src, backupDir.resolve(name + ".source" + stamp),
                                StandardCopyOption.REPLACE_EXISTING);
                        conflicts.add(new Conflict(uuid, src, live));
                        kept++;
                    } else {
                        Files.copy(src, live, StandardCopyOption.COPY_ATTRIBUTES);
                        copied++;
                    }
                } catch (Exception ex) {
                    LOG.warn("Skipping playerdata {}: {}", name, ex.toString());
                    skipped++;
                }
            }
        }
        return new MergeReport(copied, kept, conflicts, skipped);
    }

    /**
     * Resolve recorded conflicts.
     * @param mode overwrite = source wins (live backed up); keep = live wins;
     *             rename-copy = source copied to a fresh UUID file (operator renames the player);
     *             delete-source = drop the source file.
     */
    public static String resolve(MinecraftServer server, List<Conflict> conflicts, String mode) throws IOException {
        Path liveDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path backupDir = liveDir.resolveSibling("playerdata_backup_worldpaste");
        Files.createDirectories(backupDir);
        int done = 0;
        for (Conflict c : conflicts) {
            Path live = liveDir.resolve(c.uuid() + ".dat");
            switch (mode) {
                case "overwrite" -> {
                    // Re-backup the CURRENT live file: it may have changed since the first pass.
                    if (Files.exists(live)) {
                        Files.copy(live, backupDir.resolve(c.uuid() + ".dat." + System.currentTimeMillis() + ".bak"),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.copy(c.sourceFile(), live, StandardCopyOption.REPLACE_EXISTING);
                    done++;
                }
                case "keep" -> done++; // already kept; nothing to do
                case "rename-copy" -> {
                    // Saved as a NEW .dat: operator renames it to the target player's UUID
                    // (see /copy players conflicts output) to assign it.
                    String fresh = UUID.randomUUID() + ".dat";
                    Files.copy(c.sourceFile(), liveDir.resolve(fresh));
                    done++;
                }
                case "delete-source" -> {
                    Files.deleteIfExists(c.sourceFile());
                    done++;
                }
                default -> throw new IOException("Unknown mode: " + mode);
            }
        }
        return "Resolved " + done + " playerdata conflicts with mode=" + mode;
    }

    /** Chunk-task used by the tick queue. */
    public record ChunkTask(ChunkPos source, ChunkPos target) {}

    /** Build the target chunk list centred on (centerBlockX, centerBlockZ). */
    public static Deque<ChunkTask> buildTasks(List<ChunkPos> sourceChunks, int centerBlockX, int centerBlockZ) {
        if (sourceChunks.isEmpty()) return new ArrayDeque<>();
        long sx = 0, sz = 0;
        for (ChunkPos c : sourceChunks) {
            sx += c.x;
            sz += c.z;
        }
        ChunkPos sourceCenter = new ChunkPos(
                (int) Math.floorDiv(sx, sourceChunks.size()),
                (int) Math.floorDiv(sz, sourceChunks.size()));
        ChunkPos targetCenter = new ChunkPos(centerBlockX >> 4, centerBlockZ >> 4);
        int dx = targetCenter.x - sourceCenter.x;
        int dz = targetCenter.z - sourceCenter.z;
        Deque<ChunkPos> sorted = new ArrayDeque<>(sourceChunks);
        Deque<ChunkTask> tasks = new ArrayDeque<>(sorted.size());
        // Paste in expanding rings from the centre so the middle is usable first.
        List<ChunkPos> ring = new ArrayList<>(sorted);
        final int cx = sourceCenter.x, cz = sourceCenter.z;
        ring.sort((a, b) -> Long.compare(
                (long) (a.x - cx) * (a.x - cx) + (long) (a.z - cz) * (a.z - cz),
                (long) (b.x - cx) * (b.x - cx) + (long) (b.z - cz) * (b.z - cz)));
        for (ChunkPos s : ring) {
            tasks.add(new ChunkTask(s, new ChunkPos(s.x + dx, s.z + dz)));
        }
        return tasks;
    }

    /** Paste a single task (blocks + block entities + entities). Returns short status. */
    public static String pasteOne(ServerLevel level, SourceWorldReader.SourceWorld source,
                                  ChunkTask task, PasteSession.Stats stats) {
        CompoundTag chunkNbt = SourceWorldReader.readChunkNbt(source.regionDir(), task.source());
        if (chunkNbt == null) {
            stats.skipped++;
            return "skip";
        }
        // Force-load the target chunk before writing.
        level.getChunk(task.target().x, task.target().z);
        ChunkPaster.PasteResult r = ChunkPaster.pasteChunk(level, chunkNbt, task.source(), task.target());
        stats.blocks += r.blocks();
        stats.blockEntities += r.blockEntities();
        stats.skipped += r.skippedMissing();

        CompoundTag entNbt = SourceWorldReader.readChunkNbt(source.entitiesDir(), task.source());
        if (entNbt != null) {
            stats.entities += ChunkPaster.pasteEntities(level, entNbt, task.source(), task.target());
        }
        stats.done++;
        return "ok";
    }
}
