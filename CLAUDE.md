# Ghost Path: working notes for Claude

> **In a new container, run `scripts/setup-container.sh` first** (installs JDK 25, points Gradle at it, installs the Maven Central mirror init script; safe to run twice). Then `./gradlew build`.

Fabric mod for **Minecraft Java 26.2** that shows where TNT and projectiles will go. Design and verified findings: `docs/DESIGN.md`. Read it first.

## Ground rules
- **Target:** Minecraft Java **26.2**, Fabric. Don't assume anything from 1.21.x still holds. 26.x is unobfuscated: no Yarn, use Mojang names exactly as `genSources` shows them.
- **Java 25.** `scripts/setup-container.sh` installs `openjdk-25-jdk-headless` and points Gradle's daemon at it in the user file `~/.gradle/gradle.properties` (`org.gradle.java.home=...`), not in the repo.
- **Never assume vanilla code exists or behaves a certain way.** Find the real method in the 26.2 sources with grep before writing a mixin or relying on a vanilla method. Quote class, method and line in a comment above each mixin. If the design doesn't match the source, stop and tell the user instead of working around it.
- **Source of truth:** only the decompiled source from `./gradlew genSources` on the official 26.2 jar. No third-party repos of Minecraft code. Keep generated sources outside git (they live in `.gradle/loom-cache`, which is ignored; unzip copies go in the scratchpad).
- **Check against the source three ways:** (1) read it; (2) let the build check it: a mixin whose target doesn't exist must fail the build or start-up, never use `require = 0`; (3) run the real game: behavior is confirmed with game tests on a headless 26.2 server, and when the source and a test disagree, the test wins and the finding goes in `docs/DESIGN.md`.
- If `genSources` fails, stop and report the exact error.
- Look up versions (meta.fabricmc.net, maven.fabricmc.net, maven.nucleoid.xyz), don't guess.
- Save tokens: grep for specific methods, run builds from the command line and read only failing output.
- When reporting problems, list them briefly first; give fixes and a plan only when asked.

## Commands
- Build: `./gradlew build`
- Game tests (headless 26.2 server): `./gradlew runGameTest` (exit code 1 on any failed required test; results in the log, `build/run/gameTest/`)
- Decompile: `./gradlew genSources`; jars end up in `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-{common,clientOnly}-*/26.2/*-sources.jar`
- Run client: `./gradlew runClient`

## Sandbox quirks (this cloud environment)
- `repo.maven.apache.org` returns HTTP 429 from the shared IP. `~/.gradle/init.d/central-mirror.gradle` (written by `scripts/setup-container.sh`, user level, not in the repo) rewrites it to `repo1.maven.org`; `settings.gradle` also lists repo1. If a build still hits a 429, just rerun: Gradle caches what it got.
- A game-test mod only exists if `fabricApi.configureTests { createSourceSet = true }` is set (already is). Never trust "N tests passed" without checking that our test's name appears in the log.

## Environment
`fabric.mod.json` says `"environment": "*"` **only so the game tests load** on a dedicated headless server. The mod is meant to be used client-side in singleplayer (built-in server). Don't read `*` as a promise of dedicated-server or multiplayer support.

## Layout
- `src/main` common code, `src/client` client-only code, `src/gametest` game tests (mod id `ghostpath-gametest`, entrypoint key `fabric-gametest`).

## Status
| Step | State |
|---|---|
| Session 1 setup (26.2, Loom 1.19, Java 25, Fabric API 0.161.0, headless game tests) | **done**: `./gradlew build` and `./gradlew runGameTest` pass; one smoke test |
| `docs/DESIGN.md` with Version risk findings | **done** (see open decision below) |
| 1a Undo log | **done**: `UndoLog` + 4 mixins, game test passes, mutation-checked (see DESIGN "Build step 1a"); known gaps listed there |
| 1b Cause tracking (use Opus 5.5) | not started |
| 1c Read/write footprint | not started (hook `BlockCollisions`, record "no collision: chunk below FULL") |
| 1d `/path machine` | not started |
| 2 Ghost copy | not started. Decided: own `ServerLevel` subclass, no Fantasy (DESIGN finding 2 lists what to override) |
| 3 Check ghost against real | not started |
| 4 Live rerun | not started |
| 5 Flight preview | not started |
| 6 Inspector and dots | not started |

Open: explosion randomness (`ServerExplosion.java:135,215,228` read `Level.random` directly; DESIGN finding 6) needs a ruling before step 3 can match exactly; undo log gaps (scheduled ticks, block events) before 1d.
