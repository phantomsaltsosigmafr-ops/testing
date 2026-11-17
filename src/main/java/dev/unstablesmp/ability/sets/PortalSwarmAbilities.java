package dev.unstablesmp.ability.sets;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.ability.AbilityService;
import dev.unstablesmp.ability.AbilityType;
import dev.unstablesmp.ability.Aim;
import dev.unstablesmp.ability.effects.SwarmPortalEffect;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.core.Ground;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/** PORTAL SWARM: tear open a destructible portal that pours waves of Vexbots out for a while. */
public final class PortalSwarmAbilities {
    private final UnstableSMP plugin;

    public PortalSwarmAbilities(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void register(AbilityService s) {
        s.register(AbilityType.PORTAL_SWARM, this::portal);
    }

    /** RC: portal where you look, facing you. Sneak: portal behind you, facing where you face. */
    private boolean portal(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        int maxActive = cfg.getInt("max-active-per-player", 1);
        long active = plugin.effects().ofType(SwarmPortalEffect.class).stream()
                .filter(e -> p.getUniqueId().equals(e.caster().id())).count();
        if (active >= maxActive) {
            plugin.actionBar().flash(p, plugin.messages().get("portal.max-active"), 40);
            return false;
        }
        Location me = p.getLocation();
        Location base;
        float yaw;
        if (sneak) {
            double r = Math.toRadians(me.getYaw());
            base = Ground.groundAt(p.getWorld(), me.getX() + Math.sin(r) * 3.5, me.getY(), me.getZ() - Math.cos(r) * 3.5);
            yaw = (float) Math.atan2(-Math.sin(r), Math.cos(r));
        } else {
            base = Aim.block(p, cfg.getDouble("range", 25));
            if (base == null) {
                plugin.actionBar().flash(p, plugin.messages().get("ability.no-target"), 30);
                return false;
            }
            yaw = (float) Math.atan2(me.getX() - base.getX(), me.getZ() - base.getZ());
        }
        Location center = base.clone().add(0, cfg.getDouble("height", 2.6), 0);
        SwarmPortalEffect e = new SwarmPortalEffect(plugin, c, center, yaw,
                (int) (cfg.getDouble("duration-seconds", 12) * 20), cfg.getInt("wave-interval-ticks", 40),
                cfg.getInt("bots-per-wave", 4), cfg.getInt("max-bots", 16), cfg.getDouble("portal-hp", 40),
                cfg.getInt("ring-blocks", 14));
        return plugin.effects().add(e);
    }
}
