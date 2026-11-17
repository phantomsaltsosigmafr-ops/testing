package dev.unstablesmp.ability.effects;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.core.Fx;
import org.bukkit.Location;
import org.bukkit.Particle;

/** An expanding particle shockwave (Law: Order, Phoenix Burst). Purely visual. */
public final class RingPulseEffect extends TimedEffect {
    private final Location center;
    private final double radius;
    private final int ticks;
    private final Particle.DustOptions a, b;
    private final Particle extra;

    public RingPulseEffect(UnstableSMP plugin, Caster caster, Location center, double radius, int ticks,
                           Particle.DustOptions a, Particle.DustOptions b, Particle extra) {
        super(plugin, caster, center.getWorld());
        this.center = center.clone();
        this.radius = radius;
        this.ticks = Math.max(2, ticks);
        this.a = a;
        this.b = b;
        this.extra = extra;
    }

    @Override
    public boolean tick(int now) {
        int age = age(now);
        if (age > ticks) return false;
        double r = radius * (age + 1) / (double) ticks;
        int points = (int) Math.max(16, r * 8);
        Fx.ring(world, center.getX(), center.getY() + 0.2, center.getZ(), r, points, age % 2 == 0 ? a : b);
        if (extra != null && age % 2 == 0) Fx.ring(world, extra, center.getX(), center.getY() + 0.6, center.getZ(), r, points / 3);
        return true;
    }
}
