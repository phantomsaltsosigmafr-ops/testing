package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;

/**
 * UHC: no natural regen, so gapples matter. Bow at range (draws, then fires a real arrow), rod to start combos,
 * lava buckets and cobwebs placed as temporary blocks, shield blocking, sword/axe melee.
 */
public final class UhcTactic extends Tactic {
    public UhcTactic(BotCombat combat) {
        super(combat);
    }

    @Override
    protected boolean special(Bot b, LivingEntity t, double dist, int now, Location bl, Location tl) {
        // Drawing the bow: hold still-ish and release after the draw time.
        if (b.phase == 1) {
            if (dist < 5) {
                b.phase = 0;
                b.body.clearActiveItem();
                combat.hold(b, weapon(b));
                return false;
            }
            if (now >= b.phaseStart + 12 + (int) ((1 - b.tier.skill()) * 14)) {
                b.body.clearActiveItem();
                combat.shoot(b, t, 1.0);
                b.phase = 0;
                b.bowCd = now + 20;
                combat.hold(b, weapon(b));
            }
            b.moveSpeed = 0.06;
            return true;
        }
        if (b.kit.bow != null && b.arrows > 0 && dist > 9 && dist < 30 && now >= b.bowCd && roll(b.s.utility)) {
            combat.unblock(b);
            combat.hold(b, b.kit.bow);
            b.body.startUsingItem(EquipmentSlot.HAND);
            b.phase = 1;
            b.phaseStart = now;
            return true;
        }
        if (dist > 3 && dist < 7.5 && roll(b.s.utility * 0.5) && combat.rod(b, t, dist, now)) return false;
        if (dist < 3.2 && roll(b.s.utility * 0.25) && combat.lava(b, t, now)) return false;
        // Being comboed and losing: web the attacker's feet to break the combo.
        if (dist < 2.8 && now - b.lastHurt < 10 && b.healthFraction() < 0.55 && roll(b.s.utility * 0.4)) combat.web(b, tl, now);
        return false;
    }
}
