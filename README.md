# WorldPaste — 1.20.1 Forge World Paste

Copy a world into the live server, chunk by chunk, while the server is running:

```
/copy world <zipfilename> <overworld|the_nether|the_end> <x> <z>
```

- `<zipfilename>` — a `.zip` of a Minecraft Java world, dropped into `./worldpaste_inbox/` (server root).
- `<overworld|the_nether|the_end>` — which dimension of THIS server to paste into.
- `<x> <z>` — block coordinates for the **centre** of the pasted world.

Extra commands (all OP level 2):

```
/copy status     — progress (chunks, blocks, block-entities, entities, skipped)
/copy cancel     — stop the running paste
/copy players conflicts                        — list playerdata conflicts
/copy players <overwrite|keep|rename-copy|delete-source>
```

## What it does

- Unpacks the zip to temp (zip-slip safe), finds the world root (handles top-level folders).
- Scans `region/*.mca` headers, pastes in expanding rings from the centre at **~3 chunks/second**
  on the server thread (tick queue), so TPS stays up.
- Copies blocks + block entities + entities (`entities/*.mca` too) with position offset,
  marks chunks dirty + relights so the paste survives relog.
- Merges `playerdata/*.dat` **without ever silently wiping a live player**: first pass backs up
  + keeps live on conflict, operator resolves with `/copy players <mode>`.

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
