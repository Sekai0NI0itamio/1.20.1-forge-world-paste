package com.worldpaste;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.List;

/**
 * {@code /copy world <zip> <overworld|the_nether|the_end> <x> <z>}
 * {@code /copy status} {@code /copy cancel}
 * {@code /copy players conflicts|overwrite|keep|rename-copy|delete-source}
 *
 * <p>Source zips live in {@code ./worldpaste_inbox/} (server root) so operators never
 * touch the live world folder. Paste runs at 3 chunks/sec on the server thread;
 * unzipping + scanning runs in a background thread first.
 */
public final class CopyCommand {
    private CopyCommand() {}

    private static List<PlayerDataMerger.Conflict> pendingConflicts = List.of();

    private static final SuggestionProvider<CommandSourceStack> ZIP_SUGGEST = (ctx, builder) -> {
        try {
            Path inbox = inboxDir(ctx.getSource().getServer());
            if (Files.isDirectory(inbox)) {
                try (var ds = Files.newDirectoryStream(inbox, "*.zip")) {
                    for (Path p : ds) builder.suggest(p.getFileName().toString());
                }
            }
        } catch (Exception ignored) {}
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> DIM_SUGGEST = (ctx, builder) -> {
        builder.suggest("overworld");
        builder.suggest("the_nether");
        builder.suggest("the_end");
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SPEED_SUGGEST = (ctx, builder) -> {
        builder.suggest("normal");
        builder.suggest("fast");
        builder.suggest("turbo");
        builder.suggest("instant");
        return builder.buildFuture();
    };

    private static Path inboxDir(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .getParent().resolve(PasteConfig.INBOX_DIR);
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("copy")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("world")
                                .then(Commands.argument("zip", StringArgumentType.word()).suggests(ZIP_SUGGEST)
                                        .then(Commands.argument("dim", StringArgumentType.word()).suggests(DIM_SUGGEST)
                                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ctx -> startPaste(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "zip"),
                                                                        StringArgumentType.getString(ctx, "dim"),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"), null))
                                                                .then(Commands.argument("speed", StringArgumentType.word()).suggests(SPEED_SUGGEST)
                                                                        .executes(ctx -> startPaste(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "zip"),
                                                                                StringArgumentType.getString(ctx, "dim"),
                                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                                StringArgumentType.getString(ctx, "speed")))))))))
                        .then(Commands.literal("speed")
                                .then(Commands.argument("mode", StringArgumentType.word()).suggests(SPEED_SUGGEST)
                                        .executes(ctx -> speed(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mode")))))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("cancel").executes(ctx -> {
                            PasteSession.cancel();
                            ctx.getSource().sendSuccess(() -> Component.literal("WorldPaste: cancel requested."), true);
                            return 1;
                        }))
                        .then(Commands.literal("players")
                                .then(Commands.argument("mode", StringArgumentType.word()).suggests((c, b) -> {
                                    b.suggest("conflicts");
                                    b.suggest("overwrite");
                                    b.suggest("keep");
                                    b.suggest("rename-copy");
                                    b.suggest("delete-source");
                                    return b.buildFuture();
                                }).executes(ctx -> players(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mode"))))));
    }

    private static int startPaste(CommandSourceStack src, String zip, String dim, int x, int z, String speedOrNull) {
        MinecraftServer server = src.getServer();
        PasteSession.PasteMode mode = speedOrNull == null ? PasteSession.defaultMode()
                : PasteSession.PasteMode.parse(speedOrNull);
        if (mode == null) {
            src.sendFailure(Component.literal("WorldPaste: unknown speed '" + speedOrNull + "' — use normal, fast, turbo, or instant."));
            return 0;
        }
        if (!dim.equals("overworld") && !dim.equals("the_nether") && !dim.equals("the_end")) {
            src.sendFailure(Component.literal("WorldPaste: dim must be overworld, the_nether, or the_end."));
            return 0;
        }
        PasteSession cur = PasteSession.active();
        if (cur != null && !cur.isFinished()) {
            src.sendFailure(Component.literal("WorldPaste: a paste is already running. /copy status or /copy cancel first."));
            return 0;
        }
        Path inbox = inboxDir(server);
        Path zipFile = inbox.resolve(zip).normalize();
        if (!zipFile.startsWith(inbox.normalize()) || !Files.isRegularFile(zipFile)) {
            src.sendFailure(Component.literal("WorldPaste: zip not found in " + PasteConfig.INBOX_DIR + "/ — drop it there first. Got: " + zip));
            return 0;
        }
        ResourceKey<Level> key = switch (dim) {
            case "the_nether" -> Level.NETHER;
            case "the_end" -> Level.END;
            default -> Level.OVERWORLD;
        };
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            src.sendFailure(Component.literal("WorldPaste: dimension is not loaded: " + dim));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("WorldPaste: unpacking " + zip + " in background…"), true);
        Thread worker = new Thread(() -> {
            try {
                Path tmp = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .getParent().resolve(PasteConfig.TMP_DIR).resolve("paste-" + System.currentTimeMillis());
                SourceWorldReader.unzip(zipFile, tmp);
                var worldRoot = SourceWorldReader.findWorldRoot(tmp);
                // Source dim mirrors the TARGET dim folder layout when present
                // (DIM-1/DIM1 travel inside the same export); else fall back to overworld.
                var source = SourceWorldReader.scan(worldRoot, "overworld");
                if (source.regionChunks().isEmpty() && !dim.equals("overworld")) {
                    source = SourceWorldReader.scan(worldRoot, dim);
                }
                if (source.regionChunks().isEmpty()) {
                    server.execute(() -> src.sendFailure(Component.literal(
                            "WorldPaste: no region/*.mca found in " + zip + " (root=" + worldRoot.getFileName() + ").")));
                    return;
                }
                final var finalSource = source;
                final Deque<PlayerDataMerger.ChunkTask> tasks =
                        PlayerDataMerger.buildTasks(finalSource.regionChunks(), x, z);

                // Playerdata merge + session start run ON the server thread: live .dat
                // files must never be copied while the server thread is saving them.
                server.execute(() -> {
                    try {
                        PlayerDataMerger.MergeReport report =
                                PlayerDataMerger.mergeKeepLive(server, finalSource.playerDataDir());
                        synchronized (CopyCommand.class) {
                            pendingConflicts = report.conflicts();
                        }

                        PasteSession session = new PasteSession(server, level, finalSource, tasks,
                                zip + " -> " + dim + " @ " + x + "," + z, mode);
                        String note = "playerdata: " + report.copied() + " copied, "
                                + report.keptLive() + " conflicts kept-live, "
                                + report.skipped() + " skipped."
                                + (report.conflicts().isEmpty() ? ""
                                : " Resolve with /copy players conflicts, then overwrite|keep|rename-copy|delete-source.");
                        if (!PasteSession.start(session)) {
                            src.sendFailure(Component.literal("WorldPaste: another paste started first."));
                            return;
                        }
                        int total = tasks.size();
                        ChunkPos center = new ChunkPos(x >> 4, z >> 4);
                        String pace = switch (mode) {
                            case NORMAL -> "~3/sec";
                            case FAST -> "~20/sec";
                            case TURBO -> "as fast as ~80% of each tick allows (heavy lag expected)";
                            case INSTANT -> "UNCAPPED — server will freeze until done";
                        };
                        if (mode == PasteSession.PasteMode.TURBO || mode == PasteSession.PasteMode.INSTANT) {
                            WorldPasteMod.LOGGER.warn("[WorldPaste] {} started in {} mode: {} chunks — expect severe lag{}",
                                    zip, mode, total,
                                    mode == PasteSession.PasteMode.INSTANT
                                            ? " and a possible watchdog kill on large worlds; kick players first"
                                            : "; consider kicking players");
                        }
                        src.sendSuccess(() -> Component.literal(
                                "WorldPaste: started " + total + " chunks at " + pace + " centred on chunk "
                                        + center + " [" + mode + "]. " + note), true);
                        if (!report.conflicts().isEmpty()) {
                            src.sendSuccess(() -> Component.literal(
                                    "WorldPaste: " + report.conflicts().size()
                                            + " playerdata conflicts — live players kept. Run /copy players conflicts to list."), true);
                        }
                    } catch (Exception ex) {
                        WorldPasteMod.LOGGER.error("WorldPaste failed to start (server thread)", ex);
                        src.sendFailure(Component.literal("WorldPaste failed: " + ex.getMessage()));
                    }
                });
            } catch (Exception ex) {
                WorldPasteMod.LOGGER.error("WorldPaste failed to start", ex);
                server.execute(() -> src.sendFailure(Component.literal("WorldPaste failed: " + ex.getMessage())));
            }
        }, "worldpaste-unpack");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static int speed(CommandSourceStack src, String modeName) {
        PasteSession.PasteMode mode = PasteSession.PasteMode.parse(modeName);
        if (mode == null) {
            src.sendFailure(Component.literal("WorldPaste: unknown speed '" + modeName + "' — use normal, fast, turbo, or instant."));
            return 0;
        }
        PasteSession.defaultMode(mode);
        PasteSession active = PasteSession.active();
        if (active != null && !active.isFinished()) {
            active.mode(mode);
            src.sendSuccess(() -> Component.literal(
                    "WorldPaste: speed set to " + mode + " (applied to the running paste immediately)."), true);
        } else {
            src.sendSuccess(() -> Component.literal(
                    "WorldPaste: default speed set to " + mode + " (applies to the next /copy world)."), true);
        }
        if (mode == PasteSession.PasteMode.TURBO) {
            src.sendSuccess(() -> Component.literal(
                    "WorldPaste warning: TURBO uses ~80% of every server tick — expect heavy lag."), true);
        } else if (mode == PasteSession.PasteMode.INSTANT) {
            src.sendSuccess(() -> Component.literal(
                    "WorldPaste warning: INSTANT is uncapped — the server WILL freeze, and large worlds "
                            + "can trip the watchdog (max-tick-time) kill. Kick players first."), true);
        }
        WorldPasteMod.LOGGER.info("[WorldPaste] speed set to {} by {}", mode, src.getTextName());
        return 1;
    }

    private static int status(CommandSourceStack src) {
        PasteSession s = PasteSession.active();
        if (s == null) {
            src.sendSuccess(() -> Component.literal("WorldPaste: no paste yet."), false);
            return 1;
        }
        var st = s.stats();
        src.sendSuccess(() -> Component.literal("WorldPaste [" + s.label() + "]: "
                + st.done + "/" + st.total + " chunks, " + st.blocks + " blocks, "
                + st.blockEntities + " block-entities, " + st.entities + " entities, "
                + st.skipped + " skipped [" + s.mode() + "]"
                + (s.isFinished() ? " FINISHED " + s.finishNote() : " …")), false);
        return 1;
    }

    private static int players(CommandSourceStack src, String mode) {
        MinecraftServer server = src.getServer();
        List<PlayerDataMerger.Conflict> conflicts;
        synchronized (CopyCommand.class) {
            conflicts = pendingConflicts;
        }
        if (mode.equals("conflicts")) {
            if (conflicts.isEmpty()) {
                src.sendSuccess(() -> Component.literal("WorldPaste players: no pending conflicts."), false);
            } else {
                src.sendSuccess(() -> Component.literal("WorldPaste players: " + conflicts.size() + " conflicts (live kept):"), false);
                for (PlayerDataMerger.Conflict c : conflicts.stream().limit(20).toList()) {
                    src.sendSuccess(() -> Component.literal(" - " + c.uuid()
                            + " — /copy players <overwrite|keep|rename-copy|delete-source>"), false);
                }
                src.sendSuccess(() -> Component.literal(
                        "overwrite = source wins (live backed up). keep = live wins. "
                                + "rename-copy = source saved as NEW player (operator gives a new name). "
                                + "delete-source = drop the incoming data."), false);
            }
            return 1;
        }
        if (conflicts.isEmpty()) {
            src.sendSuccess(() -> Component.literal("WorldPaste players: nothing to resolve."), false);
            return 1;
        }
        try {
            String result = PlayerDataMerger.resolve(server, conflicts, mode);
            synchronized (CopyCommand.class) {
                pendingConflicts = List.of();
            }
            src.sendSuccess(() -> Component.literal("WorldPaste players: " + result), true);
        } catch (Exception ex) {
            src.sendFailure(Component.literal("WorldPaste players failed: " + ex.getMessage()));
            return 0;
        }
        return 1;
    }
}
