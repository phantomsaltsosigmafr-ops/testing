package dev.unstablesmp.event;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Ticker;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

/** Starts, ticks and stops server events. One of each type can run at a time. */
public final class EventManager implements Ticker.Tickable {
    private final UnstableSMP plugin;
    private final Map<EventType, ServerEvent> running = new EnumMap<>(EventType.class);
    private final List<ServerEvent> scratch = new ArrayList<>();

    public EventManager(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    /** Starts an event. Returns null on success or a messages.yml key explaining why not. */
    public String start(EventType type, Location at, String starter) {
        if (running.containsKey(type)) return "event.already-running";
        int max = plugin.getConfig().getInt("events.max-concurrent", 2);
        if (running.size() >= max) return "event.too-many";
        if (!plugin.regions().canRunEvent(at)) return "event.region-denied";
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("events." + type.configKey);
        if (cfg == null || !cfg.getBoolean("enabled", true)) return "event.disabled";
        ServerEvent ev = type == EventType.PORTAL
                ? new PortalInvasionEvent(plugin, cfg, at, starter)
                : new FactionWaveEvent(plugin, type, cfg, at, starter);
        running.put(type, ev);
        ev.start();
        return null;
    }

    public boolean stop(EventType type) {
        ServerEvent ev = running.get(type);
        if (ev == null) return false;
        ev.stopNow();
        return true;
    }

    public int stopAll() {
        scratch.clear();
        scratch.addAll(running.values());
        for (ServerEvent ev : scratch) ev.stopNow();
        running.clear();
        return scratch.size();
    }

    public boolean isRunning(EventType type) {
        return running.containsKey(type);
    }

    public Map<EventType, ServerEvent> running() {
        return running;
    }

    void ended(ServerEvent ev) {
        running.remove(ev.type(), ev);
    }

    @Override
    public void tick(int now) {
        if (running.isEmpty()) return;
        scratch.clear();
        scratch.addAll(running.values());
        for (ServerEvent ev : scratch) ev.tick(now);
    }

    @Override
    public String name() {
        return "events";
    }
}
