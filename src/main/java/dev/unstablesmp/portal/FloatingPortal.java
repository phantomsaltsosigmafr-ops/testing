package dev.unstablesmp.portal;

import dev.unstablesmp.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * One floating obsidian-framed nether portal hanging in the air (the classic NULL invasion look). Only ever built in
 * empty air, through the {@link BlockJournal}, so closing it (or a crash) just puts the air back.
 */
public final class FloatingPortal {

    /** One block of the layout: {@code across} along the portal, {@code up} from the bottom frame row. */
    public record Cell(int across, int up, boolean frame) {
    }

    public final World world;
    /** Bottom-left frame corner. */
    public final int x, y, z;
    /** True when the portal spans the X axis (its faces look north/south). */
    public final boolean alongX;
    public final int width, height;
    final List<Block> frame = new ArrayList<>();
    final List<Block> inner = new ArrayList<>();

    private FloatingPortal(World world, int x, int y, int z, boolean alongX, int width, int height) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.alongX = alongX;
        this.width = width;
        this.height = height;
    }

    /** The layout for an opening of {@code width x height}: frame ring plus the portal blocks inside. */
    public static List<Cell> cells(int width, int height) {
        List<Cell> out = new ArrayList<>((width + 2) * (height + 2));
        for (int j = 0; j <= height + 1; j++) {
            for (int i = 0; i <= width + 1; i++) {
                boolean edge = i == 0 || i == width + 1 || j == 0 || j == height + 1;
                out.add(new Cell(i, j, edge));
            }
        }
        return out;
    }

    private Block at(int across, int up) {
        return alongX ? world.getBlockAt(x + across, y + up, z) : world.getBlockAt(x, y + up, z + across);
    }

    /** Builds a portal at (x, y, z) if every block it needs is empty air nobody else is using; otherwise null. */
    static FloatingPortal tryBuild(World w, int x, int y, int z, boolean alongX, Settings s, BlockJournal journal) {
        if (y <= w.getMinHeight() || y + s.height + 2 >= w.getMaxHeight()) return null;
        FloatingPortal p = new FloatingPortal(w, x, y, z, alongX, s.width, s.height);
        List<Cell> cells = cells(s.width, s.height);
        for (Cell c : cells) {
            Block b = p.at(c.across(), c.up());
            if (!b.getType().isAir() || journal.tracked(b)) return null;
        }
        BlockData portal = Bukkit.createBlockData("minecraft:nether_portal[axis=" + (alongX ? "x" : "z") + "]");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // frame first, then the portal blocks, so the portal never sees a broken frame
        for (Cell c : cells) {
            if (!c.frame()) continue;
            Block b = p.at(c.across(), c.up());
            journal.set(b, s.frame.get(r.nextInt(s.frame.size())).createBlockData());
            p.frame.add(b);
        }
        for (Cell c : cells) {
            if (c.frame()) continue;
            Block b = p.at(c.across(), c.up());
            journal.set(b, portal);
            p.inner.add(b);
        }
        return p;
    }

    /** Puts the air back: portal blocks first so nothing pops, then the frame. */
    void destroy(BlockJournal journal) {
        for (Block b : inner) journal.restore(b);
        for (Block b : frame) journal.restore(b);
        inner.clear();
        frame.clear();
    }

    // ------------------------------------------------------------------ geometry for effects and spawns

    public double centreX() {
        return alongX ? x + (width + 2) / 2.0 : x + 0.5;
    }

    public double centreY() {
        return y + (height + 2) / 2.0;
    }

    public double centreZ() {
        return alongX ? z + 0.5 : z + (width + 2) / 2.0;
    }

    /** Across axis as (ax, az) and the face normal as (nx, nz) for {@link Fx#play}. */
    public int ax() {
        return alongX ? 1 : 0;
    }

    public int az() {
        return alongX ? 0 : 1;
    }

    public int nx() {
        return alongX ? 0 : 1;
    }

    public int nz() {
        return alongX ? 1 : 0;
    }

    /** A random block in the bottom row of the opening: where the bots come through. */
    public int[] spawnBlock() {
        int col = 1 + ThreadLocalRandom.current().nextInt(width);
        return alongX ? new int[] {x + col, y + 1, z} : new int[] {x, y + 1, z + col};
    }

    /** +1 or -1: which face bots come out of, toward a point (random when the point is level with the portal). */
    public int sideToward(double px, double pz) {
        double d = alongX ? pz - centreZ() : px - centreX();
        if (Math.abs(d) < 0.75) return ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        return d > 0 ? 1 : -1;
    }

    public boolean isOpen() {
        return !inner.isEmpty();
    }
}
