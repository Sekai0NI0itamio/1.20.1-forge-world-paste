# WorldPaste — 1.20.1 Forge World Paste

Copy a world into the live server, chunk by chunk, while the server is running:

```
/copy world <zipfilename> <overworld|the_nether|the_end> <x> <z> [speed]
```

- `<zipfilename>` — a `.zip` of a Minecraft Java world, dropped into `./worldpaste_inbox/` (server root).
- `<overworld|the_nether|the_end>` — which dimension of THIS server to paste into.
- `<x> <z>` — block coordinates for the **centre** of the pasted world.
- `[speed]` — optional: `normal`, `fast`, `turbo`, or `instant` (default: `normal`,
  or whatever `/copy speed` set last).

Extra commands (all OP level 2):

```
/copy status     — progress (chunks, blocks, block-entities, entities, skipped, mode)
/copy cancel     — stop the running paste
/copy speed <normal|fast|turbo|instant>  — change speed live, mid-paste
/copy players conflicts                        — list playerdata conflicts
/copy players <overwrite|keep|rename-copy|delete-source>
```

## Console progress

Every paste prints progress to the server console / `latest.log` automatically:
every 5s in `normal`/`fast`, every 1s in `turbo`/`instant`, plus 25/50/75%
milestones and a finish line — chunks done/total, chunks left, block/entity
counters, current rate, ETA, and mode. Example:

```
[WorldPaste] myworld.zip -> overworld @ 0,0 — 450/1200 chunks (37.5%) — 750 left | 812340 blocks, 120 block-entities, 45 entities, 3 skipped | 19.8 chunks/s, ETA 38s | mode=FAST
```

## Speed modes

| Mode | Behaviour | When to use |
|------|-----------|-------------|
| `normal` | 3 chunks/sec, tiny tick budget | Live play, default |
| `fast` | ~20 chunks/sec, 25ms per tick | Noticeable lag, still playable |
| `turbo` | No per-second cap, up to ~80% of every 50ms server tick | Kick/warn players first |
| `instant` | No caps at all — drains as fast as the CPU allows | **Freezes the server; can trip the watchdog (`max-tick-time`) kill on large worlds. Kick players and raise `max-tick-time` first.** |

Switch modes any time with `/copy speed <mode>` — it applies to the running
paste on the very next tick. Example: start safe (`normal`), then
`/copy speed turbo` once players log off, then `/copy speed instant` for the
last stretch.

## What it does

- Unpacks the zip to temp (zip-slip safe), finds the world root (handles top-level folders).
- Scans `region/*.mca` headers, pastes in expanding rings from the centre at **~3 chunks/second**
  on the server thread (tick queue), so TPS stays up.
- Copies blocks + block entities + entities (`entities/*.mca` too) with position offset
  (flag-2 writes: no neighbour cascades), marks chunks dirty so the paste survives relog.
  Lighting/heightmaps recompute via vanilla chunk save/load — relog after a big paste
  if lighting looks streaky.
- Merges `playerdata/*.dat` **without ever silently wiping a live player**: first pass backs up
  (timestamped, in `playerdata_backup_worldpaste/`) + keeps live on conflict, operator resolves
  with `/copy players <mode>`. `rename-copy` saves the incoming data as a NEW `.dat` —
  rename that file to the target player's UUID to assign it.
- Temp extractions live under `./worldpaste_tmp/paste-<millis>/` — delete old ones after
  conflicts are resolved (they're kept until then because pending conflicts reference them).

## Heavily-modded worlds

Works when **this server runs the same mods the exported world had**:

- Chunks are deserialized with the LIVE server registries, modded blocks resolve to the
  server's current modded set; modded tile-entity NBT is copied verbatim.
- Missing modded entries (mod not installed here) are **skipped + counted**, never crash the paste.
- Modded entities re-spawn via `EntityType.loadEntityRecursive` with offset; unknown ones skipped.
- Modded playerdata (`ForgeCaps` etc.) is preserved byte-for-byte inside the `.dat` copy.

If the source used mods you don't have here, you'll get the vanilla parts + a skip count —
install the missing mods and re-run for a 1:1 copy.

## Setup

1. Take the build from **Actions → build → Artifacts** (`worldpaste-jar`) — never built locally.
2. Put the jar in `mods/` on a Forge 1.20.1 server (47.4.10, Java 17).
3. Start once (creates `worldpaste_inbox/`), stop, drop your world `.zip` in there, start.
4. Run `/copy world myworld.zip overworld 0 0`, watch `/copy status`.

## Build

**CI only.** Pushing to `main` (or any PR) triggers `.github/workflows/build.yml`
(`gradle build --no-daemon`, Java 17, Gradle 8.8). Tags `v*` also cut a GitHub Release.
Do not run `gradle build` locally — the author builds exclusively via GitHub Actions.

If this project is useful, consider giving it a star. It helps others discover the project and motivates continued development.
