package dev.unstablesmp.game;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.bot.BotGroup;
import dev.unstablesmp.bot.BotSpawner;
import dev.unstablesmp.bot.PvpMode;
import dev.unstablesmp.bot.SkillTier;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Ticker;
import dev.unstablesmp.kit.Kit;
import dev.unstablesmp.map.MapService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Command-block friendly "event" games played on maps: WAVES (2v1000-style endless LAW), BATTLE (bot group vs
 * bot group, e.g. 250 NULL vs 250 LAW), TDM (players + ally bots vs bots), FFA, BOSS (kill the leader) and DUEL.
 */
public final class GameService implements Ticker.Tickable, Listener {
    public enum Type { WAVES, BATTLE, TDM, FFA, BOSS, DUEL }

    /** Start options (all optional except type/map). */
    public static final class Options {
        public String group = "law";
        public String groupB = "null";
        public int count = 10;
        public int increment = 5;
        public int waves = 10;
        public String mode;
        public String tier;
        public String kit;
        public String team = "players";
        public boolean leaders = true;
    }

    private static final class Session {
        final String id;
        final Type type;
        final MapService.GameMap map;
        final Options o;
        final Set<UUID> players = new HashSet<>();
        final Set<UUID> alive = new HashSet<>();
        final BossBar bar;
        int state; // 0 countdown, 1 running, 2 ending
        int stateAt;
        int wave;
        int nextWaveAt = -1;
        Bot boss;
        String result = "";

        Session(String id, Type type, MapService.GameMap map, Options o) {
            this.id = id;
            this.type = type;
            this.map = map;
            this.o = o;
            this.bar = BossBar.bossBar(Component.text(type.name()), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        }
    }

    private final UnstableSMP plugin;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private int countdownTicks = 100;
    private int endDelay = 100;

    public GameService(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void reload(org.bukkit.configuration.ConfigurationSection c) {
        if (c == null) return;
        countdownTicks = Math.max(0, c.getInt("countdown-seconds", 5) * 20);
        endDelay = Math.max(20, c.getInt("end-delay-seconds", 5) * 20);
    }

    public Collection<String> running() {
        return sessions.keySet();
    }

    // ------------------------------------------------------------------ lobby / map travel

    /** Teleports a player to a map spawn, optionally giving a kit and team, and joins any game running there. */
    public boolean sendToMap(Player p, String mapName, String spawn, String kit, String team) {
        MapService.GameMap m = plugin.maps().get(mapName);
        if (m == null) {
            plugin.messages().send(p, "map.unknown", Messages.p("map", mapName));
            return false;
        }
        Location at = m.spawn(spawn == null || spawn.isBlank() ? "players" : spawn);
        if (at == null) {
            plugin.messages().send(p, "map.no-spawn", Messages.p("map", mapName));
            return false;
        }
        if (team != null && !team.isBlank()) plugin.teams().join(p, team);
        if (kit != null && !kit.isBlank()) {
            Kit k = plugin.kits().get(kit);
            if (k != null) k.apply(p, true);
        }
        p.teleport(at);
        p.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
        Session s = sessions.get(m.name);
        if (s != null && s.state < 2) {
            s.players.add(p.getUniqueId());
            s.alive.add(p.getUniqueId());
            p.showBossBar(s.bar);
        }
        return true;
    }

    public void toLobby(Player p) {
        Location l = plugin.maps().lobby();
        if (l != null) p.teleport(l);
        else p.teleport(p.getWorld().getSpawnLocation());
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts a game; returns null on success or a messages.yml key. */
    public String start(Type type, String mapName, Options o, Collection<Player> participants) {
        MapService.GameMap m = plugin.maps().get(mapName);
        if (m == null) return "map.unknown";
        if (sessions.containsKey(m.name)) return "game.already-running";
        Session s = new Session(m.name, type, m, o);
        sessions.put(m.name, s);
        s.stateAt = Bukkit.getCurrentTick() + countdownTicks;
        for (Player p : participants) {
            sendToMap(p, m.name, o.team != null && !o.team.equals("players") ? o.team : "players", o.kit, o.team);
            s.players.add(p.getUniqueId());
            s.alive.add(p.getUniqueId());
            p.showBossBar(s.bar);
        }
        s.bar.name(plugin.messages().get("game.bar-countdown", Messages.p("type", type.name())));
        return null;
    }

    public boolean stop(String mapName) {
        Session s = sessions.get(mapName == null ? "" : mapName.toLowerCase(Locale.ROOT));
        if (s == null) return false;
        end(s, plugin.messages().get("game.stopped"), true);
        return true;
    }

    public int stopAll() {
        int n = sessions.size();
        for (Session s : new ArrayList<>(sessions.values())) end(s, plugin.messages().get("game.stopped"), true);
        return n;
    }

    @Override
    public void tick(int now) {
        if (sessions.isEmpty()) return;
        for (Session s : new ArrayList<>(sessions.values())) {
            try {
                tickSession(s, now);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Game " + s.id + " error: " + ex);
            }
        }
    }

    private void tickSession(Session s, int now) {
        if (s.state == 0) {
            int left = s.stateAt - now;
            if (left > 0 && left % 20 == 0) titleAll(s, plugin.messages().get("game.countdown", Messages.p("seconds", Integer.toString(left / 20))), Component.empty(), Sound.BLOCK_NOTE_BLOCK_HAT);
            if (left <= 0) begin(s, now);
            return;
        }
        if (s.state == 2) {
            if (now >= s.stateAt) finish(s);
            return;
        }
        if (now % 10 != 0) return;
        int[] counts = count(s);
        switch (s.type) {
            case WAVES -> {
                if (counts[0] == 0) {
                    if (s.nextWaveAt < 0) s.nextWaveAt = now + 60;
                    else if (now >= s.nextWaveAt) {
                        if (s.wave >= s.o.waves) {
                            end(s, plugin.messages().get("game.win"), false);
                            return;
                        }
                        wave(s);
                    }
                }
                s.bar.name(plugin.messages().get("game.bar-waves", Messages.p("wave", Integer.toString(s.wave)),
                        Messages.p("waves", Integer.toString(s.o.waves)), Messages.p("left", Integer.toString(counts[0]))));
                s.bar.progress(Math.max(0f, Math.min(1f, s.wave / (float) Math.max(1, s.o.waves))));
            }
            case BATTLE -> {
                s.bar.name(plugin.messages().get("game.bar-battle", Messages.p("side_a", s.o.group), Messages.p("side_b", s.o.groupB),
                        Messages.p("ca", Integer.toString(counts[1])), Messages.p("cb", Integer.toString(counts[2]))));
                if (counts[1] == 0 || counts[2] == 0) {
                    String winner = counts[1] == 0 && counts[2] == 0 ? "-" : counts[1] == 0 ? s.o.groupB : s.o.group;
                    end(s, plugin.messages().get("game.battle-won", Messages.p("winner", winner.toUpperCase(Locale.ROOT))), false);
                    return;
                }
            }
            case BOSS -> {
                s.bar.name(plugin.messages().get("game.bar-boss", Messages.p("left", Integer.toString(counts[0]))));
                if (s.boss != null) s.bar.progress((float) Math.max(0, Math.min(1, s.boss.valid() ? s.boss.healthFraction() : 0)));
                if (s.boss == null || !s.boss.valid()) {
                    end(s, plugin.messages().get("game.win"), false);
                    return;
                }
            }
            default -> {
                s.bar.name(plugin.messages().get("game.bar-generic", Messages.p("type", s.type.name()), Messages.p("left", Integer.toString(counts[0]))));
                if (counts[0] == 0) {
                    end(s, plugin.messages().get("game.win"), false);
                    return;
                }
            }
        }
        if (!s.players.isEmpty() && s.alive.isEmpty() && s.type != Type.BATTLE) end(s, plugin.messages().get("game.lose"), false);
    }

    private void begin(Session s, int now) {
        s.state = 1;
        titleAll(s, plugin.messages().get("game.start-title", Messages.p("type", s.type.name())),
                plugin.messages().get("game.start-subtitle", Messages.p("map", s.map.name)), Sound.EVENT_RAID_HORN);
        switch (s.type) {
            case WAVES -> wave(s);
            case BATTLE -> {
                spawn(s, s.o.group, s.o.count, "a", null, s.o.leaders);
                spawn(s, s.o.groupB, s.o.count, "b", null, s.o.leaders);
            }
            case TDM -> spawn(s, s.o.group, s.o.count, "bots", null, s.o.leaders);
            case FFA -> {
                for (int i = 0; i < s.o.count; i++) spawn(s, s.o.group, 1, "bots", "ffa" + i, false);
            }
            case BOSS -> {
                List<Bot> made = spawn(s, s.o.group, s.o.count, "bots", null, true);
                for (Bot b : made) if (b.leader) s.boss = b;
                if (s.boss == null && !made.isEmpty()) s.boss = made.get(0);
            }
            case DUEL -> {
                for (UUID id : s.players) {
                    Player p = Bukkit.getPlayer(id);
                    List<Bot> made = spawn(s, s.o.group, 1, "bots", null, false);
                    if (p != null) for (Bot b : made) b.forcedTarget = p;
                }
            }
        }
    }

    private void wave(Session s) {
        s.wave++;
        s.nextWaveAt = -1;
        int n = s.o.count + (s.wave - 1) * s.o.increment;
        spawn(s, s.o.group, n, "bots", null, s.wave == s.o.waves && s.o.leaders);
        titleAll(s, plugin.messages().get("game.wave-title", Messages.p("wave", Integer.toString(s.wave))),
                plugin.messages().get("game.wave-subtitle", Messages.p("count", Integer.toString(n)), Messages.p("group", s.o.group)), Sound.ENTITY_WITHER_SPAWN);
    }

    private List<Bot> spawn(Session s, String groupId, int count, String spawnKey, String teamOverride, boolean leaders) {
        BotGroup g = plugin.bots().group(groupId);
        if (g == null) g = plugin.bots().group("random");
        Location at = s.map.spawn(spawnKey);
        if (at == null) at = s.map.spawn("bots");
        if (at == null) return List.of();
        BotSpawner.Request r = new BotSpawner.Request();
        r.group = g;
        r.at = at;
        r.map = s.id;
        r.team = teamOverride;
        r.mode = s.o.mode == null ? null : PvpMode.parse(s.o.mode);
        r.tier = s.o.tier == null ? null : SkillTier.pick(s.o.tier);
        r.announce = count <= 30;
        List<Bot> out = new ArrayList<>();
        // Spread big spawns across all spawn points for this key.
        List<Location> points = s.map.spawns.getOrDefault(spawnKey, List.of(at));
        int per = Math.max(1, (int) Math.ceil(count / (double) Math.max(1, points.size())));
        int left = count;
        boolean first = true;
        for (Location p : points.isEmpty() ? List.of(at) : points) {
            if (left <= 0) break;
            r.at = p.clone();
            if (s.o.tier != null && s.o.tier.contains("-")) r.tier = null;
            int n = Math.min(per, left);
            out.addAll(plugin.botSpawner().spawnBatch(r, n, leaders && first, 4 + Math.sqrt(n)));
            left -= n;
            first = false;
        }
        return out;
    }

    /** {sessionBots, sideA, sideB}. */
    private int[] count(Session s) {
        int all = 0, a = 0, b = 0;
        BotGroup ga = plugin.bots().group(s.o.group), gb = plugin.bots().group(s.o.groupB);
        for (Bot bot : plugin.bots().all()) {
            if (bot.removed || !s.id.equals(bot.map)) continue;
            all++;
            if (ga != null && bot.group == ga) a++;
            else if (gb != null && bot.group == gb) b++;
        }
        return new int[] {all, a, b};
    }

    private void end(Session s, Component result, boolean immediate) {
        if (s.state == 2 && !immediate) return;
        s.state = 2;
        s.stateAt = Bukkit.getCurrentTick() + (immediate ? 0 : endDelay);
        titleAll(s, result, Component.empty(), Sound.UI_TOAST_CHALLENGE_COMPLETE);
        Bukkit.getServer().sendMessage(plugin.messages().get("game.ended", Messages.p("map", s.map.name), Messages.c("result", result)));
        if (immediate) finish(s);
    }

    private void finish(Session s) {
        sessions.remove(s.id);
        plugin.bots().clear(b -> s.id.equals(b.map), false);
        for (UUID id : s.players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.hideBossBar(s.bar);
            if (s.map.exit != null) p.teleport(s.map.exit);
            else toLobby(p);
        }
    }

    private void titleAll(Session s, Component title, Component sub, Sound sound) {
        Title t = Title.title(title, sub, Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400)));
        for (UUID id : s.players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.showTitle(t);
            p.playSound(p.getLocation(), sound, 1f, 1f);
        }
    }

    public void onBotDeath(Bot b, Player killer) {
        // Counting happens on the next session tick; kept as a hook for future stats.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        for (Session s : sessions.values()) {
            if (s.alive.remove(e.getEntity().getUniqueId())) {
                e.getEntity().hideBossBar(s.bar);
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        for (Session s : sessions.values()) {
            if (s.players.contains(e.getPlayer().getUniqueId())) {
                Location exit = s.map.exit != null ? s.map.exit : plugin.maps().lobby();
                if (exit != null) e.setRespawnLocation(exit);
                if (e.getPlayer().getGameMode() == GameMode.SPECTATOR) e.getPlayer().setGameMode(GameMode.SURVIVAL);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        for (Session s : sessions.values()) s.alive.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public String name() {
        return "games";
    }
}
