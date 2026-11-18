package dev.unstablesmp.bot.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Budgeted grid A* for bots that are stuck or need to climb/drop: walkable = solid floor + 2 free blocks, 8-way
 * moves, step up 1 / drop up to 3. A global node budget per tick keeps 500+ bots from ever spiking the tick.
 * Returns packed waypoints {x0,y0,z0,x1,y1,z1,...} (block coords) or null.
 */
public final class BotPathfinder {
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        double g, f;
        Node parent;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private int budgetPerTick = 4000;
    private int maxNodes = 600;
    private int usedTick = -1;
    private int used;

    public void configure(int budgetPerTick, int maxNodes) {
        this.budgetPerTick = Math.max(200, budgetPerTick);
        this.maxNodes = Math.max(50, maxNodes);
    }

    /** True if there is budget left this tick for another search. */
    public boolean canSearch(int now) {
        if (usedTick != now) {
            usedTick = now;
            used = 0;
        }
        return used + maxNodes / 2 <= budgetPerTick;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    static boolean passable(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        Material t = b.getType();
        return b.isPassable() && t != Material.LAVA && t != Material.FIRE && t != Material.COBWEB && t != Material.SWEET_BERRY_BUSH;
    }

    static boolean walkable(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() || y + 2 >= w.getMaxHeight()) return false;
        Block floor = w.getBlockAt(x, y - 1, z);
        return !floor.isPassable() && floor.getType() != Material.MAGMA_BLOCK && passable(w, x, y, z) && passable(w, x, y + 1, z);
    }

    public int[] find(World w, int sx, int sy, int sz, int tx, int ty, int tz, int now) {
        if (!canSearch(now)) return null;
        if (!walkable(w, sx, sy, sz)) {
            if (walkable(w, sx, sy + 1, sz)) sy++;
            else if (walkable(w, sx, sy - 1, sz)) sy--;
        }
        Map<Long, Node> seen = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        Node start = new Node(sx, sy, sz);
        start.f = h(sx, sy, sz, tx, ty, tz);
        open.add(start);
        seen.put(key(sx, sy, sz), start);
        Node best = start;
        double bestH = start.f;
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node n = open.poll();
            expanded++;
            double hn = h(n.x, n.y, n.z, tx, ty, tz);
            if (hn < bestH) {
                bestH = hn;
                best = n;
            }
            if (Math.abs(n.x - tx) <= 1 && Math.abs(n.z - tz) <= 1 && Math.abs(n.y - ty) <= 1) {
                best = n;
                break;
            }
            for (int[] d : DIRS) {
                int nx = n.x + d[0], nz = n.z + d[1];
                if (d[0] != 0 && d[1] != 0
                        && (!passable(w, n.x + d[0], n.y, n.z) || !passable(w, n.x, n.y, n.z + d[1]))) continue; // no corner cutting
                for (int dy = 1; dy >= -3; dy--) {
                    int ny = n.y + dy;
                    if (dy == 1 && !passable(w, n.x, n.y + 2, n.z)) continue;
                    if (!walkable(w, nx, ny, nz)) continue;
                    if (dy < 0 && !(passable(w, nx, n.y, nz) && passable(w, nx, n.y + 1, nz))) continue;
                    double cost = (d[0] != 0 && d[1] != 0 ? 1.414 : 1.0) + (dy > 0 ? 0.6 : dy < 0 ? 0.2 * -dy : 0);
                    long k = key(nx, ny, nz);
                    Node m = seen.get(k);
                    double g = n.g + cost;
                    if (m == null) {
                        m = new Node(nx, ny, nz);
                        seen.put(k, m);
                    } else if (g >= m.g) {
                        continue;
                    } else {
                        open.remove(m);
                    }
                    m.g = g;
                    m.f = g + h(nx, ny, nz, tx, ty, tz);
                    m.parent = n;
                    open.add(m);
                    break; // first valid height for this column
                }
            }
        }
        used += expanded;
        if (best == start) return null;
        int len = 0;
        for (Node p = best; p != null; p = p.parent) len++;
        int[] out = new int[len * 3];
        int i = len - 1;
        for (Node p = best; p != null; p = p.parent, i--) {
            out[i * 3] = p.x;
            out[i * 3 + 1] = p.y;
            out[i * 3 + 2] = p.z;
        }
        return out;
    }

    private static double h(int x, int y, int z, int tx, int ty, int tz) {
        int dx = Math.abs(x - tx), dz = Math.abs(z - tz);
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + Math.abs(y - ty) * 0.5;
    }
}
