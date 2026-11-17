package dev.unstablesmp.faction;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

/** Per-2-tick movement: flyer halos / orbits and ground fighting skills (strafe, crit jumps, leaps, kiting). */
final class UnitMovement {
    private final UnitManager mgr;
    private final Location ml = new Location(null, 0, 0, 0);
    private final Location tl = new Location(null, 0, 0, 0);
    private final Vector vel = new Vector();

    UnitMovement(UnitManager mgr) {
        this.mgr = mgr;
    }

    void tick(Unit u, int now) {
        Mob m = u.mob;
        if (u.flickerUntil != 0 && now >= u.flickerUntil) {
            m.setInvisible(false);
            u.flickerUntil = 0;
        }
        m.getLocation(ml);
        if (u.t.trail != null && (now + u.order) % 4 == 0) {
            m.getWorld().spawnParticle(u.t.trail, ml.getX(), ml.getY() + 0.8, ml.getZ(), 2, 0.2, 0.4, 0.2, 0.0,
                    u.t.trail == Particle.DRAGON_BREATH ? (Object) 1.0f
                            : u.t.trail == Particle.DUST ? u.f.dust : null);
        }
        if (u.t.flying) {
            flyMove(u, now);
            return;
        }
        LivingEntity tgt = u.combatTarget;
        if (tgt == null || !tgt.isValid() || u.retreating(now) || tgt.getWorld() != m.getWorld()) return;
        fightMove(u, tgt, now);
    }

    private void flyMove(Unit u, int now) {
        Mob m = u.mob;
        LivingEntity tgt = u.combatTarget;
        if (tgt != null && tgt.isValid() && tgt.getWorld() == m.getWorld()) {
            if (u.t.has(Trait.ORBIT) && now < u.orbitUntil) {
                tgt.getLocation(tl);
                double cx = tl.getX(), cy = tl.getY() + 2.2, cz = tl.getZ();
                double ang = Math.atan2(ml.getZ() - cz, ml.getX() - cx) + 0.4;
                double r = 4.5;
                vel.setX((cx + Math.cos(ang) * r - ml.getX()) * 0.3);
                vel.setY((cy + Math.sin(now * 0.15 + u.order) * 0.8 - ml.getY()) * 0.3);
                vel.setZ((cz + Math.sin(ang) * r - ml.getZ()) * 0.3);
                clamp(vel, 0.7);
                m.setVelocity(vel);
            }
            return; // diving: vanilla vex charge AI does the rest
        }
        if (!u.hasSlot) return;
        double ang = Math.PI * 2 * u.haloIndex / Math.max(1, u.haloCount) + now * 0.02;
        double sx = u.slotX + Math.cos(ang) * u.haloRadius;
        double sy = u.slotY + Math.sin(now * 0.05 + u.order) * 0.5;
        double sz = u.slotZ + Math.sin(ang) * u.haloRadius;
        double dx = sx - ml.getX(), dy = sy - ml.getY(), dz = sz - ml.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 64 && mgr.s.teleportStragglers) {
            m.teleport(new Location(m.getWorld(), sx, sy, sz));
        } else if (len > 0.6) {
            double sp = Math.min(0.5, len * 0.15) / len;
            vel.setX(dx * sp);
            vel.setY(dy * sp);
            vel.setZ(dz * sp);
            m.setVelocity(vel);
        }
    }

    private void fightMove(Unit u, LivingEntity tgt, int now) {
        Mob m = u.mob;
        tgt.getLocation(tl);
        double dx = tl.getX() - ml.getX(), dz = tl.getZ() - ml.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        if (h < 0.01) return;
        double nx = dx / h, nz = dz / h;
        boolean ground = m.isOnGround();
        Vector v = m.getVelocity();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (u.t.ranged()) {
            if (u.t.has(Trait.KITE) && h < 5 && ground && now % 10 == 0) {
                vel.setX(-nx * 0.4);
                vel.setY(v.getY());
                vel.setZ(-nz * 0.4);
                m.setVelocity(vel);
            }
            return;
        }
        if (u.t.has(Trait.LEAP) && h > 4 && h < 9 && ground && now >= u.cdLeap && Math.abs(tl.getY() - ml.getY()) < 3) {
            vel.setX(nx * 0.85);
            vel.setY(0.42);
            vel.setZ(nz * 0.85);
            m.setVelocity(vel);
            u.cdLeap = now + 70 + rnd.nextInt(40);
            return;
        }
        if (u.t.has(Trait.CRIT) && h < 3.2 && ground && now >= u.cdCrit) {
            vel.setX(v.getX() + nx * 0.08);
            vel.setY(0.42);
            vel.setZ(v.getZ() + nz * 0.08);
            m.setVelocity(vel);
            u.cdCrit = now + 16 + rnd.nextInt(10);
            return;
        }
        if (u.t.has(Trait.STRAFE) && h < 4.5 && ground && now % 6 == 0) {
            if (now >= u.cdStrafeFlip) {
                if (rnd.nextBoolean()) u.strafeDir = -u.strafeDir;
                u.cdStrafeFlip = now + 20 + rnd.nextInt(30);
            }
            vel.setX(v.getX() - nz * 0.16 * u.strafeDir);
            vel.setY(v.getY());
            vel.setZ(v.getZ() + nx * 0.16 * u.strafeDir);
            m.setVelocity(vel);
        }
    }

    private static void clamp(Vector v, double max) {
        double l = v.length();
        if (l > max) v.multiply(max / l);
    }
}
