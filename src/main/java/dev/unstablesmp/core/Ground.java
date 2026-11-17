package dev.unstablesmp.core;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Ground and safe-spot searches shared by abilities, units and events. */
public final class Ground {
    private Ground() {
    }

    /** Standing position (feet) near (x, yHint, z): solid floor with two passable blocks above. */
    public static Location groundAt(World w, double x, double yHint, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(yHint);
        for (int dy = 3; dy >= -8; dy--) {
            int y = by + dy;
            if (y <= w.getMinHeight() || y + 1 >= w.getMaxHeight()) continue;
            Block floor = w.getBlockAt(bx, y - 1, bz);
            if (!floor.isPassable() && w.getBlockAt(bx, y, bz).isPassable() && w.getBlockAt(bx, y + 1, bz).isPassable()) {
                return new Location(w, bx + 0.5, y, bz + 0.5);
            }
        }
        return new Location(w, bx + 0.5, w.getHighestBlockYAt(bx, bz) + 1, bz + 0.5);
    }

    /** Y of the first solid block at or below y (searching up to maxDown blocks), or Integer.MIN_VALUE. */
    public static int floorBelow(World w, int x, int y, int z, int maxDown) {
        for (int i = 0; i <= maxDown; i++) {
            int yy = y - i;
            if (yy <= w.getMinHeight()) break;
            Block b = w.getBlockAt(x, yy, z);
            if (!b.isPassable() && !b.isLiquid()) return yy;
        }
        return Integer.MIN_VALUE;
    }

    /** True if a player-sized body fits with feet at this block position. */
    public static boolean fits(World w, int x, int y, int z) {
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()
                && feet.getType() != org.bukkit.Material.LAVA && feet.getType() != org.bukkit.Material.FIRE;
    }

    /**
     * Safe landing spot for a blink at (x, y, z): the body must fit and there must be solid ground within
     * maxDrop blocks below. Returns null if unsafe.
     */
    public static Location safeLanding(World w, double x, double y, double z, int maxDrop) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        for (int up = 0; up <= 1; up++) {
            if (!fits(w, bx, by + up, bz)) continue;
            int floor = floorBelow(w, bx, by + up - 1, bz, maxDrop);
            if (floor == Integer.MIN_VALUE) return null;
            Block floorBlock = w.getBlockAt(bx, floor, bz);
            if (floorBlock.getType() == org.bukkit.Material.LAVA || floorBlock.getType() == org.bukkit.Material.MAGMA_BLOCK) return null;
            if (!fits(w, bx, floor + 1, bz)) continue;
            return new Location(w, x, floor + 1, z);
        }
        return null;
    }
}
