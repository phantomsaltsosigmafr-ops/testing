package dev.unstablesmp.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tick-based cooldowns keyed by player and an arbitrary string (e.g. "null_step:sneak"). */
public final class Cooldowns {
    private final Map<UUID, Map<String, Integer>> until = new HashMap<>();

    /** Remaining ticks, 0 if ready. */
    public int remaining(UUID id, String key, int now) {
        Map<String, Integer> m = until.get(id);
        if (m == null) return 0;
        Integer t = m.get(key);
        if (t == null) return 0;
        if (t <= now) {
            m.remove(key);
            return 0;
        }
        return t - now;
    }

    public void set(UUID id, String key, int now, int ticks) {
        if (ticks <= 0) return;
        until.computeIfAbsent(id, k -> new HashMap<>()).put(key, now + ticks);
    }

    public void clear(UUID id) {
        until.remove(id);
    }

    public void clearAll() {
        until.clear();
    }
}
