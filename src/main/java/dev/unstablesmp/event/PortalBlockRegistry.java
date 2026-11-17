package dev.unstablesmp.event;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;

/**
 * Blocks belonging to event portals: they can't be broken, exploded, pushed or changed, and (unless allowed)
 * the portals don't teleport anyone.
 */
public final class PortalBlockRegistry implements Listener {
    private final Map<UUID, Set<Long>> blocks = new HashMap<>();
    private boolean protect = true;
    private boolean allowTeleport;

    public void configure(boolean protect, boolean allowTeleport) {
        this.protect = protect;
        this.allowTeleport = allowTeleport;
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    public void register(List<Block> list) {
        for (Block b : list) blocks.computeIfAbsent(b.getWorld().getUID(), k -> new HashSet<>()).add(key(b.getX(), b.getY(), b.getZ()));
    }

    public void unregister(List<Block> list) {
        for (Block b : list) {
            Set<Long> s = blocks.get(b.getWorld().getUID());
            if (s != null) s.remove(key(b.getX(), b.getY(), b.getZ()));
        }
    }

    public boolean isProtected(Block b) {
        if (blocks.isEmpty()) return false;
        Set<Long> s = blocks.get(b.getWorld().getUID());
        return s != null && s.contains(key(b.getX(), b.getY(), b.getZ()));
    }

    public boolean near(Location loc) {
        World w = loc == null ? null : loc.getWorld();
        if (w == null || blocks.isEmpty()) return false;
        Set<Long> s = blocks.get(w.getUID());
        if (s == null || s.isEmpty()) return false;
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 2; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (s.contains(key(bx + dx, by + dy, bz + dz))) return true;
        return false;
    }

    public void clear() {
        blocks.clear();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (protect && isProtected(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!blocks.isEmpty()) e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!blocks.isEmpty()) e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (isProtected(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (isProtected(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) {
        if (isProtected(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (!allowTeleport && near(e.getFrom())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (!allowTeleport && near(e.getFrom())) e.setCancelled(true);
    }
}
