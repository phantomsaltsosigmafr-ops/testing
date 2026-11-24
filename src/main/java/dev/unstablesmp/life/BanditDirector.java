package dev.unstablesmp.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Bandits: small groups of broke players (scraps of leather, chain or iron, a stone sword) who log on near a real
 * player, jump them, and once it's over run off and log off. Raids happen on their own every so often while residents
 * are on, or on demand with /uss bandits raid [player].
 */
public final class BanditDirector {

    private static final class Raid {
        final UUID target;
        final String targetName;
        final List<String> members = new ArrayList<>();
        final int startedAt;
        final int endAt;
        boolean fleeing;
        int fleeAt;

        Raid(Player target, int startedAt, int endAt) {
            this.target = target.getUniqueId();
            this.targetName = target.getName();
            this.startedAt = startedAt;
            this.endAt = endAt;
        }
    }

    private record Pending(int at, Runnable action) {
    }

    private final Logger log;
    private final LifeDirector residents;
    private LifeConfig cfg;
    private boolean setupDone;
    private boolean auto;
    private final List<Raid> raids = new ArrayList<>();
    private final Set<String> banditsOnline = new HashSet<>();
    private final Map<UUID, Integer> lastRaided = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private int now;
    private int nextRaid = 20 * 60 * 3;
    private final java.io.File file;
    /** The bandit hideout: where it is, how far it's built, whether it exists at all. */
    private Homestead.Job camp;
    private String campWorld;
    private int nextCampTry = 20 * 30;

    public BanditDirector(java.io.File dataFolder, Logger log, LifeDirector residents, LifeConfig cfg) {
        this.file = new java.io.File(dataFolder, "bandits.yml");
        this.log = log;
        this.residents = residents;
        this.cfg = cfg;
        this.auto = cfg.banditsAuto;
        loadCamp();
    }

    public void config(LifeConfig cfg) {
        this.cfg = cfg;
        this.auto = cfg.banditsAuto;
    }

    /** Turns the automatic raids on or off (raids on demand always work). */
    public void auto(boolean on) {
        this.auto = on;
        if (on) nextRaid = Math.min(nextRaid, now + 20 * 60);
    }

    public boolean auto() {
        return auto;
    }

    public boolean isBandit(Player p) {
        return banditsOnline.contains(LifeUtil.key(p.getName()));
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        for (Raid r : raids) out.add(String.join(", ", r.members) + (r.fleeing ? " (running off)" : " -> " + r.targetName));
        return out;
    }

    // ------------------------------------------------------------------ tick

    public void tick(int tick) {
        now = tick;
        runPending();
        if (now % 20 != 0) return;
        tickCamp();
        if (auto && residents.running() && raids.isEmpty() && now >= nextRaid) {
            Player target = pickTarget();
            if (target != null) raid(target, 0);
            nextRaid = now + 20 * 60 * cfg.raidEveryMinutes.random();
        }
        for (Iterator<Raid> it = raids.iterator(); it.hasNext(); ) {
            Raid r = it.next();
            r.members.removeIf(n -> Bukkit.getPlayerExact(n) == null && now - r.startedAt > 200);
            if (r.members.isEmpty() && now - r.startedAt > 200) {
                stash(); // the hideout fills up with what they took
                it.remove();
                continue;
            }
            if (!r.fleeing) {
                Player t = Bukkit.getPlayer(r.target);
                String why = t == null ? "target left" : t.isDead() ? "target down" : now >= r.endAt ? "gave up" : null;
                if (why != null) {
                    log.info("Bandits " + r.members + " are running off (" + why + ").");
                    flee(r);
                }
            } else if (now >= r.fleeAt) {
                int d = 0;
                for (String n : r.members) {
                    final String name = n;
                    later(d, () -> leave(name));
                    d += 20 * (2 + ThreadLocalRandom.current().nextInt(10));
                }
                it.remove();
            }
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
                log.warning("Bandits: " + ex);
            }
        }
    }

    private void later(int ticks, Runnable r) {
        pending.add(new Pending(now + Math.max(1, ticks), r));
    }

    /** A real player in survival, not near spawn, not jumped recently. */
    private Player pickTarget() {
        List<Player> list = new ArrayList<>();
        int cooldown = 20 * 60 * cfg.playerCooldownMinutes;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (residents.isResident(p) || isBandit(p) || p.getAddress() == null) continue;
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            Integer last = lastRaided.get(p.getUniqueId());
            if (last != null && now - last < cooldown) continue;
            Location spawn = p.getWorld().getSpawnLocation();
            if (cfg.safeRadius > 0 && p.getLocation().distanceSquared(spawn) < (double) cfg.safeRadius * cfg.safeRadius) continue;
            list.add(p);
        }
        return list.isEmpty() ? null : list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    // ------------------------------------------------------------------ raids

    /** Sends a group of {@code size} bandits (0 = random) after a player. Returns the bandit names, or empty. */
    public List<String> raid(Player target, int size) {
        if (target.isDead() || !target.isOnline()) return List.of();
        if (!setupDone) {
            LifeUtil.run(cfg.banditSetup, Map.of(), log);
            setupDone = true;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int n = size > 0 ? Math.min(size, 6) : LifeConfig.pickSize(cfg.banditGroupWeights, 4, rnd);
        List<String> free = new ArrayList<>();
        for (String name : cfg.banditNames) if (Bukkit.getPlayerExact(name) == null && !banditsOnline.contains(LifeUtil.key(name))) free.add(name);
        java.util.Collections.shuffle(free, rnd);
        if (free.isEmpty()) return List.of();
        // they come out of the trees together, somewhere behind the target
        World w = target.getWorld();
        Location tl = target.getLocation();
        int sx = tl.getBlockX(), sy = tl.getBlockY(), sz = tl.getBlockZ();
        for (int attempt = 0; attempt < 16; attempt++) {
            double ang = rnd.nextDouble(Math.PI * 2);
            int d = cfg.banditSpawnDistance.random();
            int x = tl.getBlockX() + (int) (Math.cos(ang) * d), z = tl.getBlockZ() + (int) (Math.sin(ang) * d);
            int g = LifeUtil.ground(w, x, z);
            if (g == Integer.MIN_VALUE || Math.abs(g - tl.getBlockY()) > 20) continue;
            sx = x;
            sy = g + 1;
            sz = z;
            break;
        }
        Raid raid = new Raid(target, now, now + 20 * 60 * cfg.raidMinutes.random());
        lastRaided.put(target.getUniqueId(), now);
        int delay = 0;
        for (int i = 0; i < n && i < free.size(); i++) {
            String name = free.get(i);
            raid.members.add(name);
            banditsOnline.add(LifeUtil.key(name));
            Map<String, String> v = values(name, sx + rnd.nextInt(-2, 3), sy, sz + rnd.nextInt(-2, 3), target.getName());
            // anyone not on yet when the raid ends just doesn't come; no attack orders once they're running off
            later(Math.max(1, delay), () -> {
                if (raid.fleeing) {
                    raid.members.remove(name);
                    banditsOnline.remove(LifeUtil.key(name));
                    return;
                }
                LifeUtil.run(cfg.banditJoin, v, log);
            });
            later(delay + 40, () -> {
                if (!raid.members.contains(name)) return;
                LifeUtil.run(cfg.banditAfterJoin, v, log);
                gear(name, 0);
            });
            later(delay + 60, () -> {
                if (!raid.fleeing && raid.members.contains(name)) LifeUtil.run(cfg.banditAttack, v, log);
            });
            delay += 20 + rnd.nextInt(50);
        }
        raids.add(raid);
        log.info("Bandits " + raid.members + " are going after " + target.getName() + ".");
        return raid.members;
    }

    /** It's over (target dead or gone, or time's up): the bandits scatter, then log off. */
    private void flee(Raid r) {
        r.fleeing = true;
        r.fleeAt = now + 20 * (20 + ThreadLocalRandom.current().nextInt(25));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (String name : r.members) {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) continue;
            Location l = p.getLocation();
            double ang = rnd.nextDouble(Math.PI * 2);
            int x = l.getBlockX() + (int) (Math.cos(ang) * 60), z = l.getBlockZ() + (int) (Math.sin(ang) * 60);
            if (camp != null && camp.w == p.getWorld() && camp.progress >= camp.plan.size()) {
                // back to the hideout with whatever they got
                int[] c = camp.at(3, 1, -3);
                x = c[0] + rnd.nextInt(-2, 3);
                z = c[2] + rnd.nextInt(-2, 3);
            }
            int g = LifeUtil.ground(p.getWorld(), x, z);
            LifeUtil.run(cfg.banditFlee, values(name, x, g == Integer.MIN_VALUE ? l.getBlockY() : g + 1, z, r.targetName), log);
        }
    }

    private void leave(String name) {
        banditsOnline.remove(LifeUtil.key(name));
        if (Bukkit.getPlayerExact(name) != null) LifeUtil.run(cfg.banditLeave, values(name, 0, 0, 0, ""), log);
    }

    /** Calls every raid off: bandits run and log off. */
    public int stopAll() {
        int n = 0;
        for (Raid r : raids) {
            if (!r.fleeing) {
                flee(r);
                r.fleeAt = now + 20 * 5;
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ camp

    /** Where the hideout is (null if there isn't one). */
    public Location campLocation() {
        if (camp == null) return null;
        int[] c = camp.at(3, 1, 2);
        return new Location(camp.w, c[0] + 0.5, c[1], c[2] + 0.5);
    }

    public boolean campBuilt() {
        return camp != null && camp.progress >= camp.plan.size();
    }

    /** Puts the hideout here (facing the way {@code yaw} looks), replacing any old one's record. */
    public void setCamp(Location at) {
        World w = at.getWorld();
        int rot = Math.floorMod(Math.round(at.getYaw() / 90f) + 2, 4);
        int g = LifeUtil.ground(w, at.getBlockX(), at.getBlockZ());
        camp = job(w, at.getBlockX(), g == Integer.MIN_VALUE ? at.getBlockY() - 1 : g, at.getBlockZ(), rot, 0);
        saveCamp();
        log.info("Bandit camp set at " + camp.ox + " " + camp.oy + " " + camp.oz + ".");
    }

    /** Finds a plot out in the wild for the hideout. */
    public boolean findCamp() {
        World w = Bukkit.getWorlds().get(0);
        Location s = w.getSpawnLocation();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Homestead home = residents.homestead();
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = rnd.nextDouble(Math.PI * 2);
            int d = cfg.campDistance.random();
            int x = s.getBlockX() + (int) (Math.cos(ang) * d), z = s.getBlockZ() + (int) (Math.sin(ang) * d);
            if (!w.isChunkGenerated(x >> 4, z >> 4)) continue;
            int rot = rnd.nextInt(4);
            int g = Homestead.floorFor(w, x, z, rot);
            if (g == Integer.MIN_VALUE || !home.siteOk(w, x, g, z, rot)) continue;
            camp = job(w, x, g, z, rot, 0);
            saveCamp();
            log.info("Bandits picked a camp at " + x + " " + g + " " + z + ".");
            return true;
        }
        return false;
    }

    private Homestead.Job job(World w, int x, int y, int z, int rot, int progress) {
        Homestead.Job j = new Homestead.Job();
        j.w = w;
        j.ox = x;
        j.oy = y;
        j.oz = z;
        j.rot = rot;
        j.plan = Blueprint.camp();
        j.progress = progress;
        j.owner = "bandits";
        j.signs = List.of("keep out|or else", "no trespassing", "turn back", "|private|");
        j.chestItems = cfg.campLoot;
        campWorld = w.getName();
        return j;
    }

    /** Builds the hideout a chunk at a time while no real player is around to see it pop up. */
    private void tickCamp() {
        if (camp == null) {
            if (cfg.campAuto && residents.running() && now >= nextCampTry) {
                nextCampTry = now + 20 * 60 * 5;
                findCamp();
            }
            return;
        }
        if (camp.progress >= camp.plan.size()) return;
        int[] c = camp.at(3, 1, 2);
        for (Player p : camp.w.getPlayers()) {
            if (p.getAddress() != null && !residents.isResident(p) && !isBandit(p)
                    && p.getLocation().distanceSquared(new Location(camp.w, c[0], c[1], c[2])) < 48 * 48) return;
        }
        if (!camp.w.isChunkLoaded(c[0] >> 4, c[2] >> 4)) camp.w.getChunkAt(c[0] >> 4, c[2] >> 4);
        Homestead home = residents.homestead();
        for (int i = 0; i < 60 && camp.progress < camp.plan.size(); i++) home.step(camp, null, true);
        if (camp.progress >= camp.plan.size()) log.info("The bandit camp is up at " + camp.ox + " " + camp.oy + " " + camp.oz + ".");
        saveCamp();
    }

    /** After a raid: more stolen loot in the camp chest (if nobody's cleared it out it just keeps piling up). */
    private void stash() {
        if (!campBuilt()) return;
        int[] c = camp.at(3, 1, 3);
        if (!(camp.w.getBlockAt(c[0], c[1], c[2]).getState(false) instanceof org.bukkit.block.Chest chest)) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        org.bukkit.inventory.Inventory inv = chest.getBlockInventory();
        for (String s : cfg.campLoot) {
            String[] p = s.split(":");
            Material m = Material.matchMaterial(p[0].trim());
            if (m == null || !m.isItem()) continue;
            int n = p.length > 1 ? dev.unstablesmp.Settings.IntRange.parse(p[1], new dev.unstablesmp.Settings.IntRange(1, 1)).random() : 1;
            if (n <= 0) continue;
            int slot = rnd.nextInt(inv.getSize());
            if (inv.getItem(slot) == null) inv.setItem(slot, new ItemStack(m, Math.min(n, m.getMaxStackSize())));
            else inv.addItem(new ItemStack(m, Math.min(n, m.getMaxStackSize())));
        }
    }

    private void loadCamp() {
        if (!file.isFile()) return;
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        if (!y.isConfigurationSection("camp")) return;
        World w = Bukkit.getWorld(y.getString("camp.world", ""));
        if (w == null) return;
        camp = job(w, y.getInt("camp.x"), y.getInt("camp.y"), y.getInt("camp.z"), y.getInt("camp.rot"), y.getInt("camp.progress"));
    }

    private void saveCamp() {
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        if (camp != null) {
            y.set("camp.world", campWorld);
            y.set("camp.x", camp.ox);
            y.set("camp.y", camp.oy);
            y.set("camp.z", camp.oz);
            y.set("camp.rot", camp.rot);
            y.set("camp.progress", camp.progress);
        }
        try {
            file.getParentFile().mkdirs();
            y.save(file);
        } catch (java.io.IOException ex) {
            log.warning("Couldn't save bandits.yml: " + ex);
        }
    }

    // ------------------------------------------------------------------ gear

    /** Kinda broke: odd scraps of leather/chain/iron, a cheap weapon, a bit of food. Retries until the bot is in. */
    private void gear(String name, int tries) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            if (tries < 10) later(20, () -> gear(name, tries + 1));
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        PlayerInventory inv = p.getInventory();
        String[] slots = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
        ItemStack[] armour = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            if (rnd.nextDouble() >= cfg.armourPieceChance) continue;
            int tier = LifeConfig.pickSize(cfg.armourWeights, 4, rnd) - 1; // 0 none, 1 leather, 2 chainmail, 3 iron
            String prefix = switch (tier) {
                case 1 -> "LEATHER_";
                case 2 -> "CHAINMAIL_";
                case 3 -> "IRON_";
                default -> null;
            };
            if (prefix == null) continue;
            Material m = Material.matchMaterial(prefix + slots[i]);
            if (m != null) armour[3 - i] = new ItemStack(m); // armour contents run boots..helmet
        }
        inv.setArmorContents(armour);
        if (!cfg.weapons.isEmpty()) {
            Material m = Material.matchMaterial(cfg.weapons.get(rnd.nextInt(cfg.weapons.size())));
            if (m != null) inv.setItem(0, new ItemStack(m));
            inv.setHeldItemSlot(0);
        }
        if (!cfg.food.isEmpty()) {
            Material m = Material.matchMaterial(cfg.food.get(rnd.nextInt(cfg.food.size())));
            if (m != null) inv.setItem(8, new ItemStack(m, Math.max(1, cfg.foodAmount.random())));
        }
    }

    private Map<String, String> values(String name, int x, int y, int z, String target) {
        Map<String, String> v = new HashMap<>();
        v.put("name", name);
        v.put("x", Integer.toString(x));
        v.put("y", Integer.toString(y));
        v.put("z", Integer.toString(z));
        v.put("target", target);
        return v;
    }
}
