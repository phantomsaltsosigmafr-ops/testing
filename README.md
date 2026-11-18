# UnstableSMP: Unstable SMP Simulator

A Paper **1.21.11** plugin (Java 21) with:

- **Vexbot swarms** made only of display entities: floating obsidian cubes with glowing purple face panels, plus dark-purple blade shards. They use boids steering, follow 10 animated formations and dive at targets.
- **14 ability items in 5 sets** (Vexbots, Null, Portal Swarm, Law, Cindercrest). Each has a right-click and a sneak-right-click variant, cooldowns shown in the action bar, and a combo table in config.
- **Faction armies of real mobs** with Paper pathfinding: formations, fighting skills, building and breaking, a Faction Horn, and a GUI to command them.
- **Battles** (e.g. Null vs LAW) and **four server events** (Portal Invasion, Null Outbreak, Law Crackdown, Cindercrest Firestorm). The events also support VexBot through `spawn-mode: COMMAND`.
- **Server polish:** combat tag (`COMBAT: 15`), styled kill messages, WorldGuard support, and every number in `config.yml` / every text in `messages.yml`.

- **Simulator toolkit (v2):**
  - **Player-looking PvP bots** (VexBot-style, 500-1000 at once) in 12 skill tiers from **HT1 to LT6**, fighting with sword, axe, mace, elytra mace, UHC, netherite pot or spear
  - **NULL** as an organised war machine led by **JamatoP**, with black skins and scrambled names
  - **LAW** led by King LettuceK with randomised skill
  - kits, teams, maps with note-block lobby links, command-block games, and cinematic and scripted-recording tools

All commands live under **`/uss`**. The plugin never registers `/vexbot` or `/vex`.

---

## File tree

```
build.gradle.kts            Paper API 1.21.11, run-paper, WorldGuard (compileOnly), JUnit
settings.gradle.kts
gradlew, gradle/wrapper/    Gradle 9.9.1 wrapper
src/main/resources/
  plugin.yml                permissions, softdepend WorldGuard
  config.yml                every number: swarm, abilities, combos, factions, templates, events, caps
  messages.yml              all text, MiniMessage
  scenes/*.yml              example scripted scenes (null_invasion, law_arrest)
src/main/java/dev/unstablesmp/
  UnstableSMP.java          wires the services; onLoad (WorldGuard flags) / onEnable / onDisable / reload
  core/       Ticker (the ONE repeating task), EntityTracker (caps, tags, chunk-unload cleanup, ghost sweep),
              DisplayFactory, Hitboxes (Interaction hits), ActionBarService, Messages, Transforms, Ground, Fx,
              Cooldowns, Keys, Text
  region/     RegionGuard, NoopRegionGuard, WorldGuardRegionGuard (custom flags uss-events / uss-unit-griefing)
  combat/     Caster, Targeting (friendly-fire rules), DamageService (DamageSource + combos + attribution),
              Marks/Mark, ComboTable, DamageAttribution, CombatTagManager, CombatListener, KillMessageListener
  swarm/      SwarmManager, Swarm, Vexbot, SwarmFormation (10 shapes), FormationParams, SlotAssigner,
              SwarmSteering (boids), SwarmRenderer, SwarmCombat, SwarmProjectiles, SwarmListener, SwarmSettings
  ability/    AbilityType, AbilityItems (PDC, item model, custom-model-data), AbilityService, AbilityListener, Aim
    sets/     VexbotAbilities, NullAbilities, PortalSwarmAbilities, LawAbilities, CinderAbilities (+ Phoenix)
    effects/  EffectManager, TimedEffect, NullFieldEffect, BrandEffect, GavelEffect, ChainsEffect,
              EmberTrailEffect, AshfallEffect, SwarmPortalEffect, RingPulseEffect
  faction/    Faction, UnitTemplate, Trait, Unit, ArmyFormation, FactionRegistry (+ data.yml members),
              UnitManager, UnitSpawner, FormationPlanner, UnitBrain, UnitMovement, Engineer, LeaderBars,
              UnitListener, UnitSettings, BlockRecord, HeadProfiles, BattleManager
  gui/        GuiService, Menu, Icons, MainMenu, SpawnMenu, BattleMenu, EventsMenu, AbilitiesMenu, FactionHorn
  event/      EventManager, ServerEvent, PortalInvasionEvent, Portal, FactionWaveEvent, EventType,
              PortalBlockRegistry, CommandSpawnCapture (VexBot COMMAND mode)
  command/    UssCommand (/uss + tab completion), SimCommands (bot/duel/kit/team/map/lobby/game/cam/scene/fake/fx/pack)
  bot/        Bot, BotManager (LOD tick, grid), BotSpawner, BotListener, BotGroup, BotLoadout, SkillTier (HT1..LT6),
              PvpMode, SpatialGrid
    ai/       BotBrain, BotMovement, BotPathfinder (budgeted A*), BotCombat, Squad (organised NULL), Tactic +
              AxeTactic, MaceTactic, ElytraMaceTactic, UhcTactic, NethPotTactic, SpearTactic
    skin/     SkinService (user/texture/pack/default skins), NameGenerator (scrambled, ____X____, gamer), PackServer
  kit/        Kit, KitService (kits.yml), DefaultKits (sword, axe, mace, elytra_mace, uhc, nethpot, spear, null, law, bandit)
  team/       TeamService (scoreboard teams for players + bots)
  map/        MapService (maps.yml: spawns, exits, regions, lobby, block links), MapLinkListener
  game/       GameService (WAVES, BATTLE, TDM, FFA, BOSS, DUEL)
  cine/       CameraService (spline camera paths), SceneService (scenes/*.yml), Cinematics (fade/shake/slowmo/titles), FakeChat
src/test/java/dev/unstablesmp/   formation math, slot assignment, army offsets, combos, config/messages checks
```

## Build and install

You need JDK 21. The Gradle wrapper is included.

```bash
./gradlew build          # compiles, runs the tests, writes build/libs/UnstableSMP-1.0.0.jar
./gradlew runServer      # starts a local Paper 1.21.11 test server in ./run with the plugin loaded
```

1. Copy `build/libs/UnstableSMP-1.0.0.jar` into your server's `plugins/` folder.
2. *(Optional)* Install WorldGuard 7 (with WorldEdit). UnstableSMP finds it automatically.
3. Start the server. `plugins/UnstableSMP/config.yml` and `messages.yml` are created. Faction membership is saved in `data.yml`.
4. Edit the config and run `/uss reload`. Live armies keep their orders.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/uss gui` | `uss.gui` | Opens the command center: factions, spawning, orders, formations, building, battles, events, ability items |
| `/uss give <ability\|set\|all> [player]` | `uss.give` | Gives ability items. Sets: `vexbots`, `null`, `portal`, `law`, `cindercrest` |
| `/uss formation <name> [player]` | `uss.formation` | Sets a swarm's formation (`halo`, `sky_crown`, `v_wedge`, `phalanx_wall`, `spiral_vortex`, `dome_cage`, `arrowhead_dive`, `pincer`, `rain`, `orbit_shield`) |
| `/uss event <portal\|null\|law\|cinder> [stop\|player]` | `uss.event` | Starts an event at your position (console: world spawn, or at a named player). `stop` ends that one. `/uss event stop` ends all |
| `/uss battle <factionA> <factionB> [size] [player]` | `uss.battle` | Spawns both armies in front of you; they fight until one side is wiped out. `/uss battle stop` ends all |
| `/uss reload` | `uss.reload` | Reloads `config.yml` and `messages.yml` |
| `/uss bot spawn <count> [group] [mode] [tier] [key=value...]` | `uss.bot` | Spawns player bots. Keys: `team=`, `at=here\|map:spawn\|@p\|x,y,z`, `target=@p`, `kit=`, `skin=`, `name=`, `leaders=false`, `spread=`, `announce=false`. The tier can be a range (`HT1-LT2`) |
| `/uss bot clear [group\|team\|map\|all]`, `list`, `target <group> <@p>`, `untarget`, `march <group> <where>`, `tp <group> <where>` | `uss.bot` | Manage bots |
| `/uss duel [player] [mode] [tier] [group]` | `uss.duel` | 1v1 a bot. You get that mode's kit |
| `/uss kit give <kit> [@targets]`, `save <name>`, `delete`, `list` | `uss.kit` | Kits for players. Bots use the same kits |
| `/uss team join <team> [@targets]`, `leave`, `gui`, `list` | `uss.team` | Teams (bots never hurt their own team) |
| `/uss map create\|delete\|setspawn\|addspawn\|setexit\|pos1\|pos2 <map> [key]`, `setlobby`, `tp <map> [spawn] [@targets] [kit=] [team=]`, `link <map> [spawn] [kit=] [team=]`, `unlink`, `list` | `uss.map` | Arenas ("maps") and lobby links (note blocks, buttons, plates) |
| `/uss lobby [@targets]` | `uss.lobby` | Back to the lobby |
| `/uss game start <waves\|battle\|tdm\|ffa\|boss\|duel> <map> [group=] [b=] [count=] [waves=] [inc=] [mode=] [tier=] [kit=] [team=] [players=@a]`, `stop [map\|all]`, `list` | `uss.game` | Command-block games with countdown, boss bar, win/lose, return to exit/lobby |
| `/uss cam add [ticks]`, `undo`, `clear`, `save <name>`, `play <name> [@targets]`, `stop`, `delete`, `list` | `uss.cine` | Smooth spline camera paths for recordings |
| `/uss scene play <name> [@targets]`, `stop`, `list`, `reload` | `uss.cine` | Run a scripted scene from `scenes/*.yml` |
| `/uss fake chat <name> <msg>`, `join <name>`, `leave <name>`, `death <victim> [killer]` | `uss.cine` | Vanilla-identical fake chat, join, leave and death lines (MiniMessage names) |
| `/uss fx fade <out\|in> [@targets] [ticks]`, `shake [@targets] [ticks] [strength]`, `slowmo <rate> [ticks]`, `reset`, `title <@targets> <title> [\| subtitle]` | `uss.cine` | Screen effects. Slow motion uses the server tick rate |
| `/uss pack send [@targets]`, `reload` | `uss.pack` | Send or rebuild the skin resource pack |

Every player argument takes selectors (`@p`, `@a[distance=..10]`, names, `all`), and positions come from where the command runs. That makes these work from **command blocks** and `/execute at`.
| `/uss status` | `uss.status` | Shows entity and unit counts, running events and battles, and per-subsystem tick time in ms |

Every argument tab-completes. Players only see subcommands they have permission for.

## Permissions

| Permission | Default | |
|---|---|---|
| `uss.admin` | op | All of the below |
| `uss.gui` | op | GUI and Faction Horns |
| `uss.give` | op | `/uss give` and the GUI ability page |
| `uss.formation` | everyone | `/uss formation` for your own swarm |
| `uss.event`, `uss.battle`, `uss.reload`, `uss.status` | op | |
| `uss.ability.*` | everyone | Use ability items: `uss.ability.vexbots`, `.null`, `.portal`, `.law`, `.cindercrest` |
| `uss.bot`, `uss.kit`, `uss.team`, `uss.map`, `uss.game`, `uss.cine`, `uss.pack` | op | Simulator tools (command blocks have op) |
| `uss.duel`, `uss.lobby` | everyone | `/uss duel`, `/uss lobby` |
| `uss.combat.bypass` | nobody | Never combat-tagged |

Players can only use abilities they have items for. Use `/uss give` or the GUI to hand them out.

## Ability items

Every item is identified by a PersistentDataContainer tag, never by its name. Each one can use a resource-pack `item-model` key and/or `custom-model-data` floats. Cooldowns and the READY state show in the action bar while you hold the item.

| Set | Item | Right-click | Sneak + right-click |
|---|---|---|---|
| Vexbots | **Summon Swarm** (Vexbot Core) | Summon or top up your swarm | Dismiss it |
| | **Cycle Formation** | Next formation (targets what you look at) | Previous formation |
| | **Dive Strike** | A squad of bots dives your target | **Arrowhead Dive**: the whole swarm divebombs |
| | **Recall** | Recall into Halo, forget the target | Toggle **Orbit Shield** |
| Null | **Null Step** | Blink forward (only lands somewhere safe) | Blink backward |
| | **Null Field** | Field where you look: Darkness, Slowness, no regeneration | Field around yourself |
| | **Null Brand** | Brand a target: bonus swarm damage, and your swarm locks on | Brand every enemy around you |
| Portal | **Portal Swarm** | Rotating display portal where you look, pours out Vexbot waves. Enemies can smash it | Portal behind you |
| Law | **Judgment** | Giant gavel slams from the sky (AoE, knockback) | **Verdict**: 3 smaller gavels |
| | **Sentence** | Chain a target in place | Chain everyone right around you |
| | **Order** | Silence enemy abilities, swarms and portals around you | Order where you look |
| Cindercrest | **Ember Crest** | Burning trail plus speed | Ember line racing forward |
| | **Ashfall** | Meteor rain | A few huge meteors |
| | **Phoenix Burst** | Passive: explodes back to life at low HP, once per life. Right-click shows status | Detonate manually under 50% HP |

**Swarm behaviour:**
- The swarm attacks whatever you hit and defends you from whatever hits you.
- Passive formations (Halo, Sky Crown, V-Wedge, Phalanx Wall) keep sending a few bots at the target.
- Vortex, Cage and Pincer deal contact damage.
- In Rain, the bots drop one after another.
- Arrowhead assembles, then the whole swarm dives at once.
- Dome Cage pushes the target back inside and blocks pearls and teleports out of it.
- Orbit Shield deletes enemy projectiles.
- Players can destroy bots by hitting them (Interaction hitboxes, with a raytrace fallback) or by shooting them. Your own hitboxes pass your swings through to the target behind them.

**Combos** (`combos.list` in config) are a mark plus a list of damage sources, with a multiplier. For example, Null Brand + Arrowhead Dive deals x1.6, and Sentence + Judgment deals x1.75. Stacked combos are capped by `max-total-multiplier`.

## Faction armies

There are 7 default factions: **Null**, **Jamatop**, **LAW**, **Cindercrest**, **Clownpierce's Circus**, **BAT** and **Invis Mafia**. Each one has a leader with a boss bar, a weighted army and a build block. Units can wear player-skin heads by username (e.g. the Clownpierce leader uses `head: Clownpierce`; skins are fetched async and cached).

- **Traits:** CRIT, STRAFE, LEAP, RETREAT, KITE, BLINK, ORBIT, FLICKER, SUMMON, RAGE, LIFESTEAL, BUILDER, BREAKER, on-hit DARKNESS/BLIND/SLOW/NAUSEA/FIRE/CHAOS, plus INVISIBLE (floating weapons), LAUGH and FIRE_ARROWS.
- **Fighting skills:**
  - focus fire (they prefer targets allies are already hitting)
  - call for help
  - grudges against players who hit them
  - retreat-and-heal, then re-engage
  - jump crits and strafing
  - archers keep their distance
  - no friendly fire, projectiles included
- **Building and breaking:**
  - pillar up to targets above, dig down to targets below, bridge gaps
  - break walls when stuck, tower over walls they can't break
  - **Fortify** raises a wall in front of the formation
  - limits: a hardness cap, a never-break list, and WorldGuard rules
  - placed blocks are tracked so **Clear Builds** can remove them
  - event units log what they break and the world is restored when the event ends
- **Flyers** (Vex-based: Wraiths, Bat Swarmers, Cinder Imps...) are fully plugin-driven. They hover in a halo, orbit, dive in, and land real melee hits that fire normal damage events.
- **Formations:** Grid, Line, Double Line, Checkerboard, Column, Wedge, Circle. Leaders stand at the front, archers at the back, and flyers circle above. Modes: Passive, Defend, Aggressive. Units follow you, or hold or march to a point.
- **Faction Horn** (from the GUI):
  - right-click an entity: focus it (it glows)
  - right-click a block: march there
  - right-click the sky: rally on me
  - left-click: **STAND DOWN**
  - sneak + right-click: open the GUI

## Player bots (simulator)

Bots are 1.21.9+ **Mannequins**: real player models with skins, armour, held items, health, knockback and physics. They have no vanilla AI; everything they do comes from the plugin.

**Skill tiers (MCTiers style, extended to LT6):** HT1, LT1, HT2, LT2 ... HT6, LT6.
- HT1 has a 1-tick reaction, about 1.5° of aim error, hits at full cooldown, crits about 90% of the time, w-taps, strafes, jump-resets knockback, blocks well, heals early and uses pearls and utility.
- LT6 reacts slowly, aims badly, spams weak hits, whiffs, never crits, walks straight at you and heals late.
- Every stat can be overridden in `bots.tiers`.

**PvP modes:**

| Mode | What the bot does |
|---|---|
| sword | Spacing, strafing, crit-jump timing, sprint/w-tap knockback, gapples |
| axe | Shield blocking; disables your shield with the axe (5 s cooldown, like vanilla); crossbow pokes |
| mace | Wind-charge launch, air control, smash with vanilla fall-scaled damage + Density; Wind Burst bounce chains |
| elytra_mace | Rocket up, glide high above you, dive and smash |
| uhc | No natural regen, gapples, bow at range, rod pulls, lava buckets and cobwebs (temporary and always cleaned up; respects WorldGuard), shield |
| nethpot | Speed and strength II, splash heals at its feet, totems (re-equipped after a pop at tier speed), pearls |
| spear | Speed-scaled charge attacks plus jabs; wind charges to reset distance |

**Groups** (`bots.groups`): each one sets skins, names, tier pool, mode pool, kit, team, behaviour, a leader and elites, chat lines, health and scale.
- **null**:
  - the generated **all-black NULL skin**, with **scrambled (obfuscated) names**
  - mostly HT1-HT2
  - **ORGANISED**: squads march in formation and focus-fire one target, with at most 3 attackers per enemy. Hurt members rotate to the back to heal.
  - led by **JamatoP**, with commanders `____D____`, `____X____` and `_________`
- **law**: led by **King LettuceK**, with elites TheGODofWar and SargeLAW. Gold-trimmed kit; random skill from HT2 to LT6.
- **bandits**: random everything, with gamer names.
- **duelist** and **random**.

**Skins:** each group lists skin specs.
- `pack:null` (or any PNG dropped into `plugins/UnstableSMP/skins/`) uses the plugin's resource pack.
- `user:<name>` uses a Minecraft account's skin.
- `texture:<base64>` uses a raw texture (e.g. from MineSkin).
- `default:steve|alex|...` uses a built-in skin.
- The black NULL skin is drawn in code (`resource-pack.null-skin`: colour, eyes). The pack is served by a built-in web server: set `resource-pack.host` to your public IP or domain and open `resource-pack.port`, or upload `pack/UnstableSMP-pack.zip` yourself and set `external-url`.
- Without the pack, NULL bots fall back to `fallback-skin` and still wear their black-trimmed netherite.

**Immersion:**
- Bots post vanilla-identical "joined the game" and "X was slain by Y" lines (the game's own translation keys) and chat taunts from their group's lines.
- NULL names are obfuscated in chat too.
- Players fighting bots get combat-tagged.
- Bots are not added to the tab list, which would need packet-level fakes.

## Simulator setup (lobby + command blocks)

1. Build an arena, stand in it and run `/uss map create arena`. Add bot spawns with `/uss map addspawn arena bots` (several points spread big spawns out). Add `a` and `b` spawns for battles, then `/uss map setexit arena`.
2. In the lobby run `/uss map setlobby`. Look at a note block and run `/uss map link arena players kit=mace team=players`. Clicking it now sends a player to the arena with the mace kit (pressure plates link the same way, by stepping on them).
3. Command blocks:
   - `uss game start waves arena group=law count=20 inc=15 waves=10 players=@a[distance=..10]` (2v1000-style LAW waves)
   - `uss game start battle arena group=null b=law count=250`
   - `uss bot spawn 50 null at=arena:bots target=@p`
   - `uss duel @p mace HT1`
   - `uss team join null @p`
   - `uss kit give nethpot @p`
4. Teams: give players the team GUI (`/uss team gui @p`). Players on `null` fight alongside NULL bots.

## Recording / POV skits

- **Camera paths:** fly to each shot and run `/uss cam add 60` (ticks to the next keyframe). Then `/uss cam save intro` and `/uss cam play intro @a`. Viewers ride a smooth spline in spectator mode and are put back where they were.
- **Scenes:** `plugins/UnstableSMP/scenes/*.yml` hold timelines of titles, fake chat, joins, deaths, sounds, fades, shakes, slow motion, camera paths, bot spawns and orders, teleports, kits, teams, games and console commands. Two examples ship with the plugin: `/uss scene play null_invasion @p` and `/uss scene play law_arrest @p`.
- **Live tools:**
  - `/uss fake chat <white>____D____ <gray>found you.`
  - `/uss fake join JamatoP`
  - `/uss fx slowmo 5 60`
  - `/uss fx fade out @a 40`
  - `/uss fx title @a <white><bold>NULL | <gray>they are here`

## Config walkthrough

| Section | What it controls |
|---|---|
| `performance` | Display caps per player and per server, mob cap, effect cap, action-bar refresh rate |
| `swarm` | Size, shard ratio, lifetime, bot HP, scale, blocks, glow colour, movement (speeds, boids weights), damage (contact, dive, cap, i-frame interval), every formation's shape |
| `abilities.<id>` | Name, lore, material, `item-model`, `custom-model-data`, cooldowns, and every number of that ability (range, radius, damage, duration...) |
| `combos` | Combo table (mark + sources gives a multiplier) and the overall cap |
| `combat-tag` | Duration, PvE tagging, logout = death, blocked teleport causes, ender pearls, blocked commands |
| `kill-messages` | Verb pools per ability, swarm move, ability set, faction (`factions.<id>.kill-verbs`), and default |
| `units` | Aggro and defend ranges, caps, grudges, call-for-help, crit, lifesteal, rage, retreat, building rules |
| `factions` | Name (MiniMessage), colour, icon, build block, leader, weighted army, default formation/spacing/mode |
| `templates` | Entity type, health, damage, speed, scale, weapon, armour tier and colour, head, trail particle, traits, summons |
| `battles` | Gap, distance, max size, cleanup delay, presets (`null:law`, `clownpierce:law`, ...) |
| `events` | Broadcast scope, boss-bar range, max concurrent, and per event: duration, faction, spawn-mode, waves, caps, strikes, portals, VexBot `command` |
| `bots` | Max bots (1000), aggro and LOD ranges, regen, collisions, pathfinding budget, immersion messages, tier overrides, groups |
| `teams` | Team list (name, colour, prefix, icon) and whether bots attack teamless players |
| `resource-pack` | Built-in pack server (host, port), external URL, NULL skin colour and eyes |
| `games` | Countdown and end delay |
| `kits.yml`, `maps.yml`, `cameras.yml`, `scenes/` | Saved in game with the commands above |

**VexBot compatibility:**
1. Set `events.<event>.spawn-mode: COMMAND`.
2. Set `command.command` to a spawn command, using the `{x} {y} {z} {world} {target}` placeholders.

Every spawn then runs that command, and whatever entity it spawns (caught via `EntityAddToWorldEvent` during the dispatch and capture window) is pulled to the portal or spawn point.

**WorldGuard flags** (registered automatically):
- `uss-events` (deny it to stop events in a region)
- `uss-unit-griefing` (allow it to let units build and break inside a region; outside regions the normal build flags apply)
- PvP abilities follow the `pvp` flag.

## Performance design

- **One repeating task** (`Ticker`) drives swarms, effects, units, events, battles, the combat tag and the action bar. `/uss status` shows the ms each one takes.
- Swarm steering, slot assignment and rendering use primitive fields and reused arrays, so the hot loops don't allocate.
  - Only the root display of each bot is teleported, every 2 ticks with client interpolation. The face and hitbox ride along as passengers.
  - Rotations are interpolated transformation keyframes, roughly every 10 ticks.
  - Swarm damage is summed and applied once per interval, which respects invulnerability frames.
- Unit brains run every 10 ticks, spread evenly across ticks. Enemy candidates are gathered once per faction per cycle, not with a world query per unit.
- Projectiles are tracked from their launch event instead of by scanning the world.
- **Caps:** displays per player and per server, mobs per faction and per server, and active effects.
- **No ghost entities:**
  - Everything spawned is `setPersistent(false)`, scoreboard-tagged `uss_entity` and tracked.
  - It is removed on disable, on owner death, quit or world change, and on chunk unload (`EntitiesUnloadEvent`).
  - Leftovers are swept on startup and on `EntitiesLoadEvent`.

**Measured** on a headless Paper 1.21.11 test server (4 vCPU):
- A Portal Invasion (40 hostiles) plus a 40-vs-40 battle (119 mobs fighting) took **0.41 ms/tick** of plugin time. The whole server averaged 5.9 ms/tick at 19.7+ TPS.
- A 24-bot portal swarm cycling all 10 formations, together with every ability effect, ran without errors.
- Afterwards, `kill @e[type=block_display|item_display|interaction]` found **no** leftover entities.

**Measured, player bots** (headless Paper 1.21.11, 4 vCPU):
- **507 bots** (250 NULL + leaders vs 250 LAW + leaders), all fully ticking, at peak combat: **10.9-11.6 ms/tick average, 20.0 TPS**. The bot AI itself used about 0.6-1.5 ms/tick. NULL won in under 30 s.
- **All 7 modes**, 12 HT1-LT2 bots vs 12 LT3-LT6 bots each, with 0 errors. The better tiers won: sword 8-1, mace 7-0, elytra mace 9-0, spear 7-2.
- After stopping mid-battle and restarting there were no leftover mannequins.

Bots set a hidden, harmless Luck effect. Paper's entity activation range would otherwise freeze bots with no player within 16 blocks.

## Manual test checklist (needs a real player)

1. `/uss give all`. Every item has a name, lore and glint. Renaming an item in an anvil doesn't break it (it's identified by PDC).
2. **Vexbot Core:**
   - A swarm of cubes and blades pops in over your head, bobbing and spinning, with a glowing purple face, purple glow and particle trails.
   - Sneak + right-click dismisses it.
3. **Formation Sigil:** cycle through all 10 formations. Bots reassign and fly smoothly to their new slots.
   - Look at a mob and pick Spiral Vortex, Pincer, Rain or Dome Cage. They anchor on that mob.
   - Dome Cage on a player: they get pushed back in and can't pearl out.
4. Hit a mob: the swarm attacks it. Let a mob hit you: the swarm defends you.
5. **Dive Strike:** a squad dives. Sneak gives **Arrowhead Dive**: it assembles, divebombs, then returns.
6. **Recall:** sneak toggles Orbit Shield. Have someone shoot arrows at you; they're deleted.
7. A second player destroys bots by punching or shooting them. You can punch *through* your own bots.
8. **Null:**
   - Step: you can't land inside walls or over the void.
   - Field: Darkness and Slowness, and regeneration is blocked.
   - Brand a target, then Arrowhead it: the "BRANDED DIVE!" combo text appears.
9. **Portal Swarm:** the ring rotates and waves of bots pour out. Another player smashes it (HP shows in their action bar). Law's **Order** collapses it and silences the caster's abilities.
10. **Judgment** (gavel slam), **Sentence** (chains: no walking, jumping or pearling), and Sentence then Judgment (the "FINAL JUDGMENT!" combo).
11. **Ember Crest** trail, **Ashfall** meteors, and **Phoenix**: take damage below 3 hearts. It triggers once, and again only after you die.
12. **Combat tag:**
    - `COMBAT: 15` counts down in the action bar.
    - `/spawn` is blocked, and so are pearls.
    - Logging out kills you, with a broadcast.
13. **Kill messages:** "X was SKEWERED by Y" and similar.
14. **GUI (`/uss gui`):**
    - Select a faction and join it.
    - Spawn units (left 1, right 5, shift 10) and the leader (boss bar).
    - Try every toggle, formation and spacing button.
15. **Horn:**
    - right-click a mob: focus it (it glows)
    - right-click a block: march there
    - right-click the sky: rally on you
    - left-click: STAND DOWN
    - sneak + right-click: opens the GUI
16. Building and breaking: put a target on a pillar, behind a wall or in a pit. Builders pillar, bridge and tower; breakers dig. **Fortify** raises a wall, and **Clear Builds** removes it.
17. **Battles page:** pick Null (side A) vs LAW (side B), then START. Both armies face off and fight, and **VICTORY** is announced. Try each preset.
18. **Events:**
    - `/uss event portal`: portals open in waves, units pour out, the commander arrives, the portals collapse and the world is restored.
    - Also run `null`, `law` and `cinder`: you get titles, the boss-bar countdown and themed strikes.
19. **WorldGuard:**
    - `/rg flag <region> uss-events deny` stops events there.
    - Units don't grief inside regions unless `uss-unit-griefing allow` is set.
    - Abilities respect `pvp deny`.
20. `/uss reload`, `/uss status`, then stop the server and start it again. There are no leftover displays and no "Removed N leftover entities" for a clean shutdown.

**Simulator checklist:**
21. `/uss duel @s sword HT1`, then LT6. The difference should be obvious (crits, w-taps, strafing, blocking).
22. `/uss bot spawn 30 null`: black skins (accept the pack), obfuscated names, squads in formation around JamatoP, focus fire, hurt bots rotating out.
23. Each mode against you: mace launches and smashes, elytra mace dives from the sky, the axe disables your shield, UHC places lava and cobwebs that vanish, nethpot pots and pops totems, spear charges.
24. Lobby note-block link, then a `waves` game on a map: countdown, waves, boss bar, win/lose, back to the exit.
25. Camera path playback and `scene play null_invasion`.

## Upgrade ideas

1. **Resource-pack models:** ship a pack with real Vexbot cube and blade models and animated item models. The `item-model` / `custom-model-data` hooks are already in config.
2. **Multi-phase Null Commander boss:** phases at 66% and 33% HP (portal storm, Dome Cage on the top damager, enrage), with telegraphed attacks and a loot table.
3. **Persistent faction progression:** faction XP, unlockable unit tiers, and territory claims that integrate with WorldGuard regions.
4. **Spectator battle mode:** a betting GUI, a live scoreboard of kills per side, and replays of the last battle's MVP unit.
5. **PlaceholderAPI and Discord hooks:** `%uss_combat%`, `%uss_swarm_size%` and the faction name in chat or tab, plus event start/victory announcements to a Discord channel.
