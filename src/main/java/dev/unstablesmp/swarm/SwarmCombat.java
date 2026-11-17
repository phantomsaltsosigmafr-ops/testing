package dev.unstablesmp.swarm;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.DamageService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Target upkeep, dives, contact damage, formation-specific attacks (rain, arrowhead, dome cage) and damage flushing. */
final class SwarmCombat {
    private final UnstableSMP plugin;
    private SwarmSettings s;
    private final Vector push = new Vector();

    SwarmCombat(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    void configure(SwarmSettings settings) {
        this.s = settings;
    }

    // ------------------------------------------------------------------ targets

    void setTarget(Swarm sw, LivingEntity t, int now) {
        if (t == null) {
            clearTarget(sw);
            return;
        }
        sw.targetSeen = now;
        if (t == sw.target) return;
        sw.target = t;
        sw.nextHarass = now + 8;
        if (sw.formation == SwarmFormation.DOME_CAGE) beginCage(sw, now);
    }

    void clearTarget(Swarm sw) {
        sw.target = null;
        sw.cageActive = false;
        sw.pending = 0;
        sw.pendingPriority = 0;
        for (Vexbot b : sw.bots) if (b.state == Vexbot.DIVE) b.state = Vexbot.FOLLOW;
    }

    /** Drops dead / far / forgotten / no-longer-harmable targets and caches the target position. */
    void validateTarget(Swarm sw, int now) {
        LivingEntity t = sw.target;
        if (t != null) {
            boolean ok = t.isValid() && !t.isDead() && t.getWorld() == sw.world && now - sw.targetSeen <= s.targetMemoryTicks;
            if (ok) {
                t.getLocation(sw.targetScratch);
                sw.tX = sw.targetScratch.getX();
                sw.tY = sw.targetScratch.getY();
                sw.tZ = sw.targetScratch.getZ();
                sw.tH = t.getHeight();
                double dx = sw.tX - sw.ownerX, dz = sw.tZ - sw.ownerZ, dy = sw.tY - sw.ownerY;
                ok = dx * dx + dy * dy + dz * dz <= s.leashRange * s.leashRange;
            }
            // Region / friendship checks can be costly with WorldGuard: once a second is plenty.
            if (ok && (now + sw.hashCode()) % 20 == 0) ok = plugin.targeting().canHarm(sw.caster, t);
            if (!ok) clearTarget(sw);
        }
        if (sw.anchored && sw.target == null && (now + sw.hashCode()) % 20 == 0) acquireNear(sw, now);
    }

    private void acquireNear(Swarm sw, int now) {
        Location at = new Location(sw.world, sw.ownerX, sw.ownerY, sw.ownerZ);
        LivingEntity best = null;
        double bestSq = s.leashRange * s.leashRange * 0.5;
        for (LivingEntity e : sw.world.getNearbyLivingEntities(at, s.leashRange * 0.7)) {
            if (!plugin.targeting().canHarm(sw.caster, e)) continue;
            double d = e.getLocation().distanceSquared(at);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        if (best != null) {
            setTarget(sw, best, now);
            sw.formation = SwarmFormation.SPIRAL_VORTEX;
            sw.formationSince = now;
            sw.reassign = true;
        } else if (sw.formation != SwarmFormation.HALO) {
            sw.formation = SwarmFormation.HALO;
            sw.formationSince = now;
            sw.reassign = true;
        }
    }

    // ------------------------------------------------------------------ dives

    void startDive(Swarm sw, Vexbot b, String key, int now) {
        b.state = Vexbot.DIVE;
        b.diveKey = key;
        b.diveHit = false;
        b.stateUntil = now + 50;
    }

    /** Steers a diving bot at the target and resolves the hit; the bot returns to its slot afterwards. */
    void diveStep(Swarm sw, Vexbot b, int now) {
        if (sw.target == null || now >= b.stateUntil) {
            b.state = Vexbot.FOLLOW;
            return;
        }
        double cy = sw.tY + sw.tH * 0.55;
        SwarmSteering.step(b, sw.bots, sw.tX, cy, sw.tZ, s.diveSpeed, s, now);
        double dx = b.x - sw.tX, dy = b.y - cy, dz = b.z - sw.tZ;
        double r = s.contactRadius + 0.3;
        if (dx * dx + dy * dy + dz * dz < r * r) {
            if (!sw.silenced(now)) {
                sw.addDamage(s.diveDamage, b.diveKey, b.diveKey.equals("swarm") ? 1 : 3);
                sw.world.spawnParticle(Particle.CRIT, b.x, b.y, b.z, 6, 0.2, 0.2, 0.2, 0.2);
                if ((now & 3) == 0) sw.world.playSound(sw.targetScratch, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 0.6f);
            }
            b.state = Vexbot.FOLLOW;
            b.vx *= -0.4;
            b.vz *= -0.4;
            b.vy = 0.55;
        }
    }

    // ------------------------------------------------------------------ per-tick attacks

    void afterMove(Swarm sw, SwarmFormation f, int now) {
        if (sw.target == null || sw.silenced(now)) {
            sw.pending = 0;
            sw.pendingPriority = 0;
            return;
        }
        int age = now - sw.formationSince;
        double cy = sw.tY + sw.tH * 0.55;
        double rSq = s.contactRadius * s.contactRadius;
        String contactKey = sw.anchored ? "portal_swarm" : f.damageKey;
        for (int i = 0, n = sw.bots.size(); i < n; i++) {
            Vexbot b = sw.bots.get(i);
            if (b.state != Vexbot.FOLLOW || now < b.nextHitTick) continue;
            double dx = b.x - sw.tX, dy = b.y - cy, dz = b.z - sw.tZ;
            if (dx * dx + dy * dy + dz * dz < rSq) {
                sw.addDamage(s.contactDamage, contactKey, 1);
                b.nextHitTick = now + s.botHitCooldown;
            }
        }

        if ((f.harasses() || sw.anchored) && s.harassCount > 0 && now >= sw.nextHarass) {
            sw.nextHarass = now + s.harassInterval;
            launchNearest(sw, s.harassCount, sw.anchored ? "portal_swarm" : "swarm", now);
        }
        if (f == SwarmFormation.RAIN && age > 20 && now >= sw.nextRainDrop) {
            sw.nextRainDrop = now + s.rainDropInterval;
            int n = sw.bots.size();
            for (int k = 0; k < n; k++) {
                Vexbot b = sw.bots.get((sw.rainCursor + k) % n);
                if (b.state != Vexbot.FOLLOW) continue;
                sw.rainCursor = (sw.rainCursor + k + 1) % n;
                startDive(sw, b, "rain", now);
                b.vy = -0.6;
                break;
            }
        }
        if (f == SwarmFormation.ARROWHEAD_DIVE) {
            if (!sw.arrowLaunched && age >= s.arrowheadAssembleTicks) {
                sw.arrowLaunched = true;
                sw.arrowLaunchTick = now;
                for (Vexbot b : sw.bots) if (b.state == Vexbot.FOLLOW) startDive(sw, b, "arrowhead_dive", now);
                sw.world.playSound(sw.targetScratch, Sound.ENTITY_ENDERMAN_SCREAM, 1.2f, 0.6f);
                sw.world.playSound(sw.targetScratch, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.6f);
            } else if (sw.arrowLaunched && now - sw.arrowLaunchTick >= s.arrowheadReturnTicks) {
                plugin.swarms().setFormation(sw, sw.returnFormation, now);
            }
        }
        if (f == SwarmFormation.DOME_CAGE) enforceCage(sw, now, age);
        if (now % s.damageInterval == 0) flush(sw);
    }

    /** Sends the {@code count} following bots closest to the target into a dive. Returns how many went. */
    int launchNearest(Swarm sw, int count, String key, int now) {
        int launched = 0;
        while (launched < count) {
            Vexbot best = null;
            double bestSq = Double.MAX_VALUE;
            for (Vexbot b : sw.bots) {
                if (b.state != Vexbot.FOLLOW) continue;
                double dx = b.x - sw.tX, dy = b.y - sw.tY, dz = b.z - sw.tZ;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestSq) {
                    bestSq = d;
                    best = b;
                }
            }
            if (best == null) break;
            startDive(sw, best, key, now);
            launched++;
        }
        if (launched > 0 && sw.world != null) {
            sw.world.playSound(sw.targetScratch, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.6f);
        }
        return launched;
    }

    private void flush(Swarm sw) {
        if (sw.pending <= 0 || sw.target == null) return;
        double amount = Math.min(sw.pending, s.maxDamagePerHit);
        String key = sw.pendingKey;
        sw.pending = 0;
        sw.pendingPriority = 0;
        plugin.damage().deal(sw.target, amount, sw.caster, DamageService.Kind.MELEE, key);
    }

    // ------------------------------------------------------------------ dome cage

    void beginCage(Swarm sw, int now) {
        if (sw.target == null) return;
        sw.target.getLocation(sw.targetScratch);
        sw.cageX = sw.targetScratch.getX();
        sw.cageY = sw.targetScratch.getY();
        sw.cageZ = sw.targetScratch.getZ();
        sw.cageActive = true;
        sw.cageUntil = now + s.domeAssembleTicks + s.domeMaxTicks;
        sw.world.playSound(sw.targetScratch, Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.6f);
    }

    private void enforceCage(Swarm sw, int now, int age) {
        if (!sw.cageActive || sw.target == null) return;
        if (now >= sw.cageUntil) {
            sw.cageActive = false;
            plugin.swarms().setFormation(sw, sw.returnFormation, now);
            return;
        }
        if (age < s.domeAssembleTicks) return;
        LivingEntity t = sw.target;
        if (t instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) return;
        double dx = sw.tX - sw.cageX, dz = sw.tZ - sw.cageZ;
        double h = Math.sqrt(dx * dx + dz * dz);
        double wall = s.formations.domeRadius - 0.8;
        if (h > wall + 6) {
            sw.cageActive = false; // escaped somehow: cage breaks
            return;
        }
        boolean above = sw.tY - sw.cageY > s.formations.domeRadius - 1.6;
        if (h > wall || above) {
            double nx = h > 1e-3 ? -dx / h : 0, nz = h > 1e-3 ? -dz / h : 0;
            push.setX(h > wall ? nx * s.domePush : t.getVelocity().getX());
            push.setY(above ? -0.4 : 0.15);
            push.setZ(h > wall ? nz * s.domePush : t.getVelocity().getZ());
            t.setVelocity(push);
            World w = sw.world;
            w.spawnParticle(Particle.REVERSE_PORTAL, sw.tX - nx * 0.6, sw.tY + 1, sw.tZ - nz * 0.6, 12, 0.2, 0.6, 0.2, 0.05);
            if ((now & 7) == 0) w.playSound(sw.targetScratch, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.5f);
        }
    }

    /** Is this player held by an active dome cage, and is {@code to} outside it? */
    boolean blocksTeleport(Swarm sw, Player p, Location to) {
        if (!sw.cageActive || sw.target != p || to.getWorld() != sw.world) return false;
        double dx = to.getX() - sw.cageX, dz = to.getZ() - sw.cageZ, dy = to.getY() - sw.cageY;
        double r = s.formations.domeRadius;
        return dx * dx + dz * dz > r * r || dy > r || dy < -2;
    }
}
