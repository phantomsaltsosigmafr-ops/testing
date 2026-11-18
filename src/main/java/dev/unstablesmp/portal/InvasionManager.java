package dev.unstablesmp.portal;

import dev.unstablesmp.Settings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Runs every invasion from one repeating task, owns the block journal (crash-safe restore), the spawn capture and the
 * optional storm.
 */
public final class InvasionManager implements Runnable {
    private record Weather(boolean storm, boolean thunder, int weatherTicks, int thunderTicks, int clearTicks) {
    }

    private final Plugin plugin;
    private final BlockJournal journal;
    private final SpawnCapture capture = new SpawnCapture();
    private final List<Invasion> invasions = new ArrayList<>();
    private final Map<UUID, Weather> weather = new HashMap<>();
    private final Set<String> logged = new HashSet<>();
    /** Every bot any invasion poured out (by UUID, so it survives chunk unloads), for the Totem of Null. */
    private final Set<UUID> poured = new java.util.LinkedHashSet<>();
    private record Doomed(Entity entity, int at) {
    }
    private final List<Doomed> doomed = new ArrayList<>();
    private Settings settings;
    private int now;
    private int nextId = 1;

    public InvasionManager(Plugin plugin, BlockJournal journal, Settings settings) {
        this.plugin = plugin;
        this.journal = journal;
        this.settings = settings;
    }

    public void settings(Settings s) {
        this.settings = s;
    }

    public Settings settings() {
        return settings;
    }

    public int now() {
        return now;
    }

    public SpawnCapture capture() {
        return capture;
    }

    public BlockJournal journal() {
        return journal;
    }

    public List<Invasion> invasions() {
        return invasions;
    }

    void trackPoured(Entity e) {
        poured.add(e.getUniqueId());
    }

    /** Poured bots that are loaded and alive right now. */
    public List<Entity> pouredBots() {
        List<Entity> out = new ArrayList<>();
        for (java.util.Iterator<UUID> it = poured.iterator(); it.hasNext(); ) {
            Entity e = Bukkit.getEntity(it.next());
            if (e == null) continue; // unloaded (kept for later) or gone
            if (!e.isValid() || e.isDead()) {
                it.remove();
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /** Kills these in a wave rolling out from {@code from} at {@code speed} blocks per tick (Totem of Null). */
    public void killWave(List<Entity> victims, org.bukkit.Location from, double speed) {
        for (Entity e : victims) {
            double d = e.getWorld() == from.getWorld() ? e.getLocation().distance(from) : 0;
            doomed.add(new Doomed(e, now + 2 + (int) Math.min(60, d / speed)));
        }
    }

    private void tickDoomed() {
        if (doomed.isEmpty()) return;
        for (java.util.Iterator<Doomed> it = doomed.iterator(); it.hasNext(); ) {
            Doomed d = it.next();
            if (d.at() > now) continue;
            it.remove();
            Entity e = d.entity();
            poured.remove(e.getUniqueId());
            if (!e.isValid() || e.isDead()) continue;
            org.bukkit.Location l = e.getLocation();
            Fx.play(settings.effect("totem-kill"), l.getWorld(), l.getX(), l.getY() + 0.9, l.getZ(), 1, 0, 0, 1);
            if (e instanceof org.bukkit.entity.LivingEntity le) le.setHealth(0);
            else e.remove();
        }
    }

    void logOnce(String msg) {
        if (logged.add(msg)) plugin.getLogger().warning(msg);
    }

    /** Starts an invasion centred on (x, y, z); portals start tearing open on the next tick. */
    public Invasion start(World w, double x, double y, double z, Invasion.Options o, UUID starter) {
        Settings s = settings;
        Invasion inv = new Invasion(this, plugin, s, nextId++, w, x, y, z, starter, o);
        inv.begin();
        if (s.storm) stormOn(w, o.durationSeconds + 60);
        invasions.add(inv);
        return inv;
    }

    /** Ends invasions (all, or one world). Portals collapse a few at a time unless {@code immediate}. */
    public int stop(World only, boolean immediate) {
        int n = 0;
        for (Invasion inv : new ArrayList<>(invasions)) {
            if (only != null && inv.world != only) continue;
            if (inv.phase() == Invasion.Phase.DONE) continue;
            inv.stop(immediate);
            n++;
        }
        cleanupDone();
        return n;
    }

    /** Plugin disable: every portal gone, every block back, weather restored. */
    public void shutdown() {
        stop(null, true);
        journal.restoreAll();
        capture.clear();
        doomed.clear();
        for (UUID id : new ArrayList<>(weather.keySet())) {
            World w = Bukkit.getWorld(id);
            if (w != null) stormOff(w);
        }
        weather.clear();
    }

    @Override
    public void run() {
        now++;
        for (int i = 0; i < invasions.size(); i++) {
            Invasion inv = invasions.get(i);
            try {
                inv.tick();
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Invasion #" + inv.id + " failed; closing it", ex);
                inv.stop(true);
            }
        }
        cleanupDone();
        capture.tick(now);
        tickDoomed();
    }

    private void cleanupDone() {
        if (!invasions.removeIf(inv -> inv.phase() == Invasion.Phase.DONE)) return;
        for (UUID id : new ArrayList<>(weather.keySet())) {
            boolean busy = false;
            for (Invasion inv : invasions) busy |= inv.world.getUID().equals(id);
            World w = Bukkit.getWorld(id);
            if (!busy && w != null) stormOff(w);
        }
    }

    private void stormOn(World w, int seconds) {
        if (!weather.containsKey(w.getUID())) {
            weather.put(w.getUID(), new Weather(w.hasStorm(), w.isThundering(), w.getWeatherDuration(),
                    w.getThunderDuration(), w.getClearWeatherDuration()));
        }
        int ticks = Math.max(w.getWeatherDuration(), seconds * 20);
        w.setStorm(true);
        w.setThundering(true);
        w.setWeatherDuration(ticks);
        w.setThunderDuration(ticks);
    }

    private void stormOff(World w) {
        Weather prev = weather.remove(w.getUID());
        if (prev == null) return;
        w.setStorm(prev.storm());
        w.setThundering(prev.thunder());
        if (prev.storm()) w.setWeatherDuration(Math.max(1, prev.weatherTicks()));
        else if (prev.clearTicks() > 0) w.setClearWeatherDuration(prev.clearTicks());
        if (prev.thunder()) w.setThunderDuration(Math.max(1, prev.thunderTicks()));
    }
}
