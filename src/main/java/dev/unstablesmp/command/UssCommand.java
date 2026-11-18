package dev.unstablesmp.command;

import dev.unstablesmp.Settings;
import dev.unstablesmp.Text;
import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.portal.Breach;
import dev.unstablesmp.portal.BreachManager;
import dev.unstablesmp.portal.NullPortal;
import dev.unstablesmp.portal.Site;
import dev.unstablesmp.portal.SiteFinder;
import dev.unstablesmp.portal.Spots;
import java.util.ArrayList;
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
 * {@code /uss} - start, portal, stop, spot, status, reload. Works from players, the console and command blocks
 * (coordinates accept {@code ~} relative to the sender). This plugin never registers /vexbot; it only runs it.
 */
public final class UssCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("start", "portal", "stop", "spot", "status", "reload", "help");
    private static final List<String> START_KEYS = List.of("portals=", "radius=", "duration=", "warning=", "count=",
            "count-end=", "interval=", "max=", "alive=");

    private final UnstableSMP plugin;

    public UssCommand(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.breaches().settings();
    }

    private void msg(CommandSender to, String key, TagResolver... r) {
        to.sendMessage(Text.prefixed(s(), key, r));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? args : java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "start" -> start(sender, rest);
            case "portal" -> portal(sender, rest);
            case "stop" -> stop(sender, rest);
            case "spot", "spots" -> spot(sender, rest);
            case "status" -> status(sender);
            case "reload" -> {
                plugin.reload();
                msg(sender, "reloaded");
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        for (String line : s().messages.getOrDefault("help", "").split("\n")) sender.sendMessage(Text.mm(line));
    }

    // ------------------------------------------------------------------ start

    private void start(CommandSender sender, String[] args) {
        BreachManager mgr = plugin.breaches();
        Breach.Options o = Breach.Options.defaults(s());
        Location where = origin(sender);
        boolean spots = false;
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
            if (first.equals("spots")) {
                spots = true;
                if (pos.size() > 1) {
                    World w = Bukkit.getWorld(pos.get(1));
                    if (w == null) {
                        msg(sender, "unknown-world", Text.ph("world", pos.get(1)));
                        return;
                    }
                    where = w.getSpawnLocation();
                }
            } else if (first.equals("at") || first.equals("here")) {
                if (first.equals("at")) {
                    where = coords(sender, pos.subList(1, pos.size()));
                    if (where == null) return;
                }
            } else if (looksLikeNumber(first)) {
                where = coords(sender, pos);
                if (where == null) return;
            } else {
                Player target = Bukkit.getPlayerExact(pos.get(0));
                if (target == null) {
                    msg(sender, "unknown-player", Text.ph("player", pos.get(0)));
                    return;
                }
                where = target.getLocation();
            }
        }
        if (where == null || where.getWorld() == null) {
            World w = Bukkit.getWorlds().get(0);
            where = w.getSpawnLocation();
        }
        World w = where.getWorld();
        List<Site> fixed = null;
        if (spots) {
            fixed = plugin.spots().in(w);
            if (fixed.isEmpty()) {
                msg(sender, "spots-none", Text.ph("world", w.getName()));
                return;
            }
        }
        Breach b = mgr.start(w, where.getX(), where.getY(), where.getZ(), o, starter(sender), fixed);
        if (b == null) {
            msg(sender, "no-sites");
            return;
        }
        msg(sender, "started", Text.ph("portals", Integer.toString(b.portals().size())),
                Text.ph("x", Integer.toString(where.getBlockX())), Text.ph("y", Integer.toString(where.getBlockY())),
                Text.ph("z", Integer.toString(where.getBlockZ())), Text.ph("world", w.getName()),
                Text.ph("seconds", Integer.toString(o.durationSeconds)));
        b.warnIfMissingNow(sender);
    }

    private boolean option(CommandSender sender, Breach.Options o, String raw) {
        int eq = raw.indexOf('=');
        String key = raw.substring(0, eq).toLowerCase(Locale.ROOT);
        String value = raw.substring(eq + 1);
        try {
            switch (key) {
                case "portals", "p" -> o.portals = clamp(Integer.parseInt(value), 1, 32);
                case "radius", "r" -> o.radius = Settings.IntRange.parse(value, o.radius);
                case "duration", "time", "t" -> o.durationSeconds = clamp(Integer.parseInt(value), 5, 3600);
                case "warning", "w" -> o.warningSeconds = clamp(Integer.parseInt(value), 0, 120);
                case "count", "c" -> {
                    o.countStart = Settings.IntRange.parse(value, o.countStart);
                    if (!value.contains("-")) o.countEnd = o.countStart;
                }
                case "count-end", "countend" -> o.countEnd = Settings.IntRange.parse(value, o.countEnd);
                case "interval", "i" -> {
                    int[] iv = Settings.startEnd(value, o.intervalStart, o.intervalEnd);
                    o.intervalStart = Math.max(2, iv[0]);
                    o.intervalEnd = Math.max(2, iv[1]);
                }
                case "max", "m" -> o.maxTotal = Math.max(0, Integer.parseInt(value));
                case "alive", "max-alive", "a" -> o.maxAlive = Math.max(0, Integer.parseInt(value));
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

    // ------------------------------------------------------------------ portal

    private void portal(CommandSender sender, String[] args) {
        BreachManager mgr = plugin.breaches();
        SiteFinder finder = mgr.finder();
        String facingOpt = null;
        List<String> pos = new ArrayList<>();
        for (String a : args) {
            if (a.toLowerCase(Locale.ROOT).startsWith("facing=")) facingOpt = a.substring(7).toLowerCase(Locale.ROOT);
            else pos.add(a);
        }
        Location origin = origin(sender);
        Location at;
        int[] facing;
        if (pos.isEmpty() || pos.get(0).equalsIgnoreCase("here")) {
            if (!(sender instanceof Entity e)) {
                msg(sender, "player-only");
                return;
            }
            Location l = e.getLocation();
            int[] look = Site.facingFromYaw(l.getYaw());
            at = l.clone().add(look[0] * 7, 0, look[1] * 7);
            facing = new int[] {-look[0], -look[1]}; // pour toward whoever opened it
        } else {
            at = coords(sender, pos);
            if (at == null) return;
            Location toward = origin != null && origin.getWorld() == at.getWorld() ? origin : nearestPlayer(at);
            facing = toward == null ? new int[] {0, 1} : Site.facingToward(at.getX(), at.getZ(), toward.getX(), toward.getZ());
        }
        if (facingOpt != null) {
            int[] f = named(facingOpt);
            if (f == null) {
                msg(sender, "bad-option", Text.ph("option", "facing=" + facingOpt));
                return;
            }
            facing = f;
        }
        Site site = finder.at(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), facing[0], facing[1], false);
        Breach b = site == null ? null : mgr.openOne(at.getWorld(), site, starter(sender));
        if (b == null) {
            msg(sender, "portal-failed");
            return;
        }
        msg(sender, "portal-opened", Text.ph("x", Integer.toString(site.x())), Text.ph("y", Integer.toString(site.y() + 1)),
                Text.ph("z", Integer.toString(site.z())), Text.ph("facing", site.facingName()));
        b.warnIfMissingNow(sender);
    }

    private static int[] named(String f) {
        return switch (f) {
            case "north", "n" -> new int[] {0, -1};
            case "south", "s" -> new int[] {0, 1};
            case "east", "e" -> new int[] {1, 0};
            case "west", "w" -> new int[] {-1, 0};
            default -> null;
        };
    }

    // ------------------------------------------------------------------ stop / status

    private void stop(CommandSender sender, String[] args) {
        boolean now = args.length > 0 && args[0].equalsIgnoreCase("now");
        int n = plugin.breaches().stop(null, now);
        msg(sender, n == 0 ? "nothing-running" : now ? "stopped-now" : "stopped", Text.ph("count", Integer.toString(n)));
    }

    private void status(CommandSender sender) {
        List<Breach> list = plugin.breaches().breaches();
        if (list.isEmpty()) {
            msg(sender, "status-none");
            return;
        }
        for (Breach b : list) {
            int open = 0;
            for (NullPortal p : b.portals()) if (p.open()) open++;
            msg(sender, "status-line", Text.ph("id", Integer.toString(b.id)), Text.ph("phase", b.phase().name().toLowerCase(Locale.ROOT)),
                    Text.ph("open", Integer.toString(open)), Text.ph("portals", Integer.toString(b.portals().size())),
                    Text.ph("spawned", Integer.toString(b.spawned())), Text.ph("alive", Integer.toString(b.alive())),
                    Text.ph("world", b.world.getName()),
                    Text.ph("x", Integer.toString((int) Math.floor(b.cx))), Text.ph("z", Integer.toString((int) Math.floor(b.cz))));
        }
    }

    // ------------------------------------------------------------------ spots

    private void spot(CommandSender sender, String[] args) {
        Spots spots = plugin.spots();
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add", "set" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "player-only");
                    return;
                }
                String name = args.length > 1 ? args[1] : "spot" + (spots.all().size() + 1);
                Location l = p.getLocation();
                int[] f = Site.facingFromYaw(l.getYaw());
                Site site = plugin.breaches().finder().at(p.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), f[0], f[1], false);
                if (site == null) {
                    msg(sender, "portal-failed");
                    return;
                }
                spots.put(name, p.getWorld(), site);
                msg(sender, "spot-added", Text.ph("name", name), Text.ph("facing", site.facingName()));
            }
            case "remove", "delete", "del" -> {
                if (args.length < 2) {
                    help(sender);
                    return;
                }
                msg(sender, spots.remove(args[1]) ? "spot-removed" : "spot-unknown", Text.ph("name", args[1]));
            }
            case "clear" -> msg(sender, "spots-cleared", Text.ph("count", Integer.toString(spots.clear())));
            default -> {
                List<Spots.Spot> all = spots.all();
                if (all.isEmpty()) {
                    msg(sender, "spots-none", Text.ph("world", "any world"));
                    return;
                }
                msg(sender, "spot-list-header", Text.ph("count", Integer.toString(all.size())));
                for (Spots.Spot sp : all) {
                    sender.sendMessage(Text.mm(s().message("spot-list-entry"), Text.ph("name", sp.name()), Text.ph("world", sp.world()),
                            Text.ph("x", Integer.toString(sp.site().x())), Text.ph("y", Integer.toString(sp.site().y() + 1)),
                            Text.ph("z", Integer.toString(sp.site().z())), Text.ph("facing", sp.site().facingName())));
                }
            }
        }
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
            help(sender);
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
            double x = coord(a.get(0), o == null ? 0 : o.getX());
            double y = coord(a.get(1), o == null ? 0 : o.getY());
            double z = coord(a.get(2), o == null ? 0 : o.getZ());
            return new Location(w, x, y, z);
        } catch (NumberFormatException ex) {
            msg(sender, "bad-option", Text.ph("option", String.join(" ", a.subList(0, 3))));
            return null;
        }
    }

    private static double coord(String s, double base) {
        if (s.startsWith("~")) return base + (s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0);
        return Double.parseDouble(s);
    }

    private static Location nearestPlayer(Location at) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : at.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(at);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best == null ? null : best.getLocation();
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
        switch (sub) {
            case "start" -> {
                List<String> opts = new ArrayList<>(START_KEYS);
                if (args.length == 2) {
                    opts.addAll(List.of("here", "at", "spots"));
                    for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName());
                }
                if (args.length >= 3 && args[1].equalsIgnoreCase("at") && args.length <= 5) return List.of("~");
                return filter(opts, last);
            }
            case "portal" -> {
                if (args.length <= 4) {
                    List<String> o = new ArrayList<>(List.of("~", "facing="));
                    if (args.length == 2) o.add("here");
                    return filter(o, last);
                }
                if (args.length == 5) {
                    List<String> o = new ArrayList<>(List.of("facing=north", "facing=south", "facing=east", "facing=west"));
                    for (World w : Bukkit.getWorlds()) o.add(w.getName());
                    return filter(o, last);
                }
                return filter(List.of("facing=north", "facing=south", "facing=east", "facing=west"), last);
            }
            case "stop" -> {
                return args.length == 2 ? filter(List.of("now"), last) : List.of();
            }
            case "spot", "spots" -> {
                if (args.length == 2) return filter(List.of("add", "remove", "list", "clear"), last);
                if (args.length == 3 && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("delete"))) {
                    List<String> names = new ArrayList<>();
                    for (Spots.Spot sp : plugin.spots().all()) names.add(sp.name());
                    return filter(names, last);
                }
                return List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
