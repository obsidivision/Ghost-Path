# Ghost Path: tutorial

Ghost Path shows where your TNT and projectiles will go before they do. It runs your machine once in a hidden copy of the world, draws the result as lines in the world, and runs it again when you change the machine. Your real machine is never fired.

## 1. Install

1. Minecraft Java **26.2** with **Fabric Loader 0.20.1**.
2. Put `ghostpath-0.19.5.jar` and **Fabric API 0.161.0+26.2** in the `mods` folder.
3. Start a **singleplayer** world with cheats on (the commands need operator rights; the owner of a singleplayer world has them). It is not meant for servers.

## 2. Your first preview

1. Build a machine with a trigger: a lever, button, note block, door, trapdoor, fence gate, repeater or comparator (iron doors and trapdoors do not open by hand, so they are refused).
2. Look at the trigger and note its coordinates (F3).
3. Run `/path machine <x> <y> <z>`.

The mod presses the trigger as a right click would, follows everything it sets in motion until things go quiet, and puts the world back. The chat prints a summary:

| Line | Meaning |
|---|---|
| `Recorded ... N ticks` | How long the machine ran. `STOPPED AT THE TICK LIMIT` means it was still busy (see `/path limit`). |
| `Footprint` | Blocks the machine wrote and read. A change in or near them reruns the preview. |
| `TNT spawned` | How many TNT the machine lit. |
| `Unseeded draws` | Chance the mod could not fix. Warnings mean the result may not repeat exactly. |
| `Not simulated` | Things the hidden copy cannot imitate, such as `player-owned pearl: chunk loading not simulated`. |
| `Ghost check: exact` | A second run in the hidden copy gave exactly the same result as the real one. Otherwise the first difference is named. |

Coloured lines now hang in the world: **red** TNT, **cyan** projectiles, **yellow** items, **orange** falling blocks, **green** vehicles. Dots mark ticks. The label at the end says why the path ended (`exploded t+89`). Identical paths merge into one line with `×N`.

## 3. Change the machine, watch the prediction change

After `/path machine` the machine is **watched**. Place or break a block that the machine reads or writes, next to a block it changed, or within 2 blocks of one. After about half a second of quiet the chat prints `rerun: ...` with what changed (for example `TNT #0 spawn t+10 -> t+3`) and the lines move to the new prediction.

- `/path watch margin <n>`: how many blocks around the machine count (0 to 8, default 2).
- `/path rerun` or `/path rerun <x y z>`: rerun now, without waiting for a change.
- `/path watch list`, `/path watch stop [x y z]`: see or stop watches.
- If you pull the real lever, the chat says `real lever is on; prediction is for off → on`: the prediction stays for the press you recorded.

## 4. Look at the details

- **Hover** a dot or line (no key): the entity's box and a panel (exact position, velocity, fuse, end reason). Pointing at a real primed TNT or arrow shows the server's entity.
- **Key V** (rebindable): hold it to hover through blocks. V + left click **pins** a point (it keeps its tick across reruns); click again to unpin. V + scroll steps through a stack of identical entities. `/path pins clear` removes all pins, `/path inspect toggle` makes V a switch instead of a hold.
- **Key G** or `/path show on|off`: show or hide all lines.
- `/path interval <ticks> [offset <n>]`: dot spacing (default 5).

## 5. The ghost area

`/path area on` draws what the machine touched as boxes: **orange** (filled) = blocks it wrote, **thin blue** = blocks it only read. The block change that started the last rerun is highlighted yellow for a few seconds (`rerun: piston placed`). At most 2000 boxes are drawn; the top left says so when there were more.

- `/path area watched on`: green boxes for the margin around the machine, the area whose changes rerun it.
- `/path area chunks on`: the chunks the last run copied; chunks a widened run added are magenta.
- A key for the overlay exists in the controls (Ghost Path) but is unbound by default.

## 6. Other settings

| Command | What it does |
|---|---|
| `/path limit <ticks>` | Longest recording (default 2000). A machine that never goes quiet (a clock) is not watched. |
| `/path kick on\|off` | The small random push TNT gets when lit. Off draws it without the sideways part. |
| `/path beyond-loaded on\|off` | On (default): a rerun that reaches past the loaded area takes the missing chunks from your save. Where the real world would freeze the entity, the line is dashed and faded with an amber dot. |

## 7. Limits to know

- Players are not in the hidden copy (a player in the way is not predicted).
- Mob-driven machines (AI, shearing, villagers, bees) cannot be predicted exactly; the summary says `inexact`.
- Random block ticks (crops, fire), weather changes and mob spawning do not run in the copy.
- Portals are not simulated: a path that reaches one ends there.
- Fence gates are pressed without the facing flip or the click sound.

More detail: `README.md` (player guide) and `docs/DESIGN.md` (how it works and what was verified).
