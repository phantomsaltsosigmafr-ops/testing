package dev.unstablesmp.ability.sets;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.ability.AbilityService;
import dev.unstablesmp.ability.AbilityType;
import dev.unstablesmp.ability.Aim;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.swarm.Swarm;
import dev.unstablesmp.swarm.SwarmFormation;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** VEXBOTS: Summon Swarm, Cycle Formation, Dive Strike, Recall. */
public final class VexbotAbilities {
    private final UnstableSMP plugin;

    public VexbotAbilities(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void register(AbilityService s) {
        s.register(AbilityType.SUMMON_SWARM, this::summon);
        s.register(AbilityType.CYCLE_FORMATION, this::cycle);
        s.register(AbilityType.DIVE_STRIKE, this::dive);
        s.register(AbilityType.RECALL, this::recall);
    }

    private Swarm need(Player p) {
        Swarm sw = plugin.swarms().swarmOf(p.getUniqueId());
        if (sw == null) plugin.actionBar().flash(p, plugin.messages().get("swarm.none"), 40);
        return sw;
    }

    /** RC: summon or top up the swarm. Sneak: dismiss it. */
    private boolean summon(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        if (sneak) {
            boolean had = plugin.swarms().dismiss(p.getUniqueId());
            if (had) plugin.actionBar().flash(p, plugin.messages().get("swarm.dismissed"), 40);
            return had;
        }
        Swarm existing = plugin.swarms().swarmOf(p.getUniqueId());
        if (existing != null && existing.aliveCount() >= plugin.swarms().settings().size) {
            plugin.actionBar().flash(p, plugin.messages().get("swarm.full"), 40);
            return false;
        }
        int n = plugin.swarms().summon(p, c);
        if (n < 0) {
            plugin.messages().send(p, "swarm.cap");
            return false;
        }
        plugin.actionBar().flash(p, plugin.messages().get("swarm.summoned", Messages.p("count", Integer.toString(n))), 40);
        return true;
    }

    /** RC: next formation. Sneak: previous formation. */
    private boolean cycle(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        Swarm sw = need(p);
        if (sw == null) return false;
        SwarmFormation next = sw.formation.next(sneak ? -1 : 1);
        return applyFormation(p, sw, next, cfg.getDouble("aim-range", 40));
    }

    /** Shared by the ability and /uss formation. */
    public boolean applyFormation(Player p, Swarm sw, SwarmFormation f, double aimRange) {
        if (f.targetAnchored && sw.target() == null) {
            LivingEntity t = Aim.entity(p, aimRange);
            if (t != null && plugin.targeting().canHarm(sw.caster, t)) plugin.swarms().setTarget(sw, t);
        }
        plugin.swarms().setFormation(sw, f, Bukkit.getCurrentTick());
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        plugin.actionBar().flash(p, plugin.messages().get("swarm.formation", Messages.p("formation", f.label)), 40);
        return true;
    }

    /** RC: some bots dive the target. Sneak: whole-swarm Arrowhead Dive. */
    private boolean dive(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        Swarm sw = need(p);
        if (sw == null) return false;
        LivingEntity t = Aim.entity(p, cfg.getDouble("range", 40));
        if (t == null || !plugin.targeting().canHarm(c, t)) t = sw.target();
        if (t == null || !plugin.targeting().canHarm(c, t)) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.no-target"), 30);
            return false;
        }
        if (sneak) {
            if (!plugin.swarms().arrowhead(sw, t)) return false;
            plugin.actionBar().flash(p, plugin.messages().get("swarm.arrowhead", Messages.c("target", t.name())), 40);
            return true;
        }
        int n = plugin.swarms().diveStrike(sw, t, cfg.getInt("bots", plugin.swarms().settings().diveStrikeCount));
        if (n == 0) return false;
        p.playSound(p.getLocation(), Sound.ENTITY_PHANTOM_SWOOP, 1f, 1.4f);
        return true;
    }

    /** RC: recall into a Halo and forget the target. Sneak: toggle Orbit Shield. */
    private boolean recall(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        Swarm sw = need(p);
        if (sw == null) return false;
        if (sneak) {
            SwarmFormation f = sw.formation == SwarmFormation.ORBIT_SHIELD ? SwarmFormation.HALO : SwarmFormation.ORBIT_SHIELD;
            return applyFormation(p, sw, f, 0);
        }
        plugin.swarms().recall(sw);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.5f);
        plugin.actionBar().flash(p, plugin.messages().get("swarm.recalled"), 40);
        return true;
    }
}
