package dev.unstablesmp.faction;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/** A block position plus (for broken blocks) what used to be there, so builds can be cleared and the world restored. */
public record BlockRecord(World world, int x, int y, int z, BlockData before) {
    public static BlockRecord of(Block b, BlockData before) {
        return new BlockRecord(b.getWorld(), b.getX(), b.getY(), b.getZ(), before);
    }

    public Block block() {
        return world.getBlockAt(x, y, z);
    }

    public boolean loaded() {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }
}
