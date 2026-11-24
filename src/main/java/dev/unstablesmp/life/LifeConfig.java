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
 * The {@code residents:}, {@code bandits:} and {@code gg:} sections of config.yml. Pure parsing (no server needed), so
 * it is unit tested.
 */
public final class LifeConfig {

    /** One thing a resident can be doing: commands to start it, how long it lasts, how often it is picked. */
    public record Activity(String id, int weight, Settings.IntRange minutes, List<String> commands) {
    }

    /** A named place residents visit (spawn, shops, the arena...). */
    public record Hotspot(String name, String world, int x, int y, int z) {
    }

    // ---- residents
    public final boolean autoStart;
    public final Settings.IntRange population;
    public final Settings.IntRange peakPopulation;
    public final int peakFrom;
    public final int peakTo;
    public final Settings.IntRange sessionMinutes;
    public final Settings.IntRange joinGapSeconds;
    public final Settings.IntRange restMinutes;
    /** Weights for crews of 1, 2, 3 and 4. */
    public final int[] crewWeights;
    public final List<String> names;

    public final String homeWorld;
    public final boolean homesAroundSpawn;
    public final int homeCenterX;
    public final int homeCenterZ;
    public final Settings.IntRange homeDistance;
    public final int homeRadius;
    public final Settings.IntRange mineDepth;
    public final List<String> ores;
    public final Settings.IntRange oreHopSeconds;
    public final List<Hotspot> hotspots;

    public final List<String> setupCommands;
    public final List<String> joinCommands;
    public final List<String> afterJoinCommands;
    public final List<String> leaveCommands;
    public final List<String> oreCommands;
    public final Map<String, Activity> activities;

    // ---- bandits
    public final boolean banditsAuto;
    public final Settings.IntRange raidEveryMinutes;
    /** Weights for bandit groups of 1, 2, 3 and 4. */
    public final int[] banditGroupWeights;
    public final Settings.IntRange banditSpawnDistance;
    public final Settings.IntRange raidMinutes;
    public final int safeRadius;
    public final int playerCooldownMinutes;
    public final List<String> banditNames;
    /** Armour tier weights: none, leather, chainmail, iron. */
    public final int[] armourWeights;
    public final double armourPieceChance;
    public final List<String> weapons;
    public final List<String> food;
    public final Settings.IntRange foodAmount;
    public final List<String> banditSetup;
    public final List<String> banditJoin;
    public final List<String> banditAfterJoin;
    public final List<String> banditAttack;
    public final List<String> banditFlee;
    public final List<String> banditLeave;

    // ---- gg wave
    public final List<String> ggLines;
    public final int ggSpreadTicks;
    public final double ggDoubleChance;

    private LifeConfig(ConfigurationSection root) {
        ConfigurationSection c = section(root, "residents");
        autoStart = c.getBoolean("auto-start", false);
        population = Settings.IntRange.parse(c.get("population"), new Settings.IntRange(3, 6));
        peakPopulation = Settings.IntRange.parse(c.get("peak-population"), new Settings.IntRange(6, 10));
        int[] peak = startEnd(c.get("peak-hours"), 16, 23);
        peakFrom = Math.floorMod(peak[0], 24);
        peakTo = Math.floorMod(peak[1], 24);
        sessionMinutes = Settings.IntRange.parse(c.get("session-minutes"), new Settings.IntRange(20, 90));
        joinGapSeconds = Settings.IntRange.parse(c.get("join-gap-seconds"), new Settings.IntRange(45, 300));
        restMinutes = Settings.IntRange.parse(c.get("rest-minutes"), new Settings.IntRange(10, 45));
        crewWeights = weights(section(c, "crews"), new String[] {"solo", "duo", "trio", "squad"}, new int[] {50, 30, 15, 5});
        names = names(c.getStringList("names"));

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
        ores = c.getStringList("ores");
        oreHopSeconds = Settings.IntRange.parse(c.get("ore-hop-seconds"), new Settings.IntRange(20, 40));

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
        oreCommands = commands(cmd.getStringList("next-ore"));

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

        ConfigurationSection b = section(root, "bandits");
        banditsAuto = b.getBoolean("auto", true);
        raidEveryMinutes = Settings.IntRange.parse(b.get("raid-every-minutes"), new Settings.IntRange(10, 25));
        banditGroupWeights = weights(section(b, "group"), new String[] {"solo", "duo", "trio", "squad"}, new int[] {30, 40, 25, 5});
        banditSpawnDistance = Settings.IntRange.parse(b.get("spawn-distance"), new Settings.IntRange(30, 55));
        raidMinutes = Settings.IntRange.parse(b.get("raid-minutes"), new Settings.IntRange(3, 6));
        safeRadius = Math.max(0, b.getInt("safe-radius", 48));
        playerCooldownMinutes = Math.max(0, b.getInt("player-cooldown-minutes", 15));
        banditNames = names(b.getStringList("names"));
        ConfigurationSection gear = section(b, "gear");
        armourWeights = weights(section(gear, "armour"), new String[] {"none", "leather", "chainmail", "iron"}, new int[] {4, 4, 2, 1});
        armourPieceChance = Math.max(0, Math.min(1, gear.getDouble("piece-chance", 0.6)));
        weapons = gear.getStringList("weapons");
        food = gear.getStringList("food");
        foodAmount = Settings.IntRange.parse(gear.get("food-amount"), new Settings.IntRange(2, 6));
        ConfigurationSection bc = section(b, "commands");
        banditSetup = commands(bc.getStringList("setup"));
        banditJoin = commands(bc.getStringList("join"));
        banditAfterJoin = commands(bc.getStringList("after-join"));
        banditAttack = commands(bc.getStringList("attack"));
        banditFlee = commands(bc.getStringList("flee"));
        banditLeave = commands(bc.getStringList("leave"));

        ConfigurationSection gg = section(root, "gg");
        List<String> lines = gg.getStringList("lines");
        ggLines = lines.isEmpty() ? List.of("GG", "gg", "gG", "Gg") : lines;
        ggSpreadTicks = Math.max(1, (int) Math.round(gg.getDouble("spread-seconds", 4) * 20));
        ggDoubleChance = Math.max(0, Math.min(1, gg.getDouble("double-chance", 0.25)));
    }

    /** Loads from the config root (the residents/bandits/gg sections). */
    public static LifeConfig load(ConfigurationSection root) {
        return new LifeConfig(root == null ? new MemoryConfiguration() : root);
    }

    /** Whether {@code hour} (0-23) falls in the peak window (which may wrap past midnight, e.g. 20-2). */
    public boolean isPeak(int hour) {
        if (peakFrom <= peakTo) return hour >= peakFrom && hour <= peakTo;
        return hour >= peakFrom || hour <= peakTo;
    }

    /** Index 0..3 (a group of 1..4) picked by weight. */
    public static int pickSize(int[] weights, int max, java.util.Random r) {
        int total = 0;
        for (int i = 0; i < weights.length && i < max; i++) total += weights[i];
        if (total <= 0) return 1;
        int roll = r.nextInt(total);
        for (int i = 0; i < weights.length && i < max; i++) {
            roll -= weights[i];
            if (roll < 0) return i + 1;
        }
        return 1;
    }

    private static int[] weights(ConfigurationSection s, String[] keys, int[] fallback) {
        int[] out = new int[keys.length];
        boolean any = false;
        for (int i = 0; i < keys.length; i++) {
            out[i] = Math.max(0, s.getInt(keys[i], 0));
            any |= s.contains(keys[i]);
        }
        return any ? out : fallback.clone();
    }

    private static List<String> names(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String n : raw) {
            String t = n.trim();
            if (t.matches("[A-Za-z0-9_]{3,16}") && !out.contains(t)) out.add(t);
        }
        return out;
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
