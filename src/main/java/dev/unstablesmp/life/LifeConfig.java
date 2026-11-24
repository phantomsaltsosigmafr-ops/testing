package dev.unstablesmp.life;

import dev.unstablesmp.Settings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * The {@code residents:} section of config.yml: how the player-like VexBot population behaves. Pure parsing (no server
 * needed), so it is unit tested.
 */
public final class LifeConfig {

    /** One thing a resident can be doing: commands to start it, how long it lasts, how often it is picked. */
    public record Activity(String id, int weight, Settings.IntRange minutes, List<String> commands) {
    }

    /** A named place residents visit (spawn, shops, the arena...). */
    public record Hotspot(String name, String world, int x, int y, int z) {
    }

    public final boolean autoStart;
    public final Settings.IntRange population;
    public final Settings.IntRange peakPopulation;
    public final int peakFrom;
    public final int peakTo;
    public final Settings.IntRange sessionMinutes;
    public final Settings.IntRange joinGapSeconds;
    public final List<String> names;

    public final String homeWorld;
    public final boolean homesAroundSpawn;
    public final int homeCenterX;
    public final int homeCenterZ;
    public final Settings.IntRange homeDistance;
    public final int homeRadius;
    public final Settings.IntRange mineDepth;
    public final List<Hotspot> hotspots;

    public final List<String> setupCommands;
    public final List<String> joinCommands;
    public final List<String> afterJoinCommands;
    public final List<String> leaveCommands;
    public final Map<String, Activity> activities;

    public final boolean chat;
    public final Settings.IntRange chatDelaySeconds;
    public final double greetChance;
    public final double byeChance;
    public final double welcomeChance;
    public final double idleChancePerMinute;
    public final List<String> greetings;
    public final List<String> goodbyes;
    public final List<String> welcomes;
    public final List<String> welcomesNew;
    public final List<String> idle;

    public final boolean hideFromServerList;

    private LifeConfig(ConfigurationSection c) {
        autoStart = c.getBoolean("auto-start", false);
        population = Settings.IntRange.parse(c.get("population"), new Settings.IntRange(2, 4));
        peakPopulation = Settings.IntRange.parse(c.get("peak-population"), new Settings.IntRange(4, 7));
        int[] peak = startEnd(c.get("peak-hours"), 16, 23);
        peakFrom = Math.floorMod(peak[0], 24);
        peakTo = Math.floorMod(peak[1], 24);
        sessionMinutes = Settings.IntRange.parse(c.get("session-minutes"), new Settings.IntRange(20, 90));
        joinGapSeconds = Settings.IntRange.parse(c.get("join-gap-seconds"), new Settings.IntRange(45, 300));
        names = new ArrayList<>();
        for (String n : c.getStringList("names")) {
            String t = n.trim();
            if (t.matches("[A-Za-z0-9_]{3,16}") && !names.contains(t)) names.add(t);
        }

        ConfigurationSection h = section(c, "homes");
        homeWorld = h.getString("world", "");
        String center = h.getString("center", "spawn").trim().toLowerCase(Locale.ROOT);
        homesAroundSpawn = center.equals("spawn");
        int[] cxz = homesAroundSpawn ? new int[] {0, 0} : parseXZ(center);
        homeCenterX = cxz[0];
        homeCenterZ = cxz[1];
        homeDistance = Settings.IntRange.parse(h.get("distance"), new Settings.IntRange(60, 400));
        homeRadius = Math.max(3, h.getInt("wander-radius", 12));
        mineDepth = Settings.IntRange.parse(h.get("mine-y"), new Settings.IntRange(-50, 20));

        hotspots = new ArrayList<>();
        ConfigurationSection hs = c.getConfigurationSection("hotspots");
        if (hs != null) {
            for (String name : hs.getKeys(false)) {
                ConfigurationSection p = hs.getConfigurationSection(name);
                if (p == null) continue;
                hotspots.add(new Hotspot(name, p.getString("world", ""), p.getInt("x"), p.getInt("y"), p.getInt("z")));
            }
        }

        ConfigurationSection cmd = section(c, "commands");
        setupCommands = commands(cmd.getStringList("setup"));
        joinCommands = commands(cmd.getStringList("join"));
        afterJoinCommands = commands(cmd.getStringList("after-join"));
        leaveCommands = commands(cmd.getStringList("leave"));

        activities = new LinkedHashMap<>();
        ConfigurationSection ac = c.getConfigurationSection("activities");
        if (ac != null) {
            for (String id : ac.getKeys(false)) {
                ConfigurationSection a = ac.getConfigurationSection(id);
                if (a == null) continue;
                int weight = Math.max(0, a.getInt("weight", 1));
                if (weight == 0) continue;
                activities.put(id.toLowerCase(Locale.ROOT), new Activity(id.toLowerCase(Locale.ROOT), weight,
                        Settings.IntRange.parse(a.get("minutes"), new Settings.IntRange(5, 15)), commands(a.getStringList("commands"))));
            }
        }

        ConfigurationSection ch = section(c, "chat");
        chat = ch.getBoolean("enabled", true);
        chatDelaySeconds = Settings.IntRange.parse(ch.get("delay-seconds"), new Settings.IntRange(2, 7));
        greetChance = chance(ch, "greet-chance", 0.45);
        byeChance = chance(ch, "bye-chance", 0.35);
        welcomeChance = chance(ch, "welcome-chance", 0.4);
        idleChancePerMinute = chance(ch, "idle-chance-per-minute", 0.04);
        greetings = ch.getStringList("greetings");
        goodbyes = ch.getStringList("goodbyes");
        welcomes = ch.getStringList("welcome-back");
        welcomesNew = ch.getStringList("welcome-new");
        idle = ch.getStringList("idle");

        hideFromServerList = c.getBoolean("hide-from-server-list", true);
    }

    public static LifeConfig load(ConfigurationSection c) {
        return new LifeConfig(c == null ? new MemoryConfiguration() : c);
    }

    /** Whether {@code hour} (0-23) falls in the peak window (which may wrap past midnight, e.g. 20-2). */
    public boolean isPeak(int hour) {
        if (peakFrom <= peakTo) return hour >= peakFrom && hour <= peakTo;
        return hour >= peakFrom || hour <= peakTo;
    }

    /** {@code "20-2"}, {@code [20, 2]} or one number, kept in order (the end may be smaller, e.g. past midnight). */
    static int[] startEnd(Object raw, int start, int end) {
        if (raw == null) return new int[] {start, end};
        try {
            if (raw instanceof Number n) return new int[] {n.intValue(), n.intValue()};
            if (raw instanceof List<?> l && !l.isEmpty()) {
                int a = Integer.parseInt(String.valueOf(l.get(0)).trim());
                return new int[] {a, l.size() > 1 ? Integer.parseInt(String.valueOf(l.get(1)).trim()) : a};
            }
            String s = String.valueOf(raw).trim();
            int dash = s.indexOf('-', 1);
            if (dash > 0) return new int[] {Integer.parseInt(s.substring(0, dash).trim()), Integer.parseInt(s.substring(dash + 1).trim())};
            int v = Integer.parseInt(s);
            return new int[] {v, v};
        } catch (NumberFormatException ex) {
            return new int[] {start, end};
        }
    }

    private static int[] parseXZ(String s) {
        String[] p = s.split("[ ,]+");
        try {
            return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[p.length - 1])};
        } catch (RuntimeException ex) {
            return new int[] {0, 0};
        }
    }

    private static double chance(ConfigurationSection c, String key, double fallback) {
        return Math.max(0, Math.min(1, c.getDouble(key, fallback)));
    }

    private static ConfigurationSection section(ConfigurationSection c, String key) {
        ConfigurationSection s = c.getConfigurationSection(key);
        return s != null ? s : new MemoryConfiguration();
    }

    private static List<String> commands(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (t.startsWith("/")) t = t.substring(1);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
