package dev.unstablesmp.swarm;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.DamageService;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

/** Swarm reactions to the world: target acquisition, owner death/quit/world change, cages, projectiles, raytrace hits. */
public final class SwarmListener implements Listener {
    private final UnstableSMP plugin;
    private final SwarmManager mgr;

    public SwarmListener(UnstableSMP plugin, SwarmManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    /** The owner hit something -> swarm attacks it. Something hit the owner -> swarm defends. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (DamageService.isDealing() || !(e.getEntity() instanceof LivingEntity victim)) return;
        Entity cause = e.getDamageSource().getCausingEntity();
        if (cause instanceof Player p) {
            Swarm sw = mgr.swarmOf(p.getUniqueId());
            if (sw != null && plugin.targeting().canHarm(sw.caster, victim)) mgr.setTarget(sw, victim);
        }
        if (victim instanceof Player owner && cause instanceof LivingEntity attacker) {
            Swarm sw = mgr.swarmOf(owner.getUniqueId());
            if (sw != null && sw.target == null && plugin.targeting().canHarm(sw.caster, attacker)) mgr.setTarget(sw, attacker);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        mgr.dismiss(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        mgr.dismiss(e.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        mgr.dismiss(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN || e.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) return;
        if (e.getTo() != null && mgr.cageBlocksTeleport(e.getPlayer(), e.getTo())) {
            e.setCancelled(true);
            plugin.messages().send(e.getPlayer(), "swarm.caged");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        mgr.projectiles.track(e.getEntity());
    }

    /** Fallback for hitting bots without hitboxes (or when the client missed the Interaction): raytrace on swing. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_AIR || e.getHand() != EquipmentSlot.HAND || mgr.swarmCount() == 0) return;
        Player p = e.getPlayer();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        Swarm bestSwarm = null;
        Vexbot best = null;
        double bestT = 4.5;
        for (Swarm sw : mgr.all()) {
            if (sw.world != p.getWorld() || !mgr.canDamageBots(sw, p)) continue;
            double cx = sw.centroidX - ex, cy = sw.centroidY - ey, cz = sw.centroidZ - ez;
            double reach = 4.5 + Math.sqrt(sw.radiusSq);
            if (cx * cx + cy * cy + cz * cz > reach * reach) continue;
            for (Vexbot b : sw.bots) {
                if (b.state == Vexbot.DYING) continue;
                double bx = b.x - ex, by = b.y + 0.25 - ey, bz = b.z - ez;
                double t = bx * dir.getX() + by * dir.getY() + bz * dir.getZ();
                if (t < 0 || t > bestT) continue;
                double perpSq = bx * bx + by * by + bz * bz - t * t;
                double r = b.shard ? 0.45 : mgr.s.cubeScale * 0.75;
                if (perpSq < r * r) {
                    bestT = t;
                    best = b;
                    bestSwarm = sw;
                }
            }
        }
        if (best != null) mgr.damageBot(bestSwarm, best, dev.unstablesmp.core.Hitboxes.attackDamage(p), p);
    }
}
