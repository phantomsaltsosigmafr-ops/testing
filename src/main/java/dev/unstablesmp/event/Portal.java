package dev.unstablesmp.event;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/** One floating 4x5 obsidian-framed nether portal (ported from the reference event). */
final class Portal {
    final List<Block> frame = new ArrayList<>();
    final List<Block> inner = new ArrayList<>();
    final Location center;

    private Portal(Location center) {
        this.center = center;
    }

    /** Builds a portal with its bottom-left frame corner at (x,y,z). Returns null if the space isn't empty air. */
    static Portal tryBuild(World world, int x, int y, int z, boolean alongX) {
        List<Block> all = new ArrayList<>(20);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 5; j++) {
                Block b = alongX ? world.getBlockAt(x + i, y + j, z) : world.getBlockAt(x, y + j, z + i);
                if (!b.getType().isAir()) return null;
                all.add(b);
            }
        }
        double cx = alongX ? x + 2.0 : x + 0.5;
        double cz = alongX ? z + 0.5 : z + 2.0;
        Portal p = new Portal(new Location(world, cx, y + 2.5, cz));
        int idx = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 5; j++) {
                Block b = all.get(idx++);
                if (i == 0 || i == 3 || j == 0 || j == 4) p.frame.add(b);
                else p.inner.add(b);
            }
        }
        BlockData portalData = Bukkit.createBlockData("minecraft:nether_portal[axis=" + (alongX ? "x" : "z") + "]");
        for (Block b : p.frame) b.setType(Material.OBSIDIAN, false);
        for (Block b : p.inner) b.setBlockData(portalData, false);
        return p;
    }

    /** Removes the portal, only touching blocks that are still ours. */
    void destroy() {
        for (Block b : inner) {
            if (b.getType() == Material.NETHER_PORTAL) b.setType(Material.AIR, false);
        }
        for (Block b : frame) {
            if (b.getType() == Material.OBSIDIAN) b.setType(Material.AIR, false);
        }
    }

    List<Block> allBlocks() {
        List<Block> l = new ArrayList<>(frame);
        l.addAll(inner);
        return l;
    }
}
