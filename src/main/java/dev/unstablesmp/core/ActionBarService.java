package dev.unstablesmp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Combines everything that wants the action bar (combat timer, ability cooldowns, short flash messages)
 * into one line so subsystems never overwrite each other.
 */
public final class ActionBarService implements Ticker.Tickable {

    /** Provides one segment of the action bar for a player, or null for nothing. */
    public interface Segment {
        Component segment(Player p, int now);
    }

    private record Flash(Component text, int until) {
    }

    private final List<Segment> segments = new ArrayList<>();
    private final Map<UUID, Flash> flashes = new HashMap<>();
    private final Set<UUID> showing = new HashSet<>();
    private final List<Component> parts = new ArrayList<>(4);
    private Component separator = Component.text(" | ");
    private int interval = 5;

    public void add(Segment s) {
        segments.add(s);
    }

    public void configure(Component separator, int intervalTicks) {
        this.separator = separator;
        this.interval = Math.max(1, intervalTicks);
    }

    /** Shows a short message for a while alongside the regular segments. */
    public void flash(Player p, Component text, int ticks) {
        flashes.put(p.getUniqueId(), new Flash(text, Bukkit.getCurrentTick() + ticks));
        render(p, Bukkit.getCurrentTick());
    }

    @Override
    public void tick(int now) {
        if (now % interval != 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) render(p, now);
    }

    private void render(Player p, int now) {
        parts.clear();
        Flash f = flashes.get(p.getUniqueId());
        if (f != null) {
            if (f.until < now) flashes.remove(p.getUniqueId());
            else parts.add(f.text);
        }
        for (Segment s : segments) {
            Component c = s.segment(p, now);
            if (c != null) parts.add(c);
        }
        if (parts.isEmpty()) {
            if (showing.remove(p.getUniqueId())) p.sendActionBar(Component.empty());
            return;
        }
        showing.add(p.getUniqueId());
        p.sendActionBar(Component.join(JoinConfiguration.separator(separator), parts));
    }

    public void forget(UUID id) {
        flashes.remove(id);
        showing.remove(id);
    }

    @Override
    public String name() {
        return "action-bar";
    }
}
