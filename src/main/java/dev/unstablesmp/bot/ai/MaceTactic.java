package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Mace PvP: wind-charge launch above the target, steer in the air, smash with fall-scaled damage, then use Wind
 * Burst's bounce to chain smashes. Fights with sword/axe on the ground between launches.
 */
public final class MaceTactic extends Tactic {
    private final Location l = new Location(null, 0, 0, 0);
    private final Location tl = new Location(null, 0, 0, 0);
    private final Vector v = new Vector();

    public MaceTactic(BotCombat combat) {
        super(combat);
    }

    @Override
    protected boolean special(Bot b, LivingEntity t, double dist, int now, Location bl, Location tl) {
        if (b.phase != 0) return true;
        if (b.windCharges > 0 && b.body.isOnGround() && dist > 1.5 && dist < 7 && now >= b.windCd && roll(b.s.utility)) {
            combat.unblock(b);
            if (combat.windLaunch(b, t, now, 1.25 + 0.15 * b.tier.skill())) {
                combat.hold(b, b.kit.mace != null ? b.kit.mace : new ItemStack(Material.MACE));
                b.phase = 1;
                b.phaseStart = now;
                b.airborne = true;
                return true;
            }
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
        double dx = tl.getX() - l.getX(), dz = tl.getZ() - l.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        Vector cur = m.getVelocity();
        // Air control: drift over the target's head.
        double ax = h > 0.3 ? dx / h * Math.min(0.06, h * 0.03) : 0, az = h > 0.3 ? dz / h * Math.min(0.06, h * 0.03) : 0;
        v.setX(cur.getX() * 0.95 + ax);
        v.setY(cur.getY());
        v.setZ(cur.getZ() * 0.95 + az);
        m.setVelocity(v);
        b.lookYaw = BotMovement.yawTo(l.getX(), l.getZ(), tl.getX(), tl.getZ());
        b.lookPitch = 60;
        double fall = b.peakY - l.getY();
        double rel = l.getY() - (tl.getY() + t.getHeight());
        if (cur.getY() < 0 && h < 1.6 + t.getWidth() * 0.5 && rel < 1.2 && rel > -t.getHeight() - 0.5 && fall >= 1.5) {
            if (b.s.miss < 0.5 || roll(1 - b.s.miss)) {
                combat.smash(b, t, now, fall);
                m.getWorld().spawnParticle(Particle.EXPLOSION, l, 1, 0, 0, 0, 0);
                if (b.kit.mace != null && b.kit.mace.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.WIND_BURST) > 0 && roll(b.s.combo)) {
                    b.phaseStart = now; // chained: stay airborne for another smash
                    return true;
                }
            }
            land(b);
            return true;
        }
        if ((m.isOnGround() && now - b.phaseStart > 4) || now - b.phaseStart > 80) land(b);
        return true;
    }

    private void land(Bot b) {
        b.phase = 0;
        b.airborne = false;
        combat.hold(b, b.kit.primary(b.mode));
    }
}
