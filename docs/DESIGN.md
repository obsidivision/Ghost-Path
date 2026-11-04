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
- Dispenser and dropper slot choice (ruling after the gaps): `DispenserBlock.dispenseFrom` (`DispenserBlock.java:98`) and `DropperBlock.dispenseFrom` (`DropperBlock.java:54`) pass `level.getRandom()` to `DispenserBlockEntity.getRandomSlot` (one `nextInt` per filled slot, `DispenserBlockEntity.java:40`); `DispenserSlotMixin` replaces that argument.
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

**What "pending" means** (`CauseTracker.pending()`): chain scheduled ticks and block events not yet run or removed; chain entities still in the level, **only** TNT, projectiles, items (until settled), vehicles and falling blocks (ruling after the gaps; tagged mobs and other entities never hold a recording open); chain block entities that are busy (since the gap ruling below: a moving piston, a block entity chain work changed in the last 8 ticks, or a hopper with cooldown above 0). **Interpretation to confirm:** the ruling says "every chain entity is gone", but the 1b machine's blast leaves item entities that lie on the ground for 6000 ticks, so a literal reading makes this machine always run into the 2000-tick limit. Item entities therefore count as gone once settled: on the ground and the position unchanged (every axis within 1e-6) for 8 consecutive ticks of their own (ruling after the gaps, see there; before: 2 ticks, exact). Everything else (TNT, falling blocks, minecarts, an item that keeps moving) must really be gone. (Fixed by the gap ruling: busy chain block entities are pending, see "Cause-tracking gaps closed" below.)

**Kick off.** `PrimedTnt` constructor `:69` `setDeltaMovement(-sin(rot) * 0.02, 0.2, -cos(rot) * 0.02)` is wrapped (`PrimedTntMixin`): the angle is still drawn from the chain stream (so everything after stays in step), the horizontal parts are replaced by 0, the fixed upward 0.2 stays (measured first velocity after one tick: (0, 0.1568, 0) off; (0.0166, 0.1568, -0.0105) on). "Use 0" is read as the horizontal kick being 0, not the whole velocity. Applies to every TNT built by chain work (`TntBlock.wasExploded` too). The setting used is stored in the recording (`kickApplied`) for the ghost.

**Summary** (printed and `Recording.summary()`): ticks; footprint = written / read / entity areas (plus block positions, light reads, heightmap columns); TNT spawned (primed TNT added to the level by chain work); unseeded draws not on the allowlist ("warnings", allowed ones in brackets); late block entities (plus begin ticks not restored, if any).

**Finding: the entity id changes how items move** [found by the test, fixed]. The second command run of the same machine diverged at tick 147: a resting item moves only when `(tickCount + getId()) % 4 == 0` (`ItemEntity.java:141`), and `Entity.id` comes from a counter (`Entity.java:315`, `Level.getNextEntityId`) that is different in every run. Mobs use the id for their tick phase (`Mob.java:718`), the elder guardian and warden too (`ElderGuardian.java:67`, `Warden.java:289`), boats for the seat side (`AbstractBoat.java:620`). The 1b seed test missed it: it ran only 130 ticks, before the items rested, and the id offset happened to line up. Fix (`EntityRandomMixin`, `CauseTracker.chainEntityId`): an entity built by chain work takes the next ids until `id % 4` equals its construction index in the chain mod 4 (skipped ids are simply unused), so the residue is the same in every run. Entities that existed before the chain keep their ids: **step 2 must copy entity ids (at least mod 4, better exact) for copied entities.** Break-it: fix off -> "second run differs at event 220". Mod 4 covers `%4` and `%2`; larger moduli (elder guardian 1200, warden 120) are not covered for chain-built entities.

**Verification.** All 14 game tests pass, five consecutive runs after the last change. `MachineCommandTest` runs the real command on the 1b machine with a lever as trigger (lever pull on the floor next to the repeater line), twice: the printed summary equals `Recording.summary()`; every number is recomputed from the recording's raw data (writes = positions of WRITE events, TNT = tagged tnt events, ticks vs the last event tick, footprint line); the second run gives identical events, writes, entity areas, heightmap columns and non-light reads; the events equal a direct tracker run that pulls the same lever (234 events); the block states of the area (and the entity count) are as before after every run; limit 5 is reported and restores; a stone target gives the clear failure and starts nothing; permission level 0 starts nothing; kick off gives zero horizontal velocity and on gives a nonzero one. Break-it checks, each made `runGameTest` exit 1 for its own reason, then restored: first-read snapshot off (\"say changed\" not restored); hurt list without PrimedTnt; entity-area recording off; kick wrap always applying the kick (\"kick off but TNT has horizontal velocity\"); entity-id fix off (\"second run differs at event 220\"); light hook off (\"no light read recorded\"); heightmap hook off (\"no heightmap read recorded\"); pending ignoring entities (the recording ends after 15 ticks, before the explosion); limit flag forced false (\"limit 5 not hit as expected\").

**Known gaps (1d)**
1. (Fixed after the gaps: saving is off during a recording, see "Rulings after the gaps".) `/save-all` (forced) or a crash during a recording still saves or leaves the half-run world.
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
1. A tagged **mob** kept `pending()` busy until the limit. Ruled and fixed, see "Rulings after the gaps".
2. Gap 1 tags on every kept-block-entity state change by chain work, also ones that do not affect the block entity's behaviour (a chest's facing). **Accepted** (ruling): a slightly bigger footprint is fine.
3. The dispenser undo snapshot (could not be made to fail): **kept** as a safety net (ruling).

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

## Rulings after the gaps  [implemented]
Code: `CauseTracker.canHoldOpen`, the chain-entity tick stack (`onChainEntityTickStart`, `ServerLevelMixin`), `SaveGuard`, `Weather`, `LevelWeatherAccessor`, `MachineRecorder`, `Recording`, `UndoLog` (unloaded chunks), `DispenserSlotMixin`, `MinecraftServerSaveMixin`. Tests: `RecordingRulesTest` (6 tests: sheared sheep, autosave and weather, resting-item velocity probe, item settle, dispenser slots, unloaded chunk restore).

**Tagged mobs.** A mob tagged by chain work stays tagged: what it does during the run is chain work. But only TNT (`PrimedTnt`), projectiles (`Projectile`), items (`ItemEntity`, settle rule below), vehicles (`VehicleEntity`: boats, minecarts) and falling blocks (`FallingBlockEntity`) can hold a recording open; on the block side, moving pistons and the busy-block-entity rule from the gaps ruling stay (read as: the list is about entities; scheduled ticks and block events of course still count). An unseeded draw made while a tagged `Mob` is ticking (innermost chain entity tick, so a passenger counts as itself) is logged as `inexact: mob AI` instead of `WARNING: not allowed`; draws on the allowlist stay "allowed". `Recording.unseededDrawsInexact` counts them; the summary adds "(+N inexact: mob AI)" to the draws line and a line "Inexact: a tagged mob's AI drew from an unseeded random, so the result may not repeat exactly".
Test `shearedSheepWithAiEndsBeforeLimit`: `/path machine` on a lever, dispenser with shears, sheep with AI in a glass pen. The recording ends after 25 ticks (limit 2000), 66 draws are "inexact: mob AI", the summary says so, the sheep is restored unsheared.

**Warnings that test found, both ruled:**
- `DispenserBlockEntity.getRandomSlot` (`DispenserBlockEntity.java:40`) picked the slot with `Level.random`: **seeded now** (see the R1/R6 site list in 1b). Test `dispenserSlotChoiceRepeatsExactly`: three dispensers with five different items each, recorded twice with `/path machine`; both runs dispensed flint, feather, stick, all 157 events equal, no unseeded draw. (Item `ENTITY_STATE` events now carry the item, `item=1 minecraft:stick`, so the choice is visible in the log.)
- `Sheep.shear` gives the wool its spread from the sheep's own random (`Sheep.java:176-178`): **left unseeded, marked `inexact: mob`** (ruling, no hook). Rule: an unseeded draw from a `Mob`'s own random outside a tagged mob's tick is `inexact: mob` (`WatchedRandom` now knows its owner); inside a tagged mob's tick it is `inexact: mob AI`. Both count as inexact; the summary says "+N inexact: mob" and "Inexact: a mob (its AI, or its own random) drew unseeded numbers, so the result may not repeat exactly". The sheep test now has 0 warnings.

**Autosave held during a recording** (`SaveGuard`, `MinecraftServerSaveMixin`). Replaces the first version, which set `ServerLevel.noSave` (the `/save-off` switch): that also stops chunk unloading (`ChunkMap.tick` runs `processUnloads` only when `!noSave`, `ChunkMap.java:448`), which changes how the real world behaves, so it was dropped (ruling). Now `noSave` is never touched. Hook: HEAD of `MinecraftServer.saveEverything(silent, flush, force)` (`MinecraftServer.java:627`). In 26.2 every non-forced save goes through it with `force = false`: the periodic autosave (`autoSave`, `:1018-1023`, called at `:991` and when the server pauses empty at `:973`) and the singleplayer pause save (`IntegratedServer.tickServer`, `IntegratedServer.java:153`, read in the client sources). Forced saves pass `force = true` (`/save-all`, `SaveAllCommand.java:26`; start-up, `IntegratedServer.java:112`; `DedicatedServer.java:273`) and the stop save calls `saveAllChunks` directly (`:678`). While a recording (and its restore) runs, a non-forced save is skipped and done once right after the restore (the `autoSave` method has already reset its countdown, so this is the only catch-up). On a server stop no held save is done (the stop saves anyway, after `abort` restored). Not held: `/save-all`; chunks saved because they unload (normal); worldgen's eager chunk saves (`ChunkMap.java:490-505`, only chunks marked by world generation, `:211-212`). Ghost runs (step 2) must use the same guard.
Test `savingOffDuringRecordingAndWeatherStored`: `noSave` unchanged throughout; a non-forced `saveEverything` during the recording returns false and is counted as held; after a normal end the held save was done once; tick limit and `MachineRecorder.abort` end the hold (no save on abort).

**Chunks unload normally during a recording; the restore loads them again** (ruling). `UndoLog` remembers chunks unloaded during the log (`ServerLevel.unload` HEAD) and, for spawned or changed entities unloaded with their chunk, the chunk they were saved into (`Entity.setRemoved(UNLOADED_TO_CHUNK)`; an unload is no longer an undo "touch"). At the end of the recording `UndoLog.chunksToLoad()` lists the chunks the restore needs that are not loaded with their entities: chunks of saved block positions, original chunks of changed entities, chunks a spawned/changed entity was saved into, and unloaded chunks that had ticks at begin or got a tick from the recording. `MachineRecorder` adds the ticket `ghostpath:restore` (registered in `GhostPath`, `TicketType(0, FLAG_LOADING)`: loads, does not simulate, not persistent; radius 0 = FULL) to each, waits until `getChunkNow` and `ServerLevel.areEntitiesLoaded` (`:1792`) are true, then restores and removes the tickets. In the restore, a spawned entity that was unloaded and loaded again is a new object: it is found by UUID and discarded; a changed entity's reloaded copy is restored with `load`; a reloaded chunk that was loaded at begin gets its begin ticks back (its saved ticks were the half-run ones: cleared and re-added, same as a chunk that stayed loaded). Anything still not loaded is counted (`entitiesNotRestored`, `ticksNotRestored`) and shown in the summary, as is "chunks loaded again for the restore: N".
- **The wait is in real time (60 s), not ticks.** Loading is asynchronous disk work; the game-test server ticks at full speed and passed 1200 ticks in under a second before the chunk was read (first version of the limit, found by the test). Seen in the test: 1323 ticks, well under a second.
- During the wait the rest of the world keeps running (nothing of the chain is pending by then); the reloaded chunks do not tick (load-only ticket).
- R9 is unchanged for the recording itself; the restore, after it, adds load-only tickets.
- A crash between the unload save and the restore leaves the half-run chunk on disk (not covered).
- (First version: the restore was level-wide, so unrelated chunks that unloaded were loaded again and the count varied by run. Fixed by the scoped restore below; the count is compared again in `MachineCommandTest` and was stable over 4 full runs: only the unload test's own chunk is ever loaded again.)
- **Open, seen once:** in one of seven full runs after this change, `MachineCommandTest`'s second run of the 1b machine had 8 heightmap columns instead of 7 (and one chunk loaded again for its restore). Not reproduced in the next six runs; cause not found. Heightmap reads only happen in rain (`Level.precipitationAt`), at chain entity positions.
Test `unloadedChunkRestored`: a lever and a dispenser with TNT in a far chunk that only the test's forced ticket keeps loaded. `/path machine` on the lever; once the TNT is dispensed the test drops the ticket, the chunk unloads, the TNT is logged "chain entity unloaded" (and the dispenser "chain block entity unloaded"), the recording ends, the chunk is loaded again for the restore. Then the test waits until the chunk has unloaded once more and loads it again: lever off, the TNT item back in the dispenser, no primed TNT (the dispensed TNT's UUID is gone).

**Weather in the recording** (`Weather`): at the trigger and at the end (before the restore; weather is not restored): `WeatherData` raining, thundering, rain/thunder/clear timers (`ServerLevel.getWeatherData`, `:1691`), the four `Level` fields `oRainLevel, rainLevel, oThunderLevel, thunderLevel` (`Level.java:120-123`, accessor; `getThunderLevel` multiplies by rain, `:920-921`), `canHaveWeather` (`:940`) and the `advance_weather` rule. Summary line "Weather: rain+thunder (rain level ..., thunder level ...) at start[, ... at end]". The test pins rain and thunder with known timers and checks start and end (rain timer minus the ticks run).

**Item settle rule (first ruling did not match the source; kept for the record).** The ruling says "settled after at least 8 ticks on the ground with zero velocity". A resting item's velocity is never zero in 26.2: on 3 of 4 ticks a grounded slow item does not move (`ItemEntity.java:141`, `(tickCount + id) % 4 != 0`), but gravity is applied first (`:129`), so y velocity goes -0.04, -0.08, -0.12 and the 4th tick's move resets it to 0; x/z decay by friction only on moving ticks and stay around 1e-8. Measured by `restingItemVelocityProbe` (40 grounded ticks, zero velocity on none): `(2.7e-8, -0.08, -1.1e-8), (2.7e-8, -0.12, ...), (1.6e-8, 0.0, ...), (1.6e-8, -0.04, ...)`. A literal rule would never settle an item and every recording with a drop would run to the limit. **New rule (ruling): settled = on the ground and the exact position unchanged for 8 consecutive ticks** of the item's own (two full 4-tick cycles), velocity ignored (`CauseTracker.ITEM_SETTLE_TICKS`). A change below 1e-6 on every axis counts as unchanged (`ITEM_STILL_EPSILON`), for the x/z drift. **Measured: the drift never moves the position**: `droppedItemSettlesBeforeLimit` counts 0 ticks that needed the tolerance (`Recording.itemDriftTicks`). Reason in the source: on its moving tick a resting item pushes y into the floor; the collision removes that, the remaining movement (x/z about 1e-8) is under `1.0E-7` squared, and `Entity.move` then does not change the position at all (`Entity.java:749`, `movementLength > 1.0E-7 || delta.lengthSqr() - movementLength < 1.0E-7`). The tolerance is kept as a safety net. Test `droppedItemSettlesBeforeLimit`: `/path machine` on a lever and a dropper with one stick; the recording ends after 43 ticks (last exact position change at t+35), limit 2000.

**Break-it checks** (each made `runGameTest` exit 1 in its own test for its own reason; other failures in the same run were knock-on effects of a recording left running):
1. `canHoldOpen` always true: "a tagged mob held the recording open: 1999 ticks, ... 1 entities".
2. Mob-AI marking off: "no 'inexact: mob AI' draw from the sheep's AI".
3. `SaveGuard` not setting `noSave`: "saving not off while recording".
4. Save guard not ended on cancel: "saving not back after a stop: {...=true, ...}" (first, `noSave` version).

Second round (autosave hold, unload restore, settle rule, dispenser slots, mob-owned draws), same rule:
5. Settle threshold 100000: "the stick did not settle before the limit: 1999 ticks" (and the sheep and 1d machine tests hit the limit).
6. Dispenser slot unseeded: "unseeded draw left: [Level.random.nextInt at DispenserBlockEntity.getRandomSlot(DispenserBlockEntity.java:40) ...]".
7. Autosave not held: "autosave not held while recording".
8. `chunksToLoad` empty: "chunk not loaded for the restore: ... entities not restored (chunk not loaded) ...".
9. Reloaded spawned entity not removed by UUID: "spawned TNT saved into the chunk not removed: 1".
10. Mob-owned draws not inexact: "sheared wool spread not marked 'inexact: mob'".

---

## Scoped restore (ruling: restore only what the chain did)  [implemented]
Code: `UndoLog.Scope`, `UndoLog.restore(Scope)` / `chunksToLoad(Scope)`, `CauseTracker.restoreScope()`, `MachineRecorder`, `Recording`. Test: `RecordingRulesTest.scopedRestoreKeepsNonChainChanges`.

The undo log still logs every change in the level (useful data). `/path machine` now restores only the **scope**:
- positions chain work wrote (block state changes and chain `setChanged`, `CauseTracker.writes()`), plus the positions of every block entity that joined the chain (a hopper's own moves mark it changed through the static `setChanged`, which no hook sees);
- chain entities: every entity ever tagged (by UUID, so a reloaded copy is found, and ones that left the dimension count): spawned ones are removed, touched ones restored;
- ticks: in every loaded chunk, ticks the chain queued (identity; merged ones are not the chain's own) and ticks at a scope position are dropped; all others are kept as the same objects; the begin ticks at scope positions come back with their remaining delay. Block events: the chain's own are removed, begin events at scope positions come back.

Outside the scope nothing is touched: a block placed by a player, a crop that grew, an unrelated clock (its blocks and ticks) stay as they are now. A position written by **both** chain and non-chain work is restored and counted as **shared**. **Shared positions go back to the state from just before the chain's first write there, not to the state at begin** (ruling after the scoped restore). `UndoLog.beforeChain`: at each chain access of a position (block write hook, block entity handed out, about to tick, container about to change) where non-chain work has written and the chain has not, the current state and block entity are captured; a later non-chain write drops that capture; the chain's first write (block state change or chain `setChanged`) freezes it. A chain `setChanged` with no access hook before it (after a non-chain change) is logged as a warning: its capture is already the changed state. If the chain wrote first and non-chain work after, nothing is captured and the begin state is used (which is the state before the chain's first write). Ticks and block events at a shared position still come back as at begin (not tracked per write; known limit). The undo log tells chain from non-chain writes at the write itself (`onBlockChange` / `onBlockEntityChanged`, CauseTracker context on or off, only real changes). Summary line: "Restore: chain changes only; shared positions restored: N; non-chain changes left in place: N positions, N entities". `chunksToLoad(scope)` only lists chunks holding something in the scope, so unrelated chunks are no longer loaded for the restore.
Direct users of the undo log (the 1a/R4 tests) still call `restore()` (no scope = everything, as before).

Test: the 1b machine with a lever and the observer clock beside it. At t+20 the test (outside the chain) places a gold block away from the blast and turns the floor block under the TNT into gold, which the blast destroys. After the recording: the machine area (clock, far gold block and the shared floor aside) is as before; the far gold block is still there; the floor block is **gold** again (its state before the blast, the chain's first write there; first version restored stone); shared 1, left in place 5 positions (clock blocks and the gold block).
Break-it checks, each made `runGameTest` exit 1 in that test for its own reason: recorder restoring without scope ("non-chain block placed during the recording was reverted"); shared positions skipped ("machine not fully restored: ... stone -> air"); non-chain write tracking off ("shared position not counted"). Restoring the begin snapshot at shared positions: "shared position not restored to its state before the chain's first write (gold): Block{minecraft:stone}".

---

## Build step 2: ghost level and copy  [implemented]
Code: `ghost/GhostLevel`, `ghost/GhostLevelData`, `ghost/GhostCopy`; mixins `GhostChunkCacheMixin`, `GhostWeatherMixin` and the `Ghost*Accessor`s; ticket types `ghostpath:ghost_loading` / `ghostpath:ghost_simulation` (`GhostPath`). Tests: `GhostTest` (2 tests).

**The level** (`GhostLevel extends ServerLevel`, option b; no Fantasy needed):
- Not put into `MinecraftServer.levels`, so the server loop never ticks or saves it (`MinecraftServer.java:1108-1121` ticks only `getAllLevels()`; its `pollTask` only polls those levels' chunk sources, `:897-904`). Only `tickGhost()` ticks it. The test checks the key is not in `server.levelKeys()`.
- Own game time: `GhostLevelData extends DerivedLevelData` (setters are no-ops there, `DerivedLevelData.java:12-40`) with its own counter for `getGameTime`/`setGameTime`; set to the real overworld's game time at the copy. `tickTime()` is overridden completely: counter + 1, no `server.getScheduledEvents()` (vanilla `ServerLevel.java:475-482`).
- `tickGhost()`: `resetEmptyTime()` first (R2; `:411-420`), then vanilla `tick(() -> true)`, then the light flush (below).
- Off in the ghost: `ServerChunkCache.tickChunks(ProfilerFiller, long)` (`ServerChunkCache.java:369-409`: natural spawning, random ticks and precipitation via `tickChunk`, thunder, custom spawners, and the spawning-chunk shuffle's `Level.random` draw), cancelled for a `GhostLevel`.
- Weather: vanilla `getWeatherData()` returns the **server's** object (`ServerLevel.java:1691-1692`), so a ghost tick would have advanced the real weather. The ghost overrides it with its own `WeatherData`, copied at the copy, plus the four `Level` rain/thunder fields. `advanceWeatherCycle` sends START/STOP_RAINING to **every** player (`broadcastAll(Packet)`, `:784-790`): suppressed for a ghost (`GhostWeatherMixin`). The dimension-scoped packets go to nobody (no players in the ghost).
- Storage: a throwaway `LevelStorageSource` in a temp directory (`Files.createTempDirectory`), so chunk, entity, POI and data files never touch the world save; deleted on `discard()`. `save()` is a no-op and `noSave = true` (which also stops chunk unloading in the ghost, `ChunkMap.java:448`).
- Generator: `FlatLevelSource` with no layers and no structures (void, plains biome); every chunk that matters is copied over it.
- Autosave of the real world is held from `GhostLevel.create` to `discard()` with the recording's `SaveGuard` (non-forced saves skipped, one save done after). So a ghost cannot exist during a recording (and vice versa): `SaveGuard.begin` throws. A failed copy discards the ghost (found by a break-it check: a copy that timed out left the hold on and the next recordings failed).
- Biome zoom seed as the server computes it (`BiomeManager.obfuscateSeed(seed)`, `MinecraftServer.java:432`); same dimension type holder as the real level.
- Waiting on the ghost's own async work (chunk generation, entity-section loads, light) is done synchronously on the server thread: `server.managedBlock` while polling the ghost's chunk source and `entityManager.tick()`; entity deserialization runs on the server executor (`EntityStorage.java:41`). Limit 20 s real time, then it fails loudly.

**The copy** (`GhostCopy.copy(real, ghost, minChunk, maxChunk)`): a rectangle of whole chunks (all sections), at the same coordinates, into a fresh ghost only (a second copy into the same ghost throws). Every real chunk in the rectangle must be FULL with entities loaded, else it throws. Called between ticks (commands and game-test callbacks run after all levels ticked, `MinecraftServer.java:1108-1134`), so the tick drain sets are empty.
1. **Ticket levels copied, not forced** (R2, R9): for each chunk the real loading and simulation levels (`DistanceManager.getChunkLevel(key, false/true)`, `:143`) become a ghost loading ticket and a ghost simulation ticket at the same level (`TicketStorage` keeps the two by flag, `:158-203`). Levels spread by +1 per chunk (Chebyshev), so the real levels are 1-Lipschitz and a ticket at each copied chunk at its own level gives exactly the real level inside the rectangle. The copy then waits until every ghost chunk has the same loading level, simulation level, block ticking (`isPositionTicking`), entity ticking (`areEntitiesActuallyLoadedAndTicking`) and loaded entities as the real one. Break-it: without the simulation ticket both ghost tests fail with "timed out waiting for ghost chunks to reach the real chunks' levels and status".
2. Game time = real game time.
3. Blocks: each `LevelChunkSection.copy()` (block states and biomes, no updates, no `onPlace`), heightmap raw data, sky light sources rebuilt from the copied blocks (`initializeLightSources`, `ChunkAccess.java:478`), inhabited time.
4. Light: section status (`updateSectionStatus(false)` for non-empty sections, as `initializeLight` does, `ThreadedLevelLightEngine.java:138-151`), then each real block and sky `DataLayer` copied in with `queueSectionData`, then a flush. **Measured: every light value matched** after the copy (sky and block, 101376 positions per run, test floor -8 to +24, in 3 runs).
5. Block entities in the real ticker order (`Level.blockEntityTickers` then `pendingBlockEntityTickers`, `Level.java:111-113, 533-539`; then the non-ticking ones), by full NBT; plus state NBT does not hold: hopper `tickedGameTime` (`HopperBlockEntity.java:39`, used at `:334`) and moving-piston `progressO`, `lastTicked`, `deathTicks` (`PistonMovingBlockEntity.java:46-48`).
6. **Scheduled ticks via `copyAreaFrom`** over the rectangle (full height); count asserted against the real count in the rectangle (R3; "ticks dropped: N of M" otherwise). Then the ghost's `Level.subTickCount` (`Level.java:135`) is moved past the copied ticks, because `copyAreaFrom` numbers them above the source's max and not from the ghost's counter (finding 1, trap 1): a tick the ghost schedules later must sort after them, as a new real tick does.
7. Block events in queue order (`ServerLevel.blockEvents`, a linked set).
8. **Entities** in the real entity tick-list order (`ServerLevel.java:425`; roots only, passengers tick through their vehicle, `:434-440`), then other entities in the rectangle that are not in the list; each root saved with its passengers and loaded with `loadEntityRecursive(LOAD)`, added with `addFreshEntity` (so the tick-list order is the copy order; the test checks it). Players are not copied (counted). **Same id, exactly** (not only mod 4), see below. Positions and velocities are NBT doubles (exact); the bounding box is copied as is. Transient state outside NBT: `xo/yo/zo`, `xOld/yOld/zOld`, `yRotO/xRotO`, the collision flags, `hurtMarked`, `moveDist/flyDist`, `fallDistance`, `noPhysics`, `tickCount`, `invulnerableTime`, powder snow flags, `mainSupportingBlockPos`, `boardingCooldown`, `stuckSpeedMultiplier`, `nextStep`, `wasTouchingWater/wasEyeInWater`, `firstTick`, `pistonDeltas` + `pistonDeltasGameTime`, `onGroundNoBlocks`, `lastKnownSpeed/Position` (`Entity.java:221-305`); `LivingEntity.lastHurt`; `Projectile.leftOwnerChecked` (`Projectile.java:47`, not saved); and the **entity's own random state** (`LegacyRandomSource` seed and cached gaussian, `LegacyRandomSource.java:14-15`, `MarsagliaPolarGaussian.java:8-9`). Not copied: `EntityFluidInteraction` caches (recomputed in the entity's tick), mob brains/goals beyond NBT (mob AI is inexact anyway), minecart lerp state.
9. Weather.

**Where an entity's id changes behaviour (26.2 sources, searched for id arithmetic and comparisons):** `Entity.hashCode()` **is the id** and `equals` compares ids (`Entity.java:429-436`), so the iteration order of every hash set or map of entities depends on the exact id, not on a residue; `ItemEntity.java:141` (`(tickCount + id) % 4`, resting item moves); `Mob.java:718` (`tickCount + id`, odd/even goal ticks); `AbstractBoat.java:620` (`id % 2`, passenger facing); `ExperienceOrb.java:225` (`(id - other) % 40`, merging); `Warden.java:289` (`% 120`); `ElderGuardian.java:67` (`% 1200`); `Phantom.java:106` (`id * 3`, flap offset); `EnderDragon.java:847` (part ids follow the dragon's). Because of the hash code, the copy keeps the **same id**, which covers every residue. Ids are per level (`ServerLevel.getNextEntityId`, `:312-320`, from one global counter, skipping ids the ghost's chunk map knows), so the same id in the ghost does not clash; entities spawned later in the ghost get fresh counter ids (chain-built ones keep the residue rule from 1d; matching real and ghost for those is step 3). Break-it: without `setId` the entity test fails with "missing in the ghost or id differs".

**Light: does the ghost need a flush each tick? Yes.** Light updates are tasks queued by block changes (`LevelChunk.java:300` -> `ThreadedLevelLightEngine.checkBlock`) and run on the light thread only when `tryScheduleUpdate` is called, which happens when that level's chunk executor is polled (`ServerChunkCache.java:633`) or a chunk unloads (`ChunkMap.java:527`). The server polls only its own levels (`MinecraftServer.java:897-904`), mostly while it waits between ticks. So in the real world, light changed in tick N is normally visible in tick N+1 if the server had idle time, but it is asynchronous and can lag under load (and in game tests, which tick at full speed). The ghost is never polled by the server and its ticks run back to back, so without a flush its light never catches up. Measured in the repeater test (block light above the lamp, compared each tick): **without a flush the ghost differed on 12 of 60 ticks (every tick the lamp was on); with the flush after each ghost tick it differed on 1 and 0 of 60 ticks in two runs** (the real light thread lagging one tick, not the ghost). Decision: `tickGhost()` flushes by default (`waitForPendingTasks` for each copied chunk, pumped until done); `setFlushLight(false)` turns it off. A flushed ghost is the "server with idle time" case; real light remains not exactly reproducible (open item from 1d).

**Tests** (`GhostTest`):
- `repeaterChainCopiedMidPulseFiresOnSameTick` (the key test): ten repeaters (delays 1,3,2,4,1,2,3,1,4,2) and a lamp; a redstone block placed for 2 ticks; 7 ticks later (2 pending block ticks, 1 repeater powered) the 4x3-chunk rectangle (forced chunks plus one ring at level 32) is copied. Then the ghost ticks once after each real tick for 60 ticks: game time, every block state on the chain line and the pending tick count in the rectangle equal on every tick; the lamp lit at +37 and went out at +49 in both (3 runs). Also asserted: not registered with the server, autosave held while the ghost exists and released after `discard()`. Break-its: ghost time set 1 behind -> "ghost game time 8544 != real 8545"; the same with the direct time checks off -> "block differs at t=4943 ...: real repeater powered=true, ghost powered=false".
- `entitiesAndWeatherCopiedExactly`: a primed TNT in flight (fuse 200), an arrow in flight and a dropped stick, copied 5 ticks after they were spawned, in rain set for the test (weather is server-wide; the test puts it back exactly, an earlier version leaked rain into `MachineCommandTest` and broke its comparison). Same UUIDs and ids, same tick order, ghost weather is its own object with the same timers and rain level; 40 ticks side by side: positions and velocities bit-identical every tick, both rain timers down by 40, and afterwards the next draw from each entity's own random is equal in both.

**Known gaps (not copied or not the same):**
- Day time / world clocks are server-wide (`ServerLevel.clockManager()` -> `server.clockManager()`, `:338`; ticked by the server, `MinecraftServer.java:1097`). The ghost reads the real clock, which does not move while the ghost runs many ticks inside one server tick. Matters for daylight detectors and sky darkness over long runs; step 3 will show it.
- World border: the ghost has its own default border (not copied).
- POI (villagers, bees, lightning rods, portals) are not copied.
- Neighbours outside the rectangle are void in the ghost (the rectangle must have a margin); light at the rectangle's edge can change differently once it is recomputed there.
- Ticks and block events at **shared** positions after the scoped restore still come back as at begin (see the scoped-restore section); unrelated to the ghost.
- Code that looks a level up by key (`server.getLevel(entity.level().dimension())`, e.g. dimension changes) does not find the ghost.
- Game rules are the server's (shared, read only in practice).
- Seen once, not reproduced: `RecordingRulesTest.shearedSheepWithAiEndsBeforeLimit` failed "sheep not tagged by the dispenser: 0" in a break-it run, before any ghost test ran; it passed in the 4 other full runs of this step.

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
