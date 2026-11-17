package dev.unstablesmp.ability.effects;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.combat.Mark;
import dev.unstablesmp.core.DisplayFactory;
import dev.unstablesmp.core.Fx;
import dev.unstablesmp.core.Keys;
import dev.unstablesmp.core.Transforms;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Law: Sentence. Golden chains anchor the target to the ground: no walking, no jumping, no knockback escape. */
public final class ChainsEffect extends TimedEffect {
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(0xFFD54A), 1.2f);
    private final LivingEntity target;
    private final int duration;
    private final Location anchor;
    private final Vector still = new Vector();

    public ChainsEffect(UnstableSMP plugin, Caster caster, LivingEntity target, int durationTicks, int chains, Material chainBlock) {
        super(plugin, caster, target.getWorld());
        this.target = target;
        this.duration = durationTicks;
        this.anchor = target.getLocation();
        int now = org.bukkit.Bukkit.getCurrentTick();
        plugin.marks().apply(target, Mark.SENTENCED, now, durationTicks);
        root(true);
        Quaternionf q = new Quaternionf();
        Vector3f v = new Vector3f();
        float chestY = (float) (target.getHeight() * 0.55);
        for (int i = 0; i < chains && plugin.tracker().canSpawnDisplays(caster.id(), 1); i++) {
            double a = Math.PI * 2 * i / chains + Math.PI / 4;
            float gx = (float) (Math.cos(a) * 2.4), gz = (float) (Math.sin(a) * 2.4);
            float dx = -gx, dy = chestY, dz = -gz;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            q.rotationTo(0, 1, 0, dx / len, dy / len, dz / len);
            float mx = gx + dx / 2, my = dy / 2, mz = gz + dz / 2;
            keep(plugin.displays().block(anchor, chainBlock, caster.id(), d -> {
                d.setTransformation(Transforms.boxAt(q, mx, my, mz, 1.0f, len, 1.0f, v));
                DisplayFactory.glow(d, Color.fromRGB(0xFFD54A));
            }));
        }
        world.playSound(anchor, Sound.BLOCK_CHAIN_PLACE, 2f, 0.6f);
        world.playSound(anchor, Sound.BLOCK_ANVIL_PLACE, 0.8f, 1.6f);
        world.spawnParticle(Particle.END_ROD, anchor.getX(), anchor.getY() + 1, anchor.getZ(), 30, 0.6, 0.8, 0.6, 0.05);
    }

    private void root(boolean on) {
        modifier(Attribute.MOVEMENT_SPEED, Keys.ROOT_SPEED, on);
        modifier(Attribute.JUMP_STRENGTH, Keys.ROOT_JUMP, on);
    }

    private void modifier(Attribute attr, org.bukkit.NamespacedKey key, boolean on) {
        AttributeInstance ai = target.getAttribute(attr);
        if (ai == null) return;
        ai.removeModifier(key);
        if (on) ai.addTransientModifier(new AttributeModifier(key, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    @Override
    public boolean tick(int now) {
        if (!target.isValid() || target.isDead() || age(now) >= duration) return false;
        Location l = target.getLocation();
        if (l.getWorld() != anchor.getWorld() || l.distanceSquared(anchor) > 36) return false; // teleported away by force
        Vector vel = target.getVelocity();
        if (Math.abs(vel.getX()) > 0.01 || Math.abs(vel.getZ()) > 0.01 || vel.getY() > 0.05) {
            still.setX(0);
            still.setY(Math.min(0, vel.getY()));
            still.setZ(0);
            target.setVelocity(still);
        }
        if (age(now) % 5 == 0) {
            Fx.ring(world, anchor.getX(), anchor.getY() + 0.1, anchor.getZ(), 1.1, 14, GOLD);
            world.spawnParticle(Particle.WAX_ON, l.getX(), l.getY() + 1, l.getZ(), 2, 0.3, 0.5, 0.3, 0.0);
        }
        return true;
    }

    @Override
    protected void onEnd() {
        if (target.isValid()) root(false);
        world.playSound(anchor, Sound.BLOCK_CHAIN_BREAK, 1.5f, 0.8f);
    }

    @Override
    public boolean disruptible() {
        return false;
    }
}
