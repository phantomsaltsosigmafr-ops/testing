package dev.unstablesmp.core;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;

/**
 * Particle and sound helpers. Handles the 1.21.x particles that require data
 * (DUST needs DustOptions, DRAGON_BREATH needs a Float, FLASH needs a Color) so callers can't get it wrong.
 */
public final class Fx {
    private Fx() {
    }

    public static void particle(World w, Particle p, double x, double y, double z, int count, double spread, double speed) {
        if (p == Particle.DRAGON_BREATH) {
            w.spawnParticle(p, x, y, z, count, spread, spread, spread, speed, 1.0f);
        } else if (p == Particle.FLASH) {
            w.spawnParticle(p, x, y, z, count, spread, spread, spread, speed, Color.WHITE);
        } else if (p == Particle.DUST) {
            w.spawnParticle(p, x, y, z, count, spread, spread, spread, speed, new Particle.DustOptions(Color.PURPLE, 1.2f));
        } else if (p.getDataType() == Void.class) {
            w.spawnParticle(p, x, y, z, count, spread, spread, spread, speed);
        }
    }

    public static void particle(World w, Particle p, Location l, int count, double spread, double speed) {
        particle(w, p, l.getX(), l.getY(), l.getZ(), count, spread, speed);
    }

    public static void dust(World w, double x, double y, double z, int count, double spread, Particle.DustOptions dust) {
        w.spawnParticle(Particle.DUST, x, y, z, count, spread, spread, spread, 0.0, dust);
    }

    public static void flash(World w, double x, double y, double z, Color color) {
        w.spawnParticle(Particle.FLASH, x, y, z, 1, 0, 0, 0, 0, color);
    }

    public static void sound(World w, Location at, Sound sound, float volume, float pitch) {
        w.playSound(at, sound, volume, pitch);
    }

    public static void sound(Location at, Sound sound, float volume, float pitch) {
        World w = at.getWorld();
        if (w != null) w.playSound(at, sound, volume, pitch);
    }

    /** A flat ring of dust particles (used for zones and shockwaves). */
    public static void ring(World w, double cx, double cy, double cz, double radius, int points, Particle.DustOptions dust) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            w.spawnParticle(Particle.DUST, cx + Math.cos(a) * radius, cy, cz + Math.sin(a) * radius, 1, 0, 0, 0, 0, dust);
        }
    }

    public static void ring(World w, Particle p, double cx, double cy, double cz, double radius, int points) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            particle(w, p, cx + Math.cos(a) * radius, cy, cz + Math.sin(a) * radius, 1, 0, 0);
        }
    }
}
