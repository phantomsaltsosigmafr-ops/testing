package dev.unstablesmp.core;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Short-lived blocks placed by bots (UHC lava, cobwebs, clutch blocks). Only placed into air/replaceable blocks and
 * always restored, including on plugin disable.
 */
public final class TempBlocks implements Ticker.Tickable {
    private record Placed(Block block, BlockData before, Material placed, int until) {
    }

    private final List<Placed> placed = new ArrayList<>();
    private int max = 600;

    public void setMax(int max) {
        this.max = Math.max(10, max);
    }

    /** Places a temporary block; returns false if the spot isn't free or the cap is reached. */
    public boolean place(Block b, Material m, int ticks, int now) {
        if (placed.size() >= max) return false;
        Material t = b.getType();
        if (!(t.isAir() || t == Material.SHORT_GRASS || t == Material.TALL_GRASS || t == Material.SNOW)) return false;
        placed.add(new Placed(b, b.getBlockData(), m, now + ticks));
        b.setType(m, true);
        return true;
    }

    @Override
    public void tick(int now) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            Placed p = placed.get(i);
            if (now >= p.until) {
                restore(p);
                placed.remove(i);
            }
        }
    }

    private static void restore(Placed p) {
        Material cur = p.block.getType();
        // Lava may have flowed into water/obsidian etc.; only undo what is still ours (or its flowing form).
        if (cur == p.placed || (p.placed == Material.LAVA && (cur == Material.LAVA || cur == Material.OBSIDIAN || cur == Material.COBBLESTONE))) {
            p.block.setBlockData(p.before, true);
        }
    }

    public void restoreAll() {
        for (Placed p : placed) restore(p);
        placed.clear();
    }

    public int count() {
        return placed.size();
    }

    @Override
    public String name() {
        return "temp-blocks";
    }
}
