package dev.unstablesmp.portal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Saved portal spots (spots.yml) for maps and recordings: stand where a portal should be, look where the bots should
 * pour, {@code /uss spot add <name>}. {@code /uss start spots} then tears open every saved spot in that world.
 */
public final class Spots {
    public record Spot(String name, String world, Site site) {
    }

    private final File file;
    private final Logger log;
    private final Map<String, Spot> spots = new LinkedHashMap<>();

    public Spots(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void load() {
        spots.clear();
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String name : y.getKeys(false)) {
            ConfigurationSection c = y.getConfigurationSection(name);
            if (c == null) continue;
            try {
                Site site = new Site(c.getInt("x"), c.getInt("y"), c.getInt("z"), c.getInt("fx"), c.getInt("fz"));
                spots.put(name.toLowerCase(Locale.ROOT), new Spot(name, c.getString("world", "world"), site));
            } catch (IllegalArgumentException ex) {
                log.warning("spots.yml: skipping '" + name + "' (" + ex.getMessage() + ")");
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Spot s : spots.values()) {
            y.set(s.name() + ".world", s.world());
            y.set(s.name() + ".x", s.site().x());
            y.set(s.name() + ".y", s.site().y());
            y.set(s.name() + ".z", s.site().z());
            y.set(s.name() + ".fx", s.site().fx());
            y.set(s.name() + ".fz", s.site().fz());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            log.warning("Could not save " + file + ": " + ex.getMessage());
        }
    }

    public void put(String name, World w, Site site) {
        spots.put(name.toLowerCase(Locale.ROOT), new Spot(name, w.getName(), site));
        save();
    }

    public boolean remove(String name) {
        boolean had = spots.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (had) save();
        return had;
    }

    public int clear() {
        int n = spots.size();
        spots.clear();
        save();
        return n;
    }

    public List<Spot> all() {
        return new ArrayList<>(spots.values());
    }

    public List<Site> in(World w) {
        List<Site> out = new ArrayList<>();
        for (Spot s : spots.values()) {
            World sw = Bukkit.getWorld(s.world());
            if (sw != null && sw.getUID().equals(w.getUID())) out.add(s.site());
        }
        return out;
    }
}
