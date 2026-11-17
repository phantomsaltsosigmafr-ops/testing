package dev.unstablesmp.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.persistence.PersistentDataType;

/**
 * Registry of every entity the plugin spawns. Enforces the per-player and per-server display caps and the
 * server mob cap, tags entities so leftovers can be found, and removes them on chunk unload so there are
 * never ghost entities.
 */
public final class EntityTracker implements Listener, Ticker.Tickable {
    public enum Kind { DISPLAY, MOB }

    private record Entry(Entity entity, UUID owner, Kind kind) {
    }

    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, int[]> displaysByOwner = new HashMap<>();
    private int displays;
    private int mobs;

    private int maxDisplaysPerPlayer = 160;
    private int maxDisplaysServer = 2000;
    private int maxMobsServer = 400;

    public void reload(ConfigurationSection perf) {
        if (perf == null) return;
        maxDisplaysPerPlayer = Math.max(1, perf.getInt("max-display-entities-per-player", 160));
        maxDisplaysServer = Math.max(1, perf.getInt("max-display-entities-server", 2000));
        maxMobsServer = Math.max(1, perf.getInt("max-mobs-server", 400));
    }

    /** Marks an entity as ours: non-persistent, tagged, counted. Call from inside the spawn consumer or right after. */
    public void track(Entity e, UUID owner, Kind kind) {
        e.setPersistent(false);
        e.addScoreboardTag(Keys.ENTITY_TAG);
        e.getPersistentDataContainer().set(Keys.KIND, PersistentDataType.STRING, kind.name());
        if (entries.put(e.getUniqueId(), new Entry(e, owner, kind)) != null) return;
        if (kind == Kind.DISPLAY) {
            displays++;
            if (owner != null) displaysByOwner.computeIfAbsent(owner, k -> new int[1])[0]++;
        } else {
            mobs++;
        }
    }

    /** Stops counting an entity (does not remove it). */
    public void release(Entity e) {
        if (e == null) return;
        Entry en = entries.remove(e.getUniqueId());
        if (en == null) return;
        uncount(en);
    }

    private void uncount(Entry en) {
        if (en.kind == Kind.DISPLAY) {
            displays--;
            if (en.owner != null) {
                int[] c = displaysByOwner.get(en.owner);
                if (c != null && --c[0] <= 0) displaysByOwner.remove(en.owner);
            }
        } else {
            mobs--;
        }
    }

    /** Releases and removes an entity. Safe to call with null or already-removed entities. */
    public void remove(Entity e) {
        if (e == null) return;
        release(e);
        e.remove();
    }

    public boolean canSpawnDisplays(UUID owner, int n) {
        if (displays + n > maxDisplaysServer) return false;
        if (owner == null) return true;
        int[] c = displaysByOwner.get(owner);
        return (c == null ? 0 : c[0]) + n <= maxDisplaysPerPlayer;
    }

    public boolean canSpawnMobs(int n) {
        return mobs + n <= maxMobsServer;
    }

    public int displays() {
        return displays;
    }

    public int mobs() {
        return mobs;
    }

    public int displaysOf(UUID owner) {
        int[] c = displaysByOwner.get(owner);
        return c == null ? 0 : c[0];
    }

    public boolean isOurs(Entity e) {
        return e != null && e.getScoreboardTags().contains(Keys.ENTITY_TAG);
    }

    @Override
    public void tick(int now) {
        // Prune entries whose entity died or was removed by something else (e.g. /kill).
        if (now % 100 != 0) return;
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry en = it.next();
            if (!en.entity.isValid()) {
                it.remove();
                uncount(en);
            }
        }
    }

    @Override
    public String name() {
        return "entity-tracker";
    }

    /** Removes every tagged entity in every loaded world. Returns the number removed. */
    public int sweepAll() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(Keys.ENTITY_TAG)) {
                    release(e);
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    /** Removes everything we track (plugin disable). */
    public void removeAll() {
        for (Entry en : entries.values()) {
            if (en.entity instanceof LivingEntity || en.entity.isValid()) en.entity.remove();
        }
        entries.clear();
        displaysByOwner.clear();
        displays = 0;
        mobs = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity ent : e.getEntities()) {
            if (ent.getScoreboardTags().contains(Keys.ENTITY_TAG)) {
                release(ent);
                ent.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) {
            if (ent.getScoreboardTags().contains(Keys.ENTITY_TAG) && !entries.containsKey(ent.getUniqueId())) {
                ent.remove(); // leftover from a crash: never resurrect ghosts
            }
        }
    }
}
