package dev.unstablesmp;

import dev.unstablesmp.portal.Fx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/** Typed snapshot of config.yml. Rebuilt on /uss reload; a running invasion keeps the snapshot it started with. */
public final class Settings {

    /** Inclusive integer range, written in config as {@code 3}, {@code "1-3"} or {@code [1, 3]}. */
    public record IntRange(int min, int max) {
        public IntRange {
            if (max < min) {
                int t = min;
                min = max;
                max = t;
            }
        }

        public int random() {
            return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        }

        public static IntRange parse(Object raw, IntRange fallback) {
            if (raw == null) return fallback;
            try {
                if (raw instanceof Number n) return new IntRange(n.intValue(), n.intValue());
                if (raw instanceof List<?> l && !l.isEmpty()) {
                    int a = Integer.parseInt(String.valueOf(l.get(0)).trim());
                    int b = l.size() > 1 ? Integer.parseInt(String.valueOf(l.get(1)).trim()) : a;
                    return new IntRange(a, b);
                }
                String s = String.valueOf(raw).trim();
                int dash = s.indexOf('-', 1);
                if (dash > 0) return new IntRange(Integer.parseInt(s.substring(0, dash).trim()), Integer.parseInt(s.substring(dash + 1).trim()));
                int v = Integer.parseInt(s);
                return new IntRange(v, v);
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }

        @Override
        public String toString() {
            return min == max ? Integer.toString(min) : min + "-" + max;
        }
    }

    public enum RunAs { CONSOLE, STARTER }

    // ---- spawning (the VexBot command)
    public final List<String> commands;
    public final RunAs runAs;
    public final IntRange count;
    public final int spawnInterval;
    public final double chance;
    public final int maxAlive;
    public final int maxTotal;
    public final double captureRadius;
    public final int captureTicks;

    // ---- the invasion
    public final int radius;
    public final int maxPortals;
    public final int portalsPerWave;
    public final int waveInterval;
    public final int minHeight;
    public final int maxHeight;
    public final int minSpacing;
    public final int closePerStep;
    public final int durationSeconds;
    public final boolean storm;
    public final boolean loadChunks;
    public final List<String> startCommands;
    public final List<String> endCommands;
    public final int leaderAtPercent;
    public final List<String> leaderCommands;

    // ---- totem of null
    public final String totemName;
    public final List<String> totemLore;
    public final String totemItemModel;
    /** Null = vanilla shimmer (on when enchanted); true/false forces it. */
    public final Boolean totemGlint;
    public final List<String> totemEnchants;
    public final boolean totemMatchName;
    public final int totemKillRadius;
    public final double totemWaveSpeed;
    public final boolean totemClosePortals;
    public final int totemQuietSeconds;
    public final List<String> totemKillCommands;

    // ---- portal look
    public final boolean buildPortals;
    public final boolean selfRepair;
    public final int width;
    public final int height;
    public final List<Material> frame;

    // ---- effects + text
    public final Map<String, Fx.Effect> effects;
    public final Map<String, String> messages;

    private Settings(ConfigurationSection c, Logger log) {
        ConfigurationSection sp = section(c, "spawn");
        commands = commandList(sp.getStringList("commands"));
        runAs = parseEnum(RunAs.class, sp.getString("run-as"), RunAs.CONSOLE, "spawn.run-as", log);
        count = IntRange.parse(sp.get("count"), new IntRange(1, 1));
        spawnInterval = Math.max(1, sp.getInt("interval-ticks", 40));
        chance = Math.max(0, Math.min(1, sp.getDouble("chance-per-portal", 0.25)));
        maxAlive = Math.max(0, sp.getInt("max-alive", 60));
        maxTotal = Math.max(0, sp.getInt("max-total", 400));
        captureRadius = Math.max(2, sp.getDouble("capture-radius", 16));
        captureTicks = Math.max(20, sp.getInt("capture-seconds", 10) * 20);

        ConfigurationSection in = section(c, "invasion");
        radius = Math.max(4, in.getInt("radius", 40));
        maxPortals = Math.max(1, Math.min(500, in.getInt("max-portals", 60)));
        portalsPerWave = Math.max(1, in.getInt("portals-per-wave", 3));
        waveInterval = Math.max(1, in.getInt("wave-interval-ticks", 10));
        minHeight = Math.max(0, in.getInt("min-height-above-ground", 2));
        maxHeight = Math.max(minHeight, in.getInt("max-height-above-ground", 18));
        minSpacing = Math.max(0, in.getInt("min-spacing", 4));
        closePerStep = Math.max(1, in.getInt("close-per-step", 2));
        durationSeconds = Math.max(5, in.getInt("duration-seconds", 120));
        storm = in.getBoolean("storm", false);
        loadChunks = in.getBoolean("keep-chunks-loaded", true);
        startCommands = commandList(in.getStringList("start-commands"));
        endCommands = commandList(in.getStringList("end-commands"));
        ConfigurationSection le = section(in, "leader");
        leaderAtPercent = Math.max(0, Math.min(100, le.getInt("at-percent", 40)));
        leaderCommands = commandList(le.getStringList("commands"));

        ConfigurationSection po = section(c, "portal");
        buildPortals = po.getBoolean("build", true);
        selfRepair = po.getBoolean("self-repair", true);
        width = Math.max(2, Math.min(21, po.getInt("width", 2)));
        height = Math.max(3, Math.min(21, po.getInt("height", 3)));
        frame = weighted(po.getStringList("frame"), log);

        ConfigurationSection to = section(c, "totem");
        totemName = to.getString("name", "<aqua>The Totem Of Null");
        totemLore = to.getStringList("lore");
        totemItemModel = to.getString("item-model", "");
        totemGlint = to.isBoolean("glint") ? Boolean.valueOf(to.getBoolean("glint")) : null;
        totemEnchants = to.isList("enchantments") ? to.getStringList("enchantments") : List.of("vanishing_curse:1");
        totemMatchName = to.getBoolean("match-by-name", true);
        totemKillRadius = Math.max(0, to.getInt("kill-radius", 0));
        totemWaveSpeed = Math.max(0.5, to.getDouble("wave-blocks-per-tick", 2.5));
        totemClosePortals = to.getBoolean("close-portals", false);
        totemQuietSeconds = Math.max(0, to.getInt("quiet-seconds", 10));
        totemKillCommands = commandList(to.getStringList("kill-commands"));

        effects = new HashMap<>();
        ConfigurationSection fx = section(c, "effects");
        for (String key : fx.getKeys(false)) {
            ConfigurationSection e = fx.getConfigurationSection(key);
            if (e != null) effects.put(key, Fx.Effect.parse(key, e, log));
        }

        messages = new HashMap<>();
        ConfigurationSection ms = section(c, "messages");
        for (String key : ms.getKeys(false)) messages.put(key, ms.getString(key, ""));
    }

    public static Settings load(ConfigurationSection root, Logger log) {
        return new Settings(root, log);
    }

    public Fx.Effect effect(String key) {
        return effects.getOrDefault(key, Fx.Effect.EMPTY);
    }

    public String message(String key) {
        return messages.getOrDefault(key, "");
    }

    // ------------------------------------------------------------------ parsing helpers

    private static ConfigurationSection section(ConfigurationSection c, String key) {
        ConfigurationSection s = c == null ? null : c.getConfigurationSection(key);
        return s != null ? s : new MemoryConfiguration();
    }

    private static List<String> commandList(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (t.startsWith("/")) t = t.substring(1);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, String path, Logger log) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            if (log != null) log.warning("config " + path + ": unknown value '" + raw + "', using " + fallback);
            return fallback;
        }
    }

    /** {@code "CRYING_OBSIDIAN:1"} entries expanded by weight, so a random pick honours the weights. */
    private static List<Material> weighted(List<String> raw, Logger log) {
        List<Material> out = new ArrayList<>();
        for (String s : raw) {
            String name = s;
            int weight = 1;
            int colon = s.lastIndexOf(':');
            if (colon > 0) {
                try {
                    weight = Math.max(1, Math.min(50, Integer.parseInt(s.substring(colon + 1).trim())));
                    name = s.substring(0, colon);
                } catch (NumberFormatException ex) {
                    name = s;
                }
            }
            Material m = Material.matchMaterial(name.trim());
            if (m == null || !m.isBlock() || !m.isSolid() || m.hasGravity()) {
                if (log != null) log.warning("config portal.frame: '" + s + "' is not a solid, non-falling block, skipped");
                continue;
            }
            for (int i = 0; i < weight; i++) out.add(m);
        }
        if (out.isEmpty()) out.add(Material.OBSIDIAN);
        return out;
    }
}
