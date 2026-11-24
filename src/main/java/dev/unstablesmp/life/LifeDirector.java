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
        /** 0 = the house; 1..STAGES = the next extension to build; above = fully grown. */
        int stage;
        boolean repairing;
        int growAt;
        final Set<String> roadsTo = new HashSet<>();

        // ---- trips, deaths, markets, wars
        final List<int[]> road = new ArrayList<>();
        String roadTarget;
        Location netherBack;
        boolean inNether;
        Location death;
        int deathAt = -100000;
        int stall = -1;
        UUID warTarget;

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

    private static final Set<String> HAND_CHORES = Set.of("build", "farm", "woodcut", "road", "market");
    /** Things that take over whatever a resident was doing (not picked at random). */
    private static final Set<String> OVERRIDES = Set.of("defend", "recover", "raid", "war");

    /** A feud between two crews (or solo players): march, TNT, fight, then the losers rebuild. */
    private static final class War {
        final Resident attackers, defenders;
        int phase; // 0 march, 1 fight
        int bombAt, endAt;

        War(Resident a, Resident d) {
            attackers = a;
            defenders = d;
        }
    }

    private final List<War> wars = new ArrayList<>();
    private int nextAutoWar = 20 * 60 * 30;
    private java.util.function.Supplier<List<Entity>> nullBots = List::of;
    private java.util.function.Supplier<List<Location>> invasionCentres = List::of;
    private final Map<Integer, Homestead.Job> stalls = new HashMap<>();

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

    /** Every resident's name (online or not). */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Resident r : residents.values()) out.add(r.name);
        return out;
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
            boolean overriding = OVERRIDES.contains(r.activity);
            boolean following = leader != null && leader.online;
            if (!following && now >= r.sessionEnd && !overriding) {
                leaveWithCrew(r);
                continue;
            }
            if (now >= r.activityEnd) {
                // a crew member back from defending/recovering/a war rejoins what the leader is doing
                if (following && overriding) follow(r, leader);
                else if (!following) nextActivity(r);
            }
            Player p = Bukkit.getPlayerExact(r.name);
            if (p == null) continue;
            try {
                chores(r, p);
            } catch (RuntimeException ex) {
                log.warning("Residents: " + r.name + " " + r.activity + ": " + ex);
            }
        }
        tickSociety();
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
                if (r.inNether) returnFromNether(r, p);
                restoreHand(r, p);
            }
            LifeUtil.run(cfg.leaveCommands, values(r, r.hx, r.hy, r.hz), log);
        });
    }

    // ------------------------------------------------------------------ activities

    private void nextActivity(Resident r) {
        LifeConfig.Activity a = pickActivity(r);
        if (a == null) a = cfg.activities.get("home"); // nothing else they can do: hang around the base
        if (a == null) a = cfg.activities.get("afk");
        if (a == null) {
            Player p = Bukkit.getPlayerExact(r.name);
            if (r.inNether && p != null) returnFromNether(r, p);
            r.activityEnd = now + 20 * 60 * 5;
            return;
        }
        start(r, a, now + 20 * 60 * a.minutes().random(), null);
        for (Resident m : residents.values()) {
            if (m.online && LifeUtil.key(r.name).equals(m.followLeader) && !OVERRIDES.contains(m.activity)) follow(m, r);
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
        boolean needsBuild = needsBuild(f);
        if (needsBuild && build != null && rnd.nextDouble() < 0.65) return build;
        List<LifeConfig.Activity> list = new ArrayList<>();
        for (LifeConfig.Activity a : cfg.activities.values()) {
            if (a.id().equals(r.activity) && cfg.activities.size() > 1) continue;
            if (a.id().equals("sleep") || OVERRIDES.contains(a.id()) || a.weight() <= 0) continue;
            if (a.id().equals("build") && !needsBuild) continue;
            if (a.id().equals("farm") && !(cfg.bases && f.built)) continue;
            if (a.id().equals("road") && !(cfg.roads && f.built && roadPartner(f) != null)) continue;
            if (a.id().equals("market") && !cfg.market) continue;
            if (a.id().equals("nether") && !(cfg.nether && f.built && f.stage > Blueprint.STAGES && netherWorld() != null && home.portalLit(f))) continue;
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

    /** The house isn't done, needs fixing after a raid, or the next extension is due (skipping any that won't fit). */
    private boolean needsBuild(Resident f) {
        if (!cfg.bases || !f.base) return false;
        if (!f.built || f.repairing) return true;
        while (f.stage >= 1 && f.stage <= Blueprint.STAGES && now >= f.growAt) {
            if (f.progress > 0 || home.extensionOk(f, f.stage)) return true; // started = keep going (its own blocks would fail the check)
            f.stage++; // that spot is taken or too rough: skip this extension
        }
        return false;
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
        if (r.inNether && !a.id().equals("nether") && p != null) returnFromNether(r, p);
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
            case "road" -> {
                Resident other = roadPartner(f);
                r.road.clear();
                r.roadTarget = other == null ? null : LifeUtil.key(other.name);
                if (other != null) r.road.addAll(roadPath(f, other));
                t = r.road.isEmpty() ? new int[] {r.hx, r.hy, r.hz} : new int[] {r.road.get(0)[0], r.hy, r.road.get(0)[1]};
            }
            case "market" -> {
                r.stall = stallFor(r);
                int[] spot = stallSpot(r.stall);
                t = spot == null ? new int[] {r.hx, r.hy, r.hz} : spot;
            }
            case "nether" -> t = Homestead.world(f, Blueprint.portalFront()[0], 1, Blueprint.portalFront()[2]);
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
                    if (f.repairing) {
                        f.repairing = false;
                    } else if (f.stage == 0) {
                        f.stage = 1; // the house is up: it grows from here
                    } else if (f.stage <= Blueprint.STAGES) {
                        f.stage++;
                    }
                    f.growAt = now + 20 * 60 * cfg.growMinutes;
                    f.progress = 0;
                    home.forget(f);
                    save();
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
            case "road" -> roadChore(r, f, p);
            case "market" -> marketChore(r, p);
            case "nether" -> netherChore(r, f, p);
            case "recover" -> recoverChore(r, p);
            case "defend", "war" -> fightChore(r, p);
            case "raid" -> { } // marching: the war director does the rest
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
        return m == Material.IRON_AXE || m == Material.FLINT_AND_STEEL || m == Material.IRON_SHOVEL || m == Material.IRON_PICKAXE || m == Material.IRON_HOE
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

    // ------------------------------------------------------------------ overrides (defend, recover, raid, war)

    /** Takes over whatever the resident is doing until {@code until}. */
    private void force(Resident r, String id, int until, int[] target, List<String> commands) {
        Player p = Bukkit.getPlayerExact(r.name);
        if (p != null) {
            if (p.isSleeping()) p.wakeup(false);
            if (r.inNether) returnFromNether(r, p);
            restoreHand(r, p);
        }
        r.activity = id;
        r.activityEnd = until;
        r.nextChoreAt = now + 20;
        r.gotoAt = now + 200;
        r.stuckSince = 0;
        if (target != null) LifeUtil.run(commands, values(r, target[0], target[1], target[2]), log);
    }

    /** Fights whoever is in front of them: NULL (defend) or the other crew (war). */
    private void fightChore(Resident r, Player p) {
        if (now < r.nextChoreAt) return;
        r.nextChoreAt = now + 20 * 6;
        Entity target = null;
        if (r.activity.equals("war")) {
            target = r.warTarget == null ? null : Bukkit.getEntity(r.warTarget);
        } else {
            double best = 64 * 64;
            for (Entity e : nullBots.get()) {
                if (e.getWorld() != p.getWorld() || !e.isValid() || e.isDead()) continue;
                double d = e.getLocation().distanceSquared(p.getLocation());
                if (d < best) {
                    best = d;
                    target = e;
                }
            }
            if (target == null && invasionNear(r) == null) {
                r.activityEnd = now;
                return;
            }
        }
        if (target == null) return;
        Map<String, String> v = values(r, target.getLocation().getBlockX(), target.getLocation().getBlockY(), target.getLocation().getBlockZ());
        v.put("target", target instanceof Player tp ? tp.getName() : target.getUniqueId().toString());
        LifeUtil.run(r.activity.equals("war") ? cfg.warAttack : cfg.defendCommands, v, log);
    }

    /** The NULL invasion nearest a resident's home, if one is close enough to threaten it. */
    private Location invasionNear(Resident r) {
        Resident f = founder(r);
        for (Location c : invasionCentres.get()) {
            if (c.getWorld() == null || r.world == null || !c.getWorld().getName().equals(r.world)) continue;
            double dx = c.getX() - f.hx, dz = c.getZ() - f.hz;
            if (dx * dx + dz * dz < 90 * 90) return c;
        }
        return null;
    }

    /** Lets residents see NULL invasions (for defending their bases). */
    public void nullSource(java.util.function.Supplier<List<Entity>> bots, java.util.function.Supplier<List<Location>> centres) {
        this.nullBots = bots;
        this.invasionCentres = centres;
    }

    /** Where a resident's base is (for /uss null base): the middle of the house, or null. "random" = any built base. */
    public Location baseCentre(String name) {
        List<Resident> pool = new ArrayList<>();
        if (name.equalsIgnoreCase("random")) {
            for (Resident r : residents.values()) if (r.base && r.built) pool.add(r);
        } else {
            Resident r = residents.get(LifeUtil.key(name));
            if (r != null) pool.add(founder(r));
        }
        pool.removeIf(r -> !r.base || Bukkit.getWorld(r.world) == null);
        if (pool.isEmpty()) return null;
        Resident f = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        int[] c = Homestead.world(f, Blueprint.homeSpot()[0], 1, Blueprint.homeSpot()[2]);
        return new Location(Bukkit.getWorld(f.world), c[0] + 0.5, c[1], c[2] + 0.5);
    }

    // ------------------------------------------------------------------ death and recovery

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Resident r = residents.get(LifeUtil.key(e.getPlayer().getName()));
        if (r == null || !r.online || !cfg.recover || e.getKeepInventory() || e.getDrops().isEmpty()) return;
        r.death = e.getPlayer().getLocation().clone();
        r.deathAt = now;
        r.inNether = false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        Resident r = residents.get(LifeUtil.key(e.getPlayer().getName()));
        if (r == null || !r.online || r.death == null || now - r.deathAt > 20 * 60 * 4) return;
        later(60 + ThreadLocalRandom.current().nextInt(100), () -> {
            if (!r.online || r.death == null) return;
            Location d = r.death;
            force(r, "recover", now + 20 * 60 * 4, new int[] {d.getBlockX(), d.getBlockY(), d.getBlockZ()}, cfg.moveCommands);
        });
    }

    /** Back where they died: goes after each of their dropped items (they scatter, float and roll) and picks it up. */
    private void recoverChore(Resident r, Player p) {
        if (now < r.nextChoreAt) return;
        r.nextChoreAt = now + 10;
        Location d = r.death;
        if (d == null || d.getWorld() != p.getWorld()) {
            r.activityEnd = now;
            return;
        }
        org.bukkit.entity.Item nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : d.getWorld().getNearbyEntities(d, 12, 12, 12)) {
            if (!(e instanceof org.bukkit.entity.Item item) || !item.isValid()) continue;
            double dist = item.getLocation().distanceSquared(p.getLocation());
            if (dist < best) {
                best = dist;
                nearest = item;
            }
        }
        if (nearest == null) {
            if (p.getLocation().distanceSquared(d) > 6 * 6 && !stuck(r)) {
                // not there yet: the items may be in unloaded/unseen spots, walk over first
                if (r.stuckSince == 0) r.stuckSince = now;
                if (now >= r.gotoAt) {
                    LifeUtil.run(cfg.moveCommands, values(r, d.getBlockX(), d.getBlockY(), d.getBlockZ()), log);
                    r.gotoAt = now + 200;
                }
                return;
            }
            r.death = null; // all picked up (or gone)
            r.activityEnd = now + 40;
            return;
        }
        if (best > 3 * 3 && !stuck(r)) {
            if (r.stuckSince == 0) r.stuckSince = now;
            if (now >= r.gotoAt) {
                int[] at = standNear(nearest.getLocation());
                LifeUtil.run(cfg.moveCommands, values(r, at[0], at[1], at[2]), log);
                r.gotoAt = now + 100;
            }
            return;
        }
        if (best > 6 * 6) {
            r.death = null; // can't get to it: they give up on the rest
            r.activityEnd = now + 40;
            return;
        }
        r.stuckSince = 0;
        r.gotoAt = now;
        int got = 0;
        for (Entity e : p.getWorld().getNearbyEntities(p.getLocation(), 4, 4, 4)) {
            if (e instanceof org.bukkit.entity.Item item && item.isValid()) {
                Homestead.give(p, List.of(item.getItemStack()));
                item.remove();
                got++;
            }
        }
        if (got > 0) {
            p.swingMainHand();
            p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.6f, 1f);
        }
    }

    /** A spot a player can stand in at or next to {@code l} (the item may sit on a slab, in a plant, on water…). */
    private static int[] standNear(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        for (int[] o : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
            for (int dy = 0; dy >= -2; dy--) {
                Block b = w.getBlockAt(x + o[0], y + dy, z + o[1]);
                if (b.isPassable() && b.getRelative(0, 1, 0).isPassable() && (b.getRelative(0, -1, 0).getType().isSolid() || b.getRelative(0, -1, 0).isLiquid()))
                    return new int[] {b.getX(), b.getY(), b.getZ()};
            }
            Block up = w.getBlockAt(x + o[0], y + 1, z + o[1]);
            if (up.isPassable() && up.getRelative(0, 1, 0).isPassable() && up.getRelative(0, -1, 0).getType().isSolid()) return new int[] {up.getX(), up.getY(), up.getZ()};
        }
        return new int[] {x, y, z};
    }

    // ------------------------------------------------------------------ roads

    /** The nearest other base without a road to this one yet (within roads.max-distance). */
    private Resident roadPartner(Resident f) {
        Resident best = null;
        double bestD = (double) cfg.roadMax * cfg.roadMax;
        for (Resident o : residents.values()) {
            if (o == f || !o.base || !o.built || o.world == null || !o.world.equals(f.world) || founder(o) != o) continue;
            if (f.roadsTo.contains(LifeUtil.key(o.name)) || o.roadsTo.contains(LifeUtil.key(f.name))) continue;
            double dx = o.bx - f.bx, dz = o.bz - f.bz, d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    /** Door to door, a gently wandering line of (x, z) points. */
    private List<int[]> roadPath(Resident a, Resident b) {
        int[] from = Homestead.world(a, 3, 0, -2), to = Homestead.world(b, 3, 0, -2);
        List<int[]> out = new ArrayList<>();
        int x = from[0], z = from[2];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < cfg.roadMax * 2 && (x != to[0] || z != to[2]); i++) {
            int dx = Integer.signum(to[0] - x), dz = Integer.signum(to[2] - z);
            if (dx != 0 && (dz == 0 || rnd.nextInt(Math.abs(to[0] - x) + Math.abs(to[2] - z)) < Math.abs(to[0] - x))) x += dx;
            else z += dz;
            out.add(new int[] {x, z});
        }
        return out;
    }

    /** Lays the next stretch of road: dirt path on grass, a plank bridge over water, plants cleared. */
    private void roadChore(Resident r, Resident f, Player p) {
        if (now < r.nextChoreAt) return;
        r.nextChoreAt = now + 10;
        World w = p.getWorld();
        if (r.road.isEmpty()) {
            if (r.roadTarget != null) {
                f.roadsTo.add(r.roadTarget);
                save();
            }
            r.activityEnd = now;
            return;
        }
        int[] pt = r.road.get(0);
        double dx = p.getX() - pt[0], dz = p.getZ() - pt[1];
        if (dx * dx + dz * dz > 10 * 10 && !stuck(r)) {
            if (r.stuckSince == 0) r.stuckSince = now;
            if (now >= r.gotoAt) {
                int[] ahead = r.road.get(Math.min(6, r.road.size() - 1));
                int g = LifeUtil.ground(w, ahead[0], ahead[1]);
                LifeUtil.run(cfg.moveCommands, values(r, ahead[0], g == Integer.MIN_VALUE ? p.getLocation().getBlockY() : g + 1, ahead[1]), log);
                r.gotoAt = now + 120;
            }
            return;
        }
        if (dx * dx + dz * dz <= 10 * 10) r.stuckSince = 0;
        r.road.remove(0);
        if (!w.isChunkGenerated(pt[0] >> 4, pt[1] >> 4) || insideAnyBase(w, pt[0], pt[1])) return;
        int top = w.getHighestBlockYAt(pt[0], pt[1], org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block b = w.getBlockAt(pt[0], top, pt[1]);
        Material m = b.getType();
        if (b.isLiquid()) {
            b.setType(Material.matchMaterial(founder(r).wood + "_PLANKS") == null ? Material.OAK_PLANKS : Material.matchMaterial(founder(r).wood + "_PLANKS"), false);
        } else if (m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.COARSE_DIRT || m == Material.PODZOL || m == Material.MYCELIUM || m == Material.ROOTED_DIRT) {
            Block above = b.getRelative(0, 1, 0);
            if (org.bukkit.Tag.REPLACEABLE.isTagged(above.getType()) || org.bukkit.Tag.FLOWERS.isTagged(above.getType())) above.setType(Material.AIR, false);
            if (above.getType().isAir()) b.setType(Material.DIRT_PATH, false);
        } else {
            return;
        }
        home.hold(p, Material.IRON_SHOVEL);
        w.playSound(b.getLocation(), org.bukkit.Sound.ITEM_SHOVEL_FLATTEN, 0.8f, 1f);
    }

    private boolean insideAnyBase(World w, int x, int z) {
        for (Resident o : residents.values()) {
            if (!o.base || o.world == null || !o.world.equals(w.getName())) continue;
            int[] a = Homestead.world(o, -10, 0, -1), b = Homestead.world(o, 15, 0, 16);
            if (x >= Math.min(a[0], b[0]) && x <= Math.max(a[0], b[0]) && z >= Math.min(a[2], b[2]) && z <= Math.max(a[2], b[2])) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ market

    private Location marketCentre() {
        World w = Bukkit.getWorlds().get(0);
        if (cfg.marketAtSpawn) {
            Location s = w.getSpawnLocation();
            int g = LifeUtil.ground(w, s.getBlockX(), s.getBlockZ());
            return new Location(w, s.getBlockX(), g == Integer.MIN_VALUE ? s.getBlockY() : g, s.getBlockZ());
        }
        return new Location(w, cfg.marketX, cfg.marketY, cfg.marketZ);
    }

    /** Each resident keeps the same stall. */
    private int stallFor(Resident r) {
        return Math.floorMod(r.name.hashCode(), cfg.marketStalls);
    }

    /** Stall {@code index}'s build job, or null if there's no free spot for it (never on anyone's build or water). */
    private Homestead.Job stallJob(int index) {
        if (stalls.containsKey(index)) return stalls.get(index);
        Location c = marketCentre();
        Homestead.Job found = null;
        // try its place in the ring, then nudged around and a bit further out
        for (int attempt = 0; attempt < 12 && found == null; attempt++) {
            double ang = Math.PI * 2 * index / cfg.marketStalls + (attempt % 4 - 1.5) * 0.12 * (attempt / 4 + 1);
            int rad = cfg.marketRadius + (attempt / 4) * 3;
            int x = c.getBlockX() + (int) Math.round(Math.cos(ang) * rad);
            int z = c.getBlockZ() + (int) Math.round(Math.sin(ang) * rad);
            Homestead.Job j = new Homestead.Job();
            j.w = c.getWorld();
            int g = LifeUtil.ground(j.w, x, z);
            if (g == Integer.MIN_VALUE) continue;
            j.ox = x;
            j.oy = g;
            j.oz = z;
            j.rot = Homestead.facingRotation(x, z, c.getBlockX(), c.getBlockZ());
            j.plan = Blueprint.stall(cfg.woods.get(index % cfg.woods.size()), cfg.stallColours.get(index % cfg.stallColours.size()));
            if (stallSiteOk(j) || stallStanding(j)) found = j;
        }
        stalls.put(index, found);
        return found;
    }

    /** Free ground: no build, no water, roughly level, nothing else's stall. */
    private boolean stallSiteOk(Homestead.Job j) {
        for (int lx = -1; lx <= 3; lx++) {
            for (int lz = -1; lz <= 3; lz++) {
                int[] c = j.at(lx, 0, lz);
                int g = LifeUtil.ground(j.w, c[0], c[2]);
                if (g == Integer.MIN_VALUE || Math.abs(g - j.oy) > 2) return false;
                for (int y = j.oy - 1; y <= j.oy + 5; y++) if (Homestead.manMade(j.w.getBlockAt(c[0], y, c[2]))) return false;
            }
        }
        return true;
    }

    /** Our own stall is already there (after a restart): its counter barrels stand where the plan says. */
    private boolean stallStanding(Homestead.Job j) {
        int[] c = j.at(1, 1, 0);
        return j.w.getBlockAt(c[0], c[1], c[2]).getType() == Material.BARREL;
    }

    private int[] stallSpot(int index) {
        if (index < 0) return null;
        Homestead.Job j = stallJob(index);
        return j == null ? null : j.at(1, 1, 1);
    }

    /** Puts their stall up if it isn't there yet, then stands behind the counter. */
    private void marketChore(Resident r, Player p) {
        if (now < r.nextChoreAt || r.stall < 0) return;
        Homestead.Job j = stallJob(r.stall);
        if (j == null) {
            r.activityEnd = now; // no room for a stall: do something else
            return;
        }
        int[] spot = j.at(1, 1, 1);
        double dx = p.getX() - (spot[0] + 0.5), dz = p.getZ() - (spot[2] + 0.5);
        boolean there = dx * dx + dz * dz < 6 * 6 && p.getWorld() == j.w;
        if (!there && !stuck(r)) {
            if (r.stuckSince == 0) r.stuckSince = now;
            if (now >= r.gotoAt) {
                LifeUtil.run(cfg.moveCommands, values(r, spot[0], spot[1], spot[2]), log);
                r.gotoAt = now + 200;
            }
            r.nextChoreAt = now + 20;
            return;
        }
        if (there) r.stuckSince = 0;
        if (j.progress < j.plan.size()) {
            home.step(j, p, true);
            r.nextChoreAt = now + cfg.buildTicks.random();
            if (j.progress >= j.plan.size()) restoreHand(r, p);
            return;
        }
        r.nextChoreAt = now + 20 * 10;
    }

    /** Right-click a resident at their stall to trade with them. */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrade(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Player seller)) return;
        Resident r = residents.get(LifeUtil.key(seller.getName()));
        if (r == null || !r.online || !r.activity.equals("market") || !connected.contains(LifeUtil.key(seller.getName()))) return;
        e.setCancelled(true);
        List<org.bukkit.inventory.MerchantRecipe> recipes = new ArrayList<>();
        List<String> pool = new ArrayList<>(cfg.trades);
        Collections.shuffle(pool, new java.util.Random(r.name.hashCode()));
        for (String t : pool.subList(0, Math.min(pool.size(), 3 + Math.floorMod(r.name.hashCode(), 3)))) {
            org.bukkit.inventory.MerchantRecipe recipe = trade(t);
            if (recipe != null) recipes.add(recipe);
        }
        if (recipes.isEmpty()) return;
        org.bukkit.inventory.Merchant m = Bukkit.createMerchant();
        m.setRecipes(recipes);
        e.getPlayer().openMerchant(m, true);
        seller.swingMainHand();
    }

    /** "DIAMOND:1 -> EMERALD:8" or "IRON_INGOT:4 + COAL:2 -> TORCH:16". */
    static org.bukkit.inventory.MerchantRecipe trade(String line) {
        String[] sides = line.split("->");
        if (sides.length != 2) return null;
        ItemStack result = stack(sides[1].trim());
        if (result == null) return null;
        org.bukkit.inventory.MerchantRecipe recipe = new org.bukkit.inventory.MerchantRecipe(result, 12);
        for (String in : sides[0].split("\\+")) {
            ItemStack it = stack(in.trim());
            if (it != null) recipe.addIngredient(it);
        }
        return recipe.getIngredients().isEmpty() ? null : recipe;
    }

    private static ItemStack stack(String s) {
        String[] p = s.split(":");
        Material m = Material.matchMaterial(p[0].trim());
        if (m == null || !m.isItem()) return null;
        int n = 1;
        try {
            if (p.length > 1) n = Integer.parseInt(p[1].trim());
        } catch (NumberFormatException ignored) {
            n = 1;
        }
        return new ItemStack(m, Math.max(1, Math.min(m.getMaxStackSize(), n)));
    }

    // ------------------------------------------------------------------ nether trips

    private static World netherWorld() {
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.NETHER) return w;
        return null;
    }

    /** Walks to the base's portal and goes through; comes back when the trip is over with nether loot. */
    private void netherChore(Resident r, Resident f, Player p) {
        if (now < r.nextChoreAt || r.inNether) return;
        r.nextChoreAt = now + 20;
        int[] front = Homestead.world(f, Blueprint.portalFront()[0], 1, Blueprint.portalFront()[2]);
        double dx = p.getX() - (front[0] + 0.5), dz = p.getZ() - (front[2] + 0.5);
        if (dx * dx + dz * dz > 5 * 5 && !stuck(r)) {
            if (r.stuckSince == 0) r.stuckSince = now;
            if (now >= r.gotoAt) {
                LifeUtil.run(cfg.moveCommands, values(r, front[0], front[1], front[2]), log);
                r.gotoAt = now + 200;
            }
            return;
        }
        World nether = netherWorld();
        if (nether == null || !home.portalLit(f)) {
            r.activityEnd = now;
            return;
        }
        Location there = netherSpot(nether, front[0] / 8, front[2] / 8);
        if (there == null) {
            r.activityEnd = now;
            return;
        }
        r.netherBack = new Location(p.getWorld(), front[0] + 0.5, front[1], front[2] + 0.5);
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.BLOCK_PORTAL_TRAVEL, 0.4f, 1f);
        p.teleport(there);
        r.inNether = true;
        LifeUtil.run(cfg.moveCommands, values(r, there.getBlockX() + 6, there.getBlockY(), there.getBlockZ() + 6), log);
    }

    /** Solid floor with headroom near (x, z) in the Nether, away from lava. */
    private static Location netherSpot(World w, int x, int z) {
        for (int r = 0; r <= 24; r += 3) {
            for (int a = 0; a < 8; a++) {
                int cx = x + (int) Math.round(Math.cos(a * Math.PI / 4) * r), cz = z + (int) Math.round(Math.sin(a * Math.PI / 4) * r);
                for (int y = 100; y >= 32; y--) {
                    Block floor = w.getBlockAt(cx, y - 1, cz);
                    if (floor.getType().isSolid() && floor.getType() != Material.BEDROCK && floor.getType() != Material.MAGMA_BLOCK
                            && w.getBlockAt(cx, y, cz).getType().isAir() && w.getBlockAt(cx, y + 1, cz).getType().isAir()
                            && !w.getBlockAt(cx, y - 2, cz).isLiquid()) return new Location(w, cx + 0.5, y, cz + 0.5);
                }
            }
        }
        return null;
    }

    private void returnFromNether(Resident r, Player p) {
        r.inNether = false;
        if (r.netherBack == null) return;
        p.teleport(r.netherBack);
        p.getWorld().playSound(r.netherBack, org.bukkit.Sound.BLOCK_PORTAL_TRAVEL, 0.4f, 1.2f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<ItemStack> loot = new ArrayList<>();
        for (String s : cfg.netherLoot) {
            String[] parts = s.split(":");
            Material m = Material.matchMaterial(parts[0].trim());
            if (m == null || !m.isItem()) continue;
            int n = parts.length > 1 ? dev.unstablesmp.Settings.IntRange.parse(parts[1], new dev.unstablesmp.Settings.IntRange(1, 1)).random() : 1;
            if (n > 0) loot.add(new ItemStack(m, Math.min(n, m.getMaxStackSize())));
        }
        Homestead.give(p, loot);
        r.netherBack = null;
        if (rnd.nextBoolean()) LifeUtil.run(cfg.moveCommands, values(r, r.hx, r.hy, r.hz), log);
    }

    // ------------------------------------------------------------------ crew wars

    /** Starts a feud: {@code a}'s crew raids {@code b}'s base. Returns null if it can't (a message key otherwise ok). */
    public String war(String a, String b) {
        Resident ra = residents.get(LifeUtil.key(a)), rb = residents.get(LifeUtil.key(b));
        if (ra == null || rb == null) return "unknown";
        Resident fa = founder(ra), fb = founder(rb);
        if (fa == fb) return "same";
        if (!fb.base || !fb.built) return "no-base";
        for (War w : wars) if (w.attackers == fa || w.defenders == fb || w.attackers == fb || w.defenders == fa) return "busy";
        War w = new War(fa, fb);
        w.bombAt = now + 20 * 25;
        w.endAt = now + 20 * 60 * cfg.warMinutes.random();
        wars.add(w);
        if (!running) start();
        int delay = 0;
        for (Resident m : crew(fa)) delay = summon(m, delay);
        for (Resident m : crew(fb)) delay = summon(m, delay);
        later(80, () -> {
            int[] target = Homestead.world(fb, 3, 1, -4);
            for (Resident m : crew(fa)) if (m.online) force(m, "raid", w.endAt, target, cfg.moveCommands);
        });
        log.info("Crew war: " + fa.name + "'s crew is raiding " + fb.name + "'s base.");
        return null;
    }

    public int stopWars() {
        int n = wars.size();
        for (War w : new ArrayList<>(wars)) endWar(w);
        return n;
    }

    private List<Resident> crew(Resident f) {
        List<Resident> out = new ArrayList<>();
        for (Resident r : residents.values()) if (r == f || (f.crew != null && f.crew.equals(r.crew))) out.add(r);
        return out;
    }

    /** Brings a crew member on for the war if they're off. */
    private int summon(Resident m, int delay) {
        if (m.online || Bukkit.getPlayerExact(m.name) != null) return delay;
        later(delay + 1, () -> {
            if (!m.online) beginSession(m, true, null);
        });
        return delay + 20 * (2 + ThreadLocalRandom.current().nextInt(6));
    }

    private void tickWars() {
        for (War w : new ArrayList<>(wars)) {
            if (w.phase == 0 && now >= w.bombAt) {
                w.phase = 1;
                bomb(w);
                List<Resident> as = online(crew(w.attackers)), ds = online(crew(w.defenders));
                for (Resident r : as) warOn(r, ds, w.endAt);
                for (Resident r : ds) warOn(r, as, w.endAt);
            }
            if (now >= w.endAt) endWar(w);
        }
    }

    private List<Resident> online(List<Resident> list) {
        List<Resident> out = new ArrayList<>();
        for (Resident r : list) if (r.online && Bukkit.getPlayerExact(r.name) != null) out.add(r);
        return out;
    }

    private void warOn(Resident r, List<Resident> enemies, int until) {
        if (enemies.isEmpty()) return;
        Player e = Bukkit.getPlayerExact(enemies.get(ThreadLocalRandom.current().nextInt(enemies.size())).name);
        r.warTarget = e == null ? null : e.getUniqueId();
        force(r, "war", until, null, List.of());
    }

    /** The attackers light TNT against the defenders' house walls. */
    private void bomb(War w) {
        World world = Bukkit.getWorld(w.defenders.world);
        if (world == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Resident> as = online(crew(w.attackers));
        int[][] walls = {{3, 1, -1}, {-1, 1, 3}, {7, 1, 3}, {3, 1, 7}, {1, 1, -1}, {5, 1, -1}};
        for (int i = 0; i < cfg.warTnt; i++) {
            int[] lc = walls[i % walls.length];
            int[] c = Homestead.world(w.defenders, lc[0], lc[1], lc[2]);
            Location at = new Location(world, c[0] + 0.5, c[1], c[2] + 0.5);
            world.spawn(at, org.bukkit.entity.TNTPrimed.class, t -> t.setFuseTicks(50 + rnd.nextInt(50)));
            world.playSound(at, org.bukkit.Sound.ITEM_FLINTANDSTEEL_USE, 1f, 1f);
            if (!as.isEmpty()) {
                Player p = Bukkit.getPlayerExact(as.get(i % as.size()).name);
                if (p != null) home.hold(p, Material.FLINT_AND_STEEL);
            }
        }
    }

    /** The raid is over: attackers head home, defenders fix their house. */
    private void endWar(War w) {
        wars.remove(w);
        for (Resident r : crew(w.attackers)) {
            if (r.online && (r.activity.equals("raid") || r.activity.equals("war"))) r.activityEnd = now;
            r.warTarget = null;
        }
        for (Resident r : crew(w.defenders)) {
            if (r.online && r.activity.equals("war")) r.activityEnd = now;
            r.warTarget = null;
        }
        w.defenders.repairing = true;
        w.defenders.progress = 0;
        home.forget(w.defenders);
        log.info("Crew war over: " + w.defenders.name + "'s crew is rebuilding.");
    }

    /** Called every second: NULL near a base pulls its residents in to defend; automatic wars. */
    private void tickSociety() {
        tickWars();
        for (Resident r : residents.values()) {
            if (!r.online || OVERRIDES.contains(r.activity)) continue;
            if (invasionNear(r) == null) continue;
            Player p = Bukkit.getPlayerExact(r.name);
            if (p == null) continue;
            for (Entity e : nullBots.get()) {
                if (e.getWorld() == p.getWorld() && e.getLocation().distanceSquared(p.getLocation()) < 64 * 64) {
                    force(r, "defend", now + 20 * 60 * 10, null, List.of());
                    break;
                }
            }
        }
        if (cfg.warsAuto && wars.isEmpty() && now >= nextAutoWar) {
            nextAutoWar = now + 20 * 60 * cfg.warEveryMinutes.random();
            List<Resident> bases = new ArrayList<>();
            for (Resident r : residents.values()) if (r.base && r.built && founder(r) == r && !online(crew(r)).isEmpty()) bases.add(r);
            if (bases.size() >= 2) {
                Collections.shuffle(bases);
                war(bases.get(0).name, bases.get(1).name);
            }
        }
    }

    /** For the bandit camp: the builder that knows how to put things up. */
    Homestead homestead() {
        return home;
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
            if (Math.abs(o.bx - x) < 36 && Math.abs(o.bz - z) < 36) return false;
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
                r.stage = b.getInt("stage", r.built ? 1 : 0);
                if (r.built && r.stage == 0) r.stage = 1;
                r.roadsTo.addAll(b.getStringList("roads"));
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
                y.set(k + ".base.stage", r.stage);
                if (!r.roadsTo.isEmpty()) y.set(k + ".base.roads", new ArrayList<>(r.roadsTo));
            }
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            log.warning("Could not save " + file + ": " + ex.getMessage());
        }
    }
}
