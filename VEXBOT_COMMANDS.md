# VexBot commands for an immersive NULL invasion

Every command here was read out of VexBot's own plugin files (VexBot 3.2.5 for Paper 1.21.11, the newest release). I haven't run them on a server, and older VexBot versions may be missing some. `/vexbot settings list` shows what your version has. Settings take `on`, `off` or `toggle`.

## First: stop VexBot breaking the portals

| Command | What it does |
|---|---|
| `/vexbot null config breakportal false` | VexBot **destroys the null portal after the soldiers come out** by default. That's what was wrecking the portals. |
| `/vexbot null config list` | Shows the null settings. |

## NULL setup

| Command | What it does |
|---|---|
| `/vexbot team create null` | Makes a team for the NULL. |
| `/vexbot null config team null` | Soldiers from portals join that team. |
| `/vexbot null config jointeam true` | Actually puts them on it. |
| `/vexbot null config skin true` | Soldiers wear the black null skin (the leader keeps his own). |
| `/vexbot null config leader jamatop` | The rare leader is JamatoP (or `spoke`). |
| `/vexbot null config leaderchance 5` | Chance (%) that a leader comes through. |
| `/vexbot null config jamatopchance 5` | Chance (%) for JamatoP specifically. |
| `/vexbot null config commanderchance 10` | Chance (%) for commander-rank soldiers. |
| `/vexbot null config specialchance 5` | Chance (%) for special soldiers. |
| `/vexbot null config commanderkit code <kit code>` | Custom kit per rank. Also `spokekit` and `specialkit`; `... clear` resets. |
| `/vexbot settings leaveondeath on` | A killed NULL leaves instead of respawning, so the invasion actually thins out. |

## Breaking and building

| Command | What it does |
|---|---|
| `/vexbot null grief 60 10 true` | The null team griefs for 60 s with 10 TNT each and sets fire. `/vexbot null grief stop` ends it. Needs the null team set up (above) with bots online. |
| `/vexbot settings doblockexplosion on` | TNT, crystals, anchors and beds break blocks. Per team: `/vexbot team null settings doblockexplosion on`. |
| `/vexbot team null mine <x1 y1 z1> <x2 y2 z2>` | The team digs out that whole area, top layer first. `... mine status`, `... mine stop`. |
| `/vexbot settings buildup on` | Bots tower up to anyone standing above them. |
| `/vexbot settings buildupminheight 2` | How high above them a target must be before they tower (default 4). |
| `/vexbot settings placeblocksmoving on` | On long chases they sprint-jump and bridge block by block. |
| `/vexbot settings bridgemode god` | Bridging style: `ninja` (safe), `god` (fast), `moonwalk`. |
| `/vexbot settings gotomining on` | When sent somewhere, they mine through walls in the way. |
| `/vexbot settings shulkersteal on` | They mine your placed shulker box mid-fight and take it. |
| `/vexbot settings vexnav on` | Pathfinding through doors, up ladders and around lava (default on). |

## Act like real players

| Command | What it does |
|---|---|
| `/vexbot settings wander on` | Idle bots stroll around, visit beds, crafting tables and chests, go into houses and stand in groups. |
| `/vexbot wander <bot> <radius> [x y z]` | Gives a bot its own area to live in. `/vexbot wander <bot> stop` ends it. |
| `/vexbot settings pickuploot on` | They loot the ground and kill drops. |
| `/vexbot settings equiparmor on` | They put on better armour they pick up. |
| `/vexbot settings shulker-regear on` | They regear from shulker boxes, like players. |
| `/vexbot settings enderchestregear on` | Ender chest regear: pearl away, place it, restock, take it back. |
| `/vexbot settings retreat on` | They back off to heal when low. |
| `/vexbot settings healingmode advanced` | Buys space (pearl, web, knockback) before eating. |
| `/vexbot settings enderpearl on` | Pearl away when low. |
| `/vexbot settings pearlchasing on` | Pearl after you when you run. |
| `/vexbot settings waterbucketclutch on` | MLG water-bucket clutches. |
| `/vexbot settings fireescape on` | Douse themselves when burning. |
| `/vexbot settings webescape on` | Get out of cobwebs. |
| `/vexbot settings chorusescape on` | Get out of cobwebs by eating chorus fruit when boxed in. |
| `/vexbot settings revengemode on` | Fight back at whoever hits them. |
| `/vexbot settings autotarget on` | Hunt on their own. |
| `/vexbot settings targetplayers on` | Hunt players. |
| `/vexbot settings targetmobs on` | Hunt mobs. |
| `/vexbot settings ignorecreative on` | Leave creative-mode players alone (handy while filming). |
| `/vexbot settings chasespread on` | When many chase one target they spread out side by side instead of clumping. |
| `/vexbot settings spread-out on` | Teams over 100 chase in a formation, then break into the fight. |
| `/vexbot settings botping 80` | The ping shown in the tab list (0–5000 ms). |
| `/vexbot settings botspawnmobs on` | Mobs spawn around bots like around players. |
| `/vexbot settings voicelines on` | Taunts from your own `.ogg` files in `vexbot_voicelines`. Run `/vexbot voicelines reload` after adding files. |
| `/vexbot settings witherdeatheffect on` | Wither roar when anyone dies. |
| `/vexbot settings silent on` | Hides VexBot's "joined the game" spam and its command replies. Leave it off if you like the join lines. |
| `/vexbot settings nametagcolour none` | Plain nametags. Also `/vexbot tag off` hides the BOT tag. |
| `/vexbot randomplaystyle all` | Gives every bot a random playstyle: `safe`, `defensive`, `balanced`, `aggressive` or `adaptive`. |
| `/vexbot difficulty preset hard` | Skill: `easy`, `medium`, `hard`, `expert` or `professional`. Per team: `/vexbot teamdifficulty null ...`. |
| `/vexbot nick <bot> <name> <skin>` | Rename a bot and give it a skin. |

## Team tactics (cinematic moments)

| Command | What it does |
|---|---|
| `/vexbot team null surround <player>` | The whole team forms a ring around a player, then closes in. `... surround stop` ends it. |
| `/vexbot teamgoto null <x y z> [formation]` | March to a spot in formation: `wedge`, `line`, `wall`, `shieldwall`, `circle`, `square`, `column` or `hexagon`. |
| `/vexbot teamfollow null <player> [formation]` | Follow someone in formation (an escort). |
| `/vexbot team null effect <effect> <seconds> [level]` | Potion effect on the whole team (e.g. `strength 60 1`). |
| `/vexbot teamhide null` | They drink invisibility and take their armour off. Good for ambushes. |
| `/vexbot movement freeze` | Everyone stops walking but still fights; `unfreeze` releases them. Good for lining up a shot. |
| `/vexbot when <event> [who] then <command>` | Run a command when something happens. Events: `death`, `botdeath`, `playerdeath`, `kill`, `totempop`, `botspawn`, `teamwiped`. Placeholders: `{player} {victim} {killer} {team} {x} {y} {z}`. E.g. `/vexbot when teamwiped null then uss stop`. |

## Performance with big waves

| Command | What it does |
|---|---|
| `/vexbot settings botsimulationdistance 4` | Fewer chunks ticking around each bot (the default). |
| `/vexbot settings spawnspertick 0` | Spawn rate follows the server's tick time (the default). |

## Hooking it into the invasion

Put these in `plugins/UnstableSMP/config.yml`, then `/uss reload`:

```yaml
invasion:
  start-commands:
    - "vexbot null config breakportal false"
  leader:
    at-percent: 40
    commands:
      - "vexbot null grief 60 10 true"   # part-way through, the NULL start blowing things up
  end-commands:
    - "vexbot null grief stop"
```

**Watch out:** in VexBot 3.2 and later, `nullportalspawn` became `/vexbot null portalspawn <x y z> <count>`. If you update VexBot, change `spawn.commands` in our config to:

`"vexbot null portalspawn {x} {y} {z} {count}"`

VexBot 3.2 also builds its own portal for that command and refuses if there's no room. So with VexBot 3.2+ you may also need `portal.build: false`.
