package dev.unstablesmp.core;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.util.RayTraceResult;

/**
 * Routes player attacks on our Interaction hitboxes (Vexbots, swarm portals) to the thing they belong to.
 * The vanilla attack is cancelled; the damage is the player's attack damage scaled by the attack cooldown.
 */
public final class Hitboxes implements Listener {

    /** Something that can be destroyed by hitting its Interaction hitbox. */
    public interface Target {
        /** False for the owner and their allies. */
        boolean canBeHitBy(Player p);

        void hit(Player p, double damage);
    }

    private final Map<UUID, Target> targets = new HashMap<>();
    private boolean passingThrough;

    public void register(Entity hitbox, Target t) {
        targets.put(hitbox.getUniqueId(), t);
    }

    public void unregister(Entity hitbox) {
        if (hitbox != null) targets.remove(hitbox.getUniqueId());
    }

    public void clear() {
        targets.clear();
    }

    public static double attackDamage(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.ATTACK_DAMAGE);
        double base = a == null ? 1.0 : a.getValue();
        return Math.max(0.5, base * Math.max(0.2, p.getAttackCooldown()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        Target t = targets.get(e.getAttacked().getUniqueId());
        if (t == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (t.canBeHitBy(p)) {
            t.hit(p, attackDamage(p));
            return;
        }
        // Your own (or an ally's) hitbox is in the way: pass the swing through to whatever is behind it.
        if (passingThrough) return;
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 4.5, 0.1,
                x -> x != p && x instanceof LivingEntity && !(x instanceof ArmorStand));
        if (r != null && r.getHitEntity() != null) {
            RayTraceResult block = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), 4.5);
            if (block != null && block.getHitPosition().distanceSquared(eye.toVector()) < r.getHitPosition().distanceSquared(eye.toVector())) return;
            passingThrough = true;
            try {
                p.attack(r.getHitEntity());
            } finally {
                passingThrough = false;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (targets.containsKey(e.getRightClicked().getUniqueId())) e.setCancelled(true);
    }
}
