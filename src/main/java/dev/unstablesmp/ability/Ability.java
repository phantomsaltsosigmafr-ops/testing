package dev.unstablesmp.ability;

import dev.unstablesmp.combat.Caster;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/** One castable ability. Returns true when it actually fired (only then is the cooldown applied). */
@FunctionalInterface
public interface Ability {
    boolean cast(Player p, Caster caster, boolean sneaking, ConfigurationSection cfg);
}
