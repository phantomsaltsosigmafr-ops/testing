# UnstableSMP: NULL Portal Invasion

Dozens of floating obsidian nether portals tear open in the sky over an area, and **VexBot** bots pour out of them. Every few seconds, random portals run:

```
vexbot nullportalspawn <x> <y> <z> <number>
```

At the end the portals collapse and the sky is empty again. There's no boss bar, no titles and no chat: players only see and hear it happen.

There's also the **Totem of Null**. It saves you like a normal totem, but when it pops a wave rolls out from you and every NULL dies.

**VexBot commands:** [VEXBOT_COMMANDS.md](VEXBOT_COMMANDS.md) lists the VexBot commands that make the NULL break, build, grief and act like real players.

**Download:** the newest jar is always on the **[latest release](https://github.com/phantomsaltsosigmafr-ops/testing/releases/latest)**.

**Requirements:**
- Paper (or Purpur) **1.21.x** with Java 21.
- The VexBot plugin, which provides `/vexbot nullportalspawn`.

This plugin only ever *runs* `/vexbot`. It never registers it.

---

## The invasion

1. **Start:** a deep End-portal boom and cave ambience at the centre.
2. **Portals open:** 3 portals tear open every half second at random spots within 40 blocks, hanging 2–18 blocks above the ground. Each one opens with reverse-portal particles, smoke, the portal trigger sound and glass breaking. The sky fills with 60 portals in about 10 seconds.
3. **The NULL pour out:** every 2 seconds each portal has a 25% chance to pour.
   - Pouring runs the VexBot command inside the bottom of the portal, with an ink and portal burst and an Enderman-teleport sound.
   - Pouring pauses while 60 poured bots are alive and resumes as they die. At most 400 come out per invasion.
   - **The portals repair themselves.** VexBot's `nullportalspawn` builds and clears its own portal blocks at the spot. That used to eat chunks out of the frames; now any missing block is put back within a tenth of a second.
   - Set `portal.build: false` to only call VexBot at the spots, without these portals.
4. **Portals hum:** vanilla nether-portal particles and sounds, plus extra drifting portal particles.
5. **Collapse:** after 120 seconds an Elder Guardian curse sounds. The portals then collapse two at a time in puffs of smoke and ink until the sky is clear.

**Safety:**
- Portals are only ever built in empty air.
- They can't be broken, blown up, burned or pushed.
- They never send anyone to the Nether and never spawn zombified piglins.
- Every block they change is written to `restore.log` the moment it changes. If the server crashes or is killed mid-invasion, the next start puts the sky back.

## The Totem of Null

`/uss totem [player] [amount]` gives it. It looks exactly like a hand-made one: a totem of undying named **The Totem Of Null** in aqua (italic, like any renamed item) with **Curse of Vanishing**, and nothing else on it. Any totem with that name and that curse counts, including one made with `/give`. Vanilla anvils can't colour a name or curse a totem, so players can't forge one.

When it pops:
1. Your life is saved, with the normal totem animation.
2. A sonic boom and shockwave go off at you.
3. A wave rolls outward and every NULL bot the invasions poured out dies as it reaches them, in a puff of ink.
4. The portals then go quiet for 10 seconds before the NULL come back.

Settings in `totem:`:
- `quiet-seconds` controls that pause.
- `close-portals: true` makes the invasion collapse instead.
- `kill-radius` limits the wave (0 = everywhere).

The totem knows which bots came out of the portals. That's anything new (mobs, or VexBot's fake players joining) that shows up within 16 blocks of a portal in the 10 seconds after it poured. You can change this with `spawn.capture-radius` / `capture-seconds`. Real players who have played before are never counted. `/uss status` shows how many are alive. For other VexBot bots, add VexBot's own kill command to `totem.kill-commands`.

## Residents (player-like bots)

`/uss residents on` brings VexBot bots to life as server regulars. As VexBot fake players they show in tab, in the player list and on the server list.

- **Sessions:** a few log on and off through the day, with more during peak hours. Each stays 20–90 minutes, then rests 10–45 minutes before coming back.
- **Crews:** players come on solo, as duos, trios or 4-player squads. A crew shares a base, logs on together, does things together and logs off together. Crews stay the same between restarts.
- **Their own base:** each solo player or crew finds a natural plot (never on anyone's build) and builds a starter base block by block, holding the block, swinging and making the right sounds:
  - the plot is cleared and levelled
  - a log-and-plank house: windows, a gable roof, a door, a bed each, a double chest (with some starter items), a furnace, a crafting table, torches, and a sign ("Kxlvinn's base", "keep out", "no griefing pls"…)
  - a fenced farm with a gate, water and wheat, carrots, potatoes or beetroot
  - the wood type, crop and bed colour are random per base
  - bases stay in the world, and building picks up where it left off after a restart
- **Bed and spawn:** their spawn point is set in their bed, and at night (or in a thunderstorm) they go to bed and sleep.
- **Doors, gates, beds, chests:** VexBot's wander has them walk in and out of houses, open doors and fence gates, and visit beds, chests and crafting tables.
- **Activities** (weighted in `residents.activities`):
  - `build`: mostly this until the base is done
  - `home`: hanging around the base, and putting loot away in the chest (ores, logs, stone, crops, mob drops; never their gear) and sorting it
  - `mine`: goes after real ore near home, vein by vein
  - `woodcut`: chops down a real tree near home with an axe, and sometimes replants a sapling
  - `farm`: harvests ripe crops and replants
  - `hunt`: goes after cows, pigs, sheep, chickens and rabbits
  - `visit`, `explore`, `afk`, and `sleep` at night
- **Server list:** bots count in the player number and show in the hover list. Minecraft normally leaves fake players out of the hover list; the `server-list` section fixes that.

## Living server (4.5.0)

- **Bases grow:** once the house is done, every `grow-minutes` (default 30) the crew builds the next part:
  1. an animal pen with animals
  2. an enchanting hut with bookshelves
  3. a workshop (anvil, blast furnace, smithing table…)
  4. a lit nether portal

  Parts that don't fit the land are skipped.
- **Roads:** residents lay dirt paths from their door to nearby bases, with plank bridges over water.
- **Market at spawn:** residents put up stalls (barrel counter, wool awning) in a ring around spawn and stand at them. Right-click one to trade; the trades are in `residents.market.trades`.
- **Nether trips:** once a base has its portal, the crew walks in and comes back later with blaze rods, gold, quartz and the like. **End trips aren't included**, because the End needs a stronghold.
- **Death and recovery:** a resident that dies respawns at its bed, walks back to where it died and picks its stuff up.
- **NULL vs residents:** when an invasion lands near a base, the residents there fight the NULL. `/uss start base <name|random>` drops an invasion right on a base.
- **Crew wars:** `/uss war <attacker> <defender>`:
  1. the attacking crew marches to the defenders' base
  2. they light TNT against the walls
  3. both crews fight
  4. when it's over, the defenders rebuild their house block by block

  `/uss war stop` ends wars. Set `wars.auto: true` for random wars.
- **Bandit camp:** the bandits get a hideout out in the wild: a shack with a campfire, beds and a chest of stolen loot that players can raid. The chest refills after raids, and bandits run back to the camp.
  - It only goes up while no real player is within 48 blocks.
  - `/uss bandits camp [here|new]` shows the camp, moves it to where you stand, or picks a new spot.

## Bandits

Small groups of broke players (1–4 at a time) who jump a real player:
1. They log on 30–55 blocks away a few seconds apart.
2. Each gets random scraps: bits of leather, chain or iron, a wooden or stone sword or axe, and a little food.
3. They go after the player.
4. When the player dies or leaves, or after a few minutes, they run off and log off one by one.

Automatic raids happen every 10–25 minutes while residents are on. They never target someone near spawn, and the same player isn't jumped again for 15 minutes. `/uss bandits raid <player> [size]` starts one right now, which is handy for filming.

## GG wave

`/uss gg` makes every bot online (residents, bandits and VexBot's own fake players) type a gg over a few seconds: GG, gg, gG, Gg, ggs, gg wp… Some send two. Put `uss gg` in `invasion.end-commands` to have it happen after every invasion.

## Commands

All commands use the `uss.admin` permission (default: op). They work from players, the console and command blocks, and coordinates can use `~`. Replies go only to whoever ran the command.

| Command | What it does |
|---|---|
| `/uss start` (or `/uss null`, `/uss invasion`) | The whole invasion in one command, centred on you |
| `/uss start at <x> <y> <z> [world]` | Invasion centred on a point (console / command blocks) |
| `/uss start <player>` | Invasion centred on a player |
| `/uss stop` | Portals collapse a few at a time |
| `/uss stop now` | Every portal vanishes instantly |
| `/uss totem [player] [amount]` | Give Totems of Null |
| `/uss status` | Running invasions: portals, bots poured, alive |
| `/uss residents on\|off\|status` | Player-like bots living on the server (see Residents) |
| `/uss residents sethome <name>` | Move a resident's home to where you stand |
| `/uss bandits raid <player> [size]` | Bandits jump that player now; `on`/`off` for automatic raids, `stop` calls raids off |
| `/uss war <attacker> <defender>` / `/uss war stop` | Crew war: one crew raids another's base |
| `/uss bandits camp [here\|new]` | Show, move or re-pick the bandit camp |
| `/uss start base <name\|random>` | Invasion on a resident's base (they defend it) |
| `/uss gg` | Every bot spams gg |
| `/uss reload` | Reload `config.yml` |

You can add options to `/uss start`, e.g. `/uss start radius=60 portals=100 duration=180 chance=0.4`:

| Option | Default | Meaning |
|---|---|---|
| `radius=` | 40 | how far from the centre portals open |
| `portals=` | 60 | most portals open at once |
| `wave=` | 3 | portals per wave |
| `duration=` | 120 | seconds |
| `chance=` | 0.25 | chance each portal pours each round |
| `interval=` | 40 | ticks per round |
| `count=` | 1 | bots per pour |
| `alive=` | 60 | alive cap |
| `max=` | 400 | most bots per invasion |

## Config (`plugins/UnstableSMP/config.yml`)

| Section | What it controls |
|---|---|
| `spawn` | The command(s) run per pour. Default `vexbot nullportalspawn {x} {y} {z} {count}`. Placeholders: `{x} {y} {z} {xd} {yd} {zd} {count} {world} {portal} {invasion} {facing} {yaw}`. Also run-as, count, interval, chance, alive cap, total cap, and how bots are recognised (`capture-radius`, `capture-seconds`) |
| `invasion` | Radius, max portals, wave size and speed, height above ground, spacing, collapse speed, duration, optional storm, keep chunks loaded, `start-commands`/`end-commands`, and a one-off `leader` arrival part-way through |
| `totem` | Name, lore, enchantments, `match-by-name`, optional glint override and `item-model` (resource pack, 1.21.4+), kill radius, wave speed, quiet time, close portals, extra kill commands |
| `portal` | `build` (our floating portals on/off), `self-repair`, opening size (2×3 = the classic 4×5 portal) and frame blocks (weighted, e.g. `["OBSIDIAN:4", "CRYING_OBSIDIAN:1"]`) |
| `effects` | Every particle and sound, one line each: `"PARTICLE count across up out speed [colour/block]"` and `"sound.key volume pitch"` |
| `messages` | Command replies (MiniMessage) |

## Build

```bash
./gradlew build      # compiles, runs the tests, writes build/libs/UnstableSMP-4.5.0.jar
./gradlew runServer  # local Paper 1.21.11 test server
```

GitHub Actions (`.github/workflows/release.yml`) builds every push. Pushes to the default branch publish the jar as release `v<version>`.

## If it doesn't load

If `/plugins` doesn't list UnstableSMP:
1. Make sure the jar in `plugins/` is about **70 KB** and ends in `.jar`. A tiny file means the download saved a web page instead; upload it with your host's file manager.
2. Remove any older UnstableSMP jar, because two jars with the same plugin name clash.
3. Fully restart the server. The console then shows `[UnstableSMP] Enabling UnstableSMP v4.5.0`, or a red error line explaining why it couldn't.

## Tested

On headless Paper 1.21.11 with a stand-in `/vexbot` plugin that logs every call:
- **Portals:** the sky filled with 18, then 36, then 60 portals within about 10 seconds.
- **Pouring:** bots poured from random portals and stayed in the overworld, with the alive cap holding at 60.
- **Performance:** the server averaged about **3 ms per tick** with 60 portals open.
- **Cleanup:** after the collapse a block scan found **0** nether portal or obsidian blocks left.
- **Totem of Null:** popped on a zombie holding it. The zombie survived, the totem was used up, and every NULL died (60 alive to 0). The portals went quiet for 10 seconds, then pouring resumed.
- **VexBot imitation:** a stand-in builds its own portal over each spot, clears it 1.5 s later, then spawns its bots 3 blocks off. All 60 portals stayed whole (360/360 portal blocks), those late bots were recognised, and the totem wiped them.
- **Other versions:** earlier builds of the same restore and spawn code were also tested on Paper 1.21.4 and survived a hard kill mid-event.
