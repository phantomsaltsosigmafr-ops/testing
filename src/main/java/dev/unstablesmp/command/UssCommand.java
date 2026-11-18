package dev.unstablesmp.command;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.ability.AbilityType;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import dev.unstablesmp.event.EventType;
import dev.unstablesmp.faction.Faction;
import dev.unstablesmp.swarm.Swarm;
import dev.unstablesmp.swarm.SwarmFormation;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /uss} - gui, give, formation, event, battle, reload, status. Every subcommand has its own permission and
 * tab completion. (Never registers /vexbot or /vex.)
 */
public final class UssCommand implements BasicCommand {
    private static final String[] SUBS = {"gui", "give", "formation", "event", "battle", "reload", "status"};
    private final UnstableSMP plugin;
    private final SimCommands sim;

    public UssCommand(UnstableSMP plugin) {
        this.plugin = plugin;
        this.sim = new SimCommands(plugin);
    }

    private static String perm(String sub) {
        return SimCommands.SUBS.contains(sub) ? SimCommands.perm(sub) : "uss." + sub;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("help") && !List.of(SUBS).contains(sub) && !SimCommands.SUBS.contains(sub)) sub = "help";
        if (!sub.equals("help") && !sender.hasPermission(perm(sub))) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        switch (sub) {
            case "gui" -> gui(sender);
            case "give" -> give(sender, args);
            case "formation" -> formation(sender, args);
            case "event" -> event(sender, stack.getLocation(), args);
            case "battle" -> battle(sender, stack.getLocation(), args);
            case "reload" -> {
                plugin.reloadAll();
                plugin.messages().send(sender, "reloaded");
            }
            case "status" -> status(sender);
            case "help" -> plugin.messages().send(sender, "help");
            default -> sim.execute(sub, sender, stack.getLocation(), args);
        }
    }

    private void gui(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        plugin.gui().openMain(p);
    }

    /** /uss give <ability|set|all> [player] */
    private void give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "usage.give");
            return;
        }
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", Messages.p("player", args.length >= 3 ? args[2] : "?"));
            return;
        }
        String what = args[1].toLowerCase(Locale.ROOT);
        List<AbilityType> list = new ArrayList<>();
        AbilityType single = AbilityType.byId(what);
        if (single != null) list.add(single);
        else for (AbilityType t : AbilityType.values()) if (what.equals("all") || t.set.equals(what)) list.add(t);
        if (list.isEmpty()) {
            plugin.messages().send(sender, "unknown-ability", Messages.p("ability", what));
            return;
        }
        for (AbilityType t : list) {
            target.getInventory().addItem(plugin.abilities().items().create(t)).values()
                    .forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
        }
        plugin.messages().send(sender, "gave", Messages.p("count", Integer.toString(list.size())), Messages.p("player", target.getName()));
    }

    /** /uss formation <name> [player] */
    private void formation(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "usage.formation");
            return;
        }
        SwarmFormation f = SwarmFormation.parse(args[1]);
        if (f == null) {
            plugin.messages().send(sender, "unknown-formation", Messages.p("formation", args[1]));
            return;
        }
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", Messages.p("player", args.length >= 3 ? args[2] : "?"));
            return;
        }
        Swarm sw = plugin.swarms().swarmOf(target.getUniqueId());
        if (sw == null) {
            plugin.messages().send(sender, "swarm.none");
            return;
        }
        plugin.abilities().vexbots().applyFormation(target, sw, f, 40);
        plugin.messages().send(sender, "swarm.formation-set", Messages.p("formation", f.label), Messages.p("player", target.getName()));
    }

    /** /uss event <portal|null|law|cinder> [stop|player] */
    private void event(CommandSender sender, Location where, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "usage.event");
            return;
        }
        if (args[1].equalsIgnoreCase("stop")) {
            int n = plugin.events().stopAll();
            plugin.messages().send(sender, "event.stopped-all", Messages.p("count", Integer.toString(n)));
            return;
        }
        EventType t = EventType.parse(args[1]);
        if (t == null) {
            plugin.messages().send(sender, "usage.event");
            return;
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("stop")) {
            if (plugin.events().stop(t)) plugin.messages().send(sender, "event.stopped", Messages.c("event", eventName(t)));
            else plugin.messages().send(sender, "event.not-running", Messages.c("event", eventName(t)));
            return;
        }
        Location at = centre(sender, where, args.length >= 3 ? args[2] : null);
        if (at == null) return;
        String err = plugin.events().start(t, at, sender.getName());
        if (err != null) plugin.messages().send(sender, err);
        else plugin.messages().send(sender, "event.started", Messages.c("event", eventName(t)));
    }

    private Component eventName(EventType t) {
        return Text.mm(plugin.getConfig().getString("events." + t.configKey + ".name", t.configKey));
    }

    /** /uss battle <factionA> <factionB> [size] [player] */
    private void battle(CommandSender sender, Location where, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            int n = plugin.battles().endAll();
            plugin.messages().send(sender, "battle.ended-all", Messages.p("count", Integer.toString(n)));
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(sender, "usage.battle");
            return;
        }
        Faction a = plugin.factions().get(args[1]), b = plugin.factions().get(args[2]);
        if (a == null || b == null) {
            plugin.messages().send(sender, "unknown-faction", Messages.p("faction", a == null ? args[1] : args[2]));
            return;
        }
        int size = plugin.battles().defaultSize();
        if (args.length >= 4) {
            try {
                size = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                plugin.messages().send(sender, "usage.battle");
                return;
            }
        }
        Location at = centre(sender, where, args.length >= 5 ? args[4] : null);
        if (at == null) return;
        String err = plugin.battles().start(at, a, b, size);
        if (err != null) plugin.messages().send(sender, err);
    }

    /** Sender's position, a named player's position, or (console) the main world's spawn. */
    private Location centre(CommandSender sender, Location where, String playerName) {
        if (playerName != null) {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) {
                plugin.messages().send(sender, "player-not-found", Messages.p("player", playerName));
                return null;
            }
            return p.getLocation();
        }
        if (sender instanceof Player p) return p.getLocation();
        if (where != null && where.getWorld() != null && !(where.getX() == 0 && where.getY() == 0 && where.getZ() == 0)) return where;
        World w = Bukkit.getWorlds().get(0);
        return w.getSpawnLocation();
    }

    private void status(CommandSender sender) {
        sender.sendMessage(plugin.messages().get("status.header"));
        sender.sendMessage(Text.mm("<gray>Swarms: <white>" + plugin.swarms().swarmCount() + "</white>  Vexbots: <white>" + plugin.swarms().botCount()
                + "</white>  Effects: <white>" + plugin.effects().count()));
        sender.sendMessage(Text.mm("<gray>Display entities: <white>" + plugin.tracker().displays() + "</white>  Mobs: <white>" + plugin.tracker().mobs()
                + "</white>  Units: <white>" + plugin.units().total()));
        StringBuilder fs = new StringBuilder("<gray>Factions:");
        for (Faction f : plugin.factions().all()) fs.append(" <white>").append(f.id).append("</white>=").append(plugin.units().count(f));
        sender.sendMessage(Text.mm(fs.toString()));
        sender.sendMessage(Text.mm("<gray>Bots: <white>" + plugin.bots().count() + "</white>/" + plugin.bots().maxTotal()
                + "  Temp blocks: <white>" + plugin.tempBlocks().count() + "</white>  Games: <white>" + plugin.games().running()));
        sender.sendMessage(Text.mm("<gray>Events: <white>" + plugin.events().running().keySet() + "</white>  Battles: <white>" + plugin.battles().running().size()
                + "</white>  Regions: <white>" + plugin.regions().name()));
        for (String line : plugin.ticker().report()) sender.sendMessage(Text.mm("<dark_gray>  " + line));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            for (String s : SUBS) if (sender.hasPermission(perm(s))) out.add(s);
            for (String s : SimCommands.SUBS) if (sender.hasPermission(perm(s))) out.add(s);
            return filter(out, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission(perm(sub))) return out;
        if (SimCommands.SUBS.contains(sub)) return filter(new ArrayList<>(sim.suggest(sub, args)), args[args.length - 1]);
        String last = args[args.length - 1];
        switch (sub) {
            case "give" -> {
                if (args.length == 2) {
                    for (AbilityType t : AbilityType.values()) out.add(t.id);
                    out.addAll(List.of(AbilityType.SETS));
                    out.add("all");
                } else if (args.length == 3) {
                    players(out);
                }
            }
            case "formation" -> {
                if (args.length == 2) for (SwarmFormation f : SwarmFormation.values()) out.add(f.id());
                else if (args.length == 3) players(out);
            }
            case "event" -> {
                if (args.length == 2) {
                    for (EventType t : EventType.values()) out.add(t.id);
                    out.add("stop");
                } else if (args.length == 3) {
                    out.add("stop");
                    players(out);
                }
            }
            case "battle" -> {
                if (args.length == 2) {
                    out.addAll(plugin.factions().ids());
                    out.add("stop");
                } else if (args.length == 3) {
                    out.addAll(plugin.factions().ids());
                } else if (args.length == 4) {
                    out.addAll(List.of("10", "20", "40"));
                } else if (args.length == 5) {
                    players(out);
                }
            }
            default -> {
            }
        }
        return filter(out, last);
    }

    private static void players(List<String> out) {
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
    }

    private static List<String> filter(List<String> in, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : in) if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        return out;
    }
}
