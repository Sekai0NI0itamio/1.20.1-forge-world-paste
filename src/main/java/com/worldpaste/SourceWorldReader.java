package com.worldpaste;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Reads a source world that was unzipped to a temp dir WITHOUT booting it.
 * Supports vanilla + modded worlds: we read raw Anvil NBT and let the live
 * server's registries resolve blocks/entities at paste time (see ChunkPaster).
 */
public final class SourceWorldReader {
    private static final Logger LOG = LogUtils.getLogger();
    private SourceWorldReader() {}

    public record SourceWorld(Path root, List<ChunkPos> regionChunks, List<ChunkPos> entityChunks,
                              Path regionDir, Path entitiesDir, Path playerDataDir) {}

    /** Unzip {@code zipFile} into {@code destDir} (cleared first). Zip-slip safe. */
    public static void unzip(Path zipFile, Path destDir) throws IOException {
        if (Files.exists(destDir)) {
            deleteRecursive(destDir);
        }
        Files.createDirectories(destDir);
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zin = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir.normalize())) {
                    throw new IOException("Bad zip entry (zip-slip): " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        zin.transferTo(os);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    /** Find the actual world root inside an extracted dir (handles zips with a top-level folder). */
    public static Path findWorldRoot(Path extractedDir) throws IOException {
        if (Files.isRegularFile(extractedDir.resolve("level.dat"))) return extractedDir;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(extractedDir)) {
            for (Path child : ds) {
                if (Files.isDirectory(child) && Files.isRegularFile(child.resolve("level.dat"))) {
                    return child;
                }
            }
        }
        // Fall back: search one more level for region/ dirs (some exports omit level.dat)
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(extractedDir)) {
            for (Path child : ds) {
                if (Files.isDirectory(child) && Files.isDirectory(child.resolve("region"))) {
                    return child;
                }
            }
        }
        return extractedDir;
    }

    /** Resolve the dimension folder: overworld -> root, nether -> DIM-1, end -> DIM1. */
    public static Path dimRoot(Path worldRoot, String dim) {
        return switch (dim) {
            case "the_nether" -> worldRoot.resolve("DIM-1");
            case "the_end" -> worldRoot.resolve("DIM1");
            default -> worldRoot; // "overworld"
        };
    }

    /** Scan region/*.mca headers and return every stored chunk pos, sorted for deterministic paste. */
    public static SourceWorld scan(Path worldRoot, String sourceDim) throws IOException {
        Path dRoot = dimRoot(worldRoot, sourceDim);
        Path regionDir = dRoot.resolve("region");
        Path entitiesDir = dRoot.resolve("entities");
        Path playerDataDir = worldRoot.resolve("playerdata");
        List<ChunkPos> regionChunks = listChunksInDir(regionDir);
        List<ChunkPos> entityChunks = listChunksInDir(entitiesDir);
        LOG.info("Source world scan dim={} regionChunks={} entityChunks={}", sourceDim, regionChunks.size(), entityChunks.size());
        return new SourceWorld(worldRoot, regionChunks, entityChunks, regionDir, entitiesDir, playerDataDir);
    }

    private static List<ChunkPos> listChunksInDir(Path dir) throws IOException {
        List<ChunkPos> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "r.*.*.mca")) {
            for (Path regionFile : ds) {
                String name = regionFile.getFileName().toString(); // r.x.z.mca
                String[] parts = name.substring(2, name.length() - 4).split("\\.");
                int rx = Integer.parseInt(parts[0]);
                int rz = Integer.parseInt(parts[1]);
                try (RegionFile rf = new RegionFile(regionFile, dir, false)) {
                    for (int lx = 0; lx < 32; lx++) {
                        for (int lz = 0; lz < 32; lz++) {
                            ChunkPos cp = new ChunkPos(rx * 32 + lx, rz * 32 + lz);
                            try {
                                if (rf.hasChunk(cp)) out.add(cp);
                            } catch (Exception ex) {
                                // Corrupt header entry — skip, counted as missing below.
                                LOG.warn("Skipping unreadable chunk header {} in {}: {}", cp, name, ex.toString());
                            }
                        }
                    }
                } catch (Exception ex) {
                    LOG.warn("Skipping unreadable region file {}: {}", regionFile, ex.toString());
                }
            }
        }
        out.sort(Comparator.<ChunkPos>comparingInt(c -> c.x).thenComparingInt(c -> c.z));
        return out;
    }

    /** Read one chunk's raw NBT from a region dir. Returns null when absent/unreadable. */
    public static CompoundTag readChunkNbt(Path regionDir, ChunkPos pos) {
        Path file = regionDir.resolve("r." + (pos.x >> 5) + "." + (pos.z >> 5) + ".mca");
        if (!Files.isRegularFile(file)) return null;
        try (RegionFile rf = new RegionFile(file, regionDir, false)) {
            if (!rf.hasChunk(pos)) return null;
            try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
                if (in == null) return null;
                return NbtIo.read(in);
            }
        } catch (IOException | UncheckedIOException ex) {
            LOG.warn("Unreadable chunk {} in {}: {}", pos, file.getFileName(), ex.toString());
            return null;
        } catch (Exception ex) {
            LOG.warn("Unreadable chunk {} in {}: {}", pos, file.getFileName(), ex.toString());
            return null;
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            var paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path q : paths) Files.deleteIfExists(q);
        }
    }
}
