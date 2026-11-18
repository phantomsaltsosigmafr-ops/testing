package dev.unstablesmp;

import dev.unstablesmp.portal.Fx;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Typed snapshot of config.yml. Rebuilt on /uss reload; running breaches keep the snapshot they started with. */
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

        /** Linear blend from min (t=0) to max (t=1); used for start-to-end ramps where min may exceed max. */
        public static int lerp(int from, int to, double t) {
            double c = Math.max(0, Math.min(1, t));
            return (int) Math.round(from + (to - from) * c);
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

    public enum Beam { NONE, CENTER, ALL }

    // ---- spawning (the VexBot command)
    public final List<String> commands;
    public final RunAs runAs;
    public final int depth;
    public final boolean spread;
    public final IntRange countStart;
    public final IntRange countEnd;
    /** Ticks between pulses per portal at the start of the breach and at the end (it ramps between them). */
    public final int intervalStart;
    public final int intervalEnd;
    public final int maxTotal;
    public final int maxAlive;
    public final int firstPulseDelay;
    public final boolean push;
    public final double pushSpeed;
    public final double pushUp;
    public final int captureTicks;

    // ---- breach
    public final List<String> startCommands;
    public final List<String> endCommands;
    public final int portals;
    public final IntRange radius;
    public final int minSpacing;
    public final int durationSeconds;
    public final int warningSeconds;
    public final int openStagger;
    public final int closeStagger;
    public final boolean storm;
    public final boolean darkness;
    public final int darknessRadius;
    public final int broadcastRadius;
    public final BossBar.Color barColor;
    public final BossBar.Overlay barOverlay;

    // ---- portal look
    public final int width;
    public final int height;
    public final List<Material> frame;
    public final Material corner;
    public final Material surface;
    public final Beam beam;
    public final boolean lightning;
    public final int buildTicks;
    public final boolean corruption;
    public final int corruptionRadius;
    public final int corruptionMax;
    public final List<Material> corruptionBlocks;
    public final Set<Material> neverReplace;

    // ---- effects + text
    public final Map<String, Fx.Effect> effects;
    public final Map<String, String> messages;

    private Settings(ConfigurationSection c, Logger log) {
        ConfigurationSection sp = section(c, "spawn");
        commands = new ArrayList<>();
        for (String s : sp.getStringList("commands")) {
            String t = s.trim();
            if (t.startsWith("/")) t = t.substring(1);
            if (!t.isEmpty()) commands.add(t);
        }
        runAs = parseEnum(RunAs.class, sp.getString("run-as"), RunAs.CONSOLE, "spawn.run-as", log);
        depth = Math.max(0, Math.min(6, sp.getInt("depth", 0)));
        spread = sp.getBoolean("spread", true);
        countStart = IntRange.parse(sp.get("count-start"), new IntRange(1, 2));
        countEnd = IntRange.parse(sp.get("count-end"), new IntRange(2, 3));
        int[] iv = startEnd(sp.get("interval-ticks"), 60, 24);
        intervalStart = Math.max(2, iv[0]);
        intervalEnd = Math.max(2, iv[1]);
        maxTotal = Math.max(0, sp.getInt("max-total", 300));
        maxAlive = Math.max(0, sp.getInt("max-alive", 60));
        firstPulseDelay = Math.max(0, sp.getInt("first-pulse-delay-ticks", 12));
        push = sp.getBoolean("push-spawned", true);
        pushSpeed = sp.getDouble("push-speed", 0.55);
        pushUp = sp.getDouble("push-up", 0.25);
        captureTicks = Math.max(0, sp.getInt("capture-ticks", 3));

        ConfigurationSection br = section(c, "breach");
        startCommands = commandList(br.getStringList("start-commands"));
        endCommands = commandList(br.getStringList("end-commands"));
        portals = Math.max(1, Math.min(32, br.getInt("portals", 4)));
        radius = IntRange.parse(br.get("radius"), new IntRange(14, 26));
        minSpacing = Math.max(3, br.getInt("min-spacing", 9));
        durationSeconds = Math.max(5, br.getInt("duration-seconds", 90));
        warningSeconds = Math.max(0, br.getInt("warning-seconds", 6));
        openStagger = Math.max(0, br.getInt("open-stagger-ticks", 22));
        closeStagger = Math.max(0, br.getInt("close-stagger-ticks", 10));
        storm = br.getBoolean("storm", true);
        darkness = br.getBoolean("darkness", true);
        darknessRadius = Math.max(0, br.getInt("darkness-radius", 28));
        broadcastRadius = Math.max(0, br.getInt("broadcast-radius", 128));
        barColor = parseEnum(BossBar.Color.class, br.getString("bossbar-color"), BossBar.Color.PURPLE, "breach.bossbar-color", log);
        barOverlay = parseEnum(BossBar.Overlay.class, br.getString("bossbar-style"), BossBar.Overlay.NOTCHED_20, "breach.bossbar-style", log);

        ConfigurationSection po = section(c, "portal");
        width = Math.max(1, Math.min(9, po.getInt("width", 3)));
        height = Math.max(2, Math.min(12, po.getInt("height", 5)));
        frame = materials(po.getStringList("frame"), List.of(Material.OBSIDIAN), "portal.frame", log);
        corner = material(po.getString("corners"), Material.CRYING_OBSIDIAN, "portal.corners", log);
        surface = material(po.getString("surface"), Material.END_GATEWAY, "portal.surface", log);
        beam = parseEnum(Beam.class, po.getString("opening-beam"), Beam.CENTER, "portal.opening-beam", log);
        lightning = po.getBoolean("lightning", true);
        buildTicks = Math.max(1, po.getInt("build-ticks", 30));
        ConfigurationSection co = section(po, "corruption");
        corruption = co.getBoolean("enabled", true);
        corruptionRadius = Math.max(1, Math.min(16, co.getInt("radius", 6)));
        corruptionMax = Math.max(0, co.getInt("max-blocks", 70));
        corruptionBlocks = weighted(co.getStringList("blocks"), log);
        neverReplace = EnumSet.noneOf(Material.class);
        neverReplace.addAll(materials(po.getStringList("never-replace"), List.of(Material.BEDROCK), "portal.never-replace", log));

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
        return s != null ? s : new org.bukkit.configuration.MemoryConfiguration();
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

    /** {@code [start, end]}, {@code "start-end"} or a single number, kept in order (start may be larger than end). */
    public static int[] startEnd(Object raw, int start, int end) {
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

    static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, String path, Logger log) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            if (log != null) log.warning("config " + path + ": unknown value '" + raw + "', using " + fallback);
            return fallback;
        }
    }

    private static Material material(String raw, Material fallback, String path, Logger log) {
        if (raw == null || raw.isBlank()) return fallback;
        Material m = Material.matchMaterial(raw.trim());
        if (m == null || !m.isBlock()) {
            if (log != null) log.warning("config " + path + ": '" + raw + "' is not a block, using " + fallback);
            return fallback;
        }
        return m;
    }

    private static List<Material> materials(List<String> raw, List<Material> fallback, String path, Logger log) {
        List<Material> out = new ArrayList<>();
        for (String s : raw) {
            Material m = Material.matchMaterial(s.trim());
            if (m != null && m.isBlock()) out.add(m);
            else if (log != null) log.warning("config " + path + ": '" + s + "' is not a block, skipped");
        }
        return out.isEmpty() ? fallback : out;
    }

    /** {@code "BLACK_CONCRETE:4"} entries expanded by weight, so a random pick honours the weights. */
    private static List<Material> weighted(List<String> raw, Logger log) {
        List<Material> out = new ArrayList<>();
        for (String s : raw) {
            String[] p = s.split(":");
            String name = p.length > 2 ? p[0] + ":" + p[1] : p[0];
            int weight = 1;
            if (p.length >= 2) {
                try {
                    weight = Math.max(1, Math.min(50, Integer.parseInt(p[p.length - 1].trim())));
                } catch (NumberFormatException ex) {
                    name = s;
                }
            }
            Material m = Material.matchMaterial(name.trim());
            if (m == null || !m.isBlock() || m.hasGravity()) {
                if (log != null) log.warning("config portal.corruption.blocks: '" + s + "' is not a solid non-falling block, skipped");
                continue;
            }
            for (int i = 0; i < weight; i++) out.add(m);
        }
        if (out.isEmpty()) out.add(Material.BLACK_CONCRETE);
        return out;
    }
}
