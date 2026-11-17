package dev.unstablesmp.ability.effects;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.combat.DamageService;
import dev.unstablesmp.combat.Mark;
import dev.unstablesmp.core.DisplayFactory;
import dev.unstablesmp.core.Fx;
import dev.unstablesmp.core.Transforms;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Null Field: a glitching void zone. Enemies inside get Darkness + Slowness and can't regenerate. */
public final class NullFieldEffect extends TimedEffect {
    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(0x0A0A0A), 1.6f);
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.fromRGB(0xF2F2F2), 1.2f);

    private final Location center;
    private final double radius;
    private final int duration;
    private final int slowAmp;
    private final double damagePerPulse;
    private final BlockDisplay[] glitches;
    private final Location scratch;
    private final Quaternionf q = new Quaternionf();
    private final Vector3f v = new Vector3f();

    public NullFieldEffect(UnstableSMP plugin, Caster caster, Location center, double radius, int durationTicks,
                           int slowAmp, double damagePerPulse, int glitchCubes) {
        super(plugin, caster, center.getWorld());
        this.center = center.clone();
        this.radius = radius;
        this.duration = durationTicks;
        this.slowAmp = slowAmp;
        this.damagePerPulse = damagePerPulse;
        this.scratch = center.clone();
        this.glitches = new BlockDisplay[glitchCubes];
        DisplayFactory df = plugin.displays();
        for (int i = 0; i < glitchCubes; i++) {
            if (!plugin.tracker().canSpawnDisplays(caster.id(), 1)) break;
            Material m = i % 2 == 0 ? Material.BLACK_CONCRETE : Material.WHITE_CONCRETE;
            glitches[i] = keep(df.block(center, m, caster.id(), d -> {
                d.setTeleportDuration(0);
                d.setInterpolationDuration(3);
                d.setTransformation(Transforms.collapsed(0, 0, 0));
                DisplayFactory.fullBright(d);
            }));
        }
        world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 0.5f);
        world.playSound(center, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.8f, 1.6f);
    }

    @Override
    public boolean tick(int now) {
        int age = age(now);
        if (age >= duration) return false;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double cx = center.getX(), cy = center.getY() + 0.15, cz = center.getZ();
        if (age % 4 == 0) {
            Fx.ring(world, cx, cy, cz, radius, 28, (age / 4) % 2 == 0 ? BLACK : WHITE);
            for (int i = 0; i < 4; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * radius;
                world.spawnParticle(Particle.SQUID_INK, cx + Math.cos(a) * r, cy + rnd.nextDouble() * 2, cz + Math.sin(a) * r, 1, 0, 0, 0, 0);
                world.spawnParticle(Particle.WHITE_ASH, cx + Math.cos(a) * r, cy + rnd.nextDouble() * 2.5, cz + Math.sin(a) * r, 2, 0.1, 0.1, 0.1, 0);
            }
        }
        if (age % 3 == 0) {
            for (BlockDisplay d : glitches) {
                if (d == null || !d.isValid()) continue;
                double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * radius * 0.85;
                scratch.setX(cx + Math.cos(a) * r);
                scratch.setY(cy + 0.4 + rnd.nextDouble() * 2.2);
                scratch.setZ(cz + Math.sin(a) * r);
                d.teleport(scratch);
                float s = rnd.nextFloat() < 0.25f ? 0.05f : 0.2f + rnd.nextFloat() * 0.35f;
                q.rotationXYZ(rnd.nextFloat() * 3f, rnd.nextFloat() * 3f, rnd.nextFloat() * 3f);
                d.setInterpolationDelay(0);
                d.setTransformation(Transforms.boxAt(q, 0, 0, 0, s, s * (0.3f + rnd.nextFloat()), s, v));
            }
        }
        if (age % 25 == 0) world.playSound(center, Sound.BLOCK_SCULK_SENSOR_CLICKING, 1f, 0.6f + rnd.nextFloat() * 0.4f);
        if (age % 10 == 0) pulse(now);
        return true;
    }

    private void pulse(int now) {
        double rSq = radius * radius;
        for (LivingEntity e : world.getNearbyLivingEntities(center, radius, 3.0)) {
            Location l = e.getLocation();
            double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ();
            if (dx * dx + dz * dz > rSq || !plugin.targeting().canHarm(caster, e)) continue;
            e.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, true));
            e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, slowAmp, false, true));
            plugin.marks().apply(e, Mark.IN_NULL_FIELD, now, 15);
            if (damagePerPulse > 0) plugin.damage().deal(e, damagePerPulse, caster, DamageService.Kind.MAGIC, "null_field");
        }
    }

    @Override
    public boolean disruptible() {
        return true;
    }

    @Override
    public boolean isNear(org.bukkit.World w, double x, double y, double z, double r) {
        if (w != world) return false;
        double dx = center.getX() - x, dz = center.getZ() - z;
        double rr = r + radius;
        return dx * dx + dz * dz <= rr * rr;
    }
}
