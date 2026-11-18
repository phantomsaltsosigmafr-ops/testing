package dev.unstablesmp.command;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.bot.BotGroup;
import dev.unstablesmp.bot.BotSpawner;
import dev.unstablesmp.bot.PvpMode;
import dev.unstablesmp.bot.SkillTier;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import dev.unstablesmp.game.GameService;
import dev.unstablesmp.kit.Kit;
import dev.unstablesmp.map.MapService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Simulator commands: bot, duel, kit, team, map, lobby, game, cam, scene, fake, fx, pack. Every player argument
 * accepts selectors (@p, @a[distance=..10], names), and positions come from where the command runs (works with
 * command blocks and /execute at). Options are positional or key=value.
 */
final class SimCommands {
    static final List<String> SUBS = List.of("bot", "duel", "kit", "team", "map", "lobby", "game", "cam", "scene", "fake", "fx", "pack");
    private final UnstableSMP plugin;

    SimCommands(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    static String perm(String sub) {
        return switch (sub) {
            case "cam", "scene", "fake", "fx" -> "uss.cine";
            default -> "uss." + sub;
        };
    }

    // ------------------------------------------------------------------ helpers

    /** Players from a selector, name, or "all". */
    List<Player> players(CommandSender s, String arg) {
        List<Player> out = new ArrayList<>();
        if (arg == null) {
            if (s instanceof Player p) out.add(p);
            return out;
        }
        if (arg.equalsIgnoreCase("all") || arg.equals("*")) {
            out.addAll(Bukkit.getOnlinePlayers());
            return out;
        }
        if (arg.startsWith("@")) {
            try {
                for (Entity e : Bukkit.selectEntities(s, arg)) if (e instanceof Player p) out.add(p);
            } catch (IllegalArgumentException ex) {
                plugin.messages().send(s, "bad-selector", Messages.p("selector", arg));
            }
            return out;
        }
        Player p = Bukkit.getPlayerExact(arg);
        if (p != null) out.add(p);
        return out;
    }

    private static Map<String, String> opts(String[] args, int from) {
        Map<String, String> m = new HashMap<>();
        for (int i = from; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq > 0) m.put(args[i].substring(0, eq).toLowerCase(Locale.ROOT), args[i].substring(eq + 1));
        }
        return m;
    }

    private static String pos(String[] args, int i) {
        return i < args.length && args[i].indexOf('=') < 0 ? args[i] : null;
    }

    private static int num(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static String rest(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, Math.min(from, args.length), args.length));
    }

    /** Resolves "here", "map[:spawn]", a selector, or "x,y,z" to a location. */
    private Location where(CommandSender s, Location here, String spec) {
        if (spec == null || spec.equalsIgnoreCase("here")) return here;
        if (spec.startsWith("@") || Bukkit.getPlayerExact(spec) != null) {
            List<Player> ps = players(s, spec);
            return ps.isEmpty() ? null : ps.get(0).getLocation();
        }
        String[] mp = spec.split(":");
        MapService.GameMap m = plugin.maps().get(mp[0]);
        if (m != null) return m.spawn(mp.length > 1 ? mp[1] : "bots");
        String[] xyz = spec.split(",");
        if (xyz.length == 3 && here != null) {
            try {
                return new Location(here.getWorld(), Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void msg(CommandSender s, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        plugin.messages().send(s, key, r);
    }

    // ------------------------------------------------------------------ dispatch

    void execute(String sub, CommandSender s, Location here, String[] args) {
        String a1 = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "bot" -> bot(s, here, a1, args);
            case "duel" -> duel(s, here, args);
            case "kit" -> kit(s, a1, args);
            case "team" -> team(s, a1, args);
            case "map" -> map(s, here, a1, args);
            case "lobby" -> {
                for (Player p : players(s, pos(args, 1))) plugin.games().toLobby(p);
            }
            case "game" -> game(s, a1, args);
            case "cam" -> cam(s, a1, args);
            case "scene" -> scene(s, a1, args);
            case "fake" -> fake(s, a1, args);
            case "fx" -> fx(s, a1, args);
            case "pack" -> {
                if (a1.equals("reload")) {
                    plugin.pack().reload(plugin.getConfig().getConfigurationSection("resource-pack"), plugin.messages().get("pack.prompt"));
                    msg(s, "pack.reloaded", Messages.p("count", Integer.toString(plugin.pack().skins().size())));
                } else {
                    List<Player> ps = players(s, pos(args, 2) != null ? pos(args, 2) : "@a");
                    for (Player p : ps) plugin.pack().send(p);
                    msg(s, plugin.pack().active() ? "pack.sent" : "pack.inactive", Messages.p("count", Integer.toString(ps.size())));
                }
            }
            default -> msg(s, "help");
        }
    }

    // ------------------------------------------------------------------ bots

    private void bot(CommandSender s, Location here, String a1, String[] args) {
        switch (a1) {
            case "spawn" -> {
                // /uss bot spawn <count> [group] [mode] [tier] [team=] [at=] [target=] [kit=] [leaders=] [spread=]
                int count = Math.max(1, num(pos(args, 2), 1));
                Map<String, String> o = opts(args, 2);
                BotGroup g = plugin.bots().group(o.getOrDefault("group", pos(args, 3) != null ? pos(args, 3) : "random"));
                if (g == null) {
                    msg(s, "bot.unknown-group", Messages.p("group", pos(args, 3)));
                    return;
                }
                String mode = o.getOrDefault("mode", pos(args, 4));
                String tier = o.getOrDefault("tier", pos(args, 5));
                Location at = where(s, here, o.get("at"));
                if (at == null) {
                    msg(s, "bot.no-location");
                    return;
                }
                BotSpawner.Request r = new BotSpawner.Request();
                r.group = g;
                r.at = at;
                r.mode = mode == null ? null : PvpMode.parse(mode);
                r.tier = tier == null || tier.contains("-") || tier.equalsIgnoreCase("random") ? null : SkillTier.parse(tier);
                r.team = o.get("team");
                r.kit = o.get("kit");
                r.skin = o.get("skin");
                r.name = o.get("name");
                r.announce = !"false".equalsIgnoreCase(o.get("announce")) && count <= 40;
                if (o.containsKey("target")) {
                    List<Player> ts = players(s, o.get("target"));
                    if (!ts.isEmpty()) r.target = ts.get(0);
                }
                List<Bot> made;
                if (tier != null && tier.contains("-")) {
                    made = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        r.tier = SkillTier.pick(tier);
                        made.addAll(plugin.botSpawner().spawnBatch(r, 1, i == 0 && !"false".equals(o.get("leaders")), num(o.get("spread"), 0) + 3));
                    }
                } else {
                    made = plugin.botSpawner().spawnBatch(r, count, !"false".equals(o.get("leaders")), num(o.get("spread"), (int) (3 + Math.sqrt(count))));
                }
                msg(s, "bot.spawned", Messages.p("count", Integer.toString(made.size())), Messages.p("group", g.id),
                        Messages.p("total", Integer.toString(plugin.bots().count())));
            }
            case "clear" -> {
                String f = pos(args, 2) == null ? "all" : pos(args, 2).toLowerCase(Locale.ROOT);
                int n = plugin.bots().clear(b -> f.equals("all") || b.group.id.equals(f) || b.team.equalsIgnoreCase(f)
                        || (b.map != null && b.map.equalsIgnoreCase(f)), "true".equals(opts(args, 2).get("announce")));
                msg(s, "bot.cleared", Messages.p("count", Integer.toString(n)));
            }
            case "list" -> {
                Map<String, int[]> by = new TreeMap<>();
                for (Bot b : plugin.bots().all()) {
                    if (b.removed) continue;
                    by.computeIfAbsent(b.group.id + " " + b.mode.id() + " " + b.tier, k -> new int[1])[0]++;
                }
                msg(s, "bot.list-header", Messages.p("count", Integer.toString(plugin.bots().count())), Messages.p("max", Integer.toString(plugin.bots().maxTotal())));
                for (Map.Entry<String, int[]> e : by.entrySet()) s.sendMessage(Text.mm("<gray> - <white>" + e.getKey() + "<gray> x" + e.getValue()[0]));
            }
            case "target" -> {
                String g = pos(args, 2);
                List<Player> ts = players(s, pos(args, 3));
                if (g == null || ts.isEmpty()) {
                    msg(s, "usage.bot");
                    return;
                }
                int n = 0, i = 0;
                for (Bot b : plugin.bots().all()) {
                    if (b.removed || !(g.equalsIgnoreCase("all") || b.group.id.equalsIgnoreCase(g) || b.team.equalsIgnoreCase(g))) continue;
                    b.forcedTarget = ts.get(i++ % ts.size());
                    n++;
                }
                msg(s, "bot.targeted", Messages.p("count", Integer.toString(n)));
            }
            case "untarget" -> {
                String g = pos(args, 2) == null ? "all" : pos(args, 2);
                for (Bot b : plugin.bots().all()) if (g.equalsIgnoreCase("all") || b.group.id.equalsIgnoreCase(g)) b.forcedTarget = null;
                msg(s, "bot.untargeted");
            }
            case "march", "tp" -> {
                String g = pos(args, 2);
                Location to = where(s, here, pos(args, 3));
                if (g == null || to == null) {
                    msg(s, "usage.bot");
                    return;
                }
                int n = 0;
                for (Bot b : plugin.bots().all()) {
                    if (b.removed || !(g.equalsIgnoreCase("all") || b.group.id.equalsIgnoreCase(g) || b.team.equalsIgnoreCase(g))) continue;
                    b.homeX = to.getX();
                    b.homeY = to.getY();
                    b.homeZ = to.getZ();
                    b.hasHome = true;
                    if (b.squad != null) b.squad.march = to.clone();
                    if (a1.equals("tp")) b.body.teleport(to.clone().add(Math.random() * 4 - 2, 0, Math.random() * 4 - 2));
                    n++;
                }
                msg(s, "bot.moved", Messages.p("count", Integer.toString(n)));
            }
            default -> msg(s, "usage.bot");
        }
    }

    private void duel(CommandSender s, Location here, String[] args) {
        // /uss duel [player] [mode] [tier] [group]
        List<Player> ps = players(s, pos(args, 1));
        if (ps.isEmpty()) {
            msg(s, "usage.duel");
            return;
        }
        BotGroup g = plugin.bots().group(pos(args, 4) == null ? "duelist" : pos(args, 4));
        if (g == null) g = plugin.bots().group("random");
        for (Player p : ps) {
            Location l = p.getLocation();
            double yaw = Math.toRadians(l.getYaw());
            Location at = dev.unstablesmp.core.Ground.groundAt(l.getWorld(), l.getX() - Math.sin(yaw) * 6, l.getY(), l.getZ() + Math.cos(yaw) * 6);
            at.setDirection(l.toVector().subtract(at.toVector()));
            BotSpawner.Request r = new BotSpawner.Request();
            r.group = g;
            r.at = at;
            r.mode = PvpMode.parse(pos(args, 2));
            r.tier = SkillTier.pick(pos(args, 3) == null ? "HT3" : pos(args, 3));
            r.target = p;
            r.team = "duel-" + p.getName().toLowerCase(Locale.ROOT);
            Bot b = plugin.botSpawner().spawn(r);
            if (b != null) {
                Kit k = plugin.kits().get(b.mode.id());
                if (k != null && !"false".equals(opts(args, 1).get("kit"))) k.apply(p, true);
                msg(p, "duel.start", Messages.c("bot", b.name), Messages.p("mode", b.mode.id()), Messages.p("tier", b.tier.name()));
            }
        }
    }

    // ------------------------------------------------------------------ kits & teams

    private void kit(CommandSender s, String a1, String[] args) {
        switch (a1) {
            case "give" -> {
                Kit k = plugin.kits().get(pos(args, 2));
                if (k == null) {
                    msg(s, "kit.unknown", Messages.p("kit", String.valueOf(pos(args, 2))));
                    return;
                }
                List<Player> ps = players(s, pos(args, 3));
                for (Player p : ps) k.apply(p, true);
                msg(s, "kit.given", Messages.p("kit", k.name), Messages.p("count", Integer.toString(ps.size())));
            }
            case "save" -> {
                if (!(s instanceof Player p) || pos(args, 2) == null) {
                    msg(s, "usage.kit");
                    return;
                }
                plugin.kits().put(Kit.capture(pos(args, 2).toLowerCase(Locale.ROOT), p));
                msg(s, "kit.saved", Messages.p("kit", pos(args, 2)));
            }
            case "delete" -> msg(s, plugin.kits().remove(String.valueOf(pos(args, 2))) ? "kit.deleted" : "kit.unknown", Messages.p("kit", String.valueOf(pos(args, 2))));
            case "list" -> msg(s, "kit.list", Messages.p("kits", String.join(", ", plugin.kits().names())));
            default -> msg(s, "usage.kit");
        }
    }

    private void team(CommandSender s, String a1, String[] args) {
        switch (a1) {
            case "join" -> {
                String t = pos(args, 2);
                if (t == null) {
                    msg(s, "usage.team");
                    return;
                }
                List<Player> ps = players(s, pos(args, 3));
                for (Player p : ps) {
                    plugin.teams().join(p, t);
                    msg(p, "team.joined", Messages.p("team", t));
                }
            }
            case "leave" -> {
                for (Player p : players(s, pos(args, 2))) {
                    plugin.teams().leave(p);
                    msg(p, "team.left");
                }
            }
            case "gui" -> {
                List<Player> ps = players(s, pos(args, 2));
                for (Player p : ps) plugin.gui().openTeams(p);
            }
            case "list" -> msg(s, "team.list", Messages.p("teams", String.join(", ", plugin.teams().ids())));
            default -> msg(s, "usage.team");
        }
    }

    // ------------------------------------------------------------------ maps & games

    private void map(CommandSender s, Location here, String a1, String[] args) {
        String name = pos(args, 2);
        switch (a1) {
            case "create" -> {
                if (name == null || here == null) {
                    msg(s, "usage.map");
                    return;
                }
                plugin.maps().create(name, here);
                msg(s, "map.created", Messages.p("map", name));
            }
            case "delete" -> msg(s, plugin.maps().delete(String.valueOf(name)) ? "map.deleted" : "map.unknown", Messages.p("map", String.valueOf(name)));
            case "setspawn", "addspawn" -> {
                MapService.GameMap m = plugin.maps().get(name);
                String key = pos(args, 3) == null ? "players" : pos(args, 3);
                if (m == null || here == null) {
                    msg(s, "map.unknown", Messages.p("map", String.valueOf(name)));
                    return;
                }
                plugin.maps().addSpawn(m, key, here, a1.equals("setspawn"));
                msg(s, "map.spawn-set", Messages.p("map", m.name), Messages.p("key", key), Messages.p("count", Integer.toString(m.spawns.get(key.toLowerCase(Locale.ROOT)).size())));
            }
            case "setexit", "pos1", "pos2" -> {
                MapService.GameMap m = plugin.maps().get(name);
                if (m == null || here == null) {
                    msg(s, "map.unknown", Messages.p("map", String.valueOf(name)));
                    return;
                }
                if (a1.equals("setexit")) m.exit = here.clone();
                else if (a1.equals("pos1")) m.pos1 = here.clone();
                else m.pos2 = here.clone();
                plugin.maps().save();
                msg(s, "map.updated", Messages.p("map", m.name), Messages.p("what", a1));
            }
            case "setlobby" -> {
                if (here == null) return;
                plugin.maps().setLobby(here);
                msg(s, "map.lobby-set");
            }
            case "tp" -> {
                // /uss map tp <map> [spawn] [targets] [kit=] [team=]
                Map<String, String> o = opts(args, 2);
                List<Player> ps = players(s, pos(args, 4));
                for (Player p : ps) plugin.games().sendToMap(p, String.valueOf(name), pos(args, 3), o.get("kit"), o.get("team"));
            }
            case "link" -> {
                if (!(s instanceof Player p) || name == null) {
                    msg(s, "usage.map");
                    return;
                }
                Block b = p.getTargetBlockExact(6);
                if (b == null || plugin.maps().get(name) == null) {
                    msg(s, "map.link-failed");
                    return;
                }
                Map<String, String> o = opts(args, 3);
                plugin.maps().link(b, new MapService.Link(name, pos(args, 3) == null ? "players" : pos(args, 3), o.getOrDefault("kit", ""), o.getOrDefault("team", "")));
                msg(s, "map.linked", Messages.p("map", name), Messages.p("block", b.getType().name().toLowerCase(Locale.ROOT)));
            }
            case "unlink" -> {
                if (s instanceof Player p) {
                    Block b = p.getTargetBlockExact(6);
                    msg(s, b != null && plugin.maps().unlink(b) ? "map.unlinked" : "map.link-failed");
                }
            }
            case "list" -> msg(s, "map.list", Messages.p("maps", String.join(", ", plugin.maps().names())));
            default -> msg(s, "usage.map");
        }
    }

    private void game(CommandSender s, String a1, String[] args) {
        switch (a1) {
            case "start" -> {
                // /uss game start <type> <map> [group=] [b=] [count=] [waves=] [inc=] [mode=] [tier=] [kit=] [team=] [players=@a]
                GameService.Type type;
                try {
                    type = GameService.Type.valueOf(String.valueOf(pos(args, 2)).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    msg(s, "usage.game");
                    return;
                }
                Map<String, String> o = opts(args, 2);
                GameService.Options op = new GameService.Options();
                op.group = o.getOrDefault("group", type == GameService.Type.BATTLE ? "null" : "law");
                op.groupB = o.getOrDefault("b", "law");
                op.count = num(o.get("count"), op.count);
                op.waves = num(o.get("waves"), op.waves);
                op.increment = num(o.get("inc"), op.increment);
                op.mode = o.get("mode");
                op.tier = o.get("tier");
                op.kit = o.get("kit");
                op.team = o.getOrDefault("team", "players");
                op.leaders = !"false".equals(o.get("leaders"));
                List<Player> ps = o.containsKey("players") ? players(s, o.get("players")) : (s instanceof Player p ? List.of(p) : List.of());
                String err = plugin.games().start(type, String.valueOf(pos(args, 3)), op, ps);
                if (err != null) msg(s, err, Messages.p("map", String.valueOf(pos(args, 3))));
                else msg(s, "game.started", Messages.p("type", type.name()), Messages.p("map", String.valueOf(pos(args, 3))));
            }
            case "stop" -> {
                String m = pos(args, 2);
                int n = m == null || m.equalsIgnoreCase("all") ? plugin.games().stopAll() : (plugin.games().stop(m) ? 1 : 0);
                msg(s, "game.stopped-count", Messages.p("count", Integer.toString(n)));
            }
            case "list" -> msg(s, "game.list", Messages.p("games", String.join(", ", plugin.games().running())));
            default -> msg(s, "usage.game");
        }
    }

    // ------------------------------------------------------------------ cinematics

    private void cam(CommandSender s, String a1, String[] args) {
        Player self = s instanceof Player p ? p : null;
        switch (a1) {
            case "add" -> {
                if (self == null) return;
                int n = plugin.camera().add(self, num(pos(args, 2), 60));
                msg(s, "cam.added", Messages.p("count", Integer.toString(n)));
            }
            case "undo" -> {
                if (self != null) msg(s, "cam.added", Messages.p("count", Integer.toString(plugin.camera().undo(self))));
            }
            case "clear" -> {
                if (self != null) plugin.camera().clear(self);
                msg(s, "cam.cleared");
            }
            case "save" -> {
                if (self == null || pos(args, 2) == null) return;
                msg(s, plugin.camera().save(self, pos(args, 2)) ? "cam.saved" : "cam.too-short", Messages.p("path", pos(args, 2)));
            }
            case "play" -> {
                List<Player> ps = players(s, pos(args, 3));
                msg(s, plugin.camera().play(String.valueOf(pos(args, 2)), ps) ? "cam.playing" : "cam.unknown", Messages.p("path", String.valueOf(pos(args, 2))));
            }
            case "stop" -> {
                for (Player p : players(s, pos(args, 2))) plugin.camera().stop(p);
            }
            case "delete" -> msg(s, plugin.camera().delete(String.valueOf(pos(args, 2))) ? "cam.deleted" : "cam.unknown", Messages.p("path", String.valueOf(pos(args, 2))));
            case "list" -> msg(s, "cam.list", Messages.p("paths", String.join(", ", plugin.camera().names())));
            default -> msg(s, "usage.cam");
        }
    }

    private void scene(CommandSender s, String a1, String[] args) {
        switch (a1) {
            case "play" -> {
                List<Player> ps = players(s, pos(args, 3));
                msg(s, plugin.scenes().play(String.valueOf(pos(args, 2)), ps) ? "scene.playing" : "scene.unknown", Messages.p("scene", String.valueOf(pos(args, 2))));
            }
            case "stop" -> msg(s, "scene.stopped", Messages.p("count", Integer.toString(plugin.scenes().stopAll())));
            case "reload" -> {
                plugin.scenes().reload();
                msg(s, "scene.list", Messages.p("scenes", String.join(", ", plugin.scenes().names())));
            }
            case "list" -> msg(s, "scene.list", Messages.p("scenes", String.join(", ", plugin.scenes().names())));
            default -> msg(s, "usage.scene");
        }
    }

    private void fake(CommandSender s, String a1, String[] args) {
        String name = pos(args, 2);
        if (name == null) {
            msg(s, "usage.fake");
            return;
        }
        var nameC = Text.mm(name);
        switch (a1) {
            case "chat" -> plugin.fakeChat().broadcast(plugin.fakeChat().chatLine(nameC, Text.mm(rest(args, 3))));
            case "join" -> plugin.fakeChat().broadcast(plugin.fakeChat().joinLine(nameC));
            case "leave" -> plugin.fakeChat().broadcast(plugin.fakeChat().leaveLine(nameC));
            case "death" -> plugin.fakeChat().broadcast(plugin.fakeChat().deathLine(nameC, pos(args, 3) == null ? null : Text.mm(pos(args, 3))));
            default -> msg(s, "usage.fake");
        }
    }

    private void fx(CommandSender s, String a1, String[] args) {
        switch (a1) {
            case "fade" -> {
                String mode = String.valueOf(pos(args, 2));
                List<Player> ps = players(s, pos(args, 3));
                if (mode.equalsIgnoreCase("out")) plugin.cinematics().fadeOut(ps, num(pos(args, 4), 40));
                else plugin.cinematics().fadeIn(ps);
            }
            case "shake" -> plugin.cinematics().shake(players(s, pos(args, 2)), num(pos(args, 3), 20), num(pos(args, 4), 2));
            case "slowmo" -> plugin.cinematics().slowmo(num(pos(args, 2), 6), num(pos(args, 3), 60));
            case "reset" -> plugin.cinematics().resetTickRate();
            case "title" -> {
                // /uss fx title <targets> <title mini> [| subtitle mini]
                List<Player> ps = players(s, pos(args, 2));
                String[] parts = rest(args, 3).split("\\|", 2);
                plugin.cinematics().title(ps, Text.mm(parts[0].trim()), parts.length > 1 ? Text.mm(parts[1].trim()) : Text.mm(""), 10, 60, 15);
            }
            default -> msg(s, "usage.fx");
        }
    }

    // ------------------------------------------------------------------ tab completion

    Collection<String> suggest(String sub, String[] args) {
        List<String> out = new ArrayList<>();
        int i = args.length - 1;
        List<String> players = new ArrayList<>(List.of("@p", "@a", "@s", "@r"));
        for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
        List<String> groups = new ArrayList<>(plugin.bots().groups().keySet());
        List<String> modes = new ArrayList<>();
        for (PvpMode m : PvpMode.values()) modes.add(m.id());
        modes.add("random");
        List<String> tiers = new ArrayList<>();
        for (SkillTier t : SkillTier.values()) tiers.add(t.name());
        tiers.addAll(List.of("random", "HT1-LT2", "HT3-LT6"));
        switch (sub) {
            case "bot" -> {
                if (i == 1) out.addAll(List.of("spawn", "clear", "list", "target", "untarget", "march", "tp"));
                else if (args[1].equalsIgnoreCase("spawn")) {
                    if (i == 2) out.addAll(List.of("1", "10", "50", "100", "250", "500"));
                    else if (i == 3) out.addAll(groups);
                    else if (i == 4) out.addAll(modes);
                    else if (i == 5) out.addAll(tiers);
                    else out.addAll(List.of("team=", "at=here", "at=", "target=@p", "kit=", "leaders=false", "spread=", "announce=false", "name=", "skin="));
                } else if (i == 2) {
                    out.addAll(groups);
                    out.add("all");
                } else if (i == 3) out.addAll(players);
            }
            case "duel" -> {
                if (i == 1) out.addAll(players);
                else if (i == 2) out.addAll(modes);
                else if (i == 3) out.addAll(tiers);
                else if (i == 4) out.addAll(groups);
            }
            case "kit" -> {
                if (i == 1) out.addAll(List.of("give", "save", "delete", "list"));
                else if (i == 2) out.addAll(plugin.kits().names());
                else if (i == 3) out.addAll(players);
            }
            case "team" -> {
                if (i == 1) out.addAll(List.of("join", "leave", "gui", "list"));
                else if (i == 2 && args[1].equalsIgnoreCase("join")) out.addAll(plugin.teams().ids());
                else out.addAll(players);
            }
            case "map" -> {
                if (i == 1) out.addAll(List.of("create", "delete", "setspawn", "addspawn", "setexit", "pos1", "pos2", "setlobby", "tp", "link", "unlink", "list"));
                else if (i == 2) out.addAll(plugin.maps().names());
                else if (i == 3) out.addAll(List.of("players", "bots", "a", "b", "red", "blue"));
                else if (i == 4) out.addAll(players);
                else out.addAll(List.of("kit=", "team="));
            }
            case "lobby" -> out.addAll(players);
            case "game" -> {
                if (i == 1) out.addAll(List.of("start", "stop", "list"));
                else if (i == 2 && args[1].equalsIgnoreCase("start")) {
                    for (GameService.Type t : GameService.Type.values()) out.add(t.name().toLowerCase(Locale.ROOT));
                } else if (i == 3 || (i == 2 && args[1].equalsIgnoreCase("stop"))) out.addAll(plugin.maps().names());
                else out.addAll(List.of("group=", "b=", "count=", "waves=", "inc=", "mode=", "tier=", "kit=", "team=", "players=@a", "leaders=false"));
            }
            case "cam" -> {
                if (i == 1) out.addAll(List.of("add", "undo", "clear", "save", "play", "stop", "delete", "list"));
                else if (i == 2) out.addAll(plugin.camera().names());
                else out.addAll(players);
            }
            case "scene" -> {
                if (i == 1) out.addAll(List.of("play", "stop", "list", "reload"));
                else if (i == 2) out.addAll(plugin.scenes().names());
                else out.addAll(players);
            }
            case "fake" -> {
                if (i == 1) out.addAll(List.of("chat", "join", "leave", "death"));
            }
            case "fx" -> {
                if (i == 1) out.addAll(List.of("fade", "shake", "slowmo", "reset", "title"));
                else if (i == 2 && args[1].equalsIgnoreCase("fade")) out.addAll(List.of("out", "in"));
                else out.addAll(players);
            }
            case "pack" -> {
                if (i == 1) out.addAll(List.of("send", "reload"));
                else out.addAll(players);
            }
            default -> {
            }
        }
        return out;
    }
}
