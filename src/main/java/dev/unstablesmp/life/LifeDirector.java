package dev.unstablesmp.life;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import dev.unstablesmp.portal.SpawnCommand;
import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Residents: VexBot bots that live on the server like players. They log on and off through the day (more at peak
 * hours), each has its own home it keeps between sessions, and while online they move between activities - hanging
 * around home, mining down near it, visiting hotspots, exploring, going AFK - by running VexBot commands. They greet,
 * say bye, welcome real players and chat now and then, typing through the normal chat so they get the same formatting.
 */
public final class LifeDirector implements Listener {

    static final class Resident {
        final String name;
        String world;
        int hx, hy, hz;
        boolean hasHome;
        boolean online;
        int joinedAt;
        int sessionEnd;
        String activity = "";
        int activityEnd;

        Resident(String name) {
            this.name = name;
        }
    }

    private record Pending(int at, Runnable action) {
    }

    private final Logger log;
    private final File file;
    private LifeConfig cfg;
    private boolean running;
    private final Map<String, Resident> residents = new LinkedHashMap<>();
    /** Names of resident bots that are connected right now (until they actually quit), lower case. */
    private final java.util.Set<String> connected = new java.util.HashSet<>();
    private final List<Pending> pending = new ArrayList<>();
    private int now;
    private int target;
    private int retargetAt;
    private boolean lastPeak;
    private int nextJoin;
    private int nextLeave;
    private int lastIdleChat = -100000;

    public LifeDirector(File dataFolder, Logger log, LifeConfig cfg) {
        this.log = log;
        this.file = new File(dataFolder, "residents.yml");
        this.cfg = cfg;
        load();
    }

    public void config(LifeConfig cfg) {
        this.cfg = cfg;
        retargetAt = 0;
    }

    public boolean running() {
        return running;
    }

    public int target() {
        return target;
    }

    public List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Resident r : residents.values()) if (r.online) out.add(r.name + " (" + (r.activity.isEmpty() ? "joining" : r.activity) + ")");
        return out;
    }

    // ------------------------------------------------------------------ on / off

    /** Starts the population: runs the one-off setup commands and lets residents start logging on. */
    public void start() {
        if (running) return;
        running = true;
        run(cfg.setupCommands, Map.of());
        retargetAt = 0;
        nextJoin = now + 40;
        // bots VexBot kept from before a restart carry on as residents
        for (Resident r : residents.values()) {
            if (Bukkit.getPlayerExact(r.name) != null && !r.online) beginSession(r, false);
        }
    }

    /** Everyone says bye (sometimes) and logs off over the next half minute. */
    public void stop() {
        if (!running) return;
        running = false;
        int delay = 20;
        for (Resident r : residents.values()) {
            if (!r.online) continue;
            leave(r, delay);
            delay += 20 + ThreadLocalRandom.current().nextInt(80);
        }
    }

    /** Plugin disable: forget pending work (VexBot decides whether its bots stay). */
    public void shutdown() {
        pending.clear();
        save();
    }

    // ------------------------------------------------------------------ tick

    public void tick(int tick) {
        now = tick;
        runPending();
        if (now % 20 != 0 || !running) return;
        refreshOnline();
        boolean peak = cfg.isPeak(LocalTime.now().getHour());
        if (now >= retargetAt || peak != lastPeak) {
            lastPeak = peak;
            target = (peak ? cfg.peakPopulation : cfg.population).random();
            retargetAt = now + 20 * 60 * (10 + ThreadLocalRandom.current().nextInt(11));
        }
        int online = countOnline();
        if (online < target && now >= nextJoin) {
            Resident r = pickOffline();
            if (r != null) beginSession(r, true);
            nextJoin = now + 20 * cfg.joinGapSeconds.random();
        } else if (online > target && now >= nextLeave) {
            Resident r = earliestSessionEnd();
            if (r != null) leave(r, 0);
            nextLeave = now + 20 * cfg.joinGapSeconds.random();
        }
        for (Resident r : residents.values()) {
            if (!r.online) continue;
            if (now >= r.sessionEnd) {
                leave(r, 0);
                continue;
            }
            if (now >= r.activityEnd) nextActivity(r);
            idleChat(r);
        }
    }

    private void runPending() {
        if (pending.isEmpty()) return;
        List<Pending> due = new ArrayList<>();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.at() <= now) {
                due.add(p);
                it.remove();
            }
        }
        for (Pending p : due) {
            try {
                p.action().run();
            } catch (RuntimeException ex) {
                log.warning("Residents: " + ex);
            }
        }
    }

    private void later(int ticks, Runnable r) {
        pending.add(new Pending(now + Math.max(1, ticks), r));
    }

    /** A resident whose bot is gone (killed, kicked) is offline; one that is online without us knowing is adopted. */
    private void refreshOnline() {
        for (Resident r : residents.values()) {
            boolean there = Bukkit.getPlayerExact(r.name) != null;
            if (r.online && !there && now - r.joinedAt > 200) r.online = false;
        }
    }

    private int countOnline() {
        int n = 0;
        for (Resident r : residents.values()) if (r.online) n++;
        return n;
    }

    private Resident pickOffline() {
        List<Resident> free = new ArrayList<>();
        for (String name : cfg.names) {
            Resident r = residents.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new Resident(name));
            if (!r.online && Bukkit.getPlayerExact(name) == null) free.add(r);
        }
        if (free.isEmpty()) return null;
        // regulars (who already have a home) come back more often than brand-new names
        List<Resident> regulars = new ArrayList<>();
        for (Resident r : free) if (r.hasHome) regulars.add(r);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Resident> pool = !regulars.isEmpty() && rnd.nextDouble() < 0.75 ? regulars : free;
        return pool.get(rnd.nextInt(pool.size()));
    }

    private Resident earliestSessionEnd() {
        Resident best = null;
        for (Resident r : residents.values()) if (r.online && (best == null || r.sessionEnd < best.sessionEnd)) best = r;
        return best;
    }

    // ------------------------------------------------------------------ sessions

    private void beginSession(Resident r, boolean spawn) {
        if (!ensureHome(r)) return;
        r.online = true;
        r.joinedAt = now;
        r.sessionEnd = now + 20 * 60 * cfg.sessionMinutes.random();
        r.activity = "";
        r.activityEnd = now + 100;
        connected.add(r.name.toLowerCase(Locale.ROOT));
        if (spawn) {
            Map<String, String> v = values(r, r.hx, groundY(r) + 1, r.hz);
            run(cfg.joinCommands, v);
            later(40, () -> run(cfg.afterJoinCommands, values(r, r.hx, r.hy, r.hz)));
            if (cfg.chat && chance(cfg.greetChance)) later(20 * cfg.chatDelaySeconds.random() + 40, () -> say(r, pick(cfg.greetings), null));
        }
        save();
    }

    private void leave(Resident r, int delay) {
        if (!r.online) return;
        r.online = false;
        int bye = delay;
        if (cfg.chat && chance(cfg.byeChance)) {
            later(Math.max(1, delay), () -> say(r, pick(cfg.goodbyes), null));
            bye += 40 + ThreadLocalRandom.current().nextInt(60);
        }
        later(Math.max(1, bye), () -> run(cfg.leaveCommands, values(r, r.hx, r.hy, r.hz)));
    }

    private void nextActivity(Resident r) {
        LifeConfig.Activity a = weightedActivity(r.activity);
        if (a == null) {
            r.activityEnd = now + 20 * 60 * 5;
            return;
        }
        r.activity = a.id();
        r.activityEnd = now + 20 * 60 * a.minutes().random();
        int[] t = targetFor(r, a.id());
        run(a.commands(), values(r, t[0], t[1], t[2]));
    }

    /** Weighted pick, avoiding doing the same thing twice in a row when there is a choice. */
    private LifeConfig.Activity weightedActivity(String last) {
        List<LifeConfig.Activity> list = new ArrayList<>(cfg.activities.values());
        if (list.isEmpty()) return null;
        if (list.size() > 1) list.removeIf(a -> a.id().equals(last));
        int total = 0;
        for (LifeConfig.Activity a : list) total += a.weight();
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (LifeConfig.Activity a : list) {
            roll -= a.weight();
            if (roll < 0) return a;
        }
        return list.get(0);
    }

    /** Where an activity takes the resident: home, down a mine near home, a hotspot, or somewhere further out. */
    private int[] targetFor(Resident r, String activity) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World w = Bukkit.getWorld(r.world);
        switch (activity) {
            case "mine" -> {
                int y = cfg.mineDepth.random();
                if (w != null) y = Math.max(w.getMinHeight() + 6, Math.min(y, groundY(r) - 4));
                return new int[] {r.hx + rnd.nextInt(-12, 13), y, r.hz + rnd.nextInt(-12, 13)};
            }
            case "visit" -> {
                List<LifeConfig.Hotspot> spots = new ArrayList<>();
                for (LifeConfig.Hotspot h : cfg.hotspots) if (h.world().isEmpty() || h.world().equalsIgnoreCase(r.world)) spots.add(h);
                if (!spots.isEmpty()) {
                    LifeConfig.Hotspot h = spots.get(rnd.nextInt(spots.size()));
                    return new int[] {h.x() + rnd.nextInt(-3, 4), h.y(), h.z() + rnd.nextInt(-3, 4)};
                }
                return new int[] {r.hx, r.hy, r.hz};
            }
            case "explore" -> {
                double ang = rnd.nextDouble(Math.PI * 2);
                int d = 50 + rnd.nextInt(120);
                int x = r.hx + (int) (Math.cos(ang) * d), z = r.hz + (int) (Math.sin(ang) * d);
                int y = w != null && w.isChunkLoaded(x >> 4, z >> 4) ? w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1 : r.hy;
                return new int[] {x, y, z};
            }
            default -> {
                return new int[] {r.hx, r.hy, r.hz};
            }
        }
    }

    // ------------------------------------------------------------------ homes

    /** Gives a resident a home the first time it plays: a spot around spawn (or the configured centre) on dry land. */
    private boolean ensureHome(Resident r) {
        if (r.hasHome && Bukkit.getWorld(r.world) != null) return true;
        World w = cfg.homeWorld.isEmpty() ? Bukkit.getWorlds().get(0) : Bukkit.getWorld(cfg.homeWorld);
        if (w == null) return false;
        int cx = cfg.homesAroundSpawn ? w.getSpawnLocation().getBlockX() : cfg.homeCenterX;
        int cz = cfg.homesAroundSpawn ? w.getSpawnLocation().getBlockZ() : cfg.homeCenterZ;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = rnd.nextDouble(Math.PI * 2);
            int d = cfg.homeDistance.random();
            int x = cx + (int) (Math.cos(ang) * d), z = cz + (int) (Math.sin(ang) * d);
            w.getChunkAt(x >> 4, z >> 4); // loads it so the ground is real
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (w.getBlockAt(x, y, z).isLiquid() || y <= w.getMinHeight() + 1) continue;
            r.world = w.getName();
            r.hx = x;
            r.hy = y + 1;
            r.hz = z;
            r.hasHome = true;
            return true;
        }
        return false;
    }

    /** Moves a resident's home to a location (/uss residents sethome). Creates the resident if needed. */
    public void setHome(String name, World w, int x, int y, int z) {
        Resident r = residents.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new Resident(name));
        r.world = w.getName();
        r.hx = x;
        r.hy = y;
        r.hz = z;
        r.hasHome = true;
        save();
    }

    private int groundY(Resident r) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) return r.hy - 1;
        w.getChunkAt(r.hx >> 4, r.hz >> 4);
        return w.getHighestBlockYAt(r.hx, r.hz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    // ------------------------------------------------------------------ chat

    private void idleChat(Resident r) {
        if (!cfg.chat || cfg.idle.isEmpty() || now - r.joinedAt < 20 * 60) return;
        if (now - lastIdleChat < 20 * 45) return; // never a chatty wall of bots
        if (!chance(cfg.idleChancePerMinute / 60.0)) return;
        lastIdleChat = now;
        say(r, pick(cfg.idle), null);
    }

    /** The bot types it itself (so chat plugins format it like a player); nothing if it isn't online. */
    private void say(Resident r, String line, String player) {
        if (line == null || line.isBlank()) return;
        Player p = Bukkit.getPlayerExact(r.name);
        if (p == null) return;
        String msg = line.replace("{player}", player == null ? "" : player).replace("{name}", r.name).trim();
        if (!msg.isEmpty()) p.chat(msg);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player joined = e.getPlayer();
        if (!running || !cfg.chat || isBot(joined) || !chance(cfg.welcomeChance)) return;
        List<Resident> awake = new ArrayList<>();
        for (Resident r : residents.values()) if (r.online && now - r.joinedAt > 20 * 30) awake.add(r);
        if (awake.isEmpty()) return;
        Resident r = awake.get(ThreadLocalRandom.current().nextInt(awake.size()));
        String line = pick(joined.hasPlayedBefore() || cfg.welcomesNew.isEmpty() ? cfg.welcomes : cfg.welcomesNew);
        String name = joined.getName();
        later(20 * cfg.chatDelaySeconds.random(), () -> say(r, line, name));
    }

    // ------------------------------------------------------------------ server list

    /** Bots are fake players with no real connection, or residents we logged in (until they actually leave). */
    public boolean isBot(Player p) {
        return connected.contains(p.getName().toLowerCase(Locale.ROOT)) || p.getAddress() == null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        connected.remove(e.getPlayer().getName().toLowerCase(Locale.ROOT));
    }

    /** The server list shows real players only (count and the hover list). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent e) {
        if (!cfg.hideFromServerList) return;
        int bots = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (isBot(p)) bots++;
        if (bots == 0) return;
        e.setNumPlayers(Math.max(0, e.getNumPlayers() - bots));
        e.getListedPlayers().removeIf(info -> {
            Player p = Bukkit.getPlayer(info.id());
            return p != null && isBot(p);
        });
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> values(Resident r, int x, int y, int z) {
        Map<String, String> v = new HashMap<>();
        v.put("name", r.name);
        v.put("x", Integer.toString(x));
        v.put("y", Integer.toString(y));
        v.put("z", Integer.toString(z));
        v.put("hx", Integer.toString(r.hx));
        v.put("hy", Integer.toString(r.hy));
        v.put("hz", Integer.toString(r.hz));
        v.put("world", r.world == null ? "world" : r.world);
        v.put("radius", Integer.toString(cfg.homeRadius));
        return v;
    }

    private void run(List<String> templates, Map<String, String> values) {
        for (String t : templates) {
            String cmd = SpawnCommand.format(t, values);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                log.warning("Residents command failed: /" + cmd + " (" + ex + ")");
            }
        }
    }

    private static boolean chance(double p) {
        return ThreadLocalRandom.current().nextDouble() < p;
    }

    private static String pick(List<String> lines) {
        return lines.isEmpty() ? null : lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    // ------------------------------------------------------------------ residents.yml (homes stay between restarts)

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            Resident r = new Resident(s.getString("name", key));
            r.world = s.getString("world", "world");
            r.hx = s.getInt("x");
            r.hy = s.getInt("y");
            r.hz = s.getInt("z");
            r.hasHome = true;
            residents.put(r.name.toLowerCase(Locale.ROOT), r);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Resident r : residents.values()) {
            if (!r.hasHome) continue;
            String k = r.name.toLowerCase(Locale.ROOT);
            y.set(k + ".name", r.name);
            y.set(k + ".world", r.world);
            y.set(k + ".x", r.hx);
            y.set(k + ".y", r.hy);
            y.set(k + ".z", r.hz);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            log.warning("Could not save " + file + ": " + ex.getMessage());
        }
    }
}
