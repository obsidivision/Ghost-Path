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
`/path machine <x y z>`, `/path kick on|off` (built, 1d), `/path limit <ticks>` (built, 1d), `/path interval <ticks> [offset <n>]`, `/path track <type>`, `/path clear`

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

### 6. TNT randomness  [verified 26.2; handling **decided**, explosions ruled: seed them]
- The kick is in the **constructor** `PrimedTnt(Level, x, y, z, owner)`: `double rot = level.getRandom().nextDouble() * (float)(Math.PI * 2); setDeltaMovement(-sin(rot)*0.02, 0.2F, -cos(rot)*0.02)` (`PrimedTnt.java:65-69`).
- Chain-reaction TNT also draws a random fuse: `TntBlock` (`TntBlock.java:77-78`) -> `PrimedTnt.getRandomShortFuse` (`PrimedTnt.java:180-181`, `random.nextInt(...)`).
- PrimedTnt is constructed in three places: `TntBlock.java:77` (explosion chain, with random fuse), `TntBlock.java:89` (ignited, no random fuse), `DispenseItemBehavior.java:212` (dispenser, kick only).
- **Decision: a mod-owned random with a fixed seed.** During a recording and during every ghost run, TNT priming (the kick draw at `PrimedTnt.java:68` and the fuse draw at `TntBlock.java:78`) draws from this mod random instead of `level.getRandom()`. It is reset to the same seed at the start of each run. Same seed in both runs means the ghost-vs-real check can match exactly. "kick off" means: draw from that random, discard the kick value, apply 0 (so the draw count and order stay identical to "kick on").
- Outside a run the vanilla random is used untouched. Side effect to accept: while recording, the real level's own random is not advanced by TNT priming, so the world's random sequence afterwards differs from a world where the machine was fired for real.
- **Finding (ruled R1, implemented in 1b):** TNT explosions also use the level's random directly: `ServerExplosion.java:135` (ray power `0.7F + random.nextFloat()*0.6F`), `:215` (`Util.shuffle(targetBlocks, level.random)`), `:228` (fire placement). They read the `protected final RandomSource random` **field** of `Level` (`Level.java:124`, `RandomSource.create()`, unseeded), not `getRandom()`. The real overworld and the ghost have different `random` instances, so unless these three draws are also taken from the seeded mod random, explosion block-destruction can differ between ghost and real and the exact check cannot hold for machines that blow up blocks. Machines that only launch TNT and do not rely on explosions breaking blocks are unaffected.

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

**Gaps from 1a, as handled after R4** (details in "Undo-log gaps closed" below):
1. Pending scheduled ticks and queued block events: **closed**.
2. The level's random and game time: **accepted, not restored** (R4).
3. Non-positional entity changes: **closed** for hurt (snapshot before damage, including the hurt state NBT does not hold). Push and velocity changes were already covered by `setDeltaMovement`.
4. Block entities changed through an old reference: **closed for containers** (snapshot before `setItem`/`removeItem`). Any block entity the chain looks up through the level is snapshotted at its first lookup, which is before the change (see "First-read snapshot" below). Only a block entity changed without ever being looked up in the recording is **detected, not fixed**: `setChanged()` runs after the change.
5. Players are never touched; entity passengers/vehicles are saved with their root entity only.

### Undo-log gaps closed (R4)  [implemented]
Test: `UndoGapsTest.ticksEventsHurtAndContainersRestored`.

**Scheduled ticks.** At `begin` the log keeps, for every loaded chunk (empty ones too), the tick container object and its queued `ScheduledTick`s, for both block and fluid ticks (`LevelTicks.allContainers`, `LevelTicks.java:34`, via an accessor). On `restore`, for every chunk whose container is still the same object:
- all its ticks are dropped with `LevelTicks.clearArea` on the chunk's box (`:236-252`; this also fixes the container scheduling);
- the begin ticks are re-added with the **same remaining delay** (`now + triggerTick - beginTime`), priority and `subTickOrder`. Their order among themselves holds, and they come before any tick scheduled later at the same tick and priority.

The world resumes as if it had been paused at `begin`. Game time itself is not restored (R4).
- Chunks loaded during the recording keep their ticks, except the ones the recording created. Those are tracked by identity from the `LevelChunkTicks.schedule` → `scheduleUnchecked` call (`LevelChunkTicks.java:55,58`) and removed.
- Begin ticks whose chunk unloaded meanwhile cannot be put back. They are counted (`ticksNotRestored`) and logged.
- This is level-wide, like the block log: unrelated machinery (a nearby clock) is also put back as it was at begin, ticks included. That is consistent, because its blocks are restored too.

**Block events.** The queue (`ServerLevel.blockEvents`, `:217`, via an accessor) is copied at `begin` and put back on `restore` (cleared, then refilled in order, the same objects).

**Entities: hurt.** `Entity.hurtServer` is abstract (`Entity.java:1931`), so `EntityHurtMixin` targets **every concrete override in 26.2: 54 classes**, generated from the genSources output (grep for the override signature), with a HEAD snapshot. If a class loses its override after a version change, start-up fails (`defaultRequire 1`). A new override is caught by `HurtCoverageTest` (below): the build's game tests fail until the class is added to the list. Push ends in `setDeltaMovement` (`Entity.java:1908-1910`), already hooked.

**Finding: hurt state that NBT does not hold** [verified by test]. The first test failed because a minecart's damage was not restored. Not saved, but deciding the next hit:
- `Entity.invulnerableTime` (`Entity.java:260`);
- `LivingEntity.lastHurt` (`LivingEntity.java:250`; the two together decide the next hit at `:1210-1220`);
- `VehicleEntity` damage, hurt time and hurt direction (synced data, `VehicleEntity.java:94-107`; damage over 40 breaks it, `:50`; it decays by 1 per tick, `AbstractMinecart.java:274-275`).

The entity snapshot now holds these next to the NBT and puts them back after `load`. Other transient fields (AI goal timers, cooldowns, path state) are still not covered.

**Block entities: finding, R4 cannot be done as written** [verified 26.2]. R4 says "snapshot on `setChanged()`". But vanilla calls `setChanged()` **after** the change (`BaseContainerBlockEntity.java:105-108` `removeItem`, `:120-123` `setItem`), so a snapshot taken there is already the changed state. What is implemented instead:
- **Exact for containers:** a HEAD snapshot on `setItem`, `removeItem` and `removeItemNoUpdate` in every block-entity class that declares them (`BaseContainerBlockEntity`; `RandomizableContainerBlockEntity` `:61-73`, which unpacks loot before calling super; `AbstractFurnaceBlockEntity:291`; `ChiseledBookShelfBlockEntity:79,90`; `CrafterBlockEntity:172`; `HopperBlockEntity:74,80`).
- **Detected, not fixed, for everything else:** if `setChanged()` is the first touch, the (already changed) state is snapshotted anyway, the position goes into `lateBlockEntities()`, and a warning is logged. The test changes a chest stack in place (`getItem(0).grow(10)` + `setChanged()`) and checks that it is reported.

**Verification.** The test passes, and so do all earlier tests. Break-it checks, each made `runGameTest` exit 1 for its own reason, then restored:
1. Hurt snapshot off: "pig health not restored: 8.0".
2. Container pre-change snapshot off: "chest changed through an old reference not restored: 9 minecraft:diamond".
3. Tick restore off: "consumed tick not restored with delay 3: []".
4. Transient hurt state off: "pig invulnerability (not in NBT) not restored: 10".
5. Block-event restore off: "block events not as at begin: []".
6. Empty containers left out of the snapshot **and** created-tick removal off: "tick created by the recording in a chunk without ticks at begin is still pending". Either one alone still passes, because each covers that case. The first version of the code had this bug: it was flaky depending on where chunk borders fell.

**First-read snapshot (ruling after R4)** [implemented, `UndoGapsTest.nonContainerBlockEntitySnapshotAtFirstRead`]. Every lookup of a block entity ends in `LevelChunk.getBlockEntity(BlockPos, EntityCreationType)` (`LevelChunk.java:368`; `Level.getBlockEntity` `:700-707` calls it, and a pending block entity that is promoted on the way is a RETURN of the same method). `LevelChunkMixin` snapshots at RETURN, before the caller can change what it was handed. So a non-container block entity that the recording looks up first is restored exactly and is not "late". The test uses a command block (no container): looked up through the level then changed + `setChanged()` -> restored exactly, not late; changed through a reference taken before the recording and never looked up -> still reported late. Break-it: hook off -> "block entity read before the change not restored exactly: say changed".

**`HurtCoverageTest.everyHurtServerOverrideIsHooked`** [implemented]. Builds every registered entity type (`EntityType.create`, 157 of 158 buildable), walks their superclasses and the dragon's parts, adds a scan of every `Entity` subclass in the Minecraft jar (finds `ServerPlayer`, which no registered type builds), and finds every class that declares a concrete `hurtServer(ServerLevel, DamageSource, float)`: **54 in 26.2**. It reads the real target list from the compiled `EntityHurtMixin` (ASM, annotation `targets`) and fails if an override is missing from it (or if a target has no override). Break-it: PrimedTnt removed from the list -> "hurtServer overrides not in EntityHurtMixin: [net.minecraft.world.entity.item.PrimedTnt]".

**Remaining undo-log gaps:** block entities changed through an old reference and never looked up in the recording (detected only); entity state outside NBT beyond the hurt fields; ticks created in another dimension; begin ticks whose chunk unloaded; players.

**Test harness finding.** Game tests tick at full speed (no 50 ms sleep), but force-loaded chunks become entity-ticking after asynchronous loading that takes real time. Waits of 0 to 413 ticks were seen, and once more than 800. The machine tests therefore use `maxTicks = 12000` and log the wait.

**Verification done** (because a first-time pass proves little):
- Mutation checks, each made `runGameTest` exit 1 for its own reason: skipping `restore()` (destroyed stone floor), skipping block-entity restore (chest NBT differs), skipping entity restore (entity set differs), skipping removal of spawned entities (entity set differs).
- A wrong mixin method name (`tickBogus` on the inner-class mixin) fails start-up with `InvalidInjectionException` (`defaultRequire: 1`), so no mixin can silently do nothing.
- Three consecutive runs pass (the explosion uses unseeded randomness, so the outcome differs per run).

### Negative tests (kept as notes)
The mutation checks and the wrong-method-name check above are the negative tests for 1a. Rerun them whenever the undo log changes: break the thing on purpose, confirm `runGameTest` fails for that reason, restore it. A pass alone proves little.

---

## Build step 1b: cause tracking (implemented)
Code: `record/CauseTracker.java`, `record/ChainEvent.java`, `record/WatchedRandom.java`; hooks in `mixin/`. Tests: `CauseTrackingTest.unrelatedClockNeverInFootprint`, `CauseTrackingTest.seededChainRepeatsExactly`.

**Rule.** Work is chain work while chain context is on. The trigger runs with it on (`runAsChain`). Synchronous work below a chain call inherits it (neighbour and shape updates finish before the call returns). Deferred work gets it at its entry point if chain work created or touched the deferred thing:
| Deferred thing | Joins the chain when | Hook (26.2) | Runs as chain at |
|---|---|---|---|
| scheduled block/fluid tick | queued in chain context; if an equal (type, pos) tick is already pending (`LevelChunkTicks.java:54` drops ours), that pending tick **object** (found in `tickQueue`, `:19`) joins ("merged") | `LevelChunkTicks.schedule` HEAD (`:53`) | `LevelTicks.runCollectedTicks`, the `output.accept` call (`:190`), matched by **identity** of the `ScheduledTick` |
| block event (pistons) | queued in chain context; the queue is a set (`ServerLevel.java:217`), so if an equal event is queued already, that queued **object** joins | the `blockEvents.add` call in `ServerLevel.blockEvent` (`:1248`) | `ServerLevel.doBlockEvent` (`:1278`), wrapped; matched by **identity** |
| entity | spawned (`addEntity` RETURN, `:992`), moved (`Entity.setPosRaw`, `:3794`), velocity changed or pushed (`setDeltaMovement`, `:3722`; `push` ends there, `:1908-1910`), or hurt (`LivingEntity.hurtServer`, `:1169`) in chain context; players never | | `ServerLevel.tickNonPassenger` (`:822`) / `tickPassenger` (`:836`), wrapped |
| block entity | put into a chunk (`LevelChunk.setBlockEntity`, `:422`) or `setChanged()` (`BlockEntity.java:194`) in chain context | | `LevelChunk$BoundTickingBlockEntity.tick` (`:739`), wrapped |

Everything else (players, mobs, random ticks, unrelated redstone) runs with context off: not in the event log, not in the footprint.

**Identity, not equality (R7).** `ScheduledTick` and `BlockEventData` are records with value equality, so chain ticks and events are held in identity sets. When one is removed without running, it leaves the chain set and a `TICK_CANCELLED` / `BLOCK_EVENT_CANCELLED` event is logged. A later, equal-looking tick or event on the same spot is a new object and is not chain work. Removal paths in 26.2:
- `LevelTicks.clearArea` (`:236-252`; its predicate is wrapped, which covers the containers and `toRunThisTick`/`alreadyRunThisTick`) and `ServerLevel.clearBlockEvents` (`:1665-1667`). Both are called only by the game-test framework (`StructureUtils.java:73-74`, `GameTestInfo.java:77-78`).
- Chunk unload, `LevelTicks.removeContainer` (`:61-68`). The ticks are saved and come back as new objects on reload (`LevelChunkTicks.unpack`, `:109-114`), so a chain tick cannot be followed across an unload. It is logged as cancelled ("saved with unloading chunk: not followed").
- A merge into a tick that exists only as an un-unpacked saved tick (`pendingTicks`, `:20`) has no object to follow. It is logged as "merged into a saved tick: not followed".

**Event log** (`ChainEvent`, ticks relative to the start, no UUIDs): `WRITE` (block state change, or block entity `setChanged`), `TICK_SCHEDULED`/`TICK_RUN`, `BLOCK_EVENT_QUEUED`/`BLOCK_EVENT_RUN`, `ENTITY_TAGGED`, `ENTITY_STATE` (exact position and velocity of each chain entity after each of its ticks), `BLOCK_ENTITY_TAGGED`, `UNSEEDED_DRAW`; since the gap ruling also `CHAIN_UNLOADED`, `LEFT_DIMENSION`, `VIBRATION_UNTRACKED`. This is the log step 3 compares.
**Footprint so far:** positions of chain `WRITE`s (destroyed blocks included, R1). Reads are step 1c.

**Randomness (R1, R6), implemented.** One `RandomSource.create(seed)` per tracker, created at `begin`. During chain work in the tracked level these sites draw from it:
- TNT: `PrimedTnt.java:68` (kick), `TntBlock.java:78` (short fuse).
- Explosions: `ServerExplosion.java:135,215,228`.
- Item drops (R6): `Block.popResource` (`Block.java:408`, jitter `:409-411`), `Block.popResourceFromFace` (`:421`, position and velocity `:422-427`), `Containers.dropItemStack` (`Containers.java:36`, a destroyed container spilling: position, stack split, velocity), `DefaultDispenseItemBehavior.spawnItem` (`:41`, dropper/dispenser launch speed and spread).
- **Entity's own random (R6).** Every entity has its own unseeded `Entity.random` (`Entity.java:254`). It makes the UUID (`:287`, `Mth.createInsecureUUID`) and, for items, the spawn velocity, yaw and bob offset (`ItemEntity.java:54,58,65`). The 1b detector was blind to it. Now an entity built by chain work gets its random seeded from one chain-stream draw, so its UUID and all its own draws repeat exactly. Any other entity's random is wrapped, and its draws during chain work are reported.

Note: chain-built entities get deterministic UUIDs, the same in every run with the same seed. This is fine while each recording is undone before the next (the undo log discards spawned entities); a ghost level is a separate entity manager.
Other item spawn paths (`JukeboxBlockEntity.java:54`, mob drops, `ShulkerBoxBlock`, lecterns) are not redirected. If chain work hits them they show up as warnings.

`Level.random` (`Level.java:124`) and every `Entity.random` are wrapped in `WatchedRandom` at construction. It passes every call through unchanged (`LegacyRandomSource` overrides none of `RandomSource`'s default methods, so the draw semantics are identical). Any draw from an unseeded wrapper during chain work is reported as `UNSEEDED_DRAW` with its call site. That catches both `level.random` and `level.getRandom()`.

**Allowlist (R6).** `record/UnseededDrawAllowlist.java` maps "File.java:line" (first caller outside `RandomSource`/`Mth`/`Util`) to a reason. A listed draw is still logged, marked "allowed: reason". Anything else is logged with "WARNING: not allowed" and a server-log warning once per site. Current list: `PistonBaseBlock.java:172` (piston extend sound pitch). An entry that stops matching after a version change turns back into a warning, which is the safe direction.
"Kick off" is done in 1d (below).

**Unseeded draws found by the test machine:** only `PistonBaseBlock.java:172` (allowed). The `Block.popResource` jitter found in the first 1b version is now seeded (R6). The loot-table rolls for explosion drops never showed up as draws from these randoms (not traced further).

**Findings while testing**
- At `LevelChunk.setBlockEntity` HEAD the block entity's own level is still null (the method sets it), so the hook uses the chunk's level. The first version missed the moving piston because of this.
- **Tests only (R9):** the game tests force-load chunks (`setChunkForced`) so the machine is entity-ticking. **The real recording (`/path machine`) must never force-load chunks or change ticket levels.** The ghost copies the real chunk ticket levels (R2), and a recording that added tickets would change what it copies and how the real run behaves.
- The default Fabric test structure is 8x8x8 (`fabric-gametest-api-v1:gametest/structure/empty.snbt`). A longer machine can reach a chunk that is not entity-ticking: the primed TNT then never ticked (seen once; depends on where the test lands). The tests force-load the area and wait for `isPositionEntityTicking` before recording. Once, the TNT froze for 21 ticks while the forced ticket was still being applied. This is finding 5 in practice: chunk state changes the path.

**Verification done**
- Both tests pass three runs in a row; `./gradlew build` passes. The clock really runs during the recording (the undo log sees the observers write) and none of its positions are in the footprint or the event log.
- Break-it checks, each made `runGameTest` exit 1 for its own reason, then restored:
  1. Filter broken (every scheduled tick treated as chain): `unrelatedClockNeverInFootprint` fails, "clock block 0, 2, 4 is in the footprint".
  2. Seeding off everywhere (`randomFor` returns the vanilla random): `seededChainRepeatsExactly` fails at the first TNT state (different kick).
  3. Only the three explosion sites unseeded (kick still seeded): fails at event 109, an explosion `WRITE` (different destroyed block); the TNT path before it is identical.
  4. The previous commit's value-based matching (main code stashed, new test kept): `removedChainWorkDoesNotLeak` fails, "unrelated work ran as chain work: BLOCK_EVENT_RUN".
  5. `Block.popResource`/`popResourceFromFace` unseeded: `seededChainRepeatsExactly` fails at the first item `ENTITY_STATE` (different position, same velocity).
  6. Entity randoms not seeded: fails at the first item `ENTITY_STATE` (different velocity). The detector logs warnings for `Mth.createInsecureUUID` and `ItemEntity.<init>` (`:57,58,65`).
- `seededChainRepeatsExactly` now compares **every** event (items and unseeded draws included) and also requires that no draw is outside the allowlist. `removedChainWorkDoesNotLeak` covers a merged tick, an own tick and a block event that are each cleared and then replaced by an unrelated one on the same spot.
- A test that times out mid-recording leaves its undo log active, and later tests then fail with "a recording is already active". Generating force-loaded chunks can take long, so the limits are 800/1000 ticks (a passing run needs about 300).
- `defaultRequire: 1` holds for the new mixins, so a wrong target fails start-up (checked for 1a; same config).

**Known gaps (1b)**
1. (Fixed, R7: identity tracking.) A chain tick is not followed across a chunk unload/reload; this is logged.
2. Ticks dropped because their chunk is not loaded (`LevelTicks.java:74-75`) are not logged.
3. Callers of the static `BlockEntity.setChanged(Level, BlockPos, BlockState)` (`:200`) bypass the hook. Non-living entities that override `hurtServer` join only when moved, pushed or spawned.
4. An entity built in chain context is tagged in its constructor (via `setPos`), even if it is never added. Harmless, but it uses an index number.
5. Chain influence through work that runs with context off is not followed. Example: a mob or random tick reacting to a block the chain placed. This is the intended rule (those are "outside the chain"), but the ghost run will diverge there if it matters.
6. `ENTITY_STATE` is logged every tick for every chain entity, items included. Log size is not limited yet.
7. `WatchedRandom` wraps the random of every level and entity, the client's included. Outside a recording that costs one static read per draw.

### Negative tests (kept as notes)
Rerun the three break-it checks above whenever cause tracking changes.

---

## Build step 1c: read footprint (implemented)
Code: `CauseTracker.onRead` / `reads()` / `footprint()`, `CauseTracker.onNoCollisionChunk`; hooks `mixin/LevelReadMixin.java`, `mixin/BlockCollisionsMixin.java`. Tests: `FootprintTest.collisionReadsInFootprint`, `FootprintTest.noCollisionBelowFullRecorded`, and the 1b tests, which now check the whole footprint.

**Footprint = every block position that chain work read or wrote**, with how it was read:
| Read kind | Hook (26.2) |
|---|---|
| `BLOCK` | `Level.getBlockState` HEAD (`Level.java:368`) |
| `FLUID` | `Level.getFluidState` HEAD (`:378`; reads the chunk itself, `:383-384`, not `getBlockState`) |
| `BLOCK_ENTITY` | `Level.getBlockEntity(BlockPos)` HEAD (`:700`) |
| `COLLISION` | `BlockCollisions.computeNext`, the `chunk.getBlockState(this.pos)` call (`BlockCollisions.java:89`) |

`ServerLevel` overrides none of the three `Level` methods. Reads are kept in the footprint only, not in the event log: one explosion reads about 800 positions.

**"No collision: chunk below FULL"** (decided in finding 3): `BlockCollisions.getChunk` (`:64-77`) returns null when `Level.getChunkForCollisions` (`Level.java:770-771`, `getChunk(x, z, FULL, false)`) finds the chunk below FULL. `computeNext` then skips every block there (`:87`). During chain work this logs a `NO_COLLISION_BELOW_FULL` event with the chunk x,z, once per chunk and tick.

**Findings**
- **Finding 3 confirmed by a running test:** a glass wall that a chain TNT flies into is in the footprint only as `COLLISION`. With the `BlockCollisions` read hook off, the wall is not read at all.
- **A chain read of an unloaded position loads the chunk.** `Level.getBlockState` goes through `getChunk(x, z)` = `getChunk(x, z, FULL)` with load=true (`Level.java:204-205, 373`). This is vanilla behaviour of the machine (a real firing does the same), not something the recording adds. The mod's own hooks only read positions that are being written or handed out, which are loaded (R9).
- **Chunk status moves while you look.** Around a test, chunks keep loading asynchronously. The first chunk below FULL found at launch was FULL one tick later, so the test accepts the event for that chunk or any further out. For step 2/5 this means a chunk's status has to be read at the tick it matters, not once up front.
- **Footprint repeatability:** within one placement, the two seeded runs give the same events **and** the same footprint (about 1100 entries compared). Between JVM runs (different test placement in the world) the read count differs (835 / 900 / 978 for the clock test). Cause found, see "Footprint depends on the place" below. It supports copying the ghost **at the same coordinates**.
- **Test harness: barrier shell.** The 26.2 framework encases each test structure in barrier blocks, one block outside its bounds, ceiling included (`GameTestInfo.java:75` `encaseStructure`; `TestInstanceBlockEntity.java:358-363, 374-391`). They are removed only after a pass. The 1b machine (15 blocks long, structure 8x8x8) had a barrier wall through it at x = 8, which partly shielded the explosion; the earlier 1b results were obtained like that. `TestSupport.removeBarriers` now clears the shell before building. The 1b results (identical seeded runs, clock out of the footprint) still hold without it.

**Verification.** All 9 game tests pass three runs in a row; `./gradlew build` passes. Break-it checks, each made `runGameTest` exit 1 for its own reason, then restored:
1. `BlockCollisions` read hook off: "wall not in the footprint as a collision read: null".
2. Below-FULL hook off: "no 'no collision: chunk below FULL' event for chunk x >= ...".
3. `Level.getBlockState` hook off: "block above the piston not read: null". The piston reads the block above it for quasi-connectivity, `PistonBaseBlock.java:138-140`.
- Clock test: the clock is now checked against the whole footprint (reads too). The chain reads 835-978 positions and none of them is a clock block.

**Known gaps (1c)**
1. (Closed after 1c, see "Footprint additions" below: entity areas, light reads, heightmap reads.) Still not recorded: light read through a `LevelLightEngine` directly or by a getter that is not a `Level`.
2. Collisions against getters other than a `Level` (for example a mob's `PathNavigationRegion`) are not recorded.
3. Other read paths that bypass the three `Level` methods and `BlockCollisions` (code reading a `LevelChunk` directly) have not been audited exhaustively.
4. `NO_COLLISION_BELOW_FULL` covers block collision only. An entity in a chunk that is not entity-ticking simply does not tick; that is for the chunk-status display (step 5).

---

## Footprint additions (after 1c)  [implemented]

**Entity areas.** Every box chain work asks `Level` for entities is kept in `CauseTracker.entityAreas()` (distinct boxes). All box lookups end in three `Level` methods, hooked at HEAD by `LevelEntityAreaMixin`: `getEntities(Entity except, AABB, Predicate)` (`Level.java:775`; `EntityGetter.getEntities(except, bb)`, `getEntityCollisions`, `getPushableEntities` and explosion knockback all call it), `getEntities(EntityTypeTest, AABB, Predicate, List, int)` (`:806`; `getEntitiesOfClass` and the typed overloads), `hasEntities` (`:834`). `ServerLevel` overrides none of them. Not events, footprint only. The 1b machine produces about 300 distinct boxes: most are the collision queries of every moving chain entity (`Entity.move`), and the explosion's knockback box. Test: `FootprintTest.entityAreasInFootprint` (each of the three paths; a lookup outside chain work is not recorded). Break-it: recording off -> "getEntities(except, bb) area missing: []".

**Light and heightmap reads: the test machine hits them, so they are logged.** Probed first with a throwaway mixin: `Entity.isInRain` (`Entity.java:1604`) -> `Level.precipitationAt` (`Level.java:955-969`) calls `canSeeSky` (light, `BlockAndLightGetter.java:18`) and `getHeightmapPos` (`:965`). **Only while it rains**: it returns at once if `!isRaining()` (`:957`). Logged as:
- light: `ReadKind.LIGHT` at the position, hooked at HEAD of the two default methods `BlockAndLightGetter.getBrightness` (`:10`) and `getRawBrightness` (`:14`), only for getters that are a `Level`;
- heightmap: `CauseTracker.heightmapColumns()`, the (x, z) column, hooked at HEAD of `Level.getHeight(Types, int, int)` (`Level.java:347`; `getHeight(type, pos)` and `getHeightmapPos` end there).

**Finding: weather changes the footprint, and is not part of the undo log or of any copy.** Without rain the machine reads no light and no heightmap ("light reads 0, heightmap columns 0" appeared in some runs, 61/9 in others). Weather advances in real time during a recording (rain level moves 0.01 per tick, `ServerLevel.java:762-769`) and decides, besides these reads, whether a burning chain entity is extinguished. Step 2 needs a rule for weather (copy the real rain level and keep it fixed in the ghost, or declare it unsupported). The tests pin it (`MachineCommandTest`: raining, rain level 1).
**Finding: which positions get a light read is not repeatable between two identical runs.** Two consecutive runs of the same machine (restored in between) have identical events but differed by one position in the light reads (63 vs 62; `isInRain` short-circuits on the first `canSeeSky`, and the skylight value had changed in between). Skylight is updated by the asynchronous light thread; the restore's block writes are relit later. The comparisons in the tests therefore leave LIGHT out (presence is still checked). For the ghost: light is a read the ghost cannot reproduce exactly unless it copies light data as well. Not solved.

**Footprint depends on the place (the 835-978 variation)**  [measured, `FootprintVariationTest`, 3 JVM runs after a first pass]. The same machine is built twice, exactly 32 blocks apart on x and z (same chunk alignment, same seed, both areas force-loaded), and recorded A, B, A again. Result, every run:
- A and A again (same place): identical footprint and events.
- A and B (32 blocks apart, same chunk alignment): **different footprints** (for example 850 vs 882 reads), same number of events and writes (195 events, 20 written, 11 destroyed).
- So it is **not chunk boundaries**: it depends on the absolute position. (The very first run of this test showed identical footprints for A and B, 940/940, without the control run; it was not seen again in the 7 later runs. Cause of that one not known: it was also before the entity-id fix below, but that is not shown to matter.)
- All differing positions are vertical columns below the destroyed floor (a falling entity's collision reads), at slightly different horizontal positions in A and B: the drops fall differently. That fits the source, but is **not isolated by an experiment**: `ServerExplosion.calculateExplodedPositions` collects blocks in a `HashSet<BlockPos>` (`ServerExplosion.java:121`) and builds the list from its iteration order (`:168`); `Vec3i.hashCode` depends on the absolute coordinates, so the order of destroyed blocks, and with it the order of the seeded drop draws (`:215` shuffles that list), depends on the place. Conclusion by the ruling's rule: different footprint = position-dependent order (hash), not chunk boundaries. Not investigated further, as ruled. It confirms that the ghost has to copy at the **same coordinates** (same hash order), and that two recordings at different places are not comparable.

---

## Build step 1d: `/path machine`  (implemented)
Code: `record/MachineRecorder.java`, `record/Recording.java`, `record/RecordingStore.java`, `command/PathCommand.java`, `PathSettings.java`; wiring in `GhostPath.java` (command registration, `END_SERVER_TICK`, `SERVER_STOPPING`). Test: `MachineCommandTest.pathMachineOnTheCauseMachine`.

**Commands.** `/path machine <x y z>`, `/path kick on|off`, `/path limit <ticks>` (default 2000, session setting, not saved). Allowed for the owner of a singleplayer world (`MinecraftServer.isSingleplayerOwner`, `MinecraftServer.java:1829`, also without cheats) and for permission level 2 (`Permissions.COMMANDS_GAMEMASTER`) and up. Game tests cover level 0 refused and level 2 allowed on a source; the singleplayer-owner branch needs a real player and is **not tested**.

**Trigger.** The block must be a `LeverBlock` (`pull`, `LeverBlock.java:95`) or a `ButtonBlock` (`press`, `:112`); anything else fails with "the block at x y z is Stone, not a lever or a button" before anything is touched. A button that is already pressed is also refused (a press would do nothing). The trigger is called without a player (both take a nullable player; only sound and game event use it) inside `runAsChain`. The position must already be loaded; a recording never loads chunks or changes tickets (R9).

**Run.** `UndoLog.begin` + `CauseTracker.begin(level, SEED, kick)` + trigger, then the end-of-server-tick hook checks `CauseTracker.pending()` once per tick. Seed `MachineRecorder.SEED` is fixed, so the same machine gives the same run. It stops when nothing is pending, or at the tick limit (counted in server ticks; the reported number is game ticks), then `tracker.end()`, `undo.restore()`, a `Recording` is stored in `RecordingStore.latest()` (memory only) and the summary is printed. **If the limit is hit the summary says so and what was still pending** ("STOPPED AT THE TICK LIMIT (5), chain work still pending: 1 ticks, ..."). A stop request restores first (`SERVER_STOPPING`) so the half-run machine is not saved. An autosave in the middle of a recording **cannot** be prevented: it would save the half-run world (not covered; at most `limit` ticks).

**What "pending" means** (`CauseTracker.pending()`): chain scheduled ticks and block events not yet run or removed; chain entities still in the level; chain block entities that are busy (since the gap ruling below: a moving piston, a block entity chain work changed in the last 8 ticks, or a hopper with cooldown above 0). **Interpretation to confirm:** the ruling says "every chain entity is gone", but the 1b machine's blast leaves item entities that lie on the ground for 6000 ticks, so a literal reading makes this machine always run into the 2000-tick limit. Item entities therefore count as gone once they are on the ground and their position did not change for two ticks in a row. Everything else (TNT, falling blocks, minecarts, an item that keeps moving) must really be gone. (Fixed by the gap ruling: busy chain block entities are pending, see "Cause-tracking gaps closed" below.)

**Kick off.** `PrimedTnt` constructor `:69` `setDeltaMovement(-sin(rot) * 0.02, 0.2, -cos(rot) * 0.02)` is wrapped (`PrimedTntMixin`): the angle is still drawn from the chain stream (so everything after stays in step), the horizontal parts are replaced by 0, the fixed upward 0.2 stays (measured first velocity after one tick: (0, 0.1568, 0) off; (0.0166, 0.1568, -0.0105) on). "Use 0" is read as the horizontal kick being 0, not the whole velocity. Applies to every TNT built by chain work (`TntBlock.wasExploded` too). The setting used is stored in the recording (`kickApplied`) for the ghost.

**Summary** (printed and `Recording.summary()`): ticks; footprint = written / read / entity areas (plus block positions, light reads, heightmap columns); TNT spawned (primed TNT added to the level by chain work); unseeded draws not on the allowlist ("warnings", allowed ones in brackets); late block entities (plus begin ticks not restored, if any).

**Finding: the entity id changes how items move** [found by the test, fixed]. The second command run of the same machine diverged at tick 147: a resting item moves only when `(tickCount + getId()) % 4 == 0` (`ItemEntity.java:141`), and `Entity.id` comes from a counter (`Entity.java:315`, `Level.getNextEntityId`) that is different in every run. Mobs use the id for their tick phase (`Mob.java:718`), the elder guardian and warden too (`ElderGuardian.java:67`, `Warden.java:289`), boats for the seat side (`AbstractBoat.java:620`). The 1b seed test missed it: it ran only 130 ticks, before the items rested, and the id offset happened to line up. Fix (`EntityRandomMixin`, `CauseTracker.chainEntityId`): an entity built by chain work takes the next ids until `id % 4` equals its construction index in the chain mod 4 (skipped ids are simply unused), so the residue is the same in every run. Entities that existed before the chain keep their ids: **step 2 must copy entity ids (at least mod 4, better exact) for copied entities.** Break-it: fix off -> "second run differs at event 220". Mod 4 covers `%4` and `%2`; larger moduli (elder guardian 1200, warden 120) are not covered for chain-built entities.

**Verification.** All 14 game tests pass, five consecutive runs after the last change. `MachineCommandTest` runs the real command on the 1b machine with a lever as trigger (lever pull on the floor next to the repeater line), twice: the printed summary equals `Recording.summary()`; every number is recomputed from the recording's raw data (writes = positions of WRITE events, TNT = tagged tnt events, ticks vs the last event tick, footprint line); the second run gives identical events, writes, entity areas, heightmap columns and non-light reads; the events equal a direct tracker run that pulls the same lever (234 events); the block states of the area (and the entity count) are as before after every run; limit 5 is reported and restores; a stone target gives the clear failure and starts nothing; permission level 0 starts nothing; kick off gives zero horizontal velocity and on gives a nonzero one. Break-it checks, each made `runGameTest` exit 1 for its own reason, then restored: first-read snapshot off (\"say changed\" not restored); hurt list without PrimedTnt; entity-area recording off; kick wrap always applying the kick (\"kick off but TNT has horizontal velocity\"); entity-id fix off (\"second run differs at event 220\"); light hook off (\"no light read recorded\"); heightmap hook off (\"no heightmap read recorded\"); pending ignoring entities (the recording ends after 15 ticks, before the explosion); limit flag forced false (\"limit 5 not hit as expected\").

**Known gaps (1d)**
1. Autosave during a recording saves the half-run world. A crash during a recording leaves it half-run.
2. (Fixed after 1d: busy chain block entities count as pending.)
3. The recording runs on the live server, so a player or mob near the machine can change the run; they are outside the chain and normally leave it alone, but the world around is real. Players inside the blast are not hurt in the undo log (players are never touched).
4. The weather and light findings above.
5. Not tested: the singleplayer-owner permission branch; a button as the trigger (same code path as the lever, `ButtonBlock.press`).

---

## Cause-tracking gaps closed (ruling after 1d)  [implemented]
Code: `CauseTracker` (new hooks, `pending()`, `notFollowed()`), `Recording.summary()` ("Not followed" line); mixins `LevelChunkMixin` (kept block entity), `VibrationListenerMixin`, `ItemEntityMixin`, `HopperBlockEntityMixin`, `HopperBlockEntityAccessor`, `EntityMixin` (removal), `ServerLevelMixin` (chunk unload), and the dispenser mixins listed below. Tests: `ChainGapsTest` (8 tests: hopper enabled by chain, hopper cooldown pending, chain piston to sculk sensor, item merge, hopper pickup, dispensers, unload, change of dimension).

| Gap | Joins the chain / logged when | Hook (26.2) |
|---|---|---|
| 1. block entity whose state the chain changes | chain work sets a new state at a position that keeps its block entity: the block entity is tagged ("block state changed") | `LevelChunk.setBlockState`, the `blockEntity.setBlockState(state)` call in the keep branch (`LevelChunk.java:344`). The undo snapshot (state + block entity NBT) is already taken at HEAD of the same method (`captureBlock`), i.e. before the change |
| 2. vibrations | chain work emits a game event that schedules a vibration on a listener. Block-entity owner (sculk sensor, calibrated sensor, shrieker): tagged ("vibration"). Entity owner (warden, allay): `VIBRATION_UNTRACKED` event "vibration to untracked entity", not followed | HEAD of `VibrationSystem$Listener.scheduleVibration` (`VibrationSystem.java:247-251`; `handleGameEvent` :235 and `forceScheduleVibration` :244 end there). Game events are delivered synchronously (`GameEventDispatcher.post`, `GameEventDispatcher.java:22-61`), so the chain context is still on |
| 5a. item merge | a chain item merges into another item, whichever of the two is ticking: the receiver is tagged ("merged chain item") | HEAD of `ItemEntity.merge(ItemEntity, ItemStack, ItemEntity, ItemStack)` (`ItemEntity.java:252`, from `tryToMerge` :222). Undo snapshot of both items there (`setItem` goes through no other hook) |
| 5b. hopper pickup | a hopper takes items from a chain item (item discarded or count changed): the hopper block entity, or a hopper minecart, is tagged ("picked up chain item") | wrap of `HopperBlockEntity.addItem(Container, ItemEntity)` (`HopperBlockEntity.java:265`; from `suckInItems` :236 and `MinecartHopper.java:110`) |
| 6. dispensers changing an entity | the behaviour changed the entity (calls that report success: only on true): the entity is tagged ("dispenser ..."), and the undo log snapshots it just before | see list below |
| 7. unloaded with its chunk | `CHAIN_UNLOADED` event, "chain entity unloaded #i type pos=..." / "chain block entity unloaded type", with position and tick. `pending()` counts it as gone; the summary names it | entity: `Entity.setRemoved` HEAD with `UNLOADED_TO_CHUNK` (`PersistentEntitySectionManager.java:224`); block entity: HEAD of `ServerLevel.unload(LevelChunk)` (`ServerLevel.java:1010`, before `clearAllBlockEntities` :1011 / `LevelChunk.java:675-680`) |
| 8. change of dimension | `LEFT_DIMENSION` event "left dimension #i type pos=..."; the entity leaves the chain set (no longer pending, no longer followed). The copy in the new level is not tagged (it is built in another level) | `Entity.setRemoved` HEAD with `CHANGED_DIMENSION` (`Entity.removeAfterChangingDimensions`, `Entity.java:3175`, from `teleportCrossDimension` :3069) |

**Dispense behaviours covered (gap 6)** (every registration in `DispenseItemBehavior.bootStrap` and `DispenserBlock.getDispenseMethod` read; the ones that change an existing entity):
- shears: `ShearsDispenseItemBehavior.tryShearEntity` (`:50-64`): `shearOffAllLeashConnections` (`:52`, tagged only if it returns true) and `Shearable.shear` (`:57`; sheep, mooshroom, snow golem, bogged, ...);
- equipment: `EquipmentDispenseItemBehavior.dispenseEquipment` (`:20-36`), `setItemSlot` at `:30`: armour, saddles, llama carpets, horse armour; also reached from the wither-skull and carved-pumpkin behaviours (`DispenseItemBehavior.java:237,258`) and for any equippable item (`DispenserBlock.java:123`);
- chest on a donkey, mule or llama: anonymous behaviour `DispenseItemBehavior$2` (`DispenseItemBehavior.java:93-114`, `slot.set` at `:104`);
- brush on an armadillo: anonymous `DispenseItemBehavior$11` (`:318-338`, `brushOffScute` at `:330`). The two anonymous class numbers were read from the compiled jar with javap; a renumbering fails start-up (`defaultRequire 1`);
- flint and steel priming a sulfur cube: `FlintAndSteelDispenseItemBehavior.tryIgniteExplosiveEntities` (`:51-58`, `primeTime` at `:57`);
- a block given to a sulfur cube: `SulfurCubeBlockDispenseItemBehavior.dispenseBlock` (`:20-29`, `equipItem` at `:22`; also from the TNT behaviour, `DispenseItemBehavior.java:208`).

Not hooked because they need nothing: behaviours that spawn an entity (projectiles, boats, minecarts, armor stands, spawn eggs, TNT, fish buckets) are already covered by `addEntity`; buckets, bone meal, glowstone, honeycomb, bottles, shulker boxes change blocks only.

**`pending()` (ruling).** A chain block entity still in the level is pending while: it is a moving piston; or chain work tagged it, changed it (`setChanged`) or changed its block state within the last 8 ticks (`CauseTracker.BLOCK_ENTITY_BUSY_TICKS`); or it is a hopper with `cooldownTime > 0` (`HopperBlockEntity.java:38`, read with an accessor; `isOnCooldown` `:426-428`). The hopper case is needed because the hopper marks itself changed through the static `BlockEntity.setChanged(Level, BlockPos, BlockState)` (`HopperBlockEntity.java:125`), which the instance hook does not see. `Pending` now reports `blockEntities` (was `movingPistons`).

**Summary.** A line `Not followed: N - ...` is added, only when there is something to list: each `CHAIN_UNLOADED`, `LEFT_DIMENSION` and `VIBRATION_UNTRACKED` event with its tick and position (`Recording.notFollowed`).

**Findings while testing**
- A sculk sensor that is receiving a vibration calls `setChanged` every tick of the travel time (`VibrationSystem.User.onDataChanged`), so a chain sensor writes a "block entity data" event each tick until it activates. Correct, just verbose.
- The first version tagged the target before the dispense call; the shears loop calls `shearOffAllLeashConnections` on every entity in the box, so the sheep was tagged as "leash" although it had no leash. Tagging now happens after the call and only on success; the undo snapshot stays before it.
- Unloading in the test: a force-loaded chunk 100 chunks away (no other ticket) unloaded 2 ticks after the ticket was removed; entity and block entity were both logged at the same tick. The game-test world persists between runs, so the test first clears what an earlier run saved in that chunk.

**Problems these hooks make visible (not fixed, for a ruling):**
1. A tagged **mob** (a sheep sheared by a chain dispenser, an allay, a horse given a chest) is chain work for good: its AI ticks become chain work, it never leaves, so `pending()` keeps the recording running up to the tick limit, and its AI draws from its own unseeded random show up as unseeded-draw warnings. The test uses a NoAI sheep and does not run the recorder, so this was read from the code, not seen.
2. Gap 1 tags on every kept-block-entity state change by chain work, also ones that do not affect the block entity's behaviour (a chest's facing). The only cost is that its ticks count as chain work.

**Verification.** All 22 game tests pass; `./gradlew build` passes. Break-it checks, one at a time, each made `runGameTest` exit 1 in its own test for its own reason, then restored:
1. Kept-block-entity hook off: "hopper not tagged when chain work changed its block state".
2. Vibration hook off: "sculk sensor not tagged by the chain vibration".
3. Merge hook off: "receiving item not tagged after a chain item merged into it".
4. Hopper pickup hook off: "hopper not tagged after picking up a chain item".
5. Dispenser tag off: "sheared sheep not tagged".
6. Block-entity unload log off: "no 'chain block entity unloaded' event at the chest" (the TNT event still there).
7. Entity unload log off: "no 'chain entity unloaded' event with the TNT's position" (the chest event still there).
8. Left-dimension branch off: "no 'left dimension' event".
9. `pending()` 8-tick rule off: "sculk sensor changed in the last 8 ticks not pending: 0".
10. `pending()` hopper cooldown off: "hopper on cooldown not pending at t+20: 0".
11. Both off (moving pistons only, the 1d rule): those two plus "pending() did not count the busy hopper/chest: max 0".

**Break-it that did not fail:** the dispenser undo snapshot off (`beforeDispenserChange` doing nothing) still passes: the sheared sheep and the equipped armor stand are restored anyway. A ticking living entity calls `setDeltaMovement` every tick, so the undo log has already snapshotted it before the dispenser fires (its scheduled tick is 4 ticks after the trigger). The snapshot only matters for an entity that has not ticked since `begin` (for example one in a chunk that is not entity-ticking). It is kept as a safety net, but no test shows it is needed.

### Not followed, by design (ruling)
Chain influence through these paths is not followed. They run with context off, and the ghost may diverge there.
- **3. POI.** A chain block change adds or removes a point of interest (`ServerLevel.updatePOIOnBlockStateChange`, `ServerLevel.java:1521`; remove `:1527`, add `:1531`). Villagers, bees and piglins read POIs later through their own AI.
- **4. Mob paths.** A chain block change makes mobs recompute their path (`ServerLevel.sendBlockUpdated` `:1131`, `shouldRecomputePath` `:1146`, `recomputePath` `:1155`). The mob is not tagged.
- **9. Random and weather ticks.** Random block and fluid ticks (`ServerLevel.tickChunk` `:496`, `randomTick` `:524` and `:529`), precipitation per chunk (`tickPrecipitation` called `:505`, defined `:578`: snow, ice, cauldrons) and the weather cycle (`advanceWeatherCycle` `:709`) run with context off, also at positions the chain wrote.
- **10. Light.** Already open (see "Footprint additions": light reads are not repeatable, and the light engine is not copied).
- **11. End gateway.** A chain entity using a gateway sets the gateway block entity's cooldown (`TheEndGatewayBlockEntity.portalTick` `:72`, `triggerCooldown` `:79`; destination `EndGatewayBlock.getPortalDestination` `:108`); the gateway is not tagged. The entity's own teleport stays in the same dimension (`Entity.teleportSameDimension`, `Entity.java:3052`) and is followed as a move.
- **12. `/schedule`.** Scheduled functions run from `ServerLevel.tickTime` (`server.getScheduledEvents().tick`, `ServerLevel.java:480`) with context off; the ghost never runs them (finding 2).
- **13. Despawn.** A chain mob despawning (`Mob.checkDespawn`, `Mob.java:685`, called at `ServerLevel.java:432`) or a chain item expiring after 6000 ticks (`ItemEntity.java:179-180`) just counts as gone; no event.

---

## Rulings (user decisions)

**R1. Explosion randomness: seed it.**
- During a recording and during every ghost run, all random draws made by chain work come from ONE mod-owned `RandomSource`, reset to the same seed at the start of each run. Covers TNT priming (kick + fuse) and the three `ServerExplosion` sites (`:135` ray strength, `:215` shuffle, `:228` fire). One stream is enough: the chain draws in the same order in both runs if the copy is right, and a mismatch then shows up as a real difference, which step 3 should catch.
- Draws by things outside the chain (mobs, random ticks, players) keep using `Level.random`.
- If cause tracking (1b) finds chain work reading `Level.random` at a site not redirected, log an "unseeded draw" event with file:line. Never silently allow it.
- The footprint records destroyed blocks as writes, like any other change.

**R2. Ghost entity ticking.** In the ghost `ServerLevel` subclass, reset `emptyTime` before each on-demand tick, and give the copied area a ticket matching the real chunk's level (not forced everywhere), so `inEntityTickingRange` matches the real run.

**R3. `copyAreaFrom`.** OK to use, but only into an empty target with the target chunks loaded first. After copying, assert the tick count equals the source count and fail loudly if any were dropped.

**R4. Undo log gaps.**
- Close the scheduled-tick and block-event gaps before 1d.
- Game time: not restored (real time passes). Level random: not restored (chain draws are seeded).
- Entities: first-touch snapshot (full NBT) also when the chain hurts, pushes or changes the velocity of an entity, not only on move/removal.
- Block entities: first-touch snapshot also on `setChanged()`, to catch changes through an old reference.

**R5. 1a accepted.** The deliberate-break checks were the right check; kept as notes above.

**R6. Item drops: seed them.** During chain work `Block.popResource` draws from the seeded stream; check the `ItemEntity` constructor and the spawn paths for their own random velocity and seed those under chain work too. Items are back in the seed test (they matter for hopper-fed machines and blast-pushed items). Known harmless unseeded draws go on an explicit allowlist (file:line + reason), still logged, marked "allowed"; anything else stays a warning.

**R7. Removed-tick gap: fix before 1c.** Track chain ticks and block events by identity, not by position and block. When one is removed without running, drop it from the chain set. Test: a cancelled chain tick followed by an unrelated tick on the same spot must not count as chain work.

**R8. Order of work:** close the undo-log gaps (scheduled ticks, block events, entity hurt/push snapshot, `setChanged` snapshot), then 1c (read footprint with the `BlockCollisions` hook and the "below FULL: no collision" event); commit after each part.

**R9. Test setup vs real recording.** Force-loading in tests is fine; the real recording must never force-load or change ticket levels (the ghost copies real ticket levels).
