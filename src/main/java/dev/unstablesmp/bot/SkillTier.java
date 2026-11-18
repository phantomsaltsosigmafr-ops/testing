package dev.unstablesmp.bot;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.configuration.ConfigurationSection;

/**
 * PvP skill tiers, MCTiers style, extended down to LT6: HT1 (best) .. LT6 (can barely hold a sword).
 * Every stat is interpolated from a skill value (HT1 = 1.0, LT6 = 0.0) and can be overridden per tier in
 * config.yml -> bots.tiers.&lt;TIER&gt;.&lt;stat&gt;.
 */
public enum SkillTier {
    HT1, LT1, HT2, LT2, HT3, LT3, HT4, LT4, HT5, LT5, HT6, LT6;

    /** Mutable per-tier stats (filled by {@link #load}). */
    public static final class Stats {
        /** Ticks between decisions (reaction time). */
        public int reaction;
        /** Aim error in degrees. */
        public double aimError;
        /** Minimum attack-cooldown charge (0..1) before swinging. Low tiers spam weak hits. */
        public double attackCharge;
        /** Chance to whiff a swing that should have hit. */
        public double miss;
        public double critRate;
        public double wtapRate;
        public double strafe;
        public double jumpReset;
        public double block;
        public double reach;
        /** Heal when health fraction drops below this. */
        public double healAt;
        /** Extra ticks before reacting with a heal. */
        public int healDelay;
        public double pearl;
        /** Chance to use utility (rod, webs, lava, bow, wind charges) when it makes sense. */
        public double utility;
        /** Fraction of time spent sprinting while chasing. */
        public double sprint;
        /** Chance to keep pressure/combo instead of backing off. */
        public double combo;
        /** Ticks to re-equip a totem after a pop. */
        public int retotem;
    }

    private final Stats stats = new Stats();

    public Stats stats() {
        return stats;
    }

    /** 1.0 for HT1 down to 0.0 for LT6. */
    public double skill() {
        return 1.0 - ordinal() / (double) (values().length - 1);
    }

    public boolean high() {
        return name().startsWith("HT");
    }

    static {
        for (SkillTier t : values()) t.defaults();
    }

    private static double lerp(double best, double worst, double s) {
        return worst + (best - worst) * s;
    }

    private void defaults() {
        double s = skill();
        stats.reaction = (int) Math.round(lerp(1, 10, s));
        stats.aimError = lerp(1.5, 28, s);
        stats.attackCharge = lerp(0.95, 0.3, s);
        stats.miss = lerp(0.02, 0.38, s);
        stats.critRate = lerp(0.9, 0.0, s);
        stats.wtapRate = lerp(0.9, 0.0, s);
        stats.strafe = lerp(0.95, 0.05, s);
        stats.jumpReset = lerp(0.8, 0.0, s);
        stats.block = lerp(0.9, 0.05, s);
        stats.reach = lerp(3.0, 2.45, s);
        stats.healAt = lerp(0.45, 0.15, s);
        stats.healDelay = (int) Math.round(lerp(1, 30, s));
        stats.pearl = lerp(0.8, 0.0, s);
        stats.utility = lerp(0.85, 0.05, s);
        stats.sprint = lerp(1.0, 0.55, s);
        stats.combo = lerp(0.95, 0.3, s);
        stats.retotem = (int) Math.round(lerp(3, 40, s));
    }

    /** Applies config overrides on top of the interpolated defaults. */
    public static void load(ConfigurationSection tiers) {
        for (SkillTier t : values()) {
            t.defaults();
            if (tiers == null) continue;
            ConfigurationSection c = tiers.getConfigurationSection(t.name());
            if (c == null) continue;
            Stats s = t.stats;
            s.reaction = Math.max(1, c.getInt("reaction-ticks", s.reaction));
            s.aimError = c.getDouble("aim-error", s.aimError);
            s.attackCharge = c.getDouble("attack-charge", s.attackCharge);
            s.miss = c.getDouble("miss-chance", s.miss);
            s.critRate = c.getDouble("crit-rate", s.critRate);
            s.wtapRate = c.getDouble("wtap-rate", s.wtapRate);
            s.strafe = c.getDouble("strafe", s.strafe);
            s.jumpReset = c.getDouble("jump-reset", s.jumpReset);
            s.block = c.getDouble("block", s.block);
            s.reach = c.getDouble("reach", s.reach);
            s.healAt = c.getDouble("heal-at", s.healAt);
            s.healDelay = Math.max(0, c.getInt("heal-delay-ticks", s.healDelay));
            s.pearl = c.getDouble("pearl", s.pearl);
            s.utility = c.getDouble("utility", s.utility);
            s.sprint = c.getDouble("sprint", s.sprint);
            s.combo = c.getDouble("combo", s.combo);
            s.retotem = Math.max(0, c.getInt("retotem-ticks", s.retotem));
        }
    }

    public static SkillTier parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Parses "HT1", "HT1-LT3" (inclusive range, picks one at random) or "random". */
    public static SkillTier pick(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("random")) {
            return values()[ThreadLocalRandom.current().nextInt(values().length)];
        }
        String[] parts = spec.split("-");
        SkillTier a = parse(parts[0]);
        SkillTier b = parts.length > 1 ? parse(parts[1]) : a;
        if (a == null) a = HT3;
        if (b == null) b = a;
        int lo = Math.min(a.ordinal(), b.ordinal()), hi = Math.max(a.ordinal(), b.ordinal());
        return values()[lo + ThreadLocalRandom.current().nextInt(hi - lo + 1)];
    }
}
