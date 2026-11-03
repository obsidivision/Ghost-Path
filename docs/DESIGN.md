# Ghost Path: design (Minecraft Java 26.2, Fabric)

Target: Minecraft Java **26.2**, Fabric Loader 0.20.1, Fabric API 0.161.0+26.2, Java 25.
No mapping layer: 26.x is unobfuscated, so code uses Mojang's own names (`PrimedTnt`, `ServerLevel`, `LevelTicks`).
Everything below marked **[verified 26.2]** was read in the sources from `./gradlew genSources` (file and line given).
Anything not marked is design intent and still unchecked.

## Goal
Show where TNT and projectiles will go as a live path in the world. The path updates when the machine that launches them is edited, for example when a piston is added to a duper or compressor.

## Scope
- Client-only install. Works in singleplayer (and a LAN world you host) by using the built-in server. Multiplayer is out of scope.
- Nether portals are out of scope. A path that reaches a portal ends there, marked "portal: not simulated."

## Pipeline
1. **Real recording:** `/path machine <trigger>`
   - Presses the trigger in the real world, with the TNT random kick set to 0 for the run.
   - Records only work that comes from the trigger's chain, using cause tracking. Players, mobs and random ticks are left out.
   - Records writes **and** reads; together they make the **footprint**.
   - Undo log: original states are saved on first change and restored afterwards with updates suppressed. Spawned entities are removed.
2. **Ghost copy:** a runtime dimension (Fantasy, so it has its own level data and game time) that is ticked on demand, with random ticks and mob spawning off. **See finding 2 below: Fantasy 0.8.3 does not give it its own game time.**
   - The footprint and a margin are copied **at the same coordinates**: block states (no updates), block entities, pending scheduled ticks (delay, priority, and the order of ticks firing on the same tick), entities (exact doubles, original processing order). Don't use StructureTemplate to copy entities.
   - Chunk ticket levels are copied too (lazy stays lazy).
3. **Check:** the first ghost run must exactly match the real recording (event log and entity states). A mismatch reports the first event where they differ.
4. **Live rerun:** any real-world block change at a footprint position reruns the ghost after a short delay. The footprint is refreshed after each run.
5. **Flight preview:** starts from the ghost's entity states once the machine has stopped acting on them. It's simulated with vanilla tick code on a copy of the entity that never joins the world; particles, sounds and hit effects are off (hits are recorded, then the copy stops). It's recomputed whenever a block changes in the client world.
6. **Inspector and dots:**
   - Ghost entities are drawn as small dots. `/path interval <ticks> [offset <n>]` sets the spacing; the start and end always get a dot. Hover a dot to expand it into the full ghost entity, with its exact bounding box and a panel. Click with the inspect key to pin it; a pin sticks to its tick number across reruns.
   - Hovering a real TNT or projectile shows the server's exact bounding box and position (round-trip doubles), in-block fraction, velocity, fuse or hit info. Stacks show a count and can be stepped through one by one.

## Entities supported
TNT, ender pearls, snowballs, eggs and thrown potions are exact. Arrows and tridents are exact in flight; trident loyalty is approximate. Wind charges and fireballs are exact. Falling blocks, boats and minecarts are mostly exact. Shulker bullets are approximate.
Random spread (TNT kick, dispensers, bows) is off, or shown as a cone.

## Chunk status
Each tick is marked with its chunk's status, and the path ends with a reason ("freezes: lazy chunk", "saved: unloaded chunk"). The mod counts chunks touched or loaded per tick. For long shots, ghost chunks are copied on demand from the save files, without loading them in the real world.

## Commands
`/path machine <trigger>`, `/path interval <ticks> [offset <n>]`, `/path kick off|cone`, `/path track <type>`, `/path clear`

## Build order
1. real recording
2. ghost copy
3. check ghost against real
4. rerun on footprint changes
5. flight preview
6. inspector and dots

Each step is tested with game tests against the one before. A key test for step 2: a repeater chain copied mid-pulse must fire on the same tick in the ghost and in the real world.

---

## Version risk: findings (26.2 sources)
Paths are relative to the decompiled `net/minecraft/`. Line numbers are from the 26.2 `genSources` output and will move if the version changes.

### 1. Scheduled ticks: storage, saving, order  [verified 26.2]
- Stored per chunk in `world/ticks/LevelChunkTicks`, all containers held in `LevelTicks.allContainers` (Long2ObjectMap, `LevelTicks.java:34`).
- A tick is `ScheduledTick(type, pos, triggerTick, priority, subTickOrder)` (`ScheduledTick.java:8`). Drain order: trigger tick, then priority, then `subTickOrder` (`ScheduledTick.java:9-20`).
- Across chunk containers, `LevelTicks` keeps a `PriorityQueue` ordered by each container's next tick (`CONTAINER_DRAIN_ORDER`, `LevelTicks.java:32,36`).
- Saved form is `SavedTick(type, pos, delay, priority)` (`SavedTick.java:13`): a **relative delay**, no `subTickOrder`.
  `LevelChunkTicks.pack` writes ticks sorted by `subTickOrder` (`LevelChunkTicks.java:93-106`); `unpack` assigns fresh increasing sub-tick numbers in list order (`LevelChunkTicks.java:109-114`, `SavedTick.unpack` at `SavedTick.java:49`).
- Consequence for step 2: order *within one chunk* survives a pack/unpack round trip, but sub-tick numbers are renumbered per load, so cross-chunk order of ticks with the same trigger tick and priority is **not** guaranteed to match. The ghost copy should copy `ScheduledTick` objects (with their `subTickOrder` values) directly, not go through NBT. `LevelTicks.java:264-269` already contains an offset-and-renumber routine used when copying areas; read it before writing our own.

### 2. Dimensions and game time; Fantasy  [verified 26.2, **contradicts the design text**]
- Vanilla: only the overworld is created with `tickTime = true` (`MinecraftServer.java:438-439`); other dimensions get `DerivedLevelData` and `tickTime = false` (`MinecraftServer.java:475-476`). `DerivedLevelData.getGameTime()` returns the wrapped data's time and `setGameTime` is a no-op (`DerivedLevelData.java:22-40`). So **non-overworld dimensions share the overworld's game time**.
- Fantasy has a 26.2 build: **`xyz.nucleoid:fantasy:0.8.3+26.2`** (also 0.8.2 and 0.8.1+26.2-rc-2), repo `https://maven.nucleoid.xyz`. Requires Java 25.
- But Fantasy's `RuntimeLevel.tickTime()` does `getRuntimeLevelData().setGameTime(server.overworld().getGameTime())` on every tick (Fantasy 0.8.3 `RuntimeLevel.java:84-85`). So a Fantasy dimension **mirrors the overworld's game time and does not have its own**. Ticking it on demand therefore would not advance its time, and `LevelTicks.tick(currentTick, ...)` is driven by `getGameTime()` (`ServerLevel.java:389`), so its scheduled ticks would never come due.
- 26.x also has a separate world-clock system (`world/clock/ServerClockManager`); Fantasy mixes into it (`RuntimeClockManager`). That is day time, not game time, but check it in step 2.
- **Decision needed before step 2:** either (a) override/accessor on Fantasy's `RuntimeLevel.tickTime` so our ghost level advances its own `GameTime` (we would depend on Fantasy internals), or (b) skip Fantasy and create our own `ServerLevel` subclass with its own `ServerLevelData`. Not decided; the design text "Fantasy gives it its own game time" is wrong as written.
- Also noted: Fantasy's `ServerLevelMixin` cancels `ServerLevel.tick` for levels with no players and no loaded chunks after a timeout (`mixin/ServerLevelMixin.java:46-55`), which matters for on-demand ticking.

### 3. Entity collision and movement: normal level lookup?  [verified 26.2, **No**]
- `Entity.move` calls `level().getEntityCollisions(...)` (`Entity.java:1148`), then `collideBoundingBox` (`Entity.java:1200-1245`), which calls `level.getBlockCollisions` / `getBlockCollisionsFromContext`.
- Blocks are read by `BlockCollisions` (`world/level/BlockCollisions.java:64-89`): it calls `collisionGetter.getChunkForCollisions(chunkX, chunkZ)` and then `chunk.getBlockState(pos)` **on the chunk**, not `Level.getBlockState`.
- `Level.getChunkForCollisions` is `getChunk(x, z, ChunkStatus.FULL, false)` (`Level.java:770-771`): a chunk below FULL status gives no collision at all.
- Consequence for step 1c: hooking `Level.getBlockState` will **miss entity block-collision reads**. Hook `BlockCollisions` (or `getChunkForCollisions`). Entity-vs-entity collisions are separate: `EntityGetter.getEntityCollisions` (`EntityGetter.java:54`).

### 4. Minecarts, pistons, rails  [verified 26.2]
- Minecarts pick their behavior from a feature flag: `AbstractMinecart.useExperimentalMovement(level)` is `level.enabledFeatures().contains(FeatureFlags.MINECART_IMPROVEMENTS)` (`AbstractMinecart.java:596-598`), which selects `NewMinecartBehavior` or `OldMinecartBehavior` (`:103-106`).
- `MINECART_IMPROVEMENTS` is **not** in `FeatureFlags.DEFAULT_FLAGS` (which is `VANILLA_SET`, `FeatureFlags.java:40-41`): it only turns on when the world uses the experimental datapack. So the **old behavior is the default**, and the mod must handle both depending on `enabledFeatures()` of the level.
- Pistons: `PistonBaseBlock.checkIfExtend` queues a block event via `level.blockEvent` (`PistonBaseBlock.java:104-123`); the move itself runs in `triggerEvent` -> `moveBlocks` (`:150`, `:267`) and creates `PistonMovingBlockEntity` (`:117,175,196`). So cause tracking must cover **block events** and **moving-piston block entities**.
- Rails: `BaseRailBlock.onPlace`/`neighborChanged` -> `updateState` -> `RailState` (`BaseRailBlock.java:64-117`); synchronous neighbor updates, no scheduled tick except waterlogging (`:290`).

### 5. Ender pearls, chunk loading, entity ticking by chunk  [verified 26.2, partly]
- `ThrownEnderpearl.tick` places a chunk ticket only if its **owner is a `ServerPlayer`** (`ThrownEnderpearl.java:~162-178`, via `ServerPlayer.registerAndUpdateEnderPearlTicket`, `ServerPlayer.java:2237`). Ticket is `TicketType.ENDER_PEARL`, radius 2 (`ServerPlayer.java:2248-2249`).
  A pearl fired by a dispenser or machine with no player owner **loads no chunks** and will freeze in an unloaded or lazy chunk. The "path ends with a reason" part of the design is therefore real.
- Entity ticking is gated by chunk state: `ServerLevel.isPositionEntityTicking` = `entityManager.canPositionTick(pos)` and `inEntityTickingRange` (`ServerLevel.java:1800-1802`); `areEntitiesActuallyLoadedAndTicking` (`:1804`).
  **Not yet verified:** the exact condition in the per-tick entity loop (`ServerLevel.java:~430-447`). Check in step 2/5 before relying on it.

### 6. TNT random kick  [verified 26.2]
- The kick is in the **constructor** `PrimedTnt(Level, x, y, z, owner)`: `double rot = level.getRandom().nextDouble() * (float)(Math.PI * 2); setDeltaMovement(-sin(rot)*0.02, 0.2F, -cos(rot)*0.02)` (`PrimedTnt.java:65-69`).
- Chain-reaction TNT also draws randomness for its fuse: `TntBlock` (`TntBlock.java:77-78`) -> `PrimedTnt.getRandomShortFuse` (`PrimedTnt.java:180-181`, `random.nextInt(...)`). Directly ignited TNT does not (`TntBlock.java:89`).
- Consequence: zeroing the kick means setting the horizontal motion to 0 right after construction, but the **`nextDouble()` call must still happen**, or the level random's sequence shifts and the next consumer (fuse rolls, other entities) differs from the real run. Fuse randomness for TNT exploded by other TNT is a second source that "kick off" does not remove; the ghost and the recording must both be handled the same way. To be decided in step 1d.

### 7. Mappings, Java, Loom  [verified]
- **No mappings.** Yarn returns no builds for 26.2 and Mojang publishes no `client_mappings` for it; Loom is used with `minecraft "com.mojang:minecraft:26.2"` and plain `implementation` dependencies (no `modImplementation`, no `mappings` line).
- **Java 25** (Mojang's `javaVersion.majorVersion` for 26.2 is 25; Loom 1.18+ itself needs a Java 25 JVM).
- **Loom:** plugin id `net.fabricmc.fabric-loom`, version `1.19-SNAPSHOT` (resolves to 1.19.3). Needs **Gradle 9.8+**; the wrapper is 9.8.2. This matches the official `FabricMC/fabric-example-mod` branch `26.2`.
- Fabric Loader 0.20.1, Fabric API 0.161.0+26.2.
- `fabric.mod.json` uses `"environment": "*"`, not `"client"`: the gametests run on a dedicated headless server and a `client`-only mod would not load there. The mod's runtime behavior is still singleplayer/built-in server; client-only code goes in the `client` source set. (Deviation from "environment: client"; say if you want it changed.)

### 8. Game test harness  [verified by running]
- Tests are `public` non-static methods taking one `GameTestHelper`, annotated `net.fabricmc.fabric.api.gametest.v1.GameTest`, on a class listed under the `fabric-gametest` entrypoint (Fabric API 4.0.22, `TestAnnotationLocator.java:46-112`).
- `fabricApi.configureTests { createSourceSet = true; ... }` is required. It defaults to off, and with it off the test mod is silently not compiled or loaded and `runGameTest` still reports success for other tests. We saw this: "1 test passed" was not ours until `createSourceSet = true`.
- A deliberately wrong assertion makes `./gradlew runGameTest` exit 1 (checked).
