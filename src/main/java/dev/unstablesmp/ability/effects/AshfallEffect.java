package dev.unstablesmp.ability.effects;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.combat.DamageService;
import dev.unstablesmp.core.DisplayFactory;
import dev.unstablesmp.core.Ground;
import dev.unstablesmp.core.Transforms;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Cindercrest: Ashfall. Spinning magma meteors rain down at an angle, exploding in fire and ash (no block damage). */
public final class AshfallEffect extends TimedEffect {
    private static final class Meteor {
        final int launch;
        final double sx, sy, sz, ex, ey, ez;
        BlockDisplay display;
        boolean done;

        Meteor(int launch, double sx, double sy, double sz, double ex, double ey, double ez) {
            this.launch = launch;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
        }
    }

    private final List<Meteor> meteors = new ArrayList<>();
    private final int flight;
    private final double radius;
    private final double damage;
    private final int fireTicks;
    private final float size;
    private final Material block;
    private final Location scratch;
    private final Quaternionf q = new Quaternionf();
    private final Vector3f v = new Vector3f();

    public AshfallEffect(UnstableSMP plugin, Caster caster, Location center, int count, double spread, int spacingTicks,
                         int flightTicks, double radius, double damage, int fireTicks, float size, Material block) {
        super(plugin, caster, center.getWorld());
        this.flight = Math.max(4, flightTicks);
        this.radius = radius;
        this.damage = damage;
        this.fireTicks = fireTicks;
        this.size = size;
        this.block = block;
        this.scratch = center.clone();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double slant = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < count; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * spread;
            double ex = center.getX() + Math.cos(a) * r, ez = center.getZ() + Math.sin(a) * r;
            Location g = Ground.groundAt(world, ex, center.getY() + 2, ez);
            double sx = ex + Math.cos(slant) * 9, sz = ez + Math.sin(slant) * 9, sy = g.getY() + 24;
            meteors.add(new Meteor(startTick + i * spacingTicks, sx, sy, sz, ex, g.getY(), ez));
        }
        world.playSound(center, Sound.ENTITY_BLAZE_AMBIENT, 2f, 0.5f);
    }

    @Override
    public boolean tick(int now) {
        boolean any = false;
        for (Meteor m : meteors) {
            if (m.done) continue;
            any = true;
            int t = now - m.launch;
            if (t < 0) continue;
            if (m.display == null && t == 0) spawn(m);
            double f = Math.min(1.0, t / (double) flight);
            double x = m.sx + (m.ex - m.sx) * f, y = m.sy + (m.ey - m.sy) * f, z = m.sz + (m.ez - m.sz) * f;
            world.spawnParticle(Particle.LARGE_SMOKE, x, y + 0.6, z, 2, 0.15, 0.15, 0.15, 0.01);
            world.spawnParticle(Particle.FLAME, x, y + 0.6, z, 3, 0.2, 0.2, 0.2, 0.02);
            if (t % 3 == 0) world.spawnParticle(Particle.LAVA, x, y + 0.6, z, 1, 0, 0, 0, 0);
            if (m.display != null && m.display.isValid() && t % 2 == 0) {
                double nf = Math.min(1.0, (t + 2) / (double) flight);
                scratch.setX(m.sx + (m.ex - m.sx) * nf);
                scratch.setY(m.sy + (m.ey - m.sy) * nf);
                scratch.setZ(m.sz + (m.ez - m.sz) * nf);
                m.display.teleport(scratch);
                q.rotationXYZ(t * 0.5f, t * 0.3f, t * 0.2f);
                m.display.setInterpolationDelay(0);
                m.display.setInterpolationDuration(2);
                m.display.setTransformation(Transforms.boxAt(q, 0, size / 2, 0, size, size, size, v));
            }
            if (t >= flight) impact(m);
        }
        return any;
    }

    private void spawn(Meteor m) {
        if (!plugin.tracker().canSpawnDisplays(caster.id(), 1)) return;
        Location at = new Location(world, m.sx, m.sy, m.sz);
        m.display = keep(plugin.displays().block(at, block, caster.id(), d -> {
            d.setTeleportDuration(2);
            d.setTransformation(Transforms.boxAt(q, 0, size / 2, 0, size, size, size, v));
            DisplayFactory.fullBright(d);
            DisplayFactory.glow(d, Color.fromRGB(0xFF5A1F));
        }));
        world.playSound(at, Sound.ENTITY_GHAST_SHOOT, 1.2f, 0.5f);
    }

    private void impact(Meteor m) {
        m.done = true;
        if (m.display != null) {
            plugin.tracker().remove(m.display);
            entities.remove(m.display);
        }
        Location at = new Location(world, m.ex, m.ey, m.ez);
        world.spawnParticle(Particle.EXPLOSION, m.ex, m.ey + 0.5, m.ez, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.LAVA, m.ex, m.ey + 0.3, m.ez, 12, radius * 0.3, 0.2, radius * 0.3, 0);
        world.spawnParticle(Particle.ASH, m.ex, m.ey + 1, m.ez, 60, radius * 0.5, 0.8, radius * 0.5, 0.02);
        world.spawnParticle(Particle.BLOCK, m.ex, m.ey + 0.2, m.ez, 30, 0.6, 0.2, 0.6, 0, block.createBlockData());
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.3f);
        world.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 0.6f);
        double rSq = radius * radius;
        for (LivingEntity e : world.getNearbyLivingEntities(at, radius, radius)) {
            Location l = e.getLocation();
            double dx = l.getX() - m.ex, dy = l.getY() - m.ey, dz = l.getZ() - m.ez;
            if (dx * dx + dy * dy + dz * dz > rSq || !plugin.targeting().canHarm(caster, e)) continue;
            plugin.damage().deal(e, damage, caster, DamageService.Kind.EXPLOSION, "ashfall");
            e.setFireTicks(Math.max(e.getFireTicks(), fireTicks));
        }
    }
}
