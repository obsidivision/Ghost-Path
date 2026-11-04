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
- Game tests: the framework puts a barrier shell around each test structure (use `TestSupport.removeBarriers` for machines larger than 8x8x8); tests tick at full speed while chunk loading takes real time, so tests that wait for chunks need a large `maxTicks`.
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
| 1b Cause tracking | **done**: `CauseTracker` (chain context through ticks, block events, entities, block entities; ticks/events by identity, R7), event log, write footprint, seeded chain random for TNT, explosions, item drops and chain-built entities' own randoms (R1, R6), `UNSEEDED_DRAW` with allowlist; 3 game tests pass, 6 break-it checks (see DESIGN "Build step 1b") |
| Undo-log gaps (R4) | **done**: ticks and block events restored as at begin (remaining delays kept), hurt snapshot on all 54 `hurtServer` overrides plus non-NBT hurt state, container pre-change snapshot; `setChanged()` only detects (it runs after the change, see DESIGN); `UndoGapsTest` + 6 break-it checks |
| 1c Read/write footprint | **done**: reads via `Level.getBlockState/getFluidState/getBlockEntity` and `BlockCollisions` (collision reads), footprint = reads + writes, `NO_COLLISION_BELOW_FULL` event; `FootprintTest` (2 tests), seed test compares footprints, 3 break-it checks (see DESIGN "Build step 1c") |
| 1d `/path machine <x y z>` | **done**: lever/button trigger, `/path kick on\|off`, `/path limit`, records until no pending chain work or the tick limit, restores, prints the summary, keeps the `Recording` in memory (`RecordingStore`); first-read snapshot for block entities, `HurtCoverageTest` (54 overrides), entity areas + light + heightmap reads in the footprint, footprint-vs-place measured (position dependent, not chunk boundaries), chain-built entity ids fixed mod 4; `MachineCommandTest` + 9 break-it checks (see DESIGN "Build step 1d"); open: items count as gone once at rest (confirm), weather and light are not reproducible |
| Cause-tracking gaps (ruling after 1d) | **done**: kept block entity on chain state change, vibrations (block-entity listeners; entity listeners logged), item merge and hopper pickup, 6 entity-changing dispense behaviours, unloaded with chunk and left dimension logged and named in the summary, busy chain block entities pending (changed in last 8 ticks, hopper cooldown); `ChainGapsTest` (8 tests) + 11 break-it checks (the dispenser undo snapshot could not be made to fail, see DESIGN); POI, mob paths, random/weather ticks, light, end gateway, `/schedule`, despawn documented as not followed (see DESIGN "Cause-tracking gaps closed"); open: a tagged mob keeps `pending()` busy until the limit |
| 2 Ghost copy | not started. Decided: own `ServerLevel` subclass, no Fantasy (DESIGN finding 2 lists what to override) |
| 3 Check ghost against real | not started |
| 4 Live rerun | not started |
| 5 Flight preview | not started |
| 6 Inspector and dots | not started |

Rulings R1-R9 are in DESIGN.md ("Rulings"). Randomness: seeded (R1, R6). Real recordings never force-load or change tickets (R9). Step 2: `copyAreaFrom` only into an empty, loaded target and assert tick counts; reset `emptyTime` per ghost tick; ticket matching the real chunk level.
