package dev.unstablesmp.faction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Faction and unit-template definitions from config.yml, plus persisted faction membership (data.yml). */
public final class FactionRegistry {
    private final Map<String, UnitTemplate> templates = new LinkedHashMap<>();
    private final Map<String, Faction> factions = new LinkedHashMap<>();
    private final File dataFile;
    private final Logger log;

    public FactionRegistry(File dataFolder, Logger log) {
        this.dataFile = new File(dataFolder, "data.yml");
        this.log = log;
    }

    public void load(ConfigurationSection root) {
        templates.clear();
        ConfigurationSection ts = root.getConfigurationSection("templates");
        if (ts != null) {
            for (String id : ts.getKeys(false)) {
                ConfigurationSection c = ts.getConfigurationSection(id);
                if (c != null) templates.put(id, UnitTemplate.load(id, c, log));
            }
        }
        ConfigurationSection fs = root.getConfigurationSection("factions");
        List<String> seen = new ArrayList<>();
        if (fs != null) {
            for (String id : fs.getKeys(false)) {
                ConfigurationSection c = fs.getConfigurationSection(id);
                if (c == null) continue;
                seen.add(id);
                Faction live = factions.get(id);
                if (live == null) {
                    live = new Faction(id);
                    live.loadDefaults(c);
                    factions.put(id, live);
                }
                live.loadDefinition(c, log); // refresh definition, keep live orders
                for (String t : live.army) {
                    if (!templates.containsKey(t)) log.warning("Faction " + id + " uses unknown template '" + t + "'");
                }
                if (!live.leaderTemplate.isEmpty() && !templates.containsKey(live.leaderTemplate)) {
                    log.warning("Faction " + id + " has unknown leader template '" + live.leaderTemplate + "'");
                }
            }
        }
        factions.keySet().removeIf(id -> !seen.contains(id));
    }

    public Collection<Faction> all() {
        return factions.values();
    }

    public List<String> ids() {
        return new ArrayList<>(factions.keySet());
    }

    public Faction get(String id) {
        return id == null ? null : factions.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public UnitTemplate template(String id) {
        return id == null ? null : templates.get(id);
    }

    public Collection<UnitTemplate> templates() {
        return templates.values();
    }

    /** The faction a player belongs to (players are in at most one faction). */
    public Faction factionOf(UUID player) {
        if (player == null) return null;
        for (Faction f : factions.values()) if (f.members.contains(player)) return f;
        return null;
    }

    public void join(Faction f, UUID player) {
        for (Faction other : factions.values()) other.members.remove(player);
        f.members.add(player);
        f.grudges.remove(player);
    }

    public void leave(Faction f, UUID player) {
        f.members.remove(player);
    }

    // ------------------------------------------------------------------ persistence

    public void loadMembers() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection m = y.getConfigurationSection("members");
        if (m == null) return;
        for (String id : m.getKeys(false)) {
            Faction f = factions.get(id);
            if (f == null) continue;
            for (String s : m.getStringList(id)) {
                try {
                    f.members.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                    log.warning("Bad UUID in data.yml: " + s);
                }
            }
        }
    }

    public void saveMembers() {
        YamlConfiguration y = new YamlConfiguration();
        for (Faction f : factions.values()) {
            List<String> list = new ArrayList<>();
            for (UUID u : f.members) list.add(u.toString());
            y.set("members." + f.id, list);
        }
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            log.warning("Could not save data.yml: " + ex.getMessage());
        }
    }
}
