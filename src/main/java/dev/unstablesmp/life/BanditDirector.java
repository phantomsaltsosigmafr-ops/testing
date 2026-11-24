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

    public BanditDirector(Logger log, LifeDirector residents, LifeConfig cfg) {
        this.log = log;
        this.residents = residents;
        this.cfg = cfg;
        this.auto = cfg.banditsAuto;
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
        if (auto && residents.running() && raids.isEmpty() && now >= nextRaid) {
            Player target = pickTarget();
            if (target != null) raid(target, 0);
            nextRaid = now + 20 * 60 * cfg.raidEveryMinutes.random();
        }
        for (Iterator<Raid> it = raids.iterator(); it.hasNext(); ) {
            Raid r = it.next();
            r.members.removeIf(n -> Bukkit.getPlayerExact(n) == null && now - r.startedAt > 200);
            if (r.members.isEmpty() && now - r.startedAt > 200) {
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
