package dev.unstablesmp.map;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * maps.yml: named arenas ("maps") with spawn lists per key (players, bots, red, blue, null, law...), a return
 * point, an optional region, plus block links (note blocks, buttons, pressure plates) that send players to a map.
 */
public final class MapService {

    public static final class GameMap {
        public final String name;
        public final Map<String, List<Location>> spawns = new LinkedHashMap<>();
        public Location exit;
        public Location pos1, pos2;

        GameMap(String name) {
            this.name = name;
        }

        /** Random spawn for a key, falling back to "players", then any spawn. */
        public Location spawn(String key) {
            List<Location> l = key == null ? null : spawns.get(key.toLowerCase(Locale.ROOT));
            if (l == null || l.isEmpty()) l = spawns.get("players");
            if (l == null || l.isEmpty()) {
                for (List<Location> any : spawns.values()) if (!any.isEmpty()) {
                    l = any;
                    break;
                }
            }
            if (l == null || l.isEmpty()) return null;
            return l.get(ThreadLocalRandom.current().nextInt(l.size())).clone();
        }

        public boolean contains(Location at) {
            if (pos1 == null || pos2 == null || at.getWorld() != pos1.getWorld()) return true;
            return between(at.getX(), pos1.getX(), pos2.getX()) && between(at.getY(), pos1.getY(), pos2.getY())
                    && between(at.getZ(), pos1.getZ(), pos2.getZ());
        }

        private static boolean between(double v, double a, double b) {
            return v >= Math.min(a, b) - 0.5 && v <= Math.max(a, b) + 1.5;
        }
    }

    /** A linked block: clicking/stepping on it sends the player to {@code map}'s {@code spawn}, optionally with a kit/team. */
    public record Link(String map, String spawn, String kit, String team) {
    }

    private final File file;
    private final Logger log;
    private final Map<String, GameMap> maps = new LinkedHashMap<>();
    private final Map<String, Link> links = new HashMap<>();
    private Location lobby;

    public MapService(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "maps.yml");
        this.log = log;
    }

    public static String blockKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    public void reload() {
        maps.clear();
        links.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        lobby = y.getLocation("lobby");
        ConfigurationSection ms = y.getConfigurationSection("maps");
        if (ms != null) {
            for (String n : ms.getKeys(false)) {
                ConfigurationSection c = ms.getConfigurationSection(n);
                if (c == null) continue;
                GameMap m = new GameMap(n);
                m.exit = c.getLocation("exit");
                m.pos1 = c.getLocation("pos1");
                m.pos2 = c.getLocation("pos2");
                ConfigurationSection sp = c.getConfigurationSection("spawns");
                if (sp != null) {
                    for (String k : sp.getKeys(false)) {
                        List<Location> list = new ArrayList<>();
                        for (Object o : sp.getList(k, List.of())) if (o instanceof Location l) list.add(l);
                        m.spawns.put(k, list);
                    }
                }
                maps.put(n.toLowerCase(Locale.ROOT), m);
            }
        }
        ConfigurationSection ls = y.getConfigurationSection("links");
        if (ls != null) {
            for (String k : ls.getKeys(false)) {
                ConfigurationSection c = ls.getConfigurationSection(k);
                if (c == null) continue;
                links.put(k.replace(';', ':'), new Link(c.getString("map"), c.getString("spawn", "players"),
                        c.getString("kit", ""), c.getString("team", "")));
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("lobby", lobby);
        for (GameMap m : maps.values()) {
            String p = "maps." + m.name + ".";
            y.set(p + "exit", m.exit);
            y.set(p + "pos1", m.pos1);
            y.set(p + "pos2", m.pos2);
            for (Map.Entry<String, List<Location>> e : m.spawns.entrySet()) y.set(p + "spawns." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Link> e : links.entrySet()) {
            String p = "links." + e.getKey().replace(':', ';') + ".";
            y.set(p + "map", e.getValue().map());
            y.set(p + "spawn", e.getValue().spawn());
            y.set(p + "kit", e.getValue().kit());
            y.set(p + "team", e.getValue().team());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            log.warning("Could not save maps.yml: " + ex.getMessage());
        }
    }

    public GameMap get(String name) {
        return name == null ? null : maps.get(name.toLowerCase(Locale.ROOT));
    }

    public GameMap create(String name, Location at) {
        GameMap m = maps.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new GameMap(name.toLowerCase(Locale.ROOT)));
        m.spawns.computeIfAbsent("players", k -> new ArrayList<>()).add(at.clone());
        save();
        return m;
    }

    public boolean delete(String name) {
        boolean r = maps.remove(name.toLowerCase(Locale.ROOT)) != null;
        links.values().removeIf(l -> l.map().equalsIgnoreCase(name));
        save();
        return r;
    }

    public void addSpawn(GameMap m, String key, Location at, boolean replace) {
        List<Location> l = m.spawns.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
        if (replace) l.clear();
        l.add(at.clone());
        save();
    }

    public List<String> names() {
        return new ArrayList<>(maps.keySet());
    }

    public Location lobby() {
        return lobby;
    }

    public void setLobby(Location l) {
        lobby = l.clone();
        save();
    }

    public void link(Block b, Link link) {
        links.put(blockKey(b), link);
        save();
    }

    public boolean unlink(Block b) {
        boolean r = links.remove(blockKey(b)) != null;
        if (r) save();
        return r;
    }

    public Link linkAt(Block b) {
        return links.isEmpty() ? null : links.get(blockKey(b));
    }
}
