# Ghost Path

Ghost Path shows where TNT and projectiles will go before you pull the lever. It runs your machine once in a hidden copy of the world, then draws every TNT, arrow, pearl, item and falling block as a line in the world, with the reason each one ends (`exploded`, `hit block`, ...). When you change the machine, the lines update by themselves. Your real world is not changed.

## What you need

- Minecraft Java **26.2**, Fabric Loader **0.20.1** or newer, Java 25.
- **Fabric API 0.161.0+26.2** (or a newer 26.2 build).
- **Singleplayer** (and a LAN world you host). It is not meant for servers or multiplayer.

Put `ghostpath-0.1.0.jar` and the Fabric API jar in your `mods` folder. Cheats must be on in the world (the commands need operator rights; the owner of a singleplayer world always has them).

## How to use it

1. Build your machine. Stand where you can see it.
2. Look at the lever or button that starts it, and run `/path machine <x> <y> <z>` with that block's position.
3. The mod presses the trigger, follows everything the machine sets in motion until it is quiet, and puts the world back as it was. The chat shows a summary. Lines and dots now hang in the world.

What the summary means:

- **Recorded ... N ticks**: how long the machine ran. `STOPPED AT THE TICK LIMIT` means it was still busy (see `/path limit`).
- **Footprint**: how many blocks the machine wrote and read. A change in any of them reruns the preview.
- **TNT spawned**: how many TNT the machine lit.
- **Unseeded draws**: places where the game uses chance that the mod could not fix. Warnings here mean the result may not repeat exactly.
- **Restore**: what was put back. Things that other parts of the world changed meanwhile are left alone and counted.
- **Ghost check: exact** means a second run in the hidden copy gave exactly the same result as the real run. If it says something else, the first difference is named (tick, what, both values).

### Live updates

After `/path machine` the machine is watched. When you place or break a block that the machine reads or writes, wait about half a second (10 quiet ticks) and the mod reruns it in a fresh copy. The chat says `rerun: N ticks, K TNT spawned, ...` and what changed since last time, and the lines move to the new prediction. If the real lever is already on, the chat says so; the prediction is still for the press from off to on.

- `/path watch list` shows what is watched. `/path watch stop` (or `stop <x y z>`) stops it.

### Looking at a path

- Point at a dot: its exact bounding box and a panel appear (position, velocity, fuse, tick, how it ends). Positions are written so that nothing is rounded.
- Paths behind blocks are hidden. Hold the **inspect key (V)** to point through blocks.
- **Pin**: hold V and left click a dot. A pin sticks to its tick number across reruns. Click it again to unpin. `/path pins clear` removes all pins.
- Many identical TNT show as one line with `×N`. Hold V and scroll to step through them.
- Point at a real TNT or arrow and you see the server's exact values; stacks show a count.
- `/path inspect hold` (default) or `/path inspect toggle` chooses whether V works while held or switches on and off each press.
- Arrows that were still flying when the recording stopped are continued on your computer and drawn lighter, labelled `continued`. You can point at those too. They are an estimate made by your client, not the server's run.

### Commands

| Command | What it does |
|---|---|
| `/path machine <x y z>` | Record, check and start watching a machine. |
| `/path show on\|off` | Show or hide the lines. Key **G** does the same. |
| `/path beyond-loaded on\|off` | On (default): a rerun that reaches past the loaded area takes the missing chunks from your save (see below). Off: loaded chunks only. |
| `/path interval <ticks> [offset <n>]` | A dot every N ticks (default 5). The first and last dot are always there. |
| `/path kick on\|off` | The small random push TNT gets when lit. Off: drawn without the sideways part, so the path does not wobble. |
| `/path limit <ticks>` | The longest a recording may run (default 2000). |
| `/path watch list\|stop` | See or stop watched machines. |
| `/path inspect hold\|toggle` | How the inspect key works. |
| `/path pins clear` | Remove all pins. |

Keys (Options, Controls, "Ghost Path"): **G** show/hide, **V** inspect.

## End reasons

| Reason | Meaning |
|---|---|
| `exploded` | TNT went off. |
| `hit block` / `hit entity` | A projectile hit something. |
| `stuck` | An arrow stayed in a block for 8 ticks. |
| `landed` | A falling block became a block again. |
| `settled` | An item or vehicle stopped moving for 8 ticks. |
| `limit` | Still going when the recording stopped. |
| `left dimension` | It went through a portal-like transfer into another dimension. |
| `portal` | It reached a portal. Portals are not simulated. |
| `unloaded` | It was removed with its chunk. |
| `removed` | It was removed for another reason. |
| `out of world`, `failed` | Only for the continued arrows: fell out of the world, or the estimate could not be made. |

## Known gaps

- **Portals** are not simulated; a path that reaches one ends there. Entities that change dimension are not followed.
- **Players** are not in the hidden copy: a player standing in the machine's way, riding something, or pushed by TNT is not predicted.
- **Mobs**: a machine that depends on mobs (their AI, sheep shearing, villagers, bees) cannot be predicted exactly. The summary says `inexact`.
- **Sculk sensors and other listeners** far from the machine, and anything the machine only reaches through vibrations to mobs, are not followed. Points of interest (villager jobs, bees, lightning rods) are not copied.
- **Chance outside the machine**: random block ticks (crops, fire, ice), weather changes, and mob spawning do not run in the hidden copy.
- **Light** and things like redstone torch burn-out history, trial spawners, vaults and bells (which keep absolute times) are not carried over exactly.
- **The world border** is not copied.
- **Long runs**: a machine that does not go quiet (a clock) runs until the tick limit and is not watched. Raise or lower `/path limit`.
- While a recording or a rerun is working, the world's autosave waits.
- Only the last finished run is drawn when several machines are watched.
- **Far shots**: the machine's own chunks must be loaded. If a rerun finds that TNT or a projectile flies beyond the copied area, the mod widens the area (up to 4 times, 3 chunks each) and takes chunks that are not loaded from your **save** (without loading them in the world; `/path beyond-loaded off` turns this off). Chunks that are loaded but do not tick entities in the real world (the two rings around loaded chunks) are treated like them. In the ghost all of those tick, so the line keeps going, but **from the first point in a chunk where the real world would freeze the thing, the line is dashed and faded**, an amber dot and the label `real: freezes here (unloaded)` or `(lazy)` mark that point, and the end label gives both: `real: frozen at t+36; ghost: hit block at t+52`. The chat line lists them too. The save gives blocks, block entities (chests...), scheduled ticks (a repeater's pending tick keeps its remaining delay) and entities (TNT, items, mobs in that chunk as last saved; they get new entity ids and fresh random numbers, so behaviour that depends on those may differ: that part of a path is labeled as beyond loaded anyway). A chunk that is half-unloaded (the server still holds it with unsaved changes) is taken from memory, newer than the save. Not from the save: block events, POI, nothing newer than the last write.

If something looks wrong, check `logs/latest.log` and tell us which machine and which command.
