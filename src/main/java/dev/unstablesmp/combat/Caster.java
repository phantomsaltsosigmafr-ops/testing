package dev.unstablesmp.combat;

import dev.unstablesmp.faction.Faction;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Who is responsible for an ability, swarm or effect: a player, a mob, or a server event (no entity).
 * Used for friendly-fire rules, damage attribution and kill messages.
 */
public record Caster(LivingEntity entity, UUID id, Component name, Faction faction) {

    public static Caster of(Player p, Faction faction) {
        return new Caster(p, p.getUniqueId(), p.name(), faction);
    }

    public static Caster server(Component name, Faction faction) {
        return new Caster(null, null, name, faction);
    }

    public Player player() {
        return entity instanceof Player p ? p : null;
    }

    public boolean isServer() {
        return entity == null;
    }

    public boolean valid() {
        return entity == null || entity.isValid();
    }
}
