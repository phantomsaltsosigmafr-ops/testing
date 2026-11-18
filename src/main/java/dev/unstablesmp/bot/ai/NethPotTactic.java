package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/**
 * Netherite pot: keeps speed and strength up, splash-heals at its feet (backing off to pot), keeps a totem in the
 * offhand and pearls in aggressively.
 */
public final class NethPotTactic extends Tactic {
    public NethPotTactic(BotCombat combat) {
        super(combat);
    }

    @Override
    protected boolean heal(Bot b, LivingEntity t, double dist, int now) {
        if (now - b.lastHurt < b.s.healDelay / 2) return false;
        double hp = b.healthFraction();
        if (b.healPots > 0 && hp < b.s.healAt + 0.15) return combat.splashHeal(b, now);
        return super.heal(b, t, dist, now);
    }

    @Override
    protected boolean special(Bot b, LivingEntity t, double dist, int now, Location bl, Location tl) {
        combat.buffs(b, now);
        if (dist > 10 && roll(b.s.pearl * 0.5)) combat.pearl(b, tl, now);
        return false;
    }
}
