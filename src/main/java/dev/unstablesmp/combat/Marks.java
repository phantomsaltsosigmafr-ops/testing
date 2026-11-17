package dev.unstablesmp.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Entity;

/** Expiring marks per entity. Stored as tick deadlines in a small array per entity. */
public final class Marks {
    private static final int N = Mark.values().length;
    private final Map<UUID, int[]> until = new HashMap<>();

    public void apply(Entity e, Mark m, int now, int ticks) {
        if (m == Mark.BURNING) {
            e.setFireTicks(Math.max(e.getFireTicks(), ticks));
            return;
        }
        int[] a = until.computeIfAbsent(e.getUniqueId(), k -> new int[N]);
        a[m.ordinal()] = Math.max(a[m.ordinal()], now + ticks);
    }

    public boolean has(Entity e, Mark m, int now) {
        if (m == Mark.BURNING) return e.getFireTicks() > 0;
        return remaining(e.getUniqueId(), m, now) > 0;
    }

    public int remaining(UUID id, Mark m, int now) {
        int[] a = until.get(id);
        if (a == null) return 0;
        return Math.max(0, a[m.ordinal()] - now);
    }

    public void clear(UUID id, Mark m) {
        int[] a = until.get(id);
        if (a != null) a[m.ordinal()] = 0;
    }

    public void clearAll(UUID id) {
        until.remove(id);
    }

    /** Drops entities with no live marks. Call occasionally. */
    public void prune(int now) {
        Iterator<int[]> it = until.values().iterator();
        while (it.hasNext()) {
            int[] a = it.next();
            boolean any = false;
            for (int v : a) {
                if (v > now) {
                    any = true;
                    break;
                }
            }
            if (!any) it.remove();
        }
    }
}
