package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

/** Axe + shield: blocks a lot, axes raised shields (5s shield cooldown), crossbow pokes at range. */
public final class AxeTactic extends Tactic {
    public AxeTactic(BotCombat combat) {
        super(combat);
    }

    @Override
    protected ItemStack weapon(Bot b) {
        return b.kit.axe != null ? b.kit.axe : b.kit.primary(b.mode);
    }

    @Override
    protected boolean special(Bot b, LivingEntity t, double dist, int now, Location bl, Location tl) {
        if (b.kit.crossbow != null && b.arrows > 0 && dist > 8 && dist < 24 && now >= b.bowCd && roll(b.s.utility)) {
            combat.unblock(b);
            combat.hold(b, b.kit.crossbow);
            combat.shoot(b, t, 1.0);
            b.bowCd = now + 45;
            return false;
        }
        // Shield-breaker: switch to the axe the moment the enemy raises a shield.
        if (BotCombat.blocking(t) && dist < b.s.reach + 0.8 && b.charge(now) > 0.5) {
            combat.unblock(b);
            combat.hold(b, weapon(b));
            combat.swing(b, t, dist, now, weapon(b), 0, "axe");
            return false;
        }
        return false;
    }
}
