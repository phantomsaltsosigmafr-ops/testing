package dev.unstablesmp.life;

import dev.unstablesmp.portal.SpawnCommand;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;

/** Shared bits for residents and bandits: running VexBot commands, finding ground, finding ore veins. */
final class LifeUtil {
    private LifeUtil() {
    }

    static void run(List<String> templates, Map<String, String> values, Logger log) {
        for (String t : templates) {
            String cmd = SpawnCommand.format(t, values);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                log.warning("VexBot command failed: /" + cmd + " (" + ex + ")");
            }
        }
    }

    /**
     * The ground's y at x, z (the top block you'd stand on, under any tree trunks, leaves or plants), loading the chunk
     * if needed; MIN_VALUE over water/lava or the void.
     */
    static int ground(World w, int x, int z) {
        w.getChunkAt(x >> 4, z >> 4);
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        while (y > w.getMinHeight() + 1) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (!(m.isAir() || org.bukkit.Tag.LOGS.isTagged(m) || org.bukkit.Tag.LEAVES.isTagged(m) || org.bukkit.Tag.REPLACEABLE.isTagged(m)
                    || org.bukkit.Tag.FLOWERS.isTagged(m) || org.bukkit.Tag.SAPLINGS.isTagged(m))) break;
            y--;
        }
        if (y <= w.getMinHeight() + 1 || w.getBlockAt(x, y, z).isLiquid()) return Integer.MIN_VALUE;
        return y;
    }

    /** The ore materials from config names ("*_ORE" style names, or "all" for every ore). */
    static Set<Material> oreSet(List<String> names) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        boolean all = names.isEmpty();
        for (String n : names) {
            if (n.equalsIgnoreCase("all")) {
                all = true;
                continue;
            }
            Material m = Material.matchMaterial(n.trim());
            if (m != null) out.add(m);
        }
        if (all) {
            for (Material m : Material.values()) {
                if (!m.name().startsWith("LEGACY_") && (m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS)) out.add(m);
            }
        }
        return out;
    }

    /**
     * Ore near home: looks in a few spots underground around (cx, cz) at the configured depth and returns up to
     * {@code max} ore blocks in walking order (each the closest to the last), i.e. a vein to work through.
     */
    static List<int[]> findOre(World w, int cx, int cz, int yMin, int yMax, Set<Material> ores, int max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<int[]> found = new ArrayList<>();
        int lo = Math.max(w.getMinHeight() + 2, Math.min(yMin, yMax)), hi = Math.min(w.getMaxHeight() - 2, Math.max(yMin, yMax));
        for (int attempt = 0; attempt < 5 && found.isEmpty(); attempt++) {
            int bx = cx + r.nextInt(-18, 19), bz = cz + r.nextInt(-18, 19);
            int by = lo + (hi > lo ? r.nextInt(hi - lo + 1) : 0);
            w.getChunkAt(bx >> 4, bz >> 4);
            for (int x = bx - 8; x <= bx + 8; x++) {
                for (int z = bz - 8; z <= bz + 8; z++) {
                    for (int y = Math.max(lo, by - 8); y <= Math.min(hi, by + 8); y++) {
                        if (ores.contains(w.getBlockAt(x, y, z).getType())) found.add(new int[] {x, y, z});
                    }
                }
            }
        }
        if (found.isEmpty()) return found;
        // walk the vein: start at a random ore, then always the nearest remaining one
        List<int[]> chain = new ArrayList<>();
        int[] cur = found.remove(r.nextInt(found.size()));
        chain.add(cur);
        while (!found.isEmpty() && chain.size() < max) {
            int best = 0;
            long bestD = Long.MAX_VALUE;
            for (int i = 0; i < found.size(); i++) {
                int[] o = found.get(i);
                long d = (long) (o[0] - cur[0]) * (o[0] - cur[0]) + (long) (o[1] - cur[1]) * (o[1] - cur[1]) + (long) (o[2] - cur[2]) * (o[2] - cur[2]);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            cur = found.remove(best);
            chain.add(cur);
        }
        return chain;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
