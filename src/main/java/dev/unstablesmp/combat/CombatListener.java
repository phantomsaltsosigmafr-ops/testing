package dev.unstablesmp.combat;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Messages;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Enforces the combat tag: tagging on PvP, logout = death, blocked commands, teleports and ender pearls. */
public final class CombatListener implements Listener {
    private final UnstableSMP plugin;
    private final CombatTagManager tags;

    public CombatListener(UnstableSMP plugin, CombatTagManager tags) {
        this.plugin = plugin;
        this.tags = tags;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            if (tags.tagOnPve && e.getDamageSource().getCausingEntity() instanceof Player attacker
                    && e.getEntity() instanceof LivingEntity) {
                tags.tag(attacker, null);
            }
            return;
        }
        Entity cause = e.getDamageSource().getCausingEntity();
        if (cause instanceof Player attacker && !attacker.equals(victim)) {
            tags.tag(victim, attacker);
            tags.tag(attacker, null);
        } else if (tags.tagOnPve && cause != null) {
            tags.tag(victim, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        tags.untag(e.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        int now = Bukkit.getCurrentTick();
        if (!tags.killOnLogout || !tags.isTagged(p.getUniqueId(), now) || p.hasPermission("uss.combat.bypass")) {
            tags.untag(p.getUniqueId());
            return;
        }
        UUID killerId = tags.lastAttacker(p.getUniqueId());
        Player killer = killerId == null ? null : Bukkit.getPlayer(killerId);
        plugin.attribution().record(p.getUniqueId(), "combat_log",
                killer != null ? killer.name() : plugin.messages().get("combat.logout-killer"), killerId, now);
        tags.untag(p.getUniqueId());
        p.setHealth(0.0);
        Bukkit.getServer().sendMessage(plugin.messages().get("combat.logout-broadcast", Messages.p("player", p.getName())));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (tags.blockedCommands.isEmpty() || !tags.isTagged(p.getUniqueId(), Bukkit.getCurrentTick())) return;
        String msg = e.getMessage();
        String label = msg.length() > 1 ? msg.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT) : "";
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        for (String blocked : tags.blockedCommands) {
            if (label.equals(blocked)) {
                e.setCancelled(true);
                plugin.messages().send(p, "combat.command-blocked",
                        Messages.p("seconds", Integer.toString(tags.remainingSeconds(p.getUniqueId(), Bukkit.getCurrentTick()))));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!tags.isTagged(p.getUniqueId(), Bukkit.getCurrentTick())) return;
        if (tags.blockedCauses.contains(e.getCause())) {
            e.setCancelled(true);
            plugin.messages().send(p, "combat.teleport-blocked");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPearl(PlayerLaunchProjectileEvent e) {
        if (!tags.blockEnderPearls || !(e.getProjectile() instanceof EnderPearl)) return;
        Player p = e.getPlayer();
        if (!tags.isTagged(p.getUniqueId(), Bukkit.getCurrentTick())) return;
        e.setShouldConsume(false);
        e.setCancelled(true);
        plugin.messages().send(p, "combat.pearl-blocked");
    }
}
