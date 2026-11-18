# UnstableSMP v2: Simulator Expansion Plan

Goal: turn UnstableSMP into a full **Unstable SMP simulator** toolkit:
- player-looking PvP bots (VexBot-style) that fight like real ranked players (HT1..LT6) across every PvP style
- NULL as an organised war machine led by **JamatoP**
- maps, teams, kits, mass spawning and game modes, all usable from command blocks and a note-block lobby
- cinematic and scripted-recording tools for POV skits
- 500+ bots at 20 TPS

Research notes (Unstable Universe wiki via search snippets, tvtropes, Modrinth/hosting pages):
- **NULL** are anonymous players with **unreadable (scrambled) names** and **black skins**. Joining means scrambling your name and changing your skin. Commanders are named `____D____`, `____X____` and `_________` ("The Commander"). **JamatoP** was the leader of NULL and head of Purgatory (Spoke took over later). Per the user, **Jamatop leads NULL** here, so he is the default NULL leader.
- **LAW** comes in endless waves. Its leader is **King LettuceK**, and elite members include **TheGODofWar** and **SargeLAW**. Skill levels are randomised (user request).
- **VexBot** (the user's reference for "best PvP bots") has many PvP modes (sword, axe, crystal, mace...), configurable difficulty, kits, teams and follow mode, and handles big counts (~1000).
- **MCTiers** naming: HT1 (best) ... LT5. The user wants it extended to **LT6** (worst), so there are 12 tiers.
- Fan simulators to draw from (2v1000 LAW battle, tournament simulator, FlameFrags combat master): waves of LAW, arenas, character selection, kits.

## Architecture (new packages)

| Package | Classes | Purpose |
|---|---|---|
| `bot` | `Bot`, `BotManager`, `BotSpawner`, `BotGroup`, `BotGroupRegistry`, `SkillTier`, `PvpMode`, `BotInventory`, `BotListener`, `SpatialGrid` | Mannequin-based player bots: registry, LOD tick, spawn/clear API, groups (null/law/bandits/...) |
| `bot.ai` | `BotBrain`, `BotMovement`, `BotPathfinder`, `MeleeCore`, `Squad`, `Tactic` + `SwordTactic`, `AxeTactic`, `MaceTactic`, `ElytraMaceTactic`, `UhcTactic`, `NethPotTactic`, `SpearTactic` | Fighting skill: sprint/w-tap/crit timing/strafe/jump-reset/shield/axe-disable/pots/gapples/totems/pearls/rods/bows/webs/lava/wind charges/elytra/mace smash/spear charge; organised squads for NULL |
| `bot.skin` | `SkinService`, `NameGenerator`, `PackServer` | Skins by username, texture value, or a generated resource-pack skin (black NULL skin served by a built-in HTTP pack server); scrambled and underscore names |
| `kit` | `KitService`, `Kit`, `DefaultKits` | Kits for players and bots (kits.yml), built-in kits per PvP mode |
| `team` | `TeamService`, `TeamMenu` | Scoreboard teams (colour, prefix, friendly fire off), team select GUI, players and bots |
| `map` | `MapService`, `GameMap`, `MapLinkListener` | Named map areas with team, bot and lobby spawns; note-block / pressure-plate / button links that teleport to maps |
| `game` | `GameService`, `GameSession`, `GameType` | Command-block "event" games: WAVES (2v1000 style), TDM, FFA, BOSS, DUEL. Countdowns, waves, win/lose, return to lobby |
| `cine` | `CameraService`, `CameraPath`, `SceneService`, `Scene`, `FakeChat`, `Cinematics` | Smooth spline camera paths (spectator plus interpolated display camera), slow motion (tick rate), fades, shakes, fake chat/join/leave/death, YAML scene scripts |
| `command` | `UssCommand` (+ `BotCommand`, `MapCommand`, `CineCommand` handlers) | All commands under `/uss`, selectors (`@p`, `@a[...]`) for command blocks |

## Bots: design

- **Entity:** `Mannequin`. It has a real player model, profile skin, armour and hands, physics and health, and no vanilla AI. Bots have no "NPC" description, `setCollidable(false)` (no cramming cost at 500+), and are non-persistent and tracked (no ghosts).
- **Movement:** we set the velocity every tick (sprint 0.28 b/t, walk 0.2), jump 0.42 when blocked or for crits, auto-jump 1-block steps, strafe circles, and run a budgeted A* (global node budget per tick) only when stuck or the target is not in a straight line. Rotation is smoothed with tier aim error.
- **Damage:** our own melee model.
  - Weapon damage table plus Sharpness, Strength and crit x1.5, with the 1.9 attack cooldown (bots attack at tier threshold charge) and sweep for swords.
  - Damage goes through `DamageSource(MOB_ATTACK, causing=bot)`, so armour, shields and totems behave like vanilla.
  - Axe vs a blocking player gives the shield cooldown and a break sound. Sprint hit and w-tap add knockback.
- **Healing:** splash pots (real `ThrownPotion`), gapples (eat animation via `startUsingItem`, then absorption + regen), totems (vanilla totem works on any living entity; bots re-equip after pops), and tier-based natural regen (off in UHC).
- **Modes:**
  - SWORD: crits, w-tap, strafe, shield
  - AXE: shield + axe, disables shields, crossbow
  - MACE: wind-charge launch, smash with fall-distance damage, breach
  - ELYTRA_MACE: firework rocket up, glide, dive smash
  - UHC: rod, bow, lava, cobweb, gapples, blocks
  - NETHPOT: netherite, splash heals, speed/strength, totems, pearls
  - SPEAR: charge (speed-scaled damage) and jab
  - RANDOM
- **Tiers HT1..LT6:** reaction ticks, aim error, attack charge threshold, crit rate, w-tap rate, strafe skill, jump-reset rate, block skill, heal threshold and speed, pearl use, reach, combo persistence. All are overridable in config.
- **Groups:**
  - `null`: black skin, scrambled names, HT1-HT2, organised squads, focus fire, rotation, leader **JamatoP**, commanders `____D____`, `____X____`
  - `law`: gold kit, random tiers, leader **King LettuceK**, elites TheGODofWar, SargeLAW
  - `bandits`: random modes, tiers and gamer names
  - `random`
  - Each group is fully configurable: skins, names, tier pool weights, mode pool, kit, team, behaviour (organised/swarm/duelist).
- **Immersion:** optional fake join/leave messages when bots spawn/despawn, vanilla-style death messages for bots, and chat lines (taunts on kills, configurable).
- **Performance:**
  - one shared tick
  - per-bot brain rate from tier reaction, with LOD (bots far from players think every 10 ticks)
  - spatial grid for targets (rebuilt every 5 ticks)
  - no per-tick allocations in the hot paths
  - global caps (`bots.max-total: 1000`)

## Commands (all command-block friendly)

```
/uss bot spawn <count> [group] [mode|random] [tier|random] [team] [map[:spawn]|here|x y z] [@targets-to-attack]
/uss bot clear [group|team|map|all]
/uss bot list | /uss bot tp <group> | /uss bot target <group> <@targets> | /uss bot follow <group> <player>
/uss duel <player> <mode> <tier>
/uss kit give <kit> <@targets> | save <kit> | delete <kit> | list
/uss team join <team> <@targets> | leave <@targets> | gui | list
/uss map create|delete|setspawn|setlobby|pos1|pos2|tp|link|unlink|list ...
/uss game start <type> <map> [args] | stop [map] | list
/uss cam add [ticks] | clear | save <name> | play <name> [@targets] | stop [@targets]
/uss scene play <name> [@targets] | stop | list
/uss fake chat <name> <msg> | join <name> | leave <name> | death <victim> <killer> [weapon]
/uss fx fade <in|out> [@targets] | shake <@targets> [ticks] | slowmo <rate> [ticks] | title <@targets> <mini>
/uss pack reload | send <@targets>
```

## Tasks

1. Remove the old `reference/` folder from the repo (the user asked to clean the reused repo).
2. Core bot runtime: Bot, BotManager, SkillTier, PvpMode, movement, pathfinder, melee core, damage, listener (death, damage, cleanup).
3. Tactics for all 7 modes plus squads (organised NULL).
4. Skins, names and the pack server (generated black NULL skin).
5. Kits, teams, maps (+ links), games.
6. Cinematics: camera paths, scenes, fake chat, fx.
7. Commands with selector support, GUI additions (team select, bot spawner page, maps page), config and messages.
8. Integrate with existing systems: Targeting/friendly fire, combat tag, kill messages, status, cleanup.
9. Tests plus a headless server smoke test: spawn 500+ bots fighting, measure MSPT; all modes run with no exceptions.
10. README update, commit, push.
