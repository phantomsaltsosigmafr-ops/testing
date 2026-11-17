package dev.unstablesmp.ability;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Line-of-sight aiming for abilities: the living entity or block a player is looking at. */
public final class Aim {
    private Aim() {
    }

    /** The first living entity in the crosshair (blocks stop the ray), or null. */
    public static LivingEntity entity(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.6,
                e -> e != p && e instanceof LivingEntity && !(e instanceof ArmorStand) && !e.isDead());
        if (r == null) return null;
        Entity hit = r.getHitEntity();
        return hit instanceof LivingEntity le ? le : null;
    }

    /** The point a player is looking at (block face hit, or the end of the ray). */
    public static Location point(Player p, double range) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        if (r != null) {
            Vector hit = r.getHitPosition();
            if (r.getHitBlockFace() != null) hit.add(r.getHitBlockFace().getDirection().multiply(0.1));
            return hit.toLocation(p.getWorld(), eye.getYaw(), 0);
        }
        return eye.clone().add(dir.multiply(range));
    }

    /** True if the ray hit a block within range. */
    public static Location block(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        if (r == null || r.getHitBlock() == null) return null;
        return r.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
    }
}
