package dev.unstablesmp.portal;

import dev.unstablesmp.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;

/**
 * Picks where portals tear open: spread evenly around the breach centre, on reasonably flat ground, facing inward,
 * never on top of containers, command blocks, bedrock or another portal.
 */
public final class SiteFinder {
    private final Settings s;
    private final PortalShape shape;
    private final BlockJournal journal;

    public SiteFinder(Settings s, PortalShape shape, BlockJournal journal) {
        this.s = s;
        this.shape = shape;
        this.journal = journal;
    }

    /** Up to {@code count} sites in a ring of {@code radius} around the centre, all facing it. */
    public List<Site> ring(World w, double cx, double cy, double cz, int count, Settings.IntRange radius, List<Site> taken) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Site> out = new ArrayList<>();
        List<Site> all = new ArrayList<>(taken);
        double base = r.nextDouble(Math.PI * 2);
        for (int i = 0; i < count; i++) {
            Site best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int attempt = 0; attempt < 48; attempt++) {
                double spreadAngle = attempt < 24 ? 0.45 : Math.PI; // stay in this portal's slice first, then anywhere
                double angle = base + i * (Math.PI * 2 / count) + r.nextDouble(-spreadAngle, spreadAngle);
                double dist = radius.min() + r.nextDouble() * Math.max(0, radius.max() - radius.min());
                int x = (int) Math.floor(cx + Math.cos(angle) * dist);
                int z = (int) Math.floor(cz + Math.sin(angle) * dist);
                int y = groundY(w, x, z, (int) Math.floor(cy), false);
                if (y == Integer.MIN_VALUE) continue;
                int[] f = Site.facingToward(x + 0.5, z + 0.5, cx, cz);
                Site site = new Site(x, y, z, f[0], f[1]);
                if (!spaced(site, all)) continue;
                int score = score(w, site);
                if (score < 0) continue;
                if (score < bestScore) {
                    bestScore = score;
                    best = site;
                }
                if (score <= 2) break;
            }
            if (best != null) {
                out.add(best);
                all.add(best);
            }
        }
        return out;
    }

    /** A single site at (x, z) near height y, or null if the spot can't hold a portal. */
    public Site at(World w, int x, int y, int z, int fx, int fz, boolean exactY) {
        int gy = exactY ? y : groundY(w, x, z, y, true);
        if (gy == Integer.MIN_VALUE) return null;
        Site site = new Site(x, gy, z, fx, fz);
        return score(w, site) < 0 ? null : site;
    }

    private boolean spaced(Site s1, List<Site> others) {
        int min2 = s.minSpacing * s.minSpacing;
        for (Site o : others) {
            int dx = o.x() - s1.x(), dz = o.z() - s1.z();
            if (dx * dx + dz * dz < min2) return false;
        }
        return true;
    }

    /**
     * Lower is better; -1 means the portal can't go there (protected blocks, another portal, out of the world).
     * Penalises terrain inside the opening, gaps under the frame, walls right in front and liquids.
     */
    int score(World w, Site site) {
        if (site.y() <= w.getMinHeight() || site.y() + shape.height + 2 >= w.getMaxHeight()) return -1;
        int score = 0;
        for (PortalShape.Cell c : shape.all()) {
            Block b = site.block(w, c.across(), c.up());
            if (!replaceable(b)) return -1;
            if (c.kind() == PortalShape.Kind.SURFACE && !b.isPassable()) score += 1;
            if (c.up() == 0) {
                if (b.isLiquid()) score += 4;
                else if (b.getType().isAir() && b.getRelative(0, -1, 0).getType().isAir()) score += 2;
            }
        }
        for (int a = shape.first; a <= shape.last; a++) {
            for (int out = 1; out <= 2; out++) {
                for (int up = 1; up <= 2; up++) {
                    Block b = site.block(w, a, up, out);
                    if (b.isLiquid()) score += 3;
                    else if (!b.isPassable()) score += 1;
                }
            }
        }
        return score;
    }

    /** Whether a portal may temporarily replace this block. */
    public boolean replaceable(Block b) {
        Material m = b.getType();
        if (s.neverReplace.contains(m) || journal.tracked(b)) return false;
        return !(b.getState(false) instanceof TileState);
    }

    /**
     * The ground block's y at (x, z): the solid block bots will stand on. Open-sky worlds use the heightmap; worlds
     * with a ceiling (the Nether) or an explicit height scan down from {@code nearY}. MIN_VALUE if nothing fits.
     */
    public int groundY(World w, int x, int z, int nearY, boolean nearGiven) {
        boolean ceiling = w.getEnvironment() == World.Environment.NETHER;
        if (!ceiling && !nearGiven) {
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block b = w.getBlockAt(x, y, z);
            if (b.isLiquid() || y <= w.getMinHeight()) return Integer.MIN_VALUE;
            return y;
        }
        int top = Math.min(w.getMaxHeight() - 3, nearY + (nearGiven ? 3 : 10));
        int bottom = Math.max(w.getMinHeight() + 1, nearY - 24);
        for (int y = top; y >= bottom; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (!b.getType().isSolid() || b.isLiquid()) continue;
            if (w.getBlockAt(x, y + 1, z).isPassable() && w.getBlockAt(x, y + 2, z).isPassable()) return y;
        }
        return Integer.MIN_VALUE;
    }
}
