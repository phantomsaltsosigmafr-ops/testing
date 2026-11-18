package dev.unstablesmp.kit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** kits.yml: named kits for players (/uss kit give) and bots (group/mode loadouts). */
public final class KitService {
    private final File file;
    private final Logger log;
    private final Map<String, Kit> kits = new LinkedHashMap<>();

    public KitService(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "kits.yml");
        this.log = log;
    }

    public void reload() {
        kits.clear();
        if (!file.exists()) {
            kits.putAll(DefaultKits.all());
            save();
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("kits");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection c = root.getConfigurationSection(id);
                if (c == null) continue;
                try {
                    kits.put(id.toLowerCase(Locale.ROOT), Kit.load(id, c));
                } catch (RuntimeException ex) {
                    log.warning("Kit " + id + " could not be loaded: " + ex.getMessage());
                }
            }
        }
        // Built-ins that were deleted come back, so bots always have a loadout for every mode.
        boolean added = false;
        for (Map.Entry<String, Kit> e : DefaultKits.all().entrySet()) {
            if (!kits.containsKey(e.getKey())) {
                kits.put(e.getKey(), e.getValue());
                added = true;
            }
        }
        if (added) save();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("UnstableSMP kits. Edit in game: /uss kit save <name> (saves your inventory)."));
        for (Kit k : kits.values()) k.save(y.createSection("kits." + k.name));
        try {
            y.save(file);
        } catch (IOException ex) {
            log.warning("Could not save kits.yml: " + ex.getMessage());
        }
    }

    public Kit get(String name) {
        return name == null ? null : kits.get(name.toLowerCase(Locale.ROOT));
    }

    public void put(Kit k) {
        kits.put(k.name.toLowerCase(Locale.ROOT), k);
        save();
    }

    public boolean remove(String name) {
        boolean r = kits.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (r) save();
        return r;
    }

    public List<String> names() {
        return new ArrayList<>(kits.keySet());
    }
}
