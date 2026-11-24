package dev.unstablesmp.life;

import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Residents: VexBot bots that live on the server like players. They log on and off through the day (more at peak
 * hours), alone or in crews of 2-4 that share a base and do things together. Each keeps its home between sessions.
 * While online they move between activities - hanging around home, mining real ore near home (vein by vein),
 * visiting hotspots, exploring, going AFK - by running VexBot commands.
 */
public final class LifeDirector implements Listener {

    static final class Resident {
        final String name;
        String world;
        int hx, hy, hz;
        boolean hasHome;
        /** Crew id (the founder's name, lower case) or null for a solo player. Kept between restarts. */
        String crew;
        boolean online;
        /** Tick before which it won't log back on (players don't rejoin the second they leave). */
        int restUntil;
        /** This session's leader (lower case) when it came on with its crew; null when it plays on its own. */
        String followLeader;
        int joinedAt;
        int sessionEnd;
        String activity = "";
        int activityEnd;
        final List<int[]> ores = new ArrayList<>();
        int nextOreAt;

        Resident(String name) {
            this.name = name;
        }
    }

    private record Pending(int at, Runnable action) {
    }

    private final Logger log;
    private final File file;
    private LifeConfig cfg;
    private Set<Material> ores;
    private boolean running;
    private final Map<String, Resident> residents = new LinkedHashMap<>();
    /** Residents connected right now (until they actually quit), lower case. */
    private final Set<String> connected = new HashSet<>();
    private final List<Pending> pending = new ArrayList<>();
    private int now;
    private int target;
    private int retargetAt;
    private boolean lastPeak;
    private int nextJoin;
    private int nextLeave;

    public LifeDirector(File dataFolder, Logger log, LifeConfig cfg) {
        this.log = log;
        this.file = new File(dataFolder, "residents.yml");
        config(cfg);
        load();
    }

    public void config(LifeConfig cfg) {
        this.cfg = cfg;
        this.ores = LifeUtil.oreSet(cfg.ores);
        retargetAt = 0;
    }

    public boolean running() {
        return running;
    }

    public int target() {
        return target;
    }

    /** Whether a player is one of our residents (connected right now). */
    public boolean isResident(Player p) {
        return connected.contains(LifeUtil.key(p.getName()));
    }

    public List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Resident r : residents.values()) {
            if (!r.online) continue;
            String crew = r.followLeader != null ? " w/ " + residents.get(r.followLeader).name : "";
            out.add(r.name + " (" + (r.activity.isEmpty() ? "joining" : r.activity) + crew + ")");
        }
        return out;
    }

    // ------------------------------------------------------------------ on / off

    /** Starts the population: runs the one-off setup commands and lets residents start logging on. */
    public void start() {
        if (running) return;
        running = true;
        LifeUtil.run(cfg.setupCommands, Map.of(), log);
        retargetAt = 0;
        nextJoin = now + 40;
        for (Resident r : residents.values()) {
            if (Bukkit.getPlayerExact(r.name) != null && !r.online) beginSession(r, false, null, 0);
        }
    }

    /** Everyone logs off over the next half minute or so. */
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
            joinCrew(target - online);
            nextJoin = now + 20 * cfg.joinGapSeconds.random();
        } else if (online > target && now >= nextLeave) {
            Resident r = earliestSessionEnd();
            if (r != null) leaveWithCrew(r);
            nextLeave = now + 20 * cfg.joinGapSeconds.random();
        }
        for (Resident r : residents.values()) {
            if (!r.online) continue;
            Resident leader = r.followLeader == null ? null : residents.get(r.followLeader);
            boolean following = leader != null && leader.online;
            if (!following && now >= r.sessionEnd) {
                leaveWithCrew(r);
                continue;
            }
            if (!following && now >= r.activityEnd) nextActivity(r);
            if (r.activity.equals("mine") && now >= r.nextOreAt) nextOre(r);
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

    private void refreshOnline() {
        for (Resident r : residents.values()) {
            if (r.online && Bukkit.getPlayerExact(r.name) == null && now - r.joinedAt > 200) {
                r.online = false; // killed or kicked: back after a rest like anyone else
                r.restUntil = now + 20 * 60 * cfg.restMinutes.random();
            }
        }
    }

    private int countOnline() {
        int n = 0;
        for (Resident r : residents.values()) if (r.online) n++;
        return n;
    }

    private List<Resident> offline() {
        List<Resident> free = new ArrayList<>();
        for (String name : cfg.names) {
            Resident r = residents.computeIfAbsent(LifeUtil.key(name), k -> new Resident(name));
            if (!r.online && now >= r.restUntil && Bukkit.getPlayerExact(name) == null) free.add(r);
        }
        return free;
    }

    private Resident earliestSessionEnd() {
        Resident best = null;
        for (Resident r : residents.values()) {
            if (r.online && r.followLeader == null && (best == null || r.sessionEnd < best.sessionEnd)) best = r;
        }
        return best;
    }

    // ------------------------------------------------------------------ crews

    /** One player logs on, maybe bringing their crew (or forming one the first time). */
    private void joinCrew(int room) {
        List<Resident> free = offline();
        if (free.isEmpty()) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Resident> regulars = new ArrayList<>();
        for (Resident r : free) if (r.hasHome) regulars.add(r);
        List<Resident> pool = !regulars.isEmpty() && rnd.nextDouble() < 0.75 ? regulars : free;
        Resident lead = pool.get(rnd.nextInt(pool.size()));
        free.remove(lead);

        List<Resident> mates = new ArrayList<>();
        if (lead.crew != null) {
            for (Resident r : free) if (lead.crew.equals(r.crew) && mates.size() < room - 1 && rnd.nextDouble() < 0.7) mates.add(r);
        } else {
            int size = Math.min(LifeConfig.pickSize(cfg.crewWeights, 4, rnd), Math.max(1, room));
            List<Resident> loners = new ArrayList<>();
            for (Resident r : free) if (r.crew == null) loners.add(r);
            java.util.Collections.shuffle(loners, rnd);
            for (int i = 0; i < size - 1 && i < loners.size(); i++) mates.add(loners.get(i));
            if (!mates.isEmpty()) {
                lead.crew = LifeUtil.key(lead.name);
                for (Resident m : mates) m.crew = lead.crew;
            }
        }
        beginSession(lead, true, null, 0);
        int delay = 0;
        for (Resident m : mates) {
            delay += 20 * (3 + rnd.nextInt(25));
            if (!lead.hasHome) break;
            // crews live together: a home right next to the founder's
            if (!m.hasHome || !lead.world.equals(m.world) || Math.abs(m.hx - lead.hx) > 10 || Math.abs(m.hz - lead.hz) > 10) {
                m.world = lead.world;
                m.hx = lead.hx + rnd.nextInt(-5, 6);
                m.hz = lead.hz + rnd.nextInt(-5, 6);
                World w = Bukkit.getWorld(m.world);
                int g = w == null ? Integer.MIN_VALUE : LifeUtil.ground(w, m.hx, m.hz);
                m.hy = g == Integer.MIN_VALUE ? lead.hy : g + 1;
                m.hasHome = true;
            }
            final int d = delay;
            later(d, () -> {
                if (lead.online && !m.online && now >= m.restUntil && Bukkit.getPlayerExact(m.name) == null) beginSession(m, true, lead, d);
            });
        }
        save();
    }

    private void beginSession(Resident r, boolean spawn, Resident leader, int offset) {
        if (!ensureHome(r)) return;
        r.online = true;
        r.followLeader = leader == null ? null : LifeUtil.key(leader.name);
        r.joinedAt = now;
        r.sessionEnd = leader != null ? leader.sessionEnd + ThreadLocalRandom.current().nextInt(20 * 60) : now + 20 * 60 * cfg.sessionMinutes.random();
        r.activity = "";
        r.activityEnd = now + 100;
        r.ores.clear();
        connected.add(LifeUtil.key(r.name));
        if (spawn) {
            World w = Bukkit.getWorld(r.world);
            int g = w == null ? Integer.MIN_VALUE : LifeUtil.ground(w, r.hx, r.hz);
            LifeUtil.run(cfg.joinCommands, values(r, r.hx, g == Integer.MIN_VALUE ? r.hy : g + 1, r.hz), log);
            later(40, () -> LifeUtil.run(cfg.afterJoinCommands, values(r, r.hx, r.hy, r.hz), log));
        }
        // a crew mate joining mid-activity falls in with what the leader is doing
        if (leader != null && !leader.activity.isEmpty()) later(60, () -> follow(r, leader));
    }

    private void leaveWithCrew(Resident r) {
        leave(r, 0);
        int delay = 0;
        for (Resident m : residents.values()) {
            if (m.online && LifeUtil.key(r.name).equals(m.followLeader)) {
                delay += 20 * (5 + ThreadLocalRandom.current().nextInt(35));
                leave(m, delay);
            }
        }
    }

    private void leave(Resident r, int delay) {
        if (!r.online) return;
        r.online = false;
        r.restUntil = now + delay + 20 * 60 * cfg.restMinutes.random();
        later(Math.max(1, delay), () -> LifeUtil.run(cfg.leaveCommands, values(r, r.hx, r.hy, r.hz), log));
    }

    // ------------------------------------------------------------------ activities

    private void nextActivity(Resident r) {
        LifeConfig.Activity a = weightedActivity(r.activity);
        if (a == null) {
            r.activityEnd = now + 20 * 60 * 5;
            return;
        }
        start(r, a, now + 20 * 60 * a.minutes().random(), null);
        for (Resident m : residents.values()) {
            if (m.online && LifeUtil.key(r.name).equals(m.followLeader)) follow(m, r);
        }
    }

    /** A crew mate does what its leader does, next to it (its own ore in the same vein, the same hotspot...). */
    private void follow(Resident m, Resident leader) {
        LifeConfig.Activity a = cfg.activities.get(leader.activity);
        if (a == null || !m.online) return;
        start(m, a, leader.activityEnd, leader);
    }

    private void start(Resident r, LifeConfig.Activity a, int until, Resident leader) {
        r.activity = a.id();
        r.activityEnd = until;
        r.ores.clear();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] t;
        if (a.id().equals("mine")) {
            World w = Bukkit.getWorld(r.world);
            Resident base = leader != null ? leader : r;
            if (leader != null && !leader.ores.isEmpty()) {
                for (int[] o : leader.ores) r.ores.add(o.clone());
                java.util.Collections.rotate(r.ores, -1 - rnd.nextInt(Math.max(1, r.ores.size())));
            } else if (w != null && !ores.isEmpty()) {
                int top = LifeUtil.ground(w, base.hx, base.hz);
                int yMax = Math.min(cfg.mineDepth.max(), top == Integer.MIN_VALUE ? cfg.mineDepth.max() : top - 4);
                r.ores.addAll(LifeUtil.findOre(w, base.hx, base.hz, cfg.mineDepth.min(), yMax, ores, 12));
            }
            if (!r.ores.isEmpty()) {
                t = r.ores.remove(0);
            } else { // no ore found: just tunnel down near home
                int y = cfg.mineDepth.random();
                t = new int[] {base.hx + rnd.nextInt(-12, 13), Math.min(y, base.hy - 6), base.hz + rnd.nextInt(-12, 13)};
            }
            r.nextOreAt = now + 20 * cfg.oreHopSeconds.random();
        } else if (leader != null) {
            int[] lt = targetFor(leader, a.id());
            t = new int[] {lt[0] + rnd.nextInt(-3, 4), lt[1], lt[2] + rnd.nextInt(-3, 4)};
        } else {
            t = targetFor(r, a.id());
        }
        LifeUtil.run(a.commands(), values(r, t[0], t[1], t[2]), log);
    }

    /** Mining: on to the next ore of the vein. */
    private void nextOre(Resident r) {
        r.nextOreAt = now + 20 * cfg.oreHopSeconds.random();
        if (r.ores.isEmpty()) return;
        int[] o = r.ores.remove(0);
        LifeUtil.run(cfg.oreCommands, values(r, o[0], o[1], o[2]), log);
    }

    private LifeConfig.Activity weightedActivity(String last) {
        List<LifeConfig.Activity> list = new ArrayList<>(cfg.activities.values());
        if (list.isEmpty()) return null;
        if (list.size() > 1) list.removeIf(a -> a.id().equals(last));
        int total = 0;
        for (LifeConfig.Activity a : list) total += a.weight();
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (LifeConfig.Activity a : list) {
            roll -= a.weight();
            if (roll < 0) return a;
        }
        return list.get(0);
    }

    private int[] targetFor(Resident r, String activity) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World w = Bukkit.getWorld(r.world);
        switch (activity) {
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
                int g = w == null ? Integer.MIN_VALUE : LifeUtil.ground(w, x, z);
                return new int[] {x, g == Integer.MIN_VALUE ? r.hy : g + 1, z};
            }
            default -> {
                return new int[] {r.hx, r.hy, r.hz};
            }
        }
    }

    // ------------------------------------------------------------------ homes

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
            int y = LifeUtil.ground(w, x, z);
            if (y == Integer.MIN_VALUE) continue;
            r.world = w.getName();
            r.hx = x;
            r.hy = y + 1;
            r.hz = z;
            r.hasHome = true;
            return true;
        }
        return false;
    }

    public void setHome(String name, World w, int x, int y, int z) {
        Resident r = residents.computeIfAbsent(LifeUtil.key(name), k -> new Resident(name));
        r.world = w.getName();
        r.hx = x;
        r.hy = y;
        r.hz = z;
        r.hasHome = true;
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        connected.remove(LifeUtil.key(e.getPlayer().getName()));
    }

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

    // ------------------------------------------------------------------ residents.yml (homes and crews stay)

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
            r.crew = s.getString("crew");
            residents.put(LifeUtil.key(r.name), r);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Resident r : residents.values()) {
            if (!r.hasHome) continue;
            String k = LifeUtil.key(r.name);
            y.set(k + ".name", r.name);
            y.set(k + ".world", r.world);
            y.set(k + ".x", r.hx);
            y.set(k + ".y", r.hy);
            y.set(k + ".z", r.hz);
            if (r.crew != null) y.set(k + ".crew", r.crew);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            log.warning("Could not save " + file + ": " + ex.getMessage());
        }
    }
}
