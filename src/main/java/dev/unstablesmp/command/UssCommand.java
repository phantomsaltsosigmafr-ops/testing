package dev.unstablesmp.command;

import dev.unstablesmp.Settings;
import dev.unstablesmp.Text;
import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.portal.Invasion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * {@code /uss} - start, stop, status, reload. Works from players, the console and command blocks (coordinates accept
 * {@code ~}). Replies go only to whoever ran it; players in the world just see the invasion. Never registers /vexbot.
 */
public final class UssCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("start", "stop", "status", "totem", "reload", "help");
    private static final List<String> OPTIONS = List.of("radius=", "portals=", "wave=", "duration=", "chance=",
            "interval=", "count=", "alive=", "max=");

    private final UnstableSMP plugin;

    public UssCommand(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.invasions().settings();
    }

    private void msg(CommandSender to, String key, TagResolver... r) {
        to.sendMessage(Text.prefixed(s(), key, r));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "start" -> start(sender, rest);
            case "stop" -> stop(sender, rest);
            case "status" -> status(sender);
            case "totem" -> totem(sender, rest);
            case "reload" -> {
                plugin.reload();
                msg(sender, "reloaded");
            }
            default -> {
                for (String line : s().message("help").split("\n")) sender.sendMessage(Text.mm(line));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ start

    private void start(CommandSender sender, String[] args) {
        Invasion.Options o = Invasion.Options.defaults(s());
        Location where = origin(sender);
        List<String> pos = new ArrayList<>();
        for (String a : args) {
            if (a.contains("=")) {
                if (!option(sender, o, a)) return;
            } else {
                pos.add(a);
            }
        }
        if (!pos.isEmpty()) {
            String first = pos.get(0).toLowerCase(Locale.ROOT);
            if (first.equals("at")) {
                where = coords(sender, pos.subList(1, pos.size()));
                if (where == null) return;
            } else if (looksLikeNumber(first)) {
                where = coords(sender, pos);
                if (where == null) return;
            } else if (!first.equals("here")) {
                Player target = Bukkit.getPlayerExact(pos.get(0));
                if (target == null) {
                    msg(sender, "unknown-player", Text.ph("player", pos.get(0)));
                    return;
                }
                where = target.getLocation();
            }
        }
        if (where == null || where.getWorld() == null) where = Bukkit.getWorlds().get(0).getSpawnLocation();
        World w = where.getWorld();
        Invasion inv = plugin.invasions().start(w, where.getX(), where.getY(), where.getZ(), o, starter(sender));
        msg(sender, "started", Text.ph("x", Integer.toString(where.getBlockX())), Text.ph("y", Integer.toString(where.getBlockY())),
                Text.ph("z", Integer.toString(where.getBlockZ())), Text.ph("world", w.getName()),
                Text.ph("portals", Integer.toString(o.maxPortals)), Text.ph("radius", Integer.toString(o.radius)),
                Text.ph("seconds", Integer.toString(o.durationSeconds)));
        inv.warnIfMissing(sender);
    }

    private boolean option(CommandSender sender, Invasion.Options o, String raw) {
        int eq = raw.indexOf('=');
        String key = raw.substring(0, eq).toLowerCase(Locale.ROOT);
        String value = raw.substring(eq + 1);
        try {
            switch (key) {
                case "radius", "r" -> o.radius = clamp(Integer.parseInt(value), 4, 256);
                case "portals", "p" -> o.maxPortals = clamp(Integer.parseInt(value), 1, 500);
                case "wave", "per-wave", "w" -> o.perWave = clamp(Integer.parseInt(value), 1, 50);
                case "duration", "time", "t" -> o.durationSeconds = clamp(Integer.parseInt(value), 5, 3600);
                case "chance", "c" -> {
                    double c = Double.parseDouble(value.replace("%", ""));
                    o.chance = Math.max(0, Math.min(1, c > 1 ? c / 100 : c));
                }
                case "interval", "i" -> o.interval = clamp(Integer.parseInt(value), 1, 1200);
                case "count", "n" -> o.count = Settings.IntRange.parse(value, o.count);
                case "alive", "max-alive", "a" -> o.maxAlive = Math.max(0, Integer.parseInt(value));
                case "max", "m" -> o.maxTotal = Math.max(0, Integer.parseInt(value));
                default -> {
                    msg(sender, "bad-option", Text.ph("option", raw));
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException ex) {
            msg(sender, "bad-option", Text.ph("option", raw));
            return false;
        }
    }

    // ------------------------------------------------------------------ stop / status

    private void stop(CommandSender sender, String[] args) {
        boolean now = args.length > 0 && args[0].equalsIgnoreCase("now");
        int n = plugin.invasions().stop(null, now);
        msg(sender, n == 0 ? "nothing-running" : now ? "stopped-now" : "stopped", Text.ph("count", Integer.toString(n)));
    }

    private void status(CommandSender sender) {
        List<Invasion> list = plugin.invasions().invasions();
        if (list.isEmpty()) {
            msg(sender, "nothing-running");
            return;
        }
        for (Invasion inv : list) {
            msg(sender, "status-line", Text.ph("id", Integer.toString(inv.id)), Text.ph("phase", inv.phase().name().toLowerCase(Locale.ROOT)),
                    Text.ph("portals", Integer.toString(inv.portalCount())), Text.ph("spawned", Integer.toString(inv.spawned())),
                    Text.ph("alive", Integer.toString(inv.alive())), Text.ph("world", inv.world.getName()),
                    Text.ph("x", Integer.toString((int) Math.floor(inv.cx))), Text.ph("z", Integer.toString((int) Math.floor(inv.cz))));
        }
    }

    // ------------------------------------------------------------------ totem

    /** {@code /uss totem [player] [amount]}: hands out Totems of Null. */
    private void totem(CommandSender sender, String[] args) {
        Player target = sender instanceof Player p ? p : null;
        int amount = 1;
        for (String a : args) {
            if (!a.isEmpty() && Character.isDigit(a.charAt(0))) {
                try {
                    amount = Integer.parseInt(a);
                } catch (NumberFormatException ex) {
                    msg(sender, "bad-option", Text.ph("option", a));
                    return;
                }
            } else {
                target = Bukkit.getPlayerExact(a);
                if (target == null) {
                    msg(sender, "unknown-player", Text.ph("player", a));
                    return;
                }
            }
        }
        if (target == null) {
            msg(sender, "bad-option", Text.ph("option", "(player name needed)"));
            return;
        }
        var leftover = target.getInventory().addItem(plugin.totem().create(amount));
        for (var stack : leftover.values()) target.getWorld().dropItem(target.getLocation(), stack);
        msg(sender, "totem-given", Text.ph("player", target.getName()), Text.ph("count", Integer.toString(amount)));
    }

    // ------------------------------------------------------------------ helpers

    private static Location origin(CommandSender sender) {
        if (sender instanceof Entity e) return e.getLocation();
        if (sender instanceof BlockCommandSender b) return b.getBlock().getLocation().add(0.5, 0, 0.5);
        return null;
    }

    private static UUID starter(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : null;
    }

    private static boolean looksLikeNumber(String s) {
        return !s.isEmpty() && (s.charAt(0) == '~' || s.charAt(0) == '-' || Character.isDigit(s.charAt(0)));
    }

    /** {@code x y z [world]}; {@code ~} and {@code ~5} are relative to the sender. Sends an error and returns null. */
    private Location coords(CommandSender sender, List<String> a) {
        if (a.size() < 3) {
            msg(sender, "bad-option", Text.ph("option", String.join(" ", a)));
            return null;
        }
        Location o = origin(sender);
        World w = o != null ? o.getWorld() : Bukkit.getWorlds().get(0);
        if (a.size() > 3) {
            w = Bukkit.getWorld(a.get(3));
            if (w == null) {
                msg(sender, "unknown-world", Text.ph("world", a.get(3)));
                return null;
            }
        }
        try {
            return new Location(w, coord(a.get(0), o == null ? 0 : o.getX()), coord(a.get(1), o == null ? 0 : o.getY()),
                    coord(a.get(2), o == null ? 0 : o.getZ()));
        } catch (NumberFormatException ex) {
            msg(sender, "bad-option", Text.ph("option", String.join(" ", a.subList(0, 3))));
            return null;
        }
    }

    private static double coord(String s, double base) {
        if (s.startsWith("~")) return base + (s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0);
        return Double.parseDouble(s);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(SUBS, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        if (sub.equals("start")) {
            if (args.length >= 3 && args[1].equalsIgnoreCase("at") && args.length <= 5) return filter(List.of("~"), last);
            if (args.length == 6 && args[1].equalsIgnoreCase("at")) {
                List<String> worlds = new ArrayList<>(OPTIONS);
                for (World w : Bukkit.getWorlds()) worlds.add(w.getName());
                return filter(worlds, last);
            }
            List<String> opts = new ArrayList<>(OPTIONS);
            if (args.length == 2) {
                opts.addAll(List.of("here", "at"));
                for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName());
            }
            return filter(opts, last);
        }
        if (sub.equals("stop") && args.length == 2) return filter(List.of("now"), last);
        if (sub.equals("totem") && args.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, last);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
