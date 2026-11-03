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
   - Presses the trigger in the real world. TNT priming (kick and random fuse) draws from a mod-owned, fixed-seed random for the run; the kick is then discarded and 0 applied (finding 6).
   - Records only work that comes from the trigger's chain, using cause tracking. Players, mobs and random ticks are left out.
   - Records writes **and** reads; together they make the **footprint**.
   - Undo log: original states are saved on first change and restored afterwards with updates suppressed. Spawned entities are removed.
2. **Ghost copy:** a mod-owned `ServerLevel` subclass (not Fantasy; see finding 2) with its own level data and its own game-time counter. It is never registered with the server's tick loop; only our code ticks it, on demand, with random ticks and mob spawning off. At every copy its game time is set to the real overworld's game time at that moment.
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
- Consequence for step 2: order *within one chunk* survives a pack/unpack round trip, but sub-tick numbers are renumbered per load, so cross-chunk order of ticks with the same trigger tick and priority is **not** guaranteed to match. **Decided: step 2 copies the `ScheduledTick` objects directly, in order, not via NBT.**
- `LevelTicks.copyAreaFrom(source, area, offset)` (`LevelTicks.java:258-272`) already does this for an area: it gathers `alreadyRunThisTick`, `toRunThisTick` and every container's ticks inside the box (`:260-263`), then re-schedules each with the **same `triggerTick` and `priority`**, and a new `subTickOrder = old - min + max + 1` (`:264-269`). Relative order is preserved; absolute `triggerTick` is **not** changed, which is why the ghost's game time must equal the real game time at copy time (finding 2).
- Two traps in that routine: (1) the new numbers are based on the *source's* `max`, not on the target level's own counter (`Level.nextSubTickCount`, `Level.java:135,1088-1089`), so they are only correct if the target has no ticks of its own, which holds for a fresh ghost; (2) `schedule` silently drops a tick whose chunk container is not loaded in the target (`LevelTicks.java:71-79`, only a `logAndPauseIfInIde`). The ghost's chunks must exist (loaded to the right status) **before** ticks are copied.
- Ticks that were already moved into `toRunThisTick`/`alreadyRunThisTick` (mid-drain) are included by that routine; whether copying mid-drain is exact is for the step 2 repeater test to show.

### 2. Dimensions and game time; Fantasy  [verified 26.2, **contradicts the design text**]
- Vanilla: only the overworld is created with `tickTime = true` (`MinecraftServer.java:438-439`); other dimensions get `DerivedLevelData` and `tickTime = false` (`MinecraftServer.java:475-476`). `DerivedLevelData.getGameTime()` returns the wrapped data's time and `setGameTime` is a no-op (`DerivedLevelData.java:22-40`). So **non-overworld dimensions share the overworld's game time**.
- Fantasy has a 26.2 build: **`xyz.nucleoid:fantasy:0.8.3+26.2`** (also 0.8.2 and 0.8.1+26.2-rc-2), repo `https://maven.nucleoid.xyz`. Requires Java 25.
- But Fantasy's `RuntimeLevel.tickTime()` does `getRuntimeLevelData().setGameTime(server.overworld().getGameTime())` on every tick (Fantasy 0.8.3 `RuntimeLevel.java:84-85`). So a Fantasy dimension **mirrors the overworld's game time and does not have its own**. Ticking it on demand therefore would not advance its time, and `LevelTicks.tick(currentTick, ...)` is driven by `getGameTime()` (`ServerLevel.java:389`), so its scheduled ticks would never come due.
- 26.x also has a separate world-clock system (`world/clock/ServerClockManager`); Fantasy mixes into it (`RuntimeClockManager`). That is day time, not game time, but check it in step 2.
- **Decided: option (b), our own `ServerLevel` subclass. Fantasy is dropped** (unless the subclass turns out to need something from it; if so the step 2 notes must say what). Requirements:
  1. Its own game-time counter; its `tickTime()` advances only that counter and never copies the overworld's.
  2. At each copy, its game time is set to the real overworld's game time at the moment of copying, so `triggerTick` values and same-tick order line up with the real run.
  3. Never ticked by the server loop; only our code calls its tick.
- **What the subclass has to override or supply** (all 26.2 `ServerLevel` / level data):

| Part | Where | What we do |
|---|---|---|
| `ServerLevel` constructor (takes `MinecraftServer`, executor, `LevelStorageAccess`, `ServerLevelData`, `ResourceKey<Level>`, `LevelStem`, isDebug, biomeZoomSeed, customSpawners, `tickTime`) | `ServerLevel.java:225-244` | Call with a unique `ResourceKey<Level>` (e.g. `ghostpath:ghost`), the real overworld's `LevelStem`, empty spawner list, `tickTime=false` (field is `private final`, `:224`; we do not rely on it). |
| `tickTime()` (`protected`) | `ServerLevel.java:475-482` | **Override completely**, without calling super. Vanilla reads `levelData.getGameTime()+1`, calls `serverLevelData.setGameTime`, and also runs `server.getScheduledEvents().tick(...)`, which we must **not** do for a ghost. Ours: `ghostTime++`. |
| `tick(BooleanSupplier)` (`public`) | `ServerLevel.java:355` | Called by our code only. It calls `tickTime()` at `:384` (when `runs`, `:360`), then block/fluid ticks at `:388-393`, `getChunkSource().tick` at `:398`, `runBlockEvents` at `:406`, entities at `:420-447`, `tickBlockEntities`, `entityManager.tick`. Needs a check (step 2) that it works with no players and with the `emptyTime` rule below. |
| Game-time source | `ServerLevel.getGameTime()` -> `Level.levelData.getGameTime()` (`Level.java:128,914`); `tickTime` writes via `serverLevelData.setGameTime` (`ServerLevel.java:201,478`) | Supply our own `ServerLevelData` whose `getGameTime`/`setGameTime` read and write our counter. Implementing the interface means: `LevelData` (`getRespawnData`, `getGameTime`, `isHardcore`, `getDifficulty`, `isDifficultyLocked`; `LevelData.java:19-27`) and `ServerLevelData` (`getLevelName`, `getGameType`, `isInitialized`/`setInitialized`, `isAllowCommands`/`setAllowCommands`, `setGameType`, `setGameTime`; `ServerLevelData.java:9-40`) plus `WritableLevelData` setters. Easiest: extend `DerivedLevelData` (its `setGameTime` is a no-op, `DerivedLevelData.java:37-39`, and `getGameTime` reads the wrapped data, `:22-25`) and override those two, or wrap and copy the vanilla pattern. |
| Set time at copy | n/a | Our `ServerLevelData.setGameTime(overworld.getGameTime())` at each copy, before ticks are copied. |
| Saving | `ServerLevel.save(...)` `ServerLevel.java:873` | Override to do nothing: the ghost must never write to the real world's save. Chunk/entity storage comes from the `LevelStorageAccess` passed to the constructor (`:253-278`, regions under `levelStorage.getDimensionPath(dimension)`); step 2 must point it at a throwaway location or make writes no-ops. Fantasy's `RuntimeLevel.save` does the equivalent. |
| Registration | `MinecraftServer.java:438-441` (overworld) and `:475-478` (derived levels go into `levels`) | **Do not** put the ghost into `MinecraftServer.levels`, so the server loop never ticks it. Consequence to check in step 2: anything that does `server.getLevel(key)` or iterates `getAllLevels()` will not see the ghost. |
| Seed / generator | `ServerLevel.getSeed()` `:1683`; chunk generation | Ghost chunks are filled by copying; use a generator that never generates terrain (Fantasy's `VoidChunkGenerator`/`TransientChunkGenerator` are the model). Not verified against 26.2 `ChunkGenerator`. |

- Nothing in the above needs Fantasy at runtime. What we reimplement from Fantasy: a non-persistent level (no save), a no-op generator, a level not registered in the server's level map.
- Vanilla `ServerLevel.tick` skips the whole entity and block-entity section once `emptyTime >= 300` (`ServerLevel.java:420`); `emptyTime` is reset by `resetEmptyTime()` when the chunk source has active tickets (`:411-414`). The ghost must either keep a ticket alive or reset it each call, or its entities will silently stop ticking.
- (Fantasy detail, no longer relevant now that Fantasy is dropped: its `ServerLevelMixin` cancels `ServerLevel.tick` for empty levels after a timeout.)

### 3. Entity collision and movement: normal level lookup?  [verified 26.2, **No**]
- `Entity.move` calls `level().getEntityCollisions(...)` (`Entity.java:1148`), then `collideBoundingBox` (`Entity.java:1200-1245`), which calls `level.getBlockCollisions` / `getBlockCollisionsFromContext`.
- Blocks are read by `BlockCollisions` (`world/level/BlockCollisions.java:64-89`): it calls `collisionGetter.getChunkForCollisions(chunkX, chunkZ)` and then `chunk.getBlockState(pos)` **on the chunk**, not `Level.getBlockState`.
- `Level.getChunkForCollisions` is `getChunk(x, z, ChunkStatus.FULL, false)` (`Level.java:770-771`): a chunk below FULL status gives no collision at all.
- Consequence for step 1c: hooking `Level.getBlockState` will **miss entity block-collision reads**. **Decided: hook `BlockCollisions`** for the footprint. Entity-vs-entity collisions are separate: `EntityGetter.getEntityCollisions` (`EntityGetter.java:54`).
- **Decided:** when `getChunkForCollisions` returns null (chunk below FULL, `Level.java:770-771`; `BlockCollisions.java:72`), record a footprint/status event `no collision: chunk below FULL` with the chunk position and tick, since that changes the entity's path.

### 4. Minecarts, pistons, rails  [verified 26.2]
- Minecarts pick their behavior from a feature flag: `AbstractMinecart.useExperimentalMovement(level)` is `level.enabledFeatures().contains(FeatureFlags.MINECART_IMPROVEMENTS)` (`AbstractMinecart.java:596-598`), which selects `NewMinecartBehavior` or `OldMinecartBehavior` (`:103-106`).
- `MINECART_IMPROVEMENTS` is **not** in `FeatureFlags.DEFAULT_FLAGS` (which is `VANILLA_SET`, `FeatureFlags.java:40-41`): it only turns on when the world uses the experimental datapack. So the **old behavior is the default**. **Decided: support both behaviors**, detected per level from `level.enabledFeatures()` (the same test vanilla uses, `useExperimentalMovement`), not from a mod setting. Tests must cover each (the experimental flag needs the datapack enabled in the test world; check how the gametest environment can enable it).
- Pistons: `PistonBaseBlock.checkIfExtend` queues a block event via `level.blockEvent` (`PistonBaseBlock.java:104-123`); the move itself runs in `triggerEvent` -> `moveBlocks` (`:150`, `:267`) and creates `PistonMovingBlockEntity` (`:117,175,196`). So cause tracking must cover **block events** and **moving-piston block entities**.
- Rails: `BaseRailBlock.onPlace`/`neighborChanged` -> `updateState` -> `RailState` (`BaseRailBlock.java:64-117`); synchronous neighbor updates, no scheduled tick except waterlogging (`:290`).

### 5. Ender pearls, chunk loading, entity ticking by chunk  [verified 26.2, partly]
- `ThrownEnderpearl.tick` places a chunk ticket only if its **owner is a `ServerPlayer`** (`ThrownEnderpearl.java:~162-178`, via `ServerPlayer.registerAndUpdateEnderPearlTicket`, `ServerPlayer.java:2237`). Ticket is `TicketType.ENDER_PEARL`, radius 2 (`ServerPlayer.java:2248-2249`).
  A pearl fired by a dispenser or machine with no player owner **loads no chunks** and will freeze in an unloaded or lazy chunk. The "path ends with a reason" part of the design is therefore real.
- **Decided/required:** a pearl fired by a machine (no player owner) loads no chunks and freezes when it leaves the ticking range or enters an unloaded chunk. The path must end with that reason, and the chunk-status display must show it ("freezes: no player owner, chunk not entity-ticking" / "saved: unloaded chunk").
- Entity ticking by chunk, **verified** (`ServerLevel.java:420-447`): the per-tick loop is `entityTickList.forEach(...)`; an entity is ticked only if `!entity.isRemoved()`, not frozen by the tick-rate manager, **and** (`entity instanceof ServerPlayer` **or** `chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(entity.chunkPosition().pack())`) (`:434`). Entities outside that range stay in the list but are skipped. The whole section is skipped when `emptyTime >= 300` (`:420`).
- Separate helper, not used by that loop: `ServerLevel.isPositionEntityTicking(pos)` = `entityManager.canPositionTick(pos) && inEntityTickingRange(...)` (`:1800-1802`). The per-tick loop uses only `inEntityTickingRange`; `entityManager` status (lazy/loaded) is applied when entities are moved in or out of `entityTickList`, which is not yet traced (step 2/5).
- Also note the loop does not depend on `runs` (the tick-rate "runs normally" flag): freezing is per entity via `tickRateManager.isEntityFrozen` (`:430`).

### 6. TNT randomness  [verified 26.2; handling **decided**]
- The kick is in the **constructor** `PrimedTnt(Level, x, y, z, owner)`: `double rot = level.getRandom().nextDouble() * (float)(Math.PI * 2); setDeltaMovement(-sin(rot)*0.02, 0.2F, -cos(rot)*0.02)` (`PrimedTnt.java:65-69`).
- Chain-reaction TNT also draws a random fuse: `TntBlock` (`TntBlock.java:77-78`) -> `PrimedTnt.getRandomShortFuse` (`PrimedTnt.java:180-181`, `random.nextInt(...)`).
- PrimedTnt is constructed in three places: `TntBlock.java:77` (explosion chain, with random fuse), `TntBlock.java:89` (ignited, no random fuse), `DispenseItemBehavior.java:212` (dispenser, kick only).
- **Decision: a mod-owned random with a fixed seed.** During a recording and during every ghost run, TNT priming (the kick draw at `PrimedTnt.java:68` and the fuse draw at `TntBlock.java:78`) draws from this mod random instead of `level.getRandom()`. It is reset to the same seed at the start of each run. Same seed in both runs means the ghost-vs-real check can match exactly. "kick off" means: draw from that random, discard the kick value, apply 0 (so the draw count and order stay identical to "kick on").
- Outside a run the vanilla random is used untouched. Side effect to accept: while recording, the real level's own random is not advanced by TNT priming, so the world's random sequence afterwards differs from a world where the machine was fired for real.
- **New finding, not covered by the decision above (needs a ruling):** TNT explosions also use the level's random directly: `ServerExplosion.java:135` (ray power `0.7F + random.nextFloat()*0.6F`), `:215` (`Util.shuffle(targetBlocks, level.random)`), `:228` (fire placement). They read the `protected final RandomSource random` **field** of `Level` (`Level.java:124`, `RandomSource.create()`, unseeded), not `getRandom()`. The real overworld and the ghost have different `random` instances, so unless these three draws are also taken from the seeded mod random, explosion block-destruction can differ between ghost and real and the exact check cannot hold for machines that blow up blocks. Machines that only launch TNT and do not rely on explosions breaking blocks are unaffected.

### 7. Mappings, Java, Loom  [verified]
- **No mappings.** Yarn returns no builds for 26.2 and Mojang publishes no `client_mappings` for it; Loom is used with `minecraft "com.mojang:minecraft:26.2"` and plain `implementation` dependencies (no `modImplementation`, no `mappings` line).
- **Java 25** (Mojang's `javaVersion.majorVersion` for 26.2 is 25; Loom 1.18+ itself needs a Java 25 JVM).
- **Loom:** plugin id `net.fabricmc.fabric-loom`, version `1.19-SNAPSHOT` (resolves to 1.19.3). Needs **Gradle 9.8+**; the wrapper is 9.8.2. This matches the official `FabricMC/fabric-example-mod` branch `26.2`.
- Fabric Loader 0.20.1, Fabric API 0.161.0+26.2.
- `fabric.mod.json` uses `"environment": "*"`, not `"client"`: the gametests run on a dedicated headless server and a `client`-only mod would not load there. The mod's runtime behavior is still singleplayer/built-in server; client-only code goes in the `client` source set. (Accepted deviation from "environment: client". The mod is only meant to be used client-side in singleplayer; `*` exists so game tests load.)

### 8. Game test harness  [verified by running]
- Tests are `public` non-static methods taking one `GameTestHelper`, annotated `net.fabricmc.fabric.api.gametest.v1.GameTest`, on a class listed under the `fabric-gametest` entrypoint (Fabric API 4.0.22, `TestAnnotationLocator.java:46-112`).
- `fabricApi.configureTests { createSourceSet = true; ... }` is required. It defaults to off, and with it off the test mod is silently not compiled or loaded and `runGameTest` still reports success for other tests. We saw this: "1 test passed" was not ours until `createSourceSet = true`.
- A deliberately wrong assertion makes `./gradlew runGameTest` exit 1 (checked).

---

## Build step 1a: undo log (implemented)
Code: `record/UndoLog.java`; hooks in `mixin/`. Test: `UndoLogTest.pistonTntRestoredExactly` (piston pushes a redstone block into TNT; stone floor, chest with items, armor stand and item entity nearby).

**What is logged, and where it is hooked** (26.2 lines, all read from the sources):
| Thing | Hook | When |
|---|---|---|
| block state + block entity NBT at a position | `LevelChunk.setBlockState` HEAD (`LevelChunk.java:268`) | first write at that position |
| block entity (same snapshot) | `LevelChunk.getBlockEntity(BlockPos, EntityCreationType)` RETURN (`:368`) and `LevelChunk$BoundTickingBlockEntity.tick` HEAD (`:739`) | first lookup or first own tick, whichever is first |
| existing entity (full NBT via `saveAsPassenger`) | `Entity.setPosRaw` HEAD (`Entity.java:3794`), `setDeltaMovement(Vec3)` HEAD (`:3722`), `setRemoved` HEAD (`:3920`) | first move, velocity change or removal |
| entity added during the run | `ServerLevel.addEntity` RETURN (`ServerLevel.java:992`) | when accepted; removed on restore |

Restore flags: `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS | UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | UPDATE_SKIP_ON_PLACE` (`Block.java:92-101`): no neighbour update, no shape update, no `onPlace`, and no `preRemoveSideEffects` (which would make a chest drop its items; `LevelChunk.java:~305`). Block entities are restored by clearing (`Clearable`) and `loadWithComponents`, because loading only sets slots present in the tag. Entities that still exist are restored with `Entity.load`; removed ones are recreated with `EntityType.loadEntityRecursive` and `ServerLevel.addWithUUID` (same UUID; worked in the test even though the item was discarded in the same tick-window).
An entity that is not yet registered in the level (`level.getEntity(uuid) != entity`) is never snapshotted, so entities still under construction are not mistaken for existing ones.

**Known gaps (not restored):**
1. Pending scheduled ticks (`LevelTicks`) and queued block events (`ServerLevel.blockEvents`): a run consumes and creates them. Matters when a run is cut short by the tick limit.
2. The level's random and game time (a recording runs real ticks).
3. Non-positional entity changes (health, equipment, effects) of an entity that neither moved, changed velocity nor was removed.
4. Block entities changed through a reference obtained *before* the recording began.
5. Players are never touched; entity passengers/vehicles are saved with their root entity only.

**Verification done** (because a first-time pass proves little):
- Mutation checks, each made `runGameTest` exit 1 for its own reason: skipping `restore()` (destroyed stone floor), skipping block-entity restore (chest NBT differs), skipping entity restore (entity set differs), skipping removal of spawned entities (entity set differs).
- A wrong mixin method name (`tickBogus` on the inner-class mixin) fails start-up with `InvalidInjectionException` (`defaultRequire: 1`), so no mixin can silently do nothing.
- Three consecutive runs pass (the explosion uses unseeded randomness, so the outcome differs per run).
