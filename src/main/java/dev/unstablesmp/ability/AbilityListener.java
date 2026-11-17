package dev.unstablesmp.ability;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Mark;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Routes right-click / sneak-right-click on ability items, and enforces Null Field and Sentence rules. */
public final class AbilityListener implements Listener {
    private final UnstableSMP plugin;
    private final Map<UUID, Integer> lastCastTick = new HashMap<>();

    public AbilityListener(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        AbilityType t = AbilityItems.of(e.getItem());
        if (t == null) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        cast(e.getPlayer(), t);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
        AbilityType t = AbilityItems.of(hand);
        if (t == null) return;
        e.setCancelled(true);
        cast(e.getPlayer(), t);
    }

    private void cast(Player p, AbilityType t) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastCastTick.get(p.getUniqueId());
        if (last != null && now - last < 2) return; // entity + air clicks in the same tick count once
        lastCastTick.put(p.getUniqueId(), now);
        plugin.abilities().tryCast(p, t, p.isSneaking());
    }

    /** Null Field: no regeneration inside. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (plugin.marks().has(e.getEntity(), Mark.IN_NULL_FIELD, Bukkit.getCurrentTick())) e.setCancelled(true);
    }

    /** Sentenced players can't pearl or chorus out of their chains. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c != PlayerTeleportEvent.TeleportCause.ENDER_PEARL && c != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) return;
        if (plugin.marks().has(e.getPlayer(), Mark.SENTENCED, Bukkit.getCurrentTick())) {
            e.setCancelled(true);
            plugin.messages().send(e.getPlayer(), "ability.sentenced-teleport");
        }
    }
}
