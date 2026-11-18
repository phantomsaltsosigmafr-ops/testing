package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Elytra mace ("ely mace"): rocket straight up, glide to a point high above the target, then dive and smash with a
 * huge fall-scaled hit. Ground melee with the sword between flights.
 */
public final class ElytraMaceTactic extends Tactic {
    private static final int ASCEND = 1, CRUISE = 2, DIVE = 3;
    private final Location l = new Location(null, 0, 0, 0);
    private final Location tl = new Location(null, 0, 0, 0);
    private final Vector v = new Vector();
    private final ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET);

    public ElytraMaceTactic(BotCombat combat) {
        super(combat);
    }

    @Override
    protected boolean special(Bot b, LivingEntity t, double dist, int now, Location bl, Location tl) {
        if (b.phase != 0) return true;
        if (!b.kit.elytra || b.rockets <= 0 || now < b.rocketCd) return false;
        if ((dist > 6 || roll(0.05 + b.s.utility * 0.1)) && b.body.isOnGround() && roll(b.s.utility)) {
            combat.unblock(b);
            b.rockets--;
            b.rocketCd = now + 140;
            b.phase = ASCEND;
            b.phaseStart = now;
            b.airborne = true;
            b.peakY = bl.getY();
            combat.hold(b, rocket);
            b.body.getWorld().playSound(bl, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
            return true;
        }
        return false;
    }

    @Override
    public boolean tick(Bot b, LivingEntity t, int now) {
        if (b.phase == 0) return false;
        Mannequin m = b.body;
        m.getLocation(l);
        t.getLocation(tl);
        b.peakY = Math.max(b.peakY, l.getY());
        double cruise = 18 + 10 * b.tier.skill();
        double dx = tl.getX() - l.getX(), dz = tl.getZ() - l.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        int age = now - b.phaseStart;
        switch (b.phase) {
            case ASCEND -> {
                m.setGliding(true);
                v.setX(h > 0.5 ? dx / h * 0.25 : 0);
                v.setY(1.1);
                v.setZ(h > 0.5 ? dz / h * 0.25 : 0);
                m.setVelocity(v);
                m.getWorld().spawnParticle(Particle.FIREWORK, l, 2, 0.1, 0.1, 0.1, 0.02);
                b.lookPitch = -70;
                if (l.getY() > tl.getY() + cruise || age > 30) {
                    b.phase = CRUISE;
                    b.phaseStart = now;
                    combat.hold(b, b.kit.mace != null ? b.kit.mace : new ItemStack(Material.MACE));
                }
            }
            case CRUISE -> {
                m.setGliding(true);
                double sp = 1.0 + 0.4 * b.tier.skill();
                double vy = (tl.getY() + cruise - l.getY()) * 0.05;
                v.setX(h > 0.5 ? dx / h * sp : 0);
                v.setY(Math.max(-0.3, Math.min(0.3, vy)));
                v.setZ(h > 0.5 ? dz / h * sp : 0);
                m.setVelocity(v);
                b.lookPitch = 10;
                if (h < 3.5 || age > 160) {
                    b.phase = DIVE;
                    b.phaseStart = now;
                    m.getWorld().playSound(l, Sound.ITEM_ELYTRA_FLYING, 1f, 1.4f);
                }
            }
            default -> { // DIVE
                m.setGliding(false);
                double dy = (tl.getY() + t.getHeight() * 0.5) - l.getY();
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double sp = 2.0 + b.tier.skill();
                v.setX(dx / Math.max(0.1, d) * sp);
                v.setY(dy / Math.max(0.1, d) * sp);
                v.setZ(dz / Math.max(0.1, d) * sp);
                m.setVelocity(v);
                b.lookPitch = 80;
                if (d < 2.2 + t.getWidth() * 0.5) {
                    double fall = b.peakY - l.getY();
                    if (!roll(b.s.miss)) combat.smash(b, t, now, Math.max(1.5, fall));
                    land(b);
                } else if (m.isOnGround() || age > 60) {
                    land(b);
                }
            }
        }
        b.lookYaw = BotMovement.yawTo(l.getX(), l.getZ(), tl.getX(), tl.getZ());
        return true;
    }

    private void land(Bot b) {
        b.phase = 0;
        b.airborne = false;
        b.body.setGliding(false);
        b.body.setFallDistance(0);
        combat.hold(b, b.kit.primary(b.mode));
    }
}
