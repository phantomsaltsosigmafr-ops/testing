package dev.unstablesmp.combat;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.faction.Faction;
import dev.unstablesmp.faction.Unit;
import org.bukkit.GameMode;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

/**
 * Who may hurt whom. Shared by swarms, abilities, effects and events so the rules are identical everywhere:
 * never yourself, never your faction's members or units, never creative/spectator players, never pets you own,
 * and player damage respects PvP region rules.
 */
public final class Targeting {
    private final UnstableSMP plugin;

    public Targeting(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public Caster casterOf(Player p) {
        return Caster.of(p, plugin.factions().factionOf(p.getUniqueId()));
    }

    public boolean canHarm(Caster c, LivingEntity t) {
        if (t == null || !t.isValid() || t.isDead()) return false;
        if (c.id() != null && c.id().equals(t.getUniqueId())) return false;
        if (t instanceof ArmorStand) return false;
        Faction cf = c.faction();

        if (t instanceof Player p) {
            GameMode gm = p.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
            if (cf != null && !cf.eventHostile && cf.members.contains(p.getUniqueId())) return false;
            if (c.player() != null && plugin.teams().sameTeam(c.player(), p)) return false;
            return plugin.regions().canPvp(c.player(), p.getLocation());
        }

        dev.unstablesmp.bot.Bot bot = plugin.bots().bot(t);
        if (bot != null) {
            if (c.player() != null && bot.team.equalsIgnoreCase(String.valueOf(plugin.teams().teamOf(c.player())))) return false;
            return cf == null || cf.eventHostile || !bot.team.equalsIgnoreCase(cf.baseId);
        }
        Unit u = plugin.units().unit(t);
        if (u != null) {
            if (cf != null && u.f == cf) return false;
            if (c.id() != null && !u.f.eventHostile && u.f.members.contains(c.id())) return false;
            return true;
        }

        if (t instanceof Tameable tame && tame.isTamed() && c.id() != null && c.id().equals(tame.getOwnerUniqueId())) {
            return false;
        }
        // Server/event casters only fight players and other factions' units, never random animals.
        if (c.isServer()) return t instanceof Enemy && cf == null;
        return true;
    }

    /** Is {@code a} hostile to player {@code p} in the sense of Order / silence effects? */
    public boolean isEnemyPlayer(Caster c, Player p) {
        if (c.id() != null && c.id().equals(p.getUniqueId())) return false;
        Faction cf = c.faction();
        return cf == null || cf.eventHostile || !cf.members.contains(p.getUniqueId());
    }
}
