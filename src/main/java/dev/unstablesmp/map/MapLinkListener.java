package dev.unstablesmp.map;

import dev.unstablesmp.UnstableSMP;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Lobby links: right-click a linked note block/button/sign, or step on a linked pressure plate, to go to a map. */
public final class MapLinkListener implements Listener {
    private final UnstableSMP plugin;
    private final Map<UUID, Long> last = new HashMap<>();

    public MapLinkListener(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Action a = e.getAction();
        if (a == Action.RIGHT_CLICK_BLOCK && e.getHand() != EquipmentSlot.HAND) return;
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.PHYSICAL) return;
        MapService.Link link = plugin.maps().linkAt(b);
        if (link == null) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && p.hasPermission("uss.map") && a == Action.RIGHT_CLICK_BLOCK) return; // ops can still edit
        e.setUseInteractedBlock(Event.Result.DENY);
        long now = System.currentTimeMillis();
        Long l = last.get(p.getUniqueId());
        if (l != null && now - l < 1000) return;
        last.put(p.getUniqueId(), now);
        plugin.games().sendToMap(p, link.map(), link.spawn(), link.kit(), link.team());
    }
}
