package com.worldpaste;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tick-driven paste queue: drains at {@link PasteConfig#CHUNKS_PER_SECOND} so the
 * server never hitches, even for heavily-modded worlds with heavy tile entities.
 */
public final class PasteSession {
    private static final Logger LOG = LogUtils.getLogger();
    private static PasteSession ACTIVE;

    public static final class Stats {
        public int total;
        public int done;
        public long blocks;
        public int blockEntities;
        public int entities;
        public int skipped;
    }

    private final MinecraftServer server;
    private final ServerLevel level;
    private final SourceWorldReader.SourceWorld source;
    /** Server-thread drained; background thread only checks isEmpty() -> concurrent deque. */
    private final java.util.concurrent.ConcurrentLinkedDeque<PlayerDataMerger.ChunkTask> queue;
    private final Stats stats = new Stats();
    private final String label;
    private long lastSecondNanos = System.nanoTime();
    private int inCurrentSecond;
    private volatile boolean cancelled;
    private String finishNote = "";

    public PasteSession(MinecraftServer server, ServerLevel level,
                        SourceWorldReader.SourceWorld source,
                        Deque<PlayerDataMerger.ChunkTask> queue, String label) {
        this.server = server;
        this.level = level;
        this.source = source;
        this.queue = new java.util.concurrent.ConcurrentLinkedDeque<>(queue);
        this.label = label;
        this.stats.total = queue.size();
    }

    public static synchronized boolean start(PasteSession s) {
        if (ACTIVE != null && !ACTIVE.isFinished()) return false;
        ACTIVE = s;
        return true;
    }

    public static synchronized PasteSession active() {
        return ACTIVE;
    }

    public static synchronized void cancel() {
        if (ACTIVE != null) ACTIVE.cancelled = true;
    }

    public boolean isFinished() {
        return cancelled || queue.isEmpty();
    }

    public Stats stats() {
        return stats;
    }

    public String label() {
        return label;
    }

    public String finishNote() {
        return finishNote;
    }

    public void setFinishNote(String note) {
        this.finishNote = note;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        PasteSession s;
        synchronized (PasteSession.class) {
            s = ACTIVE;
        }
        if (s == null || s.isFinished()) return;
        s.tick();
    }

    private void tick() {
        if (cancelled) return;
        long now = System.nanoTime();
        if (now - lastSecondNanos >= 1_000_000_000L) {
            lastSecondNanos = now;
            inCurrentSecond = 0;
        }
        while (inCurrentSecond < PasteConfig.CHUNKS_PER_SECOND && !queue.isEmpty() && !cancelled) {
            PlayerDataMerger.ChunkTask task = queue.poll();
            inCurrentSecond++;
            try {
                PlayerDataMerger.pasteOne(level, source, task, stats);
            } catch (Exception ex) {
                stats.skipped++;
                LOG.warn("Paste task failed for {} -> {}: {}", task.source(), task.target(), ex.toString());
            }
        }
        if (queue.isEmpty() && finishNote.isEmpty()) {
            finishNote = cancelled ? "cancelled" : "done";
            LOG.info("WorldPaste finished: {}/{} chunks, {} blocks, {} blockEntities, {} entities, {} skipped",
                    stats.done, stats.total, stats.blocks, stats.blockEntities, stats.entities, stats.skipped);
        }
    }
}
