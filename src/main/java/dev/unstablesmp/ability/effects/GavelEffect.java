package dev.unstablesmp.ability.effects;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.combat.DamageService;
import dev.unstablesmp.core.DisplayFactory;
import dev.unstablesmp.core.Fx;
import dev.unstablesmp.core.Transforms;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Law: Judgment. A giant gavel built from block displays drops out of the sky, swings down and slams the target
 * area: AoE damage, knockback, bells and white light.
 */
public final class GavelEffect extends TimedEffect {
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(0xFFC83D), 2.0f);
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.WHITE, 1.6f);
    private static final float RAISED = -2.1f;

    private final Location impact;
    private final double radius;
    private final double damage;
    private final double knockback;
    private final float scale;
    private final float handleLen;
    private final float yaw;
    private final int delay;
    private final BlockDisplay handle, head, bandA, bandB;
    private final Quaternionf q = new Quaternionf();
    private final Vector3f v = new Vector3f();
    private final Location pivot;
    private boolean slammed;

    public GavelEffect(UnstableSMP plugin, Caster caster, Location impact, float yawRad, double radius, double damage,
                       double knockback, float scale, int delayTicks, Material headBlock, Material handleBlock, Material bandBlock) {
        super(plugin, caster, impact.getWorld());
        this.impact = impact.clone();
        this.radius = radius;
        this.damage = damage;
        this.knockback = knockback;
        this.scale = scale;
        this.handleLen = 3.4f * scale;
        this.yaw = yawRad;
        this.delay = Math.max(0, delayTicks);
        this.pivot = impact.clone().add(0, handleLen + 0.75 * scale, 0);
        Location sky = pivot.clone().add(0, 14, 0);
        boolean room = plugin.tracker().canSpawnDisplays(caster.id(), 4);
        handle = room ? keep(part(sky, handleBlock)) : null;
        head = room ? keep(part(sky, headBlock)) : null;
        bandA = room ? keep(part(sky, bandBlock)) : null;
        bandB = room ? keep(part(sky, bandBlock)) : null;
        pose(RAISED, 0);
        world.playSound(impact, Sound.BLOCK_BELL_RESONATE, 2f, 0.6f);
    }

    private BlockDisplay part(Location at, Material m) {
        return plugin.displays().block(at, m, caster.id(), d -> {
            d.setTeleportDuration(10);
            DisplayFactory.fullBright(d);
            DisplayFactory.glow(d, Color.fromRGB(0xFFE08A));
        });
    }

    /** Places every part for swing angle {@code alpha} (0 = head straight down under the pivot). */
    private void pose(float alpha, int interp) {
        if (handle == null) return;
        q.rotationY(yaw).rotateX(alpha);
        float s = scale;
        set(handle, interp, 0, -handleLen / 2, 0, 0.32f * s, handleLen, 0.32f * s);
        set(head, interp, 0, -handleLen, 0, 2.6f * s, 1.35f * s, 1.35f * s);
        set(bandA, interp, 0.85f * s, -handleLen, 0, 0.22f * s, 1.45f * s, 1.45f * s);
        set(bandB, interp, -0.85f * s, -handleLen, 0, 0.22f * s, 1.45f * s, 1.45f * s);
    }

    private void set(BlockDisplay d, int interp, float cx, float cy, float cz, float sx, float sy, float sz) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interp);
        d.setTransformation(Transforms.box(q, cx, cy, cz, sx, sy, sz, v));
    }

    @Override
    public boolean tick(int now) {
        int age = age(now) - delay;
        if (age < 0) return true;
        if (age == 2 && handle != null) {
            for (BlockDisplay d : new BlockDisplay[] {handle, head, bandA, bandB}) if (d.isValid()) d.teleport(pivot);
        }
        if (age == 12) {
            pose(0f, 5);
            world.playSound(impact, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1.5f, 0.6f);
        }
        if (age < 17) {
            if (age % 2 == 0) Fx.dust(world, impact.getX(), impact.getY() + 0.2, impact.getZ(), 6, radius * 0.4, GOLD);
            return true;
        }
        if (!slammed) {
            slammed = true;
            slam(now);
        }
        int after = age - 17;
        if (after <= 6) {
            double r = radius * (after + 1) / 7.0;
            Fx.ring(world, impact.getX(), impact.getY() + 0.2, impact.getZ(), r, 24 + after * 4, after % 2 == 0 ? GOLD : WHITE);
        }
        if (after == 14) {
            for (BlockDisplay d : new BlockDisplay[] {handle, head, bandA, bandB}) {
                if (d == null || !d.isValid()) continue;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(8);
                d.setTransformation(Transforms.collapsed(0, -handleLen, 0));
            }
        }
        return after < 23;
    }

    private void slam(int now) {
        world.playSound(impact, Sound.BLOCK_ANVIL_LAND, 2f, 0.5f);
        world.playSound(impact, Sound.BLOCK_BELL_USE, 2.5f, 0.7f);
        world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
        double x = impact.getX(), y = impact.getY() + 0.3, z = impact.getZ();
        Fx.flash(world, x, y + 1, z, Color.WHITE);
        world.spawnParticle(Particle.END_ROD, x, y + 0.5, z, 60, radius * 0.4, 0.4, radius * 0.4, 0.15);
        world.spawnParticle(Particle.BLOCK, x, y, z, 50, radius * 0.4, 0.1, radius * 0.4, 0.0, Material.GOLD_BLOCK.createBlockData());
        world.spawnParticle(Particle.EXPLOSION, x, y + 0.5, z, 2, 0.4, 0.2, 0.4, 0.0);
        double rSq = radius * radius;
        for (LivingEntity e : world.getNearbyLivingEntities(impact, radius, radius)) {
            Location l = e.getLocation();
            double dx = l.getX() - x, dz = l.getZ() - z;
            double d2 = dx * dx + dz * dz;
            if (d2 > rSq || !plugin.targeting().canHarm(caster, e)) continue;
            double falloff = 1.0 - 0.4 * Math.sqrt(d2) / radius;
            plugin.damage().deal(e, damage * falloff, caster, DamageService.Kind.MAGIC, "judgment");
            double d = Math.max(0.3, Math.sqrt(d2));
            e.setVelocity(new Vector(dx / d * knockback, 0.55, dz / d * knockback));
        }
    }

    @Override
    public boolean disruptible() {
        return false;
    }
}
