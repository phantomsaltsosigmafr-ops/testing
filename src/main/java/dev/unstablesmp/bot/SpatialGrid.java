package dev.unstablesmp.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;

/**
 * 16-block buckets of combatants (players + bots) per world, rebuilt every few ticks. Lets 500+ bots find targets
 * without any world entity queries. Lists are pooled so a rebuild allocates nothing in steady state.
 */
public final class SpatialGrid {
    private static final int SHIFT = 4;
    private final Map<World, Map<Long, List<LivingEntity>>> cells = new HashMap<>();
    private final List<List<LivingEntity>> pool = new ArrayList<>();
    private final List<List<LivingEntity>> used = new ArrayList<>();

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public void clear() {
        for (List<LivingEntity> l : used) {
            l.clear();
            pool.add(l);
        }
        used.clear();
        for (Map<Long, List<LivingEntity>> m : cells.values()) m.clear();
    }

    public void add(LivingEntity e, World w, double x, double z) {
        Map<Long, List<LivingEntity>> m = cells.computeIfAbsent(w, k -> new HashMap<>());
        long k = key((int) Math.floor(x) >> SHIFT, (int) Math.floor(z) >> SHIFT);
        List<LivingEntity> l = m.get(k);
        if (l == null) {
            l = pool.isEmpty() ? new ArrayList<>(8) : pool.remove(pool.size() - 1);
            used.add(l);
            m.put(k, l);
        }
        l.add(e);
    }

    /** Appends every combatant in cells overlapping the square of half-size r around (x, z) to {@code out}. */
    public void query(World w, double x, double z, double r, List<LivingEntity> out) {
        Map<Long, List<LivingEntity>> m = cells.get(w);
        if (m == null || m.isEmpty()) return;
        int x0 = (int) Math.floor(x - r) >> SHIFT, x1 = (int) Math.floor(x + r) >> SHIFT;
        int z0 = (int) Math.floor(z - r) >> SHIFT, z1 = (int) Math.floor(z + r) >> SHIFT;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                List<LivingEntity> l = m.get(key(cx, cz));
                if (l != null) out.addAll(l);
            }
        }
    }

    /** True if any entity of the given class-check is in range (used for "is a player near" LOD tests). */
    public boolean anyNear(World w, double x, double z, double r, java.util.function.Predicate<LivingEntity> test) {
        Map<Long, List<LivingEntity>> m = cells.get(w);
        if (m == null || m.isEmpty()) return false;
        int x0 = (int) Math.floor(x - r) >> SHIFT, x1 = (int) Math.floor(x + r) >> SHIFT;
        int z0 = (int) Math.floor(z - r) >> SHIFT, z1 = (int) Math.floor(z + r) >> SHIFT;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                List<LivingEntity> l = m.get(key(cx, cz));
                if (l == null) continue;
                for (int i = 0, n = l.size(); i < n; i++) if (test.test(l.get(i))) return true;
            }
        }
        return false;
    }
}
