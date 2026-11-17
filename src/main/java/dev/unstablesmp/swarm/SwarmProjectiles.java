package dev.unstablesmp.swarm;

import dev.unstablesmp.UnstableSMP;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

/**
 * Projectiles launched while swarms exist are tracked here (no world scans). Each tick they are tested against
 * Orbit Shields (intercepted) and against Vexbots (arrows can shoot bots down).
 */
final class SwarmProjectiles {
    private final UnstableSMP plugin;
    private final SwarmManager mgr;
    private final List<Projectile> live = new ArrayList<>();
    private final Location scratch = new Location(null, 0, 0, 0);

    SwarmProjectiles(UnstableSMP plugin, SwarmManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    void track(Projectile p) {
        if (mgr.swarmCount() > 0 && live.size() < 512) live.add(p);
    }

    void clear() {
        live.clear();
    }

    void tick(int now) {
        if (live.isEmpty()) return;
        List<Swarm> swarms = mgr.all();
        for (int i = live.size() - 1; i >= 0; i--) {
            Projectile pr = live.get(i);
            if (!pr.isValid() || pr.getTicksLived() > 100 || (pr instanceof AbstractArrow a && a.isInBlock()) || swarms.isEmpty()) {
                drop(i);
                continue;
            }
            pr.getLocation(scratch);
            World w = scratch.getWorld();
            double x = scratch.getX(), y = scratch.getY(), z = scratch.getZ();
            LivingEntity shooter = pr.getShooter() instanceof LivingEntity le ? le : null;
            for (Swarm sw : swarms) {
                if (sw.world != w || friendly(sw, shooter)) continue;
                if (!sw.anchored && sw.formation == SwarmFormation.ORBIT_SHIELD && !sw.silenced(now)) {
                    double r = mgr.s.formations.shieldRadius + 0.6;
                    double dx = x - sw.ownerX, dy = y - (sw.ownerY + 1.1), dz = z - sw.ownerZ;
                    if (dx * dx + dy * dy + dz * dz < r * r) {
                        w.spawnParticle(Particle.END_ROD, x, y, z, 10, 0.15, 0.15, 0.15, 0.06);
                        w.spawnParticle(Particle.REVERSE_PORTAL, x, y, z, 12, 0.2, 0.2, 0.2, 0.1);
                        w.playSound(scratch, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 1.5f);
                        pr.remove();
                        drop(i);
                        break;
                    }
                }
                double cx = x - sw.centroidX, cy = y - sw.centroidY, cz = z - sw.centroidZ;
                if (cx * cx + cy * cy + cz * cz > sw.radiusSq + 9) continue;
                Vexbot hit = null;
                for (Vexbot b : sw.bots) {
                    if (b.state == Vexbot.DYING) continue;
                    double r = b.shard ? 0.5 : mgr.s.cubeScale * 0.8;
                    double dx = x - b.x, dy = y - (b.y + 0.25), dz = z - b.z;
                    if (dx * dx + dy * dy + dz * dz < r * r) {
                        hit = b;
                        break;
                    }
                }
                if (hit != null) {
                    mgr.damageBot(sw, hit, mgr.s.projectileBotDamage, shooter instanceof Player p ? p : null);
                    pr.remove();
                    drop(i);
                    break;
                }
            }
        }
    }

    private boolean friendly(Swarm sw, LivingEntity shooter) {
        if (shooter == null) return false;
        if (shooter.getUniqueId().equals(sw.ownerId)) return true;
        return !plugin.targeting().canHarm(sw.caster, shooter);
    }

    private void drop(int i) {
        int last = live.size() - 1;
        live.set(i, live.get(last));
        live.remove(last);
    }
}
