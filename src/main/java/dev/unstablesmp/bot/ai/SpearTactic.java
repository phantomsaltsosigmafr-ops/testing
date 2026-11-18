package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.bot.BotLoadout;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.Vector;

/**
 * Spear (1.21.11): lowers the spear and charges in a straight line, dealing damage scaled by its speed on impact,
 * then jabs at close range. Uses wind charges to re-open distance for another charge.
 */
public final class SpearTactic extends Tactic {
    private final Location l = new Location(null, 0, 0, 0);
    private final Location tl = new Location(null, 0, 0, 0);
    private final Vector v = new Vector();

    public SpearTactic(BotCombat combat) {
        super(combat);
    }

    @Override
    protected boolean special(Bot b, LivingEntity t, double dist, int now, Location bl, Location tl) {
        if (b.phase != 0) return true;
        if (b.kit.spear != null && dist > 4 && dist < 13 && now >= b.chargeCd && b.body.isOnGround() && roll(b.s.utility + 0.2)) {
            combat.unblock(b);
            combat.hold(b, b.kit.spear);
            // Note: no startUsingItem here: 1.21.11's vanilla kinetic-spear tick needs an attack_damage attribute
            // that mannequins don't have. The charge and its speed-scaled damage are fully plugin-driven.
            b.phase = 1;
            b.phaseStart = now;
            b.moveYaw = BotMovement.yawTo(bl.getX(), bl.getZ(), tl.getX(), tl.getZ());
            b.body.getWorld().playSound(bl, Sound.ENTITY_HORSE_GALLOP, 0.6f, 1.4f);
            return true;
        }
        if (dist < 2.5 && b.windCharges > 0 && now >= b.chargeCd - 20 && roll(b.s.utility * 0.2)) {
            // Back off with a wind charge to set up the next charge.
            combat.windLaunch(b, t, now, 0.5);
        }
        return false;
    }

    @Override
    public boolean tick(Bot b, LivingEntity t, int now) {
        if (b.phase == 0) return false;
        Mannequin m = b.body;
        m.getLocation(l);
        t.getLocation(tl);
        double speed = 0.42 + 0.2 * b.tier.skill();
        // Good bots keep steering the charge; weak bots commit to the starting line.
        float to = BotMovement.yawTo(l.getX(), l.getZ(), tl.getX(), tl.getZ());
        float diff = BotMovement.wrap(to - b.moveYaw);
        b.moveYaw += diff * (float) (0.1 + 0.3 * b.tier.skill());
        double rad = Math.toRadians(b.moveYaw);
        Vector cur = m.getVelocity();
        v.setX(-Math.sin(rad) * speed);
        v.setY(cur.getY());
        v.setZ(Math.cos(rad) * speed);
        if (m.isOnGround() && BotMovement.blockedAhead(m.getWorld(), l, rad)) v.setY(0.42);
        m.setVelocity(v);
        b.lookYaw = b.moveYaw;
        b.lookPitch = 5;
        double dx = tl.getX() - l.getX(), dz = tl.getZ() - l.getZ(), dy = tl.getY() - l.getY();
        double dist = Math.sqrt(dx * dx + dz * dz + dy * dy);
        if (dist < 1.6 + t.getWidth() * 0.5) {
            double dmg = BotLoadout.damage(b.kit.spear) + speed * 9;
            if (!roll(b.s.miss)) {
                combat.hit(b, t, now, b.kit.spear, 1.0, dmg, "spear");
                Vector kb = t.getVelocity();
                kb.setX(kb.getX() - Math.sin(rad) * 0.9);
                kb.setY(0.45);
                kb.setZ(kb.getZ() + Math.cos(rad) * 0.9);
                t.setVelocity(kb);
            }
            end(b, now);
        } else if (now - b.phaseStart > 26 || b.stuckTicks > 6) {
            end(b, now);
        }
        return true;
    }

    private void end(Bot b, int now) {
        b.phase = 0;
        b.chargeCd = now + 50 + (int) ((1 - b.tier.skill()) * 40);
        combat.hold(b, b.kit.primary(b.mode));
    }
}
