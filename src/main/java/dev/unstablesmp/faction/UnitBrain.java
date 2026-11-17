package dev.unstablesmp.faction;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Ground;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Per-unit decision making every 10 ticks: focus fire, target choice, retreat and re-engage, special abilities
 * (blink, summon, flicker, laugh, rage) and handing off to the {@link Engineer}. Enemy candidates are gathered
 * once per faction per brain cycle instead of a world query per unit.
 */
final class UnitBrain {
    private static final class Candidates {
        int tick = Integer.MIN_VALUE;
        World world;
        final List<LivingEntity> list = new ArrayList<>();
        final Map<LivingEntity, int[]> attackers = new IdentityHashMap<>();
    }

    private final UnstableSMP plugin;
    private final UnitManager mgr;
    private final Map<Faction, Candidates> cache = new HashMap<>();
    private final Location ml = new Location(null, 0, 0, 0);
    private final Location tl = new Location(null, 0, 0, 0);

    UnitBrain(UnstableSMP plugin, UnitManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    void tick(Unit u, int now) {
        Mob m = u.mob;
        Faction f = u.f;
        UnitSettings s = mgr.s;
        double max = maxHealth(m);
        if (u.t.has(Trait.RAGE) && !u.raged && m.getHealth() < max * s.rageThreshold) rage(u);

        if (u.t.has(Trait.RETREAT) && !u.t.leader && !u.retreating(now) && now >= u.cdRetreat
                && u.combatTarget != null && m.getHealth() < max * s.retreatThreshold) {
            u.retreatUntil = now + 70;
            u.cdRetreat = now + 400;
            m.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 1));
            u.combatTarget = null;
            mgr.setTargetSafe(m, null);
        }
        m.getLocation(ml);
        if (u.retreating(now)) {
            if (!u.t.flying) goToSlot(u, 1.4);
            remember(u);
            return;
        }

        LivingEntity desired = chooseTarget(u, now);
        u.combatTarget = desired;

        boolean orbiting = false;
        if (desired != null && u.t.has(Trait.ORBIT)) {
            if (now >= u.diveUntil) {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                u.orbitUntil = now + 30 + rnd.nextInt(40);
                u.diveUntil = u.orbitUntil + 50 + rnd.nextInt(30);
            }
            orbiting = now < u.orbitUntil;
        }
        LivingEntity mobTarget = orbiting ? null : desired;
        if (m.getTarget() != mobTarget) mgr.setTargetSafe(m, mobTarget);
        if (!orbiting && desired != null && m instanceof Vex v) v.setCharging(true);

        if (desired == null) {
            if (!u.t.flying) {
                goToSlot(u, 1.0);
                if (f.fortify && f.build && u.t.has(Trait.BUILDER)) mgr.engineer.fortify(u, now);
                if (u.hasSlot) mgr.engineer.engineer(u, u.slotX, u.slotY, u.slotZ, false, now);
            }
        } else {
            abilities(u, desired, now);
            if (!u.t.flying) {
                desired.getLocation(tl);
                mgr.engineer.engineer(u, tl.getX(), tl.getY(), tl.getZ(), true, now);
            }
        }
        remember(u);
    }

    private void remember(Unit u) {
        u.lastX = ml.getX();
        u.lastY = ml.getY();
        u.lastZ = ml.getZ();
        u.hasLast = true;
    }

    static double maxHealth(LivingEntity e) {
        AttributeInstance ai = e.getAttribute(Attribute.MAX_HEALTH);
        return ai == null ? 20 : ai.getValue();
    }

    // ------------------------------------------------------------------ targeting

    private LivingEntity chooseTarget(Unit u, int now) {
        Faction f = u.f;
        Mob m = u.mob;
        UnitSettings s = mgr.s;
        if (f.focus != null) {
            Entity e = Bukkit.getEntity(f.focus);
            if (e instanceof LivingEntity le && le.isValid() && le.getWorld() == m.getWorld() && attackable(le)) return le;
            f.focus = null;
        }
        if (f.mode == Faction.Mode.PASSIVE && !f.eventHostile) return null;

        boolean defend = f.mode == Faction.Mode.DEFEND && !f.eventHostile;
        double cx = ml.getX(), cy = ml.getY(), cz = ml.getZ();
        if (defend && u.hasSlot) {
            cx = u.slotX;
            cy = u.slotY;
            cz = u.slotZ;
        }
        double range = defend ? s.defendRange : s.aggroRange;

        LivingEntity cur = u.combatTarget;
        if (cur != null && cur.isValid() && cur.getWorld() == m.getWorld() && attackable(cur) && isEnemy(f, cur, now)) {
            cur.getLocation(tl);
            double keep = range + 8;
            if (dsq(tl, cx, cy, cz) < keep * keep) return cur;
        }
        Candidates c = candidates(f, m.getWorld(), now);
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        double rSq = range * range;
        for (int i = 0, n = c.list.size(); i < n; i++) {
            LivingEntity le = c.list.get(i);
            if (!le.isValid()) continue;
            le.getLocation(tl);
            if (dsq(tl, cx, cy, cz) > rSq) continue;
            double d = dsq(tl, ml.getX(), ml.getY(), ml.getZ());
            int[] att = c.attackers.get(le);
            double score = d / (1 + (att == null ? 0 : att[0]) * 0.75); // focus fire: prefer what allies already hit
            if (score < bestScore) {
                bestScore = score;
                best = le;
            }
        }
        if (best != null) c.attackers.computeIfAbsent(best, k -> new int[1])[0]++;
        return best;
    }

    private Candidates candidates(Faction f, World w, int now) {
        Candidates c = cache.computeIfAbsent(f, k -> new Candidates());
        if (c.world == w && now - c.tick < 10) return c;
        c.tick = now;
        c.world = w;
        c.list.clear();
        c.attackers.clear();
        for (Unit o : mgr.all()) {
            if (o.removed) continue;
            if (o.f == f) {
                if (o.combatTarget != null) c.attackers.computeIfAbsent(o.combatTarget, k -> new int[1])[0]++;
                continue;
            }
            if (o.mob.getWorld() == w && isEnemy(f, o.mob, now)) c.list.add(o.mob);
        }
        for (Player p : w.getPlayers()) {
            if (attackable(p) && isEnemy(f, p, now)) c.list.add(p);
        }
        if (mgr.s.attackMonsters && !f.eventHostile && f.anchor != null && f.anchor.getWorld() == w) {
            for (LivingEntity e : w.getNearbyLivingEntities(f.anchor, mgr.s.aggroRange + 8, x -> x instanceof Enemy && !mgr.isUnit(x))) {
                c.list.add(e);
            }
        }
        return c;
    }

    /** Drops the per-faction caches (factions disbanded, events ended). */
    void forget(Faction f) {
        cache.remove(f);
    }

    static boolean attackable(LivingEntity e) {
        if (!e.isValid() || e.isDead()) return false;
        if (e instanceof Player p) {
            GameMode gm = p.getGameMode();
            return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
        }
        return true;
    }

    boolean isEnemy(Faction f, LivingEntity e, int now) {
        if (e == null) return false;
        Unit o = mgr.unit(e);
        if (o != null) {
            if (o.f == f) return false;
            return f.eventHostile || o.f.eventHostile || (f.fightFactions && o.f.fightFactions);
        }
        if (e instanceof Player p) {
            if (f.eventHostile) return true;
            if (f.members.contains(p.getUniqueId())) return false;
            return f.attackOutsiders || f.hasGrudge(p.getUniqueId(), now);
        }
        if (f.eventHostile) return false;
        return e instanceof Enemy;
    }

    private static double dsq(Location l, double x, double y, double z) {
        double dx = l.getX() - x, dy = l.getY() - y, dz = l.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------------ movement orders

    void goToSlot(Unit u, double speed) {
        if (!u.hasSlot) return;
        Mob m = u.mob;
        double d = Math.sqrt(dsq(ml, u.slotX, u.slotY, u.slotZ));
        Location slot = new Location(m.getWorld(), u.slotX, u.slotY, u.slotZ, u.slotYaw, 0);
        if (d > 48 && mgr.s.teleportStragglers) {
            m.teleport(slot);
        } else if (d > 1.4) {
            m.getPathfinder().moveTo(slot, d > 10 ? Math.max(speed, 1.35) : speed);
        } else {
            m.getPathfinder().stopPathfinding();
            m.setRotation(u.slotYaw, 0f);
        }
    }

    // ------------------------------------------------------------------ abilities

    private void abilities(Unit u, LivingEntity tgt, int now) {
        Mob m = u.mob;
        UnitTemplate t = u.t;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        tgt.getLocation(tl);
        double dist = Math.sqrt(dsq(tl, ml.getX(), ml.getY(), ml.getZ()));

        if (t.has(Trait.BLINK) && now >= u.cdBlink && dist > 4 && dist < 26 && rnd.nextDouble() < 0.5) {
            if (blinkBehind(u, tgt)) u.cdBlink = now + 140 + rnd.nextInt(100);
        }
        if (t.has(Trait.SUMMON) && !t.summon.isEmpty() && now >= u.cdSummon && dist < 30) {
            u.cdSummon = now + 300 + rnd.nextInt(120);
            UnitTemplate st = plugin.factions().template(t.summon);
            if (st != null) {
                m.getWorld().playSound(ml, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 0.6f);
                for (int i = 0; i < t.summonCount; i++) {
                    Location at = ml.clone().add(rnd.nextDouble(-1.5, 1.5), st.flying ? 2 : 0.2, rnd.nextDouble(-1.5, 1.5));
                    Unit s = mgr.spawn(u.f, st, at);
                    if (s != null) {
                        s.combatTarget = tgt;
                        mgr.setTargetSafe(s.mob, tgt);
                    }
                }
            }
        }
        if (t.has(Trait.FLICKER) && !t.has(Trait.INVISIBLE) && u.flickerUntil == 0 && rnd.nextDouble() < 0.08) {
            m.setInvisible(true);
            u.flickerUntil = now + 8;
            m.getWorld().spawnParticle(Particle.SQUID_INK, ml.getX(), ml.getY() + 0.8, ml.getZ(), 6, 0.2, 0.4, 0.2, 0.02);
        }
        if (t.has(Trait.LAUGH) && now >= u.cdLaugh && rnd.nextDouble() < 0.35) {
            m.getWorld().playSound(ml, Sound.ENTITY_WITCH_CELEBRATE, 1.2f, 1.3f + rnd.nextFloat() * 0.3f);
            u.cdLaugh = now + 60 + rnd.nextInt(80);
        }
    }

    private boolean blinkBehind(Unit u, LivingEntity tgt) {
        Mob m = u.mob;
        Location t = tgt.getLocation();
        Vector back = t.getDirection().setY(0);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (back.lengthSquared() < 1e-4) back = new Vector(rnd.nextDouble() - 0.5, 0, rnd.nextDouble() - 0.5);
        back.normalize().multiply(-2.2);
        Location dest = t.clone().add(back);
        if (u.t.flying) {
            dest.add(0, 1.5, 0);
        } else {
            Location g = Ground.groundAt(dest.getWorld(), dest.getX(), dest.getY(), dest.getZ());
            if (Math.abs(g.getY() - t.getY()) > 3) return false;
            dest = g;
        }
        dest.setDirection(t.toVector().subtract(dest.toVector()));
        World w = m.getWorld();
        Location from = m.getLocation().add(0, 1, 0);
        w.spawnParticle(Particle.SQUID_INK, from, 15, 0.3, 0.6, 0.3, 0.05);
        w.spawnParticle(Particle.REVERSE_PORTAL, from, 30, 0.3, 0.6, 0.3, 0.2);
        w.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
        m.teleport(dest);
        w.spawnParticle(Particle.SQUID_INK, dest.clone().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.05);
        w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
        return true;
    }

    private void rage(Unit u) {
        Mob m = u.mob;
        u.raged = true;
        m.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, true));
        m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, true));
        Location l = m.getLocation();
        m.getWorld().playSound(l, Sound.ENTITY_RAVAGER_ROAR, 2.5f, 0.5f);
        m.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, l.add(0, 2, 0), 12, 0.6, 0.6, 0.6, 0.1);
    }
}
