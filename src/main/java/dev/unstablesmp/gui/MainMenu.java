package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import dev.unstablesmp.faction.ArmyFormation;
import dev.unstablesmp.faction.Faction;
import dev.unstablesmp.faction.UnitTemplate;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** The main command page: faction selection, membership, spawning, orders, formation, building and toggles. */
final class MainMenu extends Menu {
    private final GuiService gui;

    MainMenu(UnstableSMP plugin, GuiService gui, Player viewer) {
        super(plugin, viewer, 6, plugin.messages().get("gui.main-title"));
        this.gui = gui;
    }

    @Override
    protected void render() {
        Faction f = gui.selected(viewer);
        int i = 0;
        for (Faction x : plugin.factions().all()) {
            if (i >= 8) break;
            final Faction fx = x;
            set(i++, Icons.faction(x, x == f, "Units: <white>" + plugin.units().count(x), "Mode: <white>" + x.mode,
                    "Members: <white>" + x.members.size(), "<yellow>Click to select"), (p, t) -> {
                gui.select(p, fx);
                refresh();
            });
        }
        if (f == null) return;
        boolean member = f.members.contains(viewer.getUniqueId());
        set(8, Icons.icon(member ? Material.RED_BANNER : Material.LIME_BANNER, member ? "<red>Leave " + f.nameMini : "<green>Join " + f.nameMini,
                "Members are never attacked by", "this faction's units.", "You can be in one faction at a time."), (p, t) -> {
            if (f.members.contains(p.getUniqueId())) {
                plugin.factions().leave(f, p.getUniqueId());
                plugin.messages().send(p, "faction.left", Messages.c("faction", f.display));
            } else {
                plugin.factions().join(f, p.getUniqueId());
                plugin.messages().send(p, "faction.joined", Messages.c("faction", f.display));
            }
            refresh();
        });

        String target = "none";
        if (f.focus != null) {
            Entity e = Bukkit.getEntity(f.focus);
            if (e != null) target = e.getName();
        }
        set(9, Icons.icon(Material.BOOK, f.nameMini + " <gray>Overview", "Units: <white>" + plugin.units().count(f), "Mode: <white>" + f.mode,
                "Focus target: <white>" + target, "Formation: <white>" + f.formation.label + " <gray>(spacing " + f.spacing + ")",
                "Following: <white>" + (f.follow ? "commander" : "holding position"),
                "You are " + (member ? "<green>a member" : "<red>not a member")), null);
        set(10, Icons.icon(Material.ZOMBIE_SPAWN_EGG, "<green>Spawn Units...", "Pick exactly which units to spawn",
                "Left 1, Right 5, Shift 10"), (p, t) -> new SpawnMenu(plugin, gui, p).open());
        UnitTemplate lt = plugin.factions().template(f.leaderTemplate);
        set(11, Icons.icon(Material.NETHER_STAR, "<gold>Spawn Leader", lt == null ? "No leader set" : Text.plain(lt.display)), (p, t) -> {
            if (lt == null) plugin.messages().send(p, "faction.no-leader");
            else gui.spawnFor(p, f, lt, 1);
            refresh();
        });
        set(12, Icons.icon(Material.IRON_SWORD, "<green>Spawn Army", "Random mix from the army list", "Left 1, Right 5, Shift 10"), (p, t) -> {
            gui.spawnArmy(p, f, amount(t));
            refresh();
        });
        set(13, Icons.icon(Material.GOAT_HORN, "<yellow>Get Faction Horn", "Right-click entity: focus it", "Right-click block: march there",
                "Right-click sky: rally on me", "Left-click: STAND DOWN", "Sneak + right-click: this menu"), (p, t) -> {
            p.getInventory().addItem(plugin.horn().create(f));
            if (!f.members.contains(p.getUniqueId())) plugin.factions().join(f, p.getUniqueId());
            f.commander = p.getUniqueId();
            plugin.messages().send(p, "faction.horn-given", Messages.c("faction", f.display));
            refresh();
        });
        set(14, Icons.icon(Material.ENDER_PEARL, "<aqua>Teleport Units To Me"), (p, t) -> plugin.units().teleportTo(f, p.getLocation()));
        set(15, Icons.icon(Material.BARRIER, "<red>Disband All Units", "Removes every unit of this faction"), (p, t) -> {
            plugin.units().disband(f);
            refresh();
        });
        set(16, Icons.icon(Material.WHITE_BANNER, "<white><bold>STAND DOWN", "Stop fighting, clear targets,", "form up on you"), (p, t) -> {
            plugin.units().standDown(f, p);
            plugin.messages().send(p, "faction.stand-down", Messages.c("faction", f.display));
            refresh();
        });
        set(17, Icons.icon(Material.MILK_BUCKET, "<white>Clear Focus Target"), (p, t) -> {
            f.focus = null;
            refresh();
        });

        set(18, Icons.glint(Icons.icon(Material.LIME_DYE, "<green>Passive", "Never start fights"), f.mode == Faction.Mode.PASSIVE), (p, t) -> mode(f, Faction.Mode.PASSIVE));
        set(19, Icons.glint(Icons.icon(Material.SHIELD, "<yellow>Defend", "Fight enemies near the formation"), f.mode == Faction.Mode.DEFEND), (p, t) -> mode(f, Faction.Mode.DEFEND));
        set(20, Icons.glint(Icons.icon(Material.NETHERITE_SWORD, "<red>Aggressive", "Hunt every enemy in range"), f.mode == Faction.Mode.AGGRESSIVE), (p, t) -> mode(f, Faction.Mode.AGGRESSIVE));
        set(21, Icons.glint(Icons.icon(Material.LEAD, "<green>Follow Me", "Formation forms up behind you"), f.follow), (p, t) -> {
            f.follow = true;
            f.commander = p.getUniqueId();
            refresh();
        });
        set(22, Icons.glint(Icons.icon(Material.ANVIL, "<yellow>Hold Here", "Formation holds at your position"), !f.follow), (p, t) -> {
            plugin.units().hold(f, p.getLocation());
            refresh();
        });
        set(23, Icons.icon(Material.CROSSBOW, "<red>Attack Outsiders: " + Icons.onOff(f.attackOutsiders), "Attack players who aren't members"), (p, t) -> {
            f.attackOutsiders = !f.attackOutsiders;
            refresh();
        });
        set(24, Icons.icon(Material.RED_BANNER, "<red>Fight Other Factions: " + Icons.onOff(f.fightFactions)), (p, t) -> {
            f.fightFactions = !f.fightFactions;
            refresh();
        });
        set(25, Icons.icon(Material.NAME_TAG, "<white>Show Names: " + Icons.onOff(f.showNames)), (p, t) -> {
            f.showNames = !f.showNames;
            plugin.units().applyNames(f);
            refresh();
        });
        set(26, Icons.icon(Material.SPECTRAL_ARROW, "<white>Glowing: " + Icons.onOff(f.glow)), (p, t) -> {
            f.glow = !f.glow;
            plugin.units().applyGlow(f);
            refresh();
        });

        int s = 27;
        for (ArmyFormation fm : ArmyFormation.values()) {
            final ArmyFormation pick = fm;
            set(s++, Icons.glint(Icons.icon(fm.icon, "<aqua>Formation: " + fm.label), f.formation == fm), (p, t) -> {
                f.formation = pick;
                refresh();
            });
        }
        set(34, Icons.icon(Material.REDSTONE, "<red>Spacing -", "Now: " + f.spacing), (p, t) -> {
            f.spacing = Math.max(1.0, f.spacing - 0.5);
            refresh();
        });
        set(35, Icons.icon(Material.GLOWSTONE_DUST, "<green>Spacing +", "Now: " + f.spacing), (p, t) -> {
            f.spacing = Math.min(8.0, f.spacing + 0.5);
            refresh();
        });

        set(36, Icons.icon(Material.BRICKS, "<gold>Building: " + Icons.onOff(f.build), "Builders pillar up, bridge gaps,", "tower over walls"), (p, t) -> {
            f.build = !f.build;
            refresh();
        });
        set(37, Icons.icon(Material.IRON_PICKAXE, "<gold>Breaking: " + Icons.onOff(f.breakBlocks), "Breakers dig through walls,", "floors and ceilings"), (p, t) -> {
            f.breakBlocks = !f.breakBlocks;
            refresh();
        });
        set(38, Icons.icon(Material.STONE_BRICK_WALL, "<gold>Fortify: " + Icons.onOff(f.fortify), "Builders raise a wall in front", "of the formation"), (p, t) -> {
            f.fortify = !f.fortify;
            refresh();
        });
        set(39, Icons.icon(Material.BRUSH, "<gold>Clear Builds", "Removes blocks this faction placed (" + f.placed.size() + ")"), (p, t) -> {
            int n = plugin.units().clearBuilds(f);
            plugin.messages().send(p, "faction.builds-cleared", Messages.p("count", Integer.toString(n)));
            refresh();
        });

        set(45, Icons.icon(Material.CRYING_OBSIDIAN, "<dark_purple>Server Events...", "Portal Invasion, Null Outbreak,", "Law Crackdown, Cindercrest Firestorm"),
                (p, t) -> {
                    if (p.hasPermission("uss.event")) new EventsMenu(plugin, gui, p).open();
                    else plugin.messages().send(p, "no-permission");
                });
        set(47, Icons.icon(Material.TNT, "<red><bold>Battles...", "Faction vs faction fights", "e.g. Null vs LAW"), (p, t) -> {
            if (p.hasPermission("uss.battle")) new BattleMenu(plugin, gui, p).open();
            else plugin.messages().send(p, "no-permission");
        });
        set(49, Icons.icon(Material.ECHO_SHARD, "<light_purple>Ability Items...", "Get any ability item"), (p, t) -> {
            if (p.hasPermission("uss.give")) new AbilitiesMenu(plugin, gui, p).open();
            else plugin.messages().send(p, "no-permission");
        });
        set(51, Icons.icon(Material.PLAYER_HEAD, "<white><bold>Player Bots...", "Spawn PvP bots (NULL, LAW, bandits...)", "Pick style and skill tier HT1..LT6"), (p, t) -> {
            if (p.hasPermission("uss.bot")) new BotMenu(plugin, gui, p).open();
            else plugin.messages().send(p, "no-permission");
        });
        set(52, Icons.icon(Material.WHITE_BANNER, "<white>Teams...", "Pick the side you fight for"), (p, t) -> new TeamMenu(plugin, p).open());
        set(53, Icons.icon(Material.OAK_DOOR, "<gray>Close"), (p, t) -> p.closeInventory());
    }

    private void mode(Faction f, Faction.Mode m) {
        f.mode = m;
        plugin.messages().send(viewer, "faction.mode", Messages.c("faction", f.display), Messages.p("mode", m.name().toLowerCase(Locale.ROOT)));
        refresh();
    }
}
