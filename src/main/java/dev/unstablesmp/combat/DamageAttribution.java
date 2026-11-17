package dev.unstablesmp.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/** Remembers which USS ability/faction last hurt each entity, for kill messages. */
public final class DamageAttribution {

    public record Hit(String key, Component killer, UUID killerId, int tick) {
    }

    private final Map<UUID, Hit> last = new HashMap<>();
    private int windowTicks = 200;

    public void setWindow(int ticks) {
        this.windowTicks = Math.max(20, ticks);
    }

    public void record(UUID victim, String key, Component killer, UUID killerId, int now) {
        last.put(victim, new Hit(key, killer, killerId, now));
    }

    public Hit get(UUID victim, int now) {
        Hit h = last.get(victim);
        if (h == null) return null;
        if (now - h.tick > windowTicks) {
            last.remove(victim);
            return null;
        }
        return h;
    }

    public void clear(UUID victim) {
        last.remove(victim);
    }

    public void prune(int now) {
        last.values().removeIf(h -> now - h.tick > windowTicks);
    }
}
