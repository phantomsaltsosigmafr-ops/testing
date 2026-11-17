package dev.unstablesmp.ability.effects;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.combat.Mark;
import dev.unstablesmp.core.DisplayFactory;
import dev.unstablesmp.core.Transforms;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.joml.Quaternionf;

/** Null Brand: a glowing sigil rides above the target's head while the brand lasts. */
public final class BrandEffect extends TimedEffect {
    private final LivingEntity target;
    private final int duration;
    private final ItemDisplay sigil;
    private final Quaternionf q = new Quaternionf();

    public BrandEffect(UnstableSMP plugin, Caster caster, LivingEntity target, int durationTicks, Material sigilItem) {
        super(plugin, caster, target.getWorld());
        this.target = target;
        this.duration = durationTicks;
        plugin.marks().apply(target, Mark.NULL_BRAND, org.bukkit.Bukkit.getCurrentTick(), durationTicks);
        ItemDisplay d = null;
        if (plugin.tracker().canSpawnDisplays(caster.id(), 1)) {
            d = plugin.displays().item(target.getLocation(), new ItemStack(sigilItem), caster.id(), it -> {
                it.setBillboard(Display.Billboard.CENTER);
                it.setInterpolationDuration(10);
                it.setTransformation(Transforms.item(0, 0.55f, 0, new Quaternionf(), 0.6f));
                DisplayFactory.fullBright(it);
                DisplayFactory.glow(it, Color.WHITE);
            });
            target.addPassenger(d);
        }
        this.sigil = keep(d);
    }

    public LivingEntity target() {
        return target;
    }

    @Override
    public boolean tick(int now) {
        if (!target.isValid() || target.isDead() || age(now) >= duration) return false;
        if (sigil != null && sigil.isValid()) {
            if (sigil.getVehicle() != target) {
                sigil.teleport(target.getLocation());
                target.addPassenger(sigil);
            }
            if (age(now) % 10 == 0) {
                q.rotationZ((age(now) / 10 % 2 == 0) ? 0.35f : -0.35f);
                sigil.setInterpolationDelay(0);
                sigil.setTransformation(Transforms.item(0, 0.55f + ((age(now) / 10) % 2) * 0.1f, 0, q, 0.6f));
            }
        }
        if (age(now) % 5 == 0) {
            Location l = target.getLocation();
            double h = target.getHeight();
            world.spawnParticle(Particle.SQUID_INK, l.getX(), l.getY() + h * 0.6, l.getZ(), 2, 0.25, 0.4, 0.25, 0.01);
            world.spawnParticle(Particle.WHITE_ASH, l.getX(), l.getY() + h + 0.3, l.getZ(), 4, 0.2, 0.2, 0.2, 0.01);
        }
        return true;
    }

    @Override
    protected void onEnd() {
        if (sigil != null && sigil.isValid() && target.isValid()) target.removePassenger(sigil);
    }
}
