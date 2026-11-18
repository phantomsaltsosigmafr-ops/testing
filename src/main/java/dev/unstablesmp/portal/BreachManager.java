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
import org.bukkit.plugin.Plugin;

/**
 * Runs every breach from a single repeating task (one tick each server tick), owns the block journal, the spawn
 * capture and the storm, and keeps restore.yml up to date for crash safety.
 */
public final class BreachManager implements Runnable {
    private record Weather(boolean storm, boolean thunder, int weatherTicks, int thunderTicks, int clearTicks) {
    }

    private final Plugin plugin;
    private final BlockJournal journal;
    private final SpawnCapture capture = new SpawnCapture();
    private final List<Breach> breaches = new ArrayList<>();
    private final Map<UUID, Weather> weather = new HashMap<>();
    private final Set<String> logged = new HashSet<>();
    private Settings settings;
    private int now;
    private int nextId = 1;

    public BreachManager(Plugin plugin, BlockJournal journal, Settings settings) {
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

    public List<Breach> breaches() {
        return breaches;
    }

    void logOnce(String msg) {
        if (logged.add(msg)) plugin.getLogger().warning(msg);
    }

    public PortalShape shape() {
        return new PortalShape(settings.width, settings.height);
    }

    public SiteFinder finder() {
        return new SiteFinder(settings, shape(), journal);
    }

    // ------------------------------------------------------------------ starting

    /**
     * Starts a breach centred on (x, y, z). With {@code fixed} sites (saved spots, /uss portal) those are used;
     * otherwise portals are spread in a ring around the centre. Returns null if no portal could be placed.
     */
    public Breach start(World w, double x, double y, double z, Breach.Options o, UUID starter, List<Site> fixed) {
        Settings s = settings;
        PortalShape shape = new PortalShape(s.width, s.height);
        SiteFinder finder = new SiteFinder(s, shape, journal);
        List<Site> sites;
        if (fixed != null) {
            sites = new ArrayList<>();
            for (Site site : fixed) if (finder.score(w, site) >= 0 && !overlaps(w, site, sites)) sites.add(site);
        } else {
            sites = finder.ring(w, x, y, z, o.portals, o.radius, takenSites(w));
        }
        if (sites.isEmpty()) return null;
        Breach b = new Breach(this, s, nextId++, w, x, y, z, starter, o);
        b.begin(now);
        for (int i = 0; i < sites.size(); i++) b.addPortal(new NullPortal(plugin, s, journal, finder, i, w, sites.get(i), shape), now);
        if (s.storm) stormOn(w, o.warningSeconds + o.durationSeconds + 60);
        breaches.add(b);
        return b;
    }

    /** Opens one portal: joins a running breach nearby, or starts a short one-portal breach. */
    public Breach openOne(World w, Site site, UUID starter) {
        for (Breach b : breaches) {
            if (!b.live() || b.world != w) continue;
            double dx = b.cx - site.x(), dz = b.cz - site.z();
            if (dx * dx + dz * dz > 160 * 160) continue;
            PortalShape shape = new PortalShape(settings.width, settings.height);
            SiteFinder finder = new SiteFinder(settings, shape, journal);
            if (finder.score(w, site) < 0) return null;
            b.addPortal(new NullPortal(plugin, settings, journal, finder, b.portals.size(), w, site, shape), now);
            return b;
        }
        Breach.Options o = Breach.Options.defaults(settings);
        o.portals = 1;
        o.warningSeconds = 0;
        return start(w, site.x() + 0.5, site.y() + 1, site.z() + 0.5, o, starter, List.of(site));
    }

    private List<Site> takenSites(World w) {
        List<Site> out = new ArrayList<>();
        for (Breach b : breaches) {
            if (b.world != w) continue;
            for (NullPortal p : b.portals) if (!p.closed()) out.add(p.site);
        }
        return out;
    }

    private boolean overlaps(World w, Site site, List<Site> chosen) {
        for (Site o : chosen) if (Math.abs(o.x() - site.x()) + Math.abs(o.z() - site.z()) < 3 && Math.abs(o.y() - site.y()) < 4) return true;
        for (Site o : takenSites(w)) if (Math.abs(o.x() - site.x()) + Math.abs(o.z() - site.z()) < 3 && Math.abs(o.y() - site.y()) < 4) return true;
        return false;
    }

    // ------------------------------------------------------------------ stopping

    /** Ends breaches (all, or in one world). Graceful collapse unless {@code immediate}. Returns how many. */
    public int stop(World only, boolean immediate) {
        int n = 0;
        for (Breach b : new ArrayList<>(breaches)) {
            if (only != null && b.world != only) continue;
            if (b.phase() == Breach.Phase.DONE) continue;
            b.stop(now, immediate);
            n++;
        }
        if (immediate) cleanupDone();
        return n;
    }

    /** Plugin disable: every block back, storm cleared, nothing left behind. */
    public void shutdown() {
        stop(null, true);
        journal.restoreAll();
        capture.clear();
        for (UUID id : new ArrayList<>(weather.keySet())) {
            World w = Bukkit.getWorld(id);
            if (w != null) stormOff(w);
        }
        weather.clear();
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void run() {
        now++;
        for (int i = 0; i < breaches.size(); i++) {
            Breach b = breaches.get(i);
            try {
                b.tick(now);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Breach #" + b.id + " failed; closing it", ex);
                b.stop(now, true);
            }
        }
        cleanupDone();
        capture.tick(now);
    }

    private void cleanupDone() {
        boolean removed = breaches.removeIf(b -> b.phase() == Breach.Phase.DONE);
        if (!removed) return;
        for (UUID id : new ArrayList<>(weather.keySet())) {
            boolean busy = false;
            for (Breach b : breaches) busy |= b.world.getUID().equals(id);
            World w = Bukkit.getWorld(id);
            if (!busy && w != null) stormOff(w);
        }
    }

    // ------------------------------------------------------------------ weather

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
