# CRITERIA — WorldPaste 1.0.0

- [ ] `/copy world <zip> <dim> <x> <z> [speed]` registers, OP-only, with tab-complete for zips + dims + speeds.
- [ ] Zip in `worldpaste_inbox/` unpacks (incl. top-level folder), region chunks scanned.
- [ ] Paste drains at ~3 chunks/sec via server tick queue without TPS death.
- [ ] Console progress logs every 5s (normal/fast) / 1s (turbo/instant) + 25/50/75% + finish: done/total, left, rate, ETA, mode.
- [ ] Speed modes work: normal 3/s, fast ~20/s, turbo ~80% of tick, instant uncapped; `/copy speed` switches mid-paste.
- [ ] Blocks + block entities + entities land offset around (x, z) centre and survive relog.
- [ ] Playerdata merges with keep-live default; conflicts listed + resolvable
      (overwrite | keep | rename-copy | delete-source) with backups.
- [ ] Modded-world safe: same-mods → full copy; missing mods → skip + count, no crash.
- [ ] `gradle build` passes on GitHub Actions (Java 17, Gradle 8.8); jar attached as artifact.
- [ ] NEVER built locally — only via `.github/workflows/build.yml`.
