package com.worldpaste;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.Deque;

/**
 * Tick-driven paste queue with selectable speed modes.
 *
 * <ul>
 *   <li>NORMAL — 3 chunks/sec, tiny per-tick budget. Safe during live play.</li>
 *   <li>FAST — 20 chunks/sec, 25ms per tick. Noticeable lag, still playable.</li>
 *   <li>TURBO — no per-second cap, up to ~40ms per tick (~80% of the 50ms server
 *       tick). Expect heavy lag; best with players warned or offline.</li>
 *   <li>INSTANT — no caps at all: drains the queue as fast as the CPU allows in as
 *       few ticks as possible. WILL freeze the server and can trip the watchdog
 *       (max-tick-time) on large worlds. Kick players / raise max-tick-time first.</li>
 * </ul>
 *
 * <p>Progress is printed to the server console (and latest.log) every few seconds
 * plus at 25/50/75% milestones and on finish, so an operator watching the console
 * always sees chunks done/left, rate, and ETA.
 */
public final class PasteSession {
    private static final Logger LOG = LogUtils.getLogger();
    private static PasteSession ACTIVE;
    private static volatile PasteMode DEFAULT_MODE = PasteMode.NORMAL;

    public enum PasteMode {
        NORMAL(3, 10_000_000L),
        FAST(20, 25_000_000L),
        TURBO(0, 40_000_000L),
        INSTANT(0, 0L);

        /** Max chunks per wall-clock second; 0 = uncapped. */
        public final int chunksPerSecond;
        /** Max paste nanos per server tick; 0 = unlimited. */
        public final long tickBudgetNanos;

        PasteMode(int chunksPerSecond, long tickBudgetNanos) {
            this.chunksPerSecond = chunksPerSecond;
            this.tickBudgetNanos = tickBudgetNanos;
        }

        public static PasteMode parse(String s) {
            for (PasteMode m : values()) {
                if (m.name().equalsIgnoreCase(s)) return m;
            }
            return null;
        }
    }

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
    private volatile PasteMode mode;
    private long lastSecondNanos = System.nanoTime();
    private long lastLogNanos = System.nanoTime();
    private final long startNanos = System.nanoTime();
    private int inCurrentSecond;
    private int lastMilestone;
    private volatile boolean cancelled;
    private String finishNote = "";

    public PasteSession(MinecraftServer server, ServerLevel level,
                        SourceWorldReader.SourceWorld source,
                        Deque<PlayerDataMerger.ChunkTask> queue, String label, PasteMode mode) {
        this.server = server;
        this.level = level;
        this.source = source;
        this.queue = new java.util.concurrent.ConcurrentLinkedDeque<>(queue);
        this.label = label;
        this.mode = mode;
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

    public static PasteMode defaultMode() {
        return DEFAULT_MODE;
    }

    public static void defaultMode(PasteMode mode) {
        DEFAULT_MODE = mode;
    }

    public PasteMode mode() {
        return mode;
    }

    /** Switch speed live, mid-paste. Takes effect on the next server tick. */
    public void mode(PasteMode mode) {
        this.mode = mode;
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
        PasteMode m = mode;
        long tickStart = System.nanoTime();
        long now = System.nanoTime();
        if (now - lastSecondNanos >= 1_000_000_000L) {
            lastSecondNanos = now;
            inCurrentSecond = 0;
        }
        int pastedThisTick = 0;
        while (!queue.isEmpty() && !cancelled) {
            if (m.chunksPerSecond > 0 && inCurrentSecond >= m.chunksPerSecond) break;
            if (m.tickBudgetNanos > 0 && System.nanoTime() - tickStart >= m.tickBudgetNanos) break;
            PlayerDataMerger.ChunkTask task = queue.poll();
            if (task == null) break;
            inCurrentSecond++;
            pastedThisTick++;
            try {
                PlayerDataMerger.pasteOne(level, source, task, stats);
            } catch (Exception ex) {
                stats.skipped++;
                LOG.warn("[WorldPaste] task failed for {} -> {}: {}", task.source(), task.target(), ex.toString());
            }
            // INSTANT mode can sit inside one giant tick: keep the console alive.
            if (m == PasteMode.INSTANT && pastedThisTick % 200 == 0 && !queue.isEmpty()) {
                logProgress();
            }
        }
        maybeLogProgress();
        if (queue.isEmpty() && finishNote.isEmpty()) {
            finishNote = cancelled ? "cancelled" : "done";
            double elapsedS = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            LOG.info("[WorldPaste] FINISHED {}: {}/{} chunks, {} blocks, {} block-entities, {} entities, {} skipped in {} (mode={})",
                    label, stats.done, stats.total, stats.blocks, stats.blockEntities,
                    stats.entities, stats.skipped, formatDuration((long) elapsedS), m);
        }
    }

    private void maybeLogProgress() {
        if (queue.isEmpty() || cancelled) return;
        PasteMode m = mode;
        long intervalNanos = (m == PasteMode.TURBO || m == PasteMode.INSTANT)
                ? 1_000_000_000L : 5_000_000_000L;
        long now = System.nanoTime();
        if (now - lastLogNanos >= intervalNanos) {
            lastLogNanos = now;
            logProgress();
        }
    }

    /** Console progress line: done/total, %, left, work counters, rate, ETA, mode. */
    private void logProgress() {
        int done = stats.done;
        int total = stats.total;
        int left = Math.max(0, total - done);
        double pct = total == 0 ? 100.0 : done * 100.0 / total;
        double elapsedS = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        double rate = elapsedS > 0.5 ? done / elapsedS : 0.0;
        String eta = rate > 0.01 ? formatDuration((long) (left / rate)) : "unknown";
        LOG.info("[WorldPaste] {} — {}/{} chunks ({}) — {} left | {} blocks, {} block-entities, {} entities, {} skipped | {}/s, ETA {} | mode={}",
                label, done, total, String.format("%.1f%%", pct), left,
                stats.blocks, stats.blockEntities, stats.entities, stats.skipped,
                String.format("%.1f chunks", rate), eta, mode);
        for (int milestone : new int[]{25, 50, 75}) {
            if (pct >= milestone && lastMilestone < milestone) {
                lastMilestone = milestone;
                LOG.info("[WorldPaste] {} reached {}% ({}/{} chunks, {} left)",
                        label, milestone, done, total, left);
            }
        }
    }

    private static String formatDuration(long totalSeconds) {
        if (totalSeconds < 60) return totalSeconds + "s";
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        if (m < 60) return m + "m " + s + "s";
        return (m / 60) + "h " + (m % 60) + "m " + s + "s";
    }
}
