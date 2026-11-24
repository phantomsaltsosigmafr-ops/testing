package dev.unstablesmp.life;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Residents: VexBot bots that live on the server like players. They log on and off through the day (more at peak
 * hours), alone or in crews of 2-4. Each solo player or crew builds a base (house, beds, chest, farm), sets their
 * spawn in their beds and sleeps there at night. While online they move between activities - building, hanging
 * around home (and putting loot away in the chest), mining real ore, chopping trees, farming, hunting, visiting,
 * exploring, AFK - running VexBot commands for the walking and fighting and doing the rest here.
 */
public final class LifeDirector implements Listener {

    /** Runs something a number of ticks from now (on the director's own clock). */
    interface Scheduler {
        void run(int ticks, Runnable r);
    }

    static final class Resident {
        final String name;
        String world;
        int hx, hy, hz;
        boolean hasHome;
        /** Crew id (the founder's name, lower case) or null for a solo player. Kept between restarts. */
        String crew;
        boolean online;
        int restUntil;
        String followLeader;
        int joinedAt;
        int sessionEnd;
        String activity = "";
        int activityEnd;
        final List<int[]> ores = new ArrayList<>();
        int nextOreAt;

        // ---- the base (kept on the founder: a solo player or the crew's founder)
        boolean base;
        int bx, by, bz, rot;
        String wood = "oak", crop = "wheat", bedColour = "red";
        int beds = 1;
        int progress;
        boolean built;
        int bedIndex;

        // ---- chores
        final List<int[]> logs = new ArrayList<>();
        int[] trunk;
        UUID prey;
        int nextChoreAt;
        int gotoAt;
        /** When the current chore went out of reach (0 = it isn't); after 30 s it's done anyway / skipped. */
        int stuckSince;
        int storedAt = -100000;
        ItemStack savedHand;

        Resident(String name) {
            this.name = name;
        }
    }

    private record Pending(int at, Runnable action) {
    }

    private static final Set<String> HAND_CHORES = Set.of("build", "farm", "woodcut");

    private final Logger log;
    private final File file;
    private LifeConfig cfg;
    private Set<Material> ores;
    private final Homestead home;
    private boolean running;
    private final Map<String, Resident> residents = new LinkedHashMap<>();
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
        this.home = new Homestead(log, cfg);
        config(cfg);
        load();
    }

    public void config(LifeConfig cfg) {
        this.cfg = cfg;
        this.ores = LifeUtil.oreSet(cfg.ores);
        home.config(cfg);
        retargetAt = 0;
    }

    public boolean running() {
        return running;
    }

    public int target() {
        return target;
    }

    public boolean isResident(Player p) {
        return connected.contains(LifeUtil.key(p.getName()));
    }

    public List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Resident r : residents.values()) {
            if (!r.online) continue;
            Resident f = founder(r);
            String extra = r.followLeader != null ? " w/ " + residents.get(r.followLeader).name : "";
            if (r.activity.equals("build") && f.base) extra += " " + (100 * f.progress / Math.max(1, home.plan(f).size())) + "%";
            out.add(r.name + " (" + (r.activity.isEmpty() ? "joining" : r.activity) + extra + ")");
        }
        return out;
    }

    private Resident founder(Resident r) {
        if (r.crew == null) return r;
        Resident f = residents.get(r.crew);
        return f == null ? r : f;
    }

    // ------------------------------------------------------------------ on / off

    public void start() {
        if (running) return;
        running = true;
        LifeUtil.run(cfg.setupCommands, Map.of(), log);
        retargetAt = 0;
        nextJoin = now + 40;
        for (Resident r : residents.values()) {
            if (Bukkit.getPlayerExact(r.name) != null && !r.online) beginSession(r, false, null);
        }
    }

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
        if (!running) return;
        if (now % 20 != 0) {
            // chores have their own pace (a block every second or so while building), checked every tick
            for (Resident r : residents.values()) {
                if (!r.online || now < r.nextChoreAt || r.activity.isEmpty()) continue;
                Player p = Bukkit.getPlayerExact(r.name);
                if (p == null) continue;
                try {
                    chores(r, p);
                } catch (RuntimeException ex) {
                    log.warning("Residents: " + r.name + " " + r.activity + ": " + ex);
                }
            }
            return;
        }
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
            Player p = Bukkit.getPlayerExact(r.name);
            if (p == null) continue;
            try {
                chores(r, p);
            } catch (RuntimeException ex) {
                log.warning("Residents: " + r.name + " " + r.activity + ": " + ex);
            }
        }
        if (now % 1200 == 0) save();
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
                r.online = false;
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

    /** Who logs off when there are too many on: never someone who only just got on (10 minutes at least). */
    private Resident earliestSessionEnd() {
        Resident best = null;
        for (Resident r : residents.values()) {
            if (!r.online || r.followLeader != null || now - r.joinedAt < 20 * 60 * 10) continue;
            if (best == null || r.sessionEnd < best.sessionEnd) best = r;
        }
        return best;
    }

    // ------------------------------------------------------------------ crews

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
        } else if (!lead.base) { // someone already living alone keeps doing so
            int size = Math.min(LifeConfig.pickSize(cfg.crewWeights, 4, rnd), Math.max(1, room));
            List<Resident> loners = new ArrayList<>();
            for (Resident r : free) if (r.crew == null && !r.base) loners.add(r);
            Collections.shuffle(loners, rnd);
            for (int i = 0; i < size - 1 && i < loners.size(); i++) mates.add(loners.get(i));
            if (!mates.isEmpty()) {
                lead.crew = LifeUtil.key(lead.name);
                for (Resident m : mates) m.crew = lead.crew;
            }
        }
        beginSession(lead, true, null);
        int delay = 0;
        for (Resident m : mates) {
            delay += 20 * (3 + rnd.nextInt(25));
            later(delay, () -> {
                if (lead.online && !m.online && now >= m.restUntil && Bukkit.getPlayerExact(m.name) == null) beginSession(m, true, lead);
            });
        }
        save();
    }

    private void beginSession(Resident r, boolean spawn, Resident leader) {
        if (!ensureHome(r)) return;
        r.online = true;
        r.followLeader = leader == null ? null : LifeUtil.key(leader.name);
        r.joinedAt = now;
        r.sessionEnd = leader != null ? leader.sessionEnd + ThreadLocalRandom.current().nextInt(20 * 60) : now + 20 * 60 * cfg.sessionMinutes.random();
        r.activity = "";
        r.activityEnd = now + 100;
        r.ores.clear();
        r.logs.clear();
        r.prey = null;
        r.savedHand = null;
        connected.add(LifeUtil.key(r.name));
        if (spawn) {
            World w = Bukkit.getWorld(r.world);
            int g = w == null ? Integer.MIN_VALUE : LifeUtil.ground(w, r.hx, r.hz);
            int y = r.hy;
            Resident f = founder(r);
            if (!(cfg.bases && f.built) && g != Integer.MIN_VALUE) y = g + 1;
            LifeUtil.run(cfg.joinCommands, values(r, r.hx, y, r.hz), log);
            later(40, () -> LifeUtil.run(cfg.afterJoinCommands, values(r, r.hx, r.hy, r.hz), log));
        }
        later(60, () -> {
            Player p = Bukkit.getPlayerExact(r.name);
            if (p != null && cfg.bases) home.setSpawn(p, founder(r), r.bedIndex);
        });
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
        later(Math.max(1, delay), () -> {
            Player p = Bukkit.getPlayerExact(r.name);
            if (p != null) {
                if (p.isSleeping()) p.wakeup(false);
                restoreHand(r, p);
            }
            LifeUtil.run(cfg.leaveCommands, values(r, r.hx, r.hy, r.hz), log);
        });
    }

    // ------------------------------------------------------------------ activities

    private void nextActivity(Resident r) {
        LifeConfig.Activity a = pickActivity(r);
        if (a == null) {
            r.activityEnd = now + 20 * 60 * 5;
            return;
        }
        start(r, a, now + 20 * 60 * a.minutes().random(), null);
        for (Resident m : residents.values()) {
            if (m.online && LifeUtil.key(r.name).equals(m.followLeader)) follow(m, r);
        }
    }

    /** Night: bed. A base still going up: mostly building. Otherwise by weight, skipping what can't be done. */
    private LifeConfig.Activity pickActivity(Resident r) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Resident f = founder(r);
        World w = Bukkit.getWorld(r.world);
        LifeConfig.Activity sleep = cfg.activities.get("sleep");
        if (cfg.bases && cfg.sleep && f.built && sleep != null && w != null && night(w) && rnd.nextDouble() < 0.8) return sleep;
        LifeConfig.Activity build = cfg.activities.get("build");
        if (cfg.bases && f.base && !f.built && build != null && rnd.nextDouble() < 0.65) return build;
        List<LifeConfig.Activity> list = new ArrayList<>();
        for (LifeConfig.Activity a : cfg.activities.values()) {
            if (a.id().equals(r.activity) && cfg.activities.size() > 1) continue;
            if (a.id().equals("sleep") || a.weight() <= 0) continue;
            if (a.id().equals("build") && !(cfg.bases && f.base && !f.built)) continue;
            if (a.id().equals("farm") && !(cfg.bases && f.built)) continue;
            list.add(a);
        }
        if (list.isEmpty()) return null;
        int total = 0;
        for (LifeConfig.Activity a : list) total += a.weight();
        int roll = rnd.nextInt(Math.max(1, total));
        for (LifeConfig.Activity a : list) {
            roll -= a.weight();
            if (roll < 0) return a;
        }
        return list.get(0);
    }

    static boolean night(World w) {
        long t = w.getTime();
        return (t >= 12542 && t <= 23459) || w.isThundering();
    }

    private void follow(Resident m, Resident leader) {
        LifeConfig.Activity a = cfg.activities.get(leader.activity);
        if (a == null || !m.online) return;
        start(m, a, leader.activityEnd, leader);
    }

    private void start(Resident r, LifeConfig.Activity a, int until, Resident leader) {
        Player p = Bukkit.getPlayerExact(r.name);
        if (p != null) {
            if (p.isSleeping() && !a.id().equals("sleep")) p.wakeup(false);
            restoreHand(r, p);
            if (HAND_CHORES.contains(a.id())) {
                ItemStack cur = p.getInventory().getItemInMainHand();
                r.savedHand = choreItem(cur.getType()) ? new ItemStack(Material.AIR) : cur.clone();
            }
        }
        r.activity = a.id();
        r.activityEnd = until;
        r.ores.clear();
        r.logs.clear();
        r.prey = null;
        r.nextChoreAt = now + 40;
        r.gotoAt = now + 200;
        r.stuckSince = 0;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Resident f = founder(r);
        World w = Bukkit.getWorld(r.world);
        int[] t;
        switch (a.id()) {
            case "mine" -> {
                Resident base = leader != null ? leader : r;
                if (leader != null && !leader.ores.isEmpty()) {
                    for (int[] o : leader.ores) r.ores.add(o.clone());
                    Collections.rotate(r.ores, -1 - rnd.nextInt(Math.max(1, r.ores.size())));
                } else if (w != null && !ores.isEmpty()) {
                    int top = LifeUtil.ground(w, base.hx, base.hz);
                    int yMax = Math.min(cfg.mineDepth.max(), top == Integer.MIN_VALUE ? cfg.mineDepth.max() : top - 4);
                    r.ores.addAll(LifeUtil.findOre(w, base.hx, base.hz, cfg.mineDepth.min(), yMax, ores, 12));
                }
                if (!r.ores.isEmpty()) {
                    t = r.ores.remove(0);
                } else {
                    int y = cfg.mineDepth.random();
                    t = new int[] {base.hx + rnd.nextInt(-12, 13), Math.min(y, base.hy - 6), base.hz + rnd.nextInt(-12, 13)};
                }
                r.nextOreAt = now + 20 * cfg.oreHopSeconds.random();
            }
            case "build" -> t = f.base ? Homestead.world(f, Blueprint.homeSpot()[0], 1, Blueprint.homeSpot()[2]) : new int[] {r.hx, r.hy, r.hz};
            case "farm" -> t = f.base ? Homestead.world(f, Blueprint.farmSpot()[0], 1, Blueprint.farmSpot()[2]) : new int[] {r.hx, r.hy, r.hz};
            case "woodcut" -> {
                if (w != null) r.logs.addAll(home.findTree(w, r.hx, r.hz, f.base ? f : null));
                r.trunk = r.logs.isEmpty() ? null : r.logs.get(0).clone();
                t = r.trunk == null ? new int[] {r.hx, r.hy, r.hz} : new int[] {r.trunk[0] + 1, r.trunk[1], r.trunk[2]};
            }
            case "visit", "explore" -> {
                int[] base = leader != null ? targetFor(leader, a.id()) : targetFor(r, a.id());
                t = leader != null ? new int[] {base[0] + rnd.nextInt(-3, 4), base[1], base[2] + rnd.nextInt(-3, 4)} : base;
            }
            default -> t = new int[] {r.hx, r.hy, r.hz};
        }
        LifeUtil.run(a.commands(), values(r, t[0], t[1], t[2]), log);
    }

    /** The per-second work of the current activity (building, chopping, farming, hunting, sleeping, stashing). */
    private void chores(Resident r, Player p) {
        Resident f = founder(r);
        switch (r.activity) {
            case "mine" -> {
                if (now >= r.nextOreAt) nextOre(r);
            }
            case "build" -> {
                if (!f.base || now < r.nextChoreAt) return;
                r.nextChoreAt = now + cfg.buildTicks.random();
                Homestead.Step s = home.buildStep(f, p, stuck(r));
                if (s == Homestead.Step.TOO_FAR) {
                    if (r.stuckSince == 0) r.stuckSince = now;
                    if (now >= r.gotoAt) {
                        int[] spot = home.nextSpot(f);
                        LifeUtil.run(cfg.moveCommands, values(r, spot[0], spot[1], spot[2]), log);
                        r.gotoAt = now + 200;
                    }
                    return;
                }
                r.stuckSince = 0;
                if (s == Homestead.Step.DONE) {
                    for (Resident m : residents.values()) {
                        Player mp = m.online ? Bukkit.getPlayerExact(m.name) : null;
                        if (mp != null && founder(m) == f) home.setSpawn(mp, f, m.bedIndex);
                    }
                    r.activityEnd = now;
                }
            }
            case "farm" -> {
                if (now < r.nextChoreAt) return;
                r.nextChoreAt = now + 30;
                if (home.harvestStep(f, p, stuck(r)) == Homestead.Step.TOO_FAR) {
                    if (r.stuckSince == 0) r.stuckSince = now;
                    if (now >= r.gotoAt) {
                        int[] spot = Homestead.world(f, Blueprint.farmSpot()[0], 1, Blueprint.farmSpot()[2]);
                        LifeUtil.run(cfg.moveCommands, values(r, spot[0], spot[1], spot[2]), log);
                        r.gotoAt = now + 200;
                    }
                } else {
                    r.stuckSince = 0;
                }
            }
            case "woodcut" -> {
                if (now < r.nextChoreAt) return;
                r.nextChoreAt = now + 25;
                if (r.logs.isEmpty()) {
                    World w = p.getWorld();
                    r.logs.addAll(home.findTree(w, r.hx, r.hz, f.base ? f : null));
                    r.trunk = r.logs.isEmpty() ? null : r.logs.get(0).clone();
                    if (r.trunk != null) LifeUtil.run(cfg.moveCommands, values(r, r.trunk[0] + 1, r.trunk[1], r.trunk[2]), log);
                    r.nextChoreAt = now + 200;
                    return;
                }
                Homestead.Step s = home.chopStep(p, r.logs, r.trunk);
                if (s == Homestead.Step.TOO_FAR) {
                    if (r.stuckSince == 0) r.stuckSince = now;
                    if (stuck(r)) { // can't get to that tree: pick another
                        r.logs.clear();
                        r.stuckSince = 0;
                    } else if (now >= r.gotoAt && r.trunk != null) {
                        LifeUtil.run(cfg.moveCommands, values(r, r.trunk[0] + 1, r.trunk[1], r.trunk[2]), log);
                        r.gotoAt = now + 200;
                    }
                } else {
                    r.stuckSince = 0;
                }
            }
            case "hunt" -> {
                if (now < r.nextChoreAt) return;
                r.nextChoreAt = now + 20 * 15;
                Entity prey = r.prey == null ? null : Bukkit.getEntity(r.prey);
                if (prey != null && prey.isValid() && !prey.isDead()) return;
                Entity next = home.findPrey(p, cfg.huntRange);
                r.prey = next == null ? null : next.getUniqueId();
                if (next != null) {
                    Map<String, String> v = values(r, next.getLocation().getBlockX(), next.getLocation().getBlockY(), next.getLocation().getBlockZ());
                    v.put("target", next.getUniqueId().toString());
                    LifeUtil.run(cfg.huntCommands, v, log);
                }
            }
            case "sleep" -> {
                World w = p.getWorld();
                if (!night(w)) {
                    if (p.isSleeping()) p.wakeup(false);
                    r.activityEnd = now;
                    return;
                }
                if (!p.isSleeping()) {
                    Location bed = home.bed(f, r.bedIndex);
                    if (bed == null) {
                        r.activityEnd = now;
                        return;
                    }
                    if (p.getLocation().distanceSquared(bed) > 16 * 16 && !stuck(r)) {
                        if (r.stuckSince == 0) r.stuckSince = now;
                        if (now >= r.gotoAt) { // walk to the floor beside the bed (the house's middle)
                            int[] inside = Homestead.world(f, Blueprint.homeSpot()[0], 1, Blueprint.homeSpot()[2]);
                            LifeUtil.run(cfg.moveCommands, values(r, inside[0], inside[1], inside[2]), log);
                            r.gotoAt = now + 200;
                        }
                        return;
                    }
                    r.stuckSince = 0;
                    p.sleep(bed, true);
                }
            }
            case "home" -> {
                if (!cfg.bases || now - r.storedAt < 20 * 60 || !Homestead.hasLoot(p)) return;
                Block chest = home.chest(f);
                if (chest == null) return;
                if (home.store(p, chest, this::later)) {
                    r.storedAt = now;
                } else if (now >= r.gotoAt) {
                    LifeUtil.run(cfg.moveCommands, values(r, chest.getX(), chest.getY(), chest.getZ()), log);
                    r.gotoAt = now + 200;
                }
            }
            default -> { }
        }
    }

    /** Out of reach for 30 seconds: do it anyway rather than stall forever. */
    private boolean stuck(Resident r) {
        return r.stuckSince != 0 && now - r.stuckSince > 20 * 30;
    }

    private void nextOre(Resident r) {
        r.nextOreAt = now + 20 * cfg.oreHopSeconds.random();
        if (r.ores.isEmpty()) return;
        int[] o = r.ores.remove(0);
        LifeUtil.run(cfg.oreCommands, values(r, o[0], o[1], o[2]), log);
    }

    /** Puts their own item back in their hand; never leaves a building block or chore tool there. */
    private void restoreHand(Resident r, Player p) {
        if (r.savedHand != null) {
            p.getInventory().setItemInMainHand(r.savedHand);
            r.savedHand = null;
            return;
        }
        if (choreItem(p.getInventory().getItemInMainHand().getType())) p.getInventory().setItemInMainHand(null);
    }

    /** What a chore puts in their hand (tools, building blocks, seeds): never kept as their own item. */
    private static boolean choreItem(Material m) {
        return m == Material.IRON_AXE || m == Material.IRON_SHOVEL || m == Material.IRON_PICKAXE || m == Material.IRON_HOE
                || m == Material.WATER_BUCKET || m == Material.TORCH || m == Material.WHEAT_SEEDS || m == Material.BEETROOT_SEEDS
                || m == Material.CARROT || m == Material.POTATO || m.name().endsWith("_SIGN") || (m.isBlock() && !m.isAir());
    }

    private int[] targetFor(Resident r, String activity) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World w = Bukkit.getWorld(r.world);
        if (activity.equals("visit")) {
            List<LifeConfig.Hotspot> spots = new ArrayList<>();
            for (LifeConfig.Hotspot h : cfg.hotspots) if (h.world().isEmpty() || h.world().equalsIgnoreCase(r.world)) spots.add(h);
            if (!spots.isEmpty()) {
                LifeConfig.Hotspot h = spots.get(rnd.nextInt(spots.size()));
                return new int[] {h.x() + rnd.nextInt(-3, 4), h.y(), h.z() + rnd.nextInt(-3, 4)};
            }
            return new int[] {r.hx, r.hy, r.hz};
        }
        if (activity.equals("explore")) {
            double ang = rnd.nextDouble(Math.PI * 2);
            int d = 50 + rnd.nextInt(120);
            int x = r.hx + (int) (Math.cos(ang) * d), z = r.hz + (int) (Math.sin(ang) * d);
            int g = w == null ? Integer.MIN_VALUE : LifeUtil.ground(w, x, z);
            return new int[] {x, g == Integer.MIN_VALUE ? r.hy : g + 1, z};
        }
        return new int[] {r.hx, r.hy, r.hz};
    }

    // ------------------------------------------------------------------ homes and bases

    private boolean ensureHome(Resident r) {
        Resident f = founder(r);
        if (cfg.bases && f != r) {
            if (!ensureHome(f)) return false;
            r.world = f.world;
            r.hx = f.hx;
            r.hy = f.hy;
            r.hz = f.hz;
            r.hasHome = true;
            List<String> crew = new ArrayList<>();
            for (Resident m : residents.values()) if (r.crew.equals(m.crew)) crew.add(LifeUtil.key(m.name));
            r.bedIndex = Math.max(0, crew.indexOf(LifeUtil.key(r.name)));
            return true;
        }
        World w = cfg.homeWorld.isEmpty() ? Bukkit.getWorlds().get(0) : Bukkit.getWorld(cfg.homeWorld);
        if (r.hasHome && r.world != null && Bukkit.getWorld(r.world) != null) {
            if (!cfg.bases || r.base) return true;
            w = Bukkit.getWorld(r.world);
            int g = LifeUtil.ground(w, r.hx, r.hz);
            if (g != Integer.MIN_VALUE && planBase(r, w, r.hx, g, r.hz)) return true;
        }
        if (w == null) return false;
        int cx = cfg.homesAroundSpawn ? w.getSpawnLocation().getBlockX() : cfg.homeCenterX;
        int cz = cfg.homesAroundSpawn ? w.getSpawnLocation().getBlockZ() : cfg.homeCenterZ;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] fallback = null;
        for (int attempt = 0; attempt < 32; attempt++) {
            double ang = rnd.nextDouble(Math.PI * 2);
            int d = cfg.homeDistance.random();
            int x = cx + (int) (Math.cos(ang) * d), z = cz + (int) (Math.sin(ang) * d);
            // only land that already exists: never make the server generate new chunks for a home
            if (!w.isChunkGenerated(x >> 4, z >> 4)) continue;
            int y = LifeUtil.ground(w, x, z);
            if (y == Integer.MIN_VALUE) continue;
            if (!cfg.bases) {
                fallback = new int[] {x, y, z};
                break;
            }
            if (planBase(r, w, x, y, z)) return true;
            if (fallback == null) fallback = new int[] {x, y, z};
        }
        if (cfg.bases && !r.base) log.info("No plot for " + r.name + "'s base yet (last one: " + home.lastProblem + "); trying again next session.");
        if (r.hasHome && Bukkit.getWorld(r.world) != null) return true; // no plot this time: keep living where they are
        if (fallback == null) return false;
        // no plot for a base this time: they still play from a plain home and try again next session
        r.world = w.getName();
        r.hx = fallback[0];
        r.hy = fallback[1] + 1;
        r.hz = fallback[2];
        r.hasHome = true;
        return true;
    }

    /** Lays out a base on a plot if it can take one (door facing spawn if it fits), and moves home into it. */
    private boolean planBase(Resident f, World w, int x, int ignoredY, int z) {
        // bases never overlap: keep clear of every other base already planned
        for (Resident o : residents.values()) {
            if (o == f || !o.base || o.world == null || !o.world.equals(w.getName())) continue;
            if (Math.abs(o.bx - x) < 26 && Math.abs(o.bz - z) < 26) return false;
        }
        Location spawn = w.getSpawnLocation();
        int preferred = Homestead.facingRotation(x, z, spawn.getBlockX(), spawn.getBlockZ());
        int rot = -1, y = 0;
        for (int i = 0; i < 4 && rot < 0; i++) {
            int r = (preferred + i) % 4;
            int floor = Homestead.floorFor(w, x, z, r);
            if (floor != Integer.MIN_VALUE && home.siteOk(w, x, floor, z, r)) {
                rot = r;
                y = floor;
            }
        }
        if (rot < 0) return false;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        f.base = true;
        f.world = w.getName();
        f.bx = x;
        f.by = y;
        f.bz = z;
        f.rot = rot;
        f.wood = cfg.woods.get(rnd.nextInt(cfg.woods.size()));
        f.crop = cfg.crops.get(rnd.nextInt(cfg.crops.size()));
        f.bedColour = cfg.bedColours.get(rnd.nextInt(cfg.bedColours.size()));
        int crew = 1;
        for (Resident m : residents.values()) if (f.crew != null && f.crew.equals(m.crew) && m != f) crew++;
        f.beds = Math.min(4, crew);
        f.progress = 0;
        f.built = false;
        home.forget(f);
        int[] c = Homestead.world(f, Blueprint.homeSpot()[0], 1, Blueprint.homeSpot()[2]);
        f.hx = c[0];
        f.hy = c[1];
        f.hz = c[2];
        f.hasHome = true;
        return true;
    }

    /** /uss residents sethome: their base goes where you stand, door facing the way you look (no plot checks). */
    public void setHome(String name, World w, int x, int y, int z, float yaw) {
        Resident r = residents.computeIfAbsent(LifeUtil.key(name), k -> new Resident(name));
        Resident f = founder(r);
        if (!cfg.bases) {
            r.world = w.getName();
            r.hx = x;
            r.hy = y;
            r.hz = z;
            r.hasHome = true;
            save();
            return;
        }
        double rad = Math.toRadians(yaw);
        int rot = Homestead.facingRotation(0, 0, (int) Math.round(-Math.sin(rad) * 10), (int) Math.round(Math.cos(rad) * 10));
        f.base = true;
        f.world = w.getName();
        f.bx = x;
        f.by = y - 1;
        f.bz = z;
        f.rot = rot;
        f.progress = 0;
        f.built = false;
        home.forget(f);
        int[] c = Homestead.world(f, Blueprint.homeSpot()[0], 1, Blueprint.homeSpot()[2]);
        f.hx = c[0];
        f.hy = c[1];
        f.hz = c[2];
        f.hasHome = true;
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        connected.remove(LifeUtil.key(e.getPlayer().getName()));
    }

    // ------------------------------------------------------------------ server list

    /** Bots count in the player number and show in the hover list like anyone else. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent e) {
        if (!cfg.listBots) return;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        e.setNumPlayers(Math.max(e.getNumPlayers(), online.size()));
        if (cfg.hoverSize <= 0) return;
        Collections.shuffle(online);
        e.setHidePlayers(false);
        e.getListedPlayers().clear();
        for (int i = 0; i < online.size() && i < cfg.hoverSize; i++) {
            Player p = online.get(i);
            e.getListedPlayers().add(new PaperServerListPingEvent.ListedPlayerInfo(p.getName(), p.getUniqueId()));
        }
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

    // ------------------------------------------------------------------ residents.yml (homes, crews and bases stay)

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
            ConfigurationSection b = s.getConfigurationSection("base");
            if (b != null) {
                r.base = true;
                r.bx = b.getInt("x");
                r.by = b.getInt("y");
                r.bz = b.getInt("z");
                r.rot = b.getInt("rot");
                r.wood = b.getString("wood", "oak");
                r.crop = b.getString("crop", "wheat");
                r.bedColour = b.getString("bed", "red");
                r.beds = b.getInt("beds", 1);
                r.progress = b.getInt("progress");
                r.built = b.getBoolean("built");
            }
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
            if (r.base) {
                y.set(k + ".base.x", r.bx);
                y.set(k + ".base.y", r.by);
                y.set(k + ".base.z", r.bz);
                y.set(k + ".base.rot", r.rot);
                y.set(k + ".base.wood", r.wood);
                y.set(k + ".base.crop", r.crop);
                y.set(k + ".base.bed", r.bedColour);
                y.set(k + ".base.beds", r.beds);
                y.set(k + ".base.progress", r.progress);
                y.set(k + ".base.built", r.built);
            }
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            log.warning("Could not save " + file + ": " + ex.getMessage());
        }
    }
}
