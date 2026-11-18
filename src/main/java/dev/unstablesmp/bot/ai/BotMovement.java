package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.Vector;

/**
 * Applies a bot's movement intent every tick: ground sprinting/strafing, air control, auto-jump over single blocks,
 * knockback and jump-reset handling. Velocity is set directly (Mannequins have no movement AI to fight).
 */
public final class BotMovement {
    public static final double SPRINT = 0.28;
    public static final double WALK = 0.215;
    private final Location loc = new Location(null, 0, 0, 0);
    private final Vector vel = new Vector();

    /** Ground + air steering. Returns false if an aerial phase owns the velocity this tick. */
    public void apply(Bot b, int now, boolean aerialControlled) {
        Mannequin m = b.body;
        m.getLocation(loc);
        if (Math.abs(wrap(loc.getYaw() - b.lookYaw)) > 0.5f || Math.abs(loc.getPitch() - b.lookPitch) > 0.5f) {
            m.setRotation(b.lookYaw, b.lookPitch);
            m.setBodyYaw(b.lookYaw);
        }
        double hx = loc.getX() - b.lastX, hz = loc.getZ() - b.lastZ;
        boolean moving = b.moveSpeed > 0.08;
        if (moving && hx * hx + hz * hz < 0.0009) b.stuckTicks++;
        else if (hx * hx + hz * hz > 0.01) b.stuckTicks = 0;
        b.lastX = loc.getX();
        b.lastZ = loc.getZ();
        if (aerialControlled) return;

        Vector v = m.getVelocity();
        boolean ground = m.isOnGround();
        double speed = b.moveSpeed;
        if (now < b.eatUntil) speed = Math.min(speed, 0.07);
        if (now < b.blockUntil) speed = Math.min(speed, 0.09);
        double rad = Math.toRadians(b.moveYaw);
        double dx = -Math.sin(rad) * speed, dz = Math.cos(rad) * speed;
        boolean knocked = now - b.lastHurt < 5 && !b.jumpResetQueued;

        double vx, vz, vy = v.getY();
        if (knocked) {
            vx = v.getX();
            vz = v.getZ();
        } else if (ground) {
            vx = dx;
            vz = dz;
        } else {
            vx = v.getX() * 0.91 + dx * 0.1;
            vz = v.getZ() * 0.91 + dz * 0.1;
            double h = Math.sqrt(vx * vx + vz * vz), cap = Math.max(speed, Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ()));
            if (h > cap && h > 1e-4) {
                vx *= cap / h;
                vz *= cap / h;
            }
        }
        if (ground) {
            boolean jump = b.wantJump || (moving && blockedAhead(loc.getWorld(), loc, rad));
            if (b.jumpResetQueued) {
                jump = true;
                b.jumpResetQueued = false;
                vx = v.getX() * 0.6; // jump-reset: cut the horizontal knockback
                vz = v.getZ() * 0.6;
            }
            if (jump) {
                vy = 0.42;
                if (b.sprinting && speed > WALK) {
                    vx += -Math.sin(rad) * 0.2;
                    vz += Math.cos(rad) * 0.2;
                }
            }
        }
        b.wantJump = false;
        vel.setX(vx);
        vel.setY(vy);
        vel.setZ(vz);
        m.setVelocity(vel);
    }

    /** Solid block at feet height just ahead, with room to jump onto it. */
    static boolean blockedAhead(World w, Location at, double rad) {
        double ax = at.getX() - Math.sin(rad) * 0.75, az = at.getZ() + Math.cos(rad) * 0.75;
        int bx = (int) Math.floor(ax), by = (int) Math.floor(at.getY() + 0.1), bz = (int) Math.floor(az);
        if (w.getBlockAt(bx, by, bz).isPassable()) return false;
        return w.getBlockAt(bx, by + 1, bz).isPassable() && w.getBlockAt(bx, by + 2, bz).isPassable()
                && w.getBlockAt((int) Math.floor(at.getX()), by + 2, (int) Math.floor(at.getZ())).isPassable();
    }

    public static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    /** Yaw (degrees, Minecraft convention) from (x1,z1) looking at (x2,z2). */
    public static float yawTo(double x1, double z1, double x2, double z2) {
        return (float) Math.toDegrees(Math.atan2(-(x2 - x1), z2 - z1));
    }

    public static float pitchTo(double dx, double dy, double dz) {
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }
}
