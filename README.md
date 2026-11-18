# UnstableSMP: NULL Portal Breach

A Paper plugin that tears NULL portals open around a point and pours **VexBot** bots out of them.

Each open portal runs this command at its mouth, over and over:

```
vexbot nullportalspawn <x> <y> <z> <number>
```

The pulses get faster and bigger as the breach goes on, so it looks like bots are flooding out of the portals.

**Download:** the newest jar is always on the **[latest release](https://github.com/phantomsaltsosigmafr-ops/testing/releases/latest)**.

Requirements:
- Paper (or Purpur) **1.21.x**. It's tested on 1.21.11 and 1.21.4, with Java 21.
- The VexBot plugin, which provides `/vexbot nullportalspawn`.

This plugin only ever *runs* `/vexbot`. It never registers it.

---

## What a breach looks like

1. **Warning** (6 s):
   - The sky turns to a thunderstorm and an Elder Guardian curse sounds.
   - A Warden heartbeat pounds and the ground cracks where each portal will appear.
   - A *REALITY IS TEARING* title shows with a boss-bar countdown.
2. **Tear**: the portals rip open one after another:
   - a lightning strike (visual only) and a shockwave
   - Darkness on nearby players
   - the End portal activation sound
3. **Rise**: an obsidian and crying-obsidian frame builds up block by block.
4. **Void**: the opening fills row by row with end gateway blocks, a black starfield that glows. A beam shoots into the sky from the top.
5. **Pour**: every pulse runs the VexBot command at a random column of the opening:
   - Bots appear *inside* the void and get shoved out of it.
   - Each pulse bursts ink, reverse-portal and smoke particles with a low teleport sound.
   - Pulses speed up from every 3 s to about every 1.2 s, and grow from 1–2 to 2–3 bots.
   - The ground in front creeps black with concrete, blackstone and sculk.
6. **Collapse**: when time's up:
   - The void drains from the top down and the frame crumbles.
   - The corrupted ground heals and a *SEALED* title shows.
   - Every block is put back exactly as it was, and the weather is restored.

## Commands

All commands use the `uss.admin` permission (default: op). They work from players, the console and command blocks; coordinates can use `~`.

| Command | What it does |
|---|---|
| `/uss start` | Breach around you: 4 portals in a ring 14–26 blocks out, facing you |
| `/uss start at <x> <y> <z> [world]` | Breach around a point (console / command blocks) |
| `/uss start <player>` | Breach around a player |
| `/uss start spots` | Breach using your saved portal spots in this world |
| `/uss portal` | One portal 7 blocks in front of you, pouring toward you |
| `/uss portal <x> <y> <z> [world] [facing=north]` | One portal at a spot. It faces the nearest player unless you give `facing=` |
| `/uss stop` | Collapse every portal (animated) |
| `/uss stop now` | Close everything instantly, every block back |
| `/uss spot add <name>` | Save a portal spot: stand where the portal goes, look where the bots should pour |
| `/uss spot remove <name>` / `list` / `clear` | Manage saved spots (`spots.yml`) |
| `/uss status` | Running breaches: phase, open portals, bots poured and alive |
| `/uss reload` | Reload `config.yml` and `spots.yml` |

You can add options to `/uss start`, e.g. `/uss start portals=6 radius=20-35 duration=120 count=2-3 interval=40-15`:

| Option | Meaning |
|---|---|
| `portals=` | how many portals |
| `radius=` | ring distance, e.g. `14-26` |
| `duration=` | seconds the portals stay open |
| `warning=` | seconds of warning (0 = none) |
| `count=` | bots per pulse at the start |
| `count-end=` | bots per pulse at the end |
| `interval=` | ticks between pulses, start-end, e.g. `60-24` |
| `alive=` | pause pouring while this many poured bots are alive |
| `max=` | most bots in one breach |

**For scripted recordings:** save the spots in your map, then run `/uss start spots warning=0` from a command block.

## Config (`plugins/UnstableSMP/config.yml`)

| Section | What it controls |
|---|---|
| `spawn.commands` | The command(s) run each pulse. Default `vexbot nullportalspawn {x} {y} {z} {count}`. Placeholders: `{x} {y} {z}` (block), `{xd} {yd} {zd}` (decimals), `{count} {world} {portal} {breach} {facing} {yaw}` |
| `spawn.*` | `run-as` (console or the player who started it), `depth` (0 = spawn inside the void), `spread`, `count-start`/`count-end`, `interval-ticks: [start, end]`, `max-alive`, `max-total`, and the push out of the portal |
| `breach` | Portals, radius, spacing, duration, warning, stagger, storm, darkness, broadcast radius, boss bar, and `start-commands`/`end-commands` (e.g. clear VexBot's bots after a recording) |
| `portal` | Opening size (3×5), frame blocks, corners, surface (`END_GATEWAY`), opening beam, lightning, build speed, ground corruption (radius, max, block weights), `never-replace` |
| `effects` | Every particle and sound, one line each: `"PARTICLE count across up out speed [colour/block]"` and `"sound.key volume pitch"` |
| `messages` | All text in MiniMessage: titles, boss bar, chat |

**Safety:**
- Portals never replace bedrock, command blocks, chests or any block entity, note blocks, or another portal.
- Portal blocks can't be broken, blown up, burned or pushed while open.
- The void never teleports anyone, even in the End.
- Every changed block is written to `restore.log` the moment it changes. If the server crashes or is killed mid-breach, the next start puts everything back. This was tested by killing the server while a frame was rising: all 20 blocks were restored.

## Build

```bash
./gradlew build      # compiles, runs the tests, writes build/libs/UnstableSMP-2.0.0.jar
./gradlew runServer  # local Paper 1.21.11 test server
```

GitHub Actions (`.github/workflows/release.yml`) builds every push. Pushes to the default branch publish the jar as release `v<version>`.

## If it doesn't load

If `/plugins` doesn't list UnstableSMP:
1. Make sure the jar in `plugins/` is about **90 KB** and ends in `.jar`. A tiny file means the download saved a web page instead. Upload the jar with your host's file manager rather than "download from URL".
2. Remove any older `UnstableSMP-1.0.0.jar`, because two jars with the same plugin name clash.
3. Fully restart the server. Paper then prints `[UnstableSMP] Enabling UnstableSMP v2.0.0`, or a red error line explaining why it couldn't.

## Tested

On headless Paper 1.21.11 with a stand-in `/vexbot` plugin that logs every call:
- **Pouring:** all 4 portals ran `vexbot nullportalspawn` at their mouths, and every spawn landed inside the void (`END_GATEWAY`).
- **Ramp:** pulses ramped from 1–2 to 2–3 bots and sped up.
- **Alive cap:** held at 60 and pouring resumed as bots were killed.
- **Cleanup:** after the collapse a block scan found **0** portal, frame or corruption blocks left.
- **Performance:** the server averaged **2.3–2.8 ms per tick** during the breach.
- **Crash recovery:** 393 blocks were restored after a hard kill mid-breach, and 20 after a kill mid-frame.
- **Other commands:** single portals, `stop now`, start/end commands and the missing-VexBot warning all behaved.
- **Paper 1.21.4:** the same jar loads and pours with no errors.
