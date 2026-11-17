package dev.unstablesmp.faction;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/** Unit behaviours, set per template in config.yml. */
public enum Trait {
    /** Jump crits for extra damage. */
    CRIT,
    /** Circle-strafe while in melee range. */
    STRAFE,
    /** Gap-closing leap. */
    LEAP,
    /** Fall back and heal at low HP, then re-engage. */
    RETREAT,
    /** Archers back off from close targets. */
    KITE,
    /** Teleport behind the target. */
    BLINK,
    /** Flyers circle the target, then dive. */
    ORBIT,
    /** Briefly turn invisible in fights. */
    FLICKER,
    /** Summon extra units (template's summon / summon-count). */
    SUMMON,
    /** Strength + speed under the rage threshold (bosses). */
    RAGE,
    /** Heal from melee damage dealt. */
    LIFESTEAL,
    /** Pillar up, bridge gaps, tower over walls, fortify. */
    BUILDER,
    /** Break through walls, floors and ceilings. */
    BREAKER,
    /** On-hit: Darkness. */
    DARKNESS,
    /** On-hit: Blindness. */
    BLIND,
    /** On-hit: Slowness. */
    SLOW,
    /** On-hit: Nausea. */
    NAUSEA,
    /** On-hit: set on fire. Also makes the unit fire resistant. */
    FIRE,
    /** On-hit: random launch. */
    CHAOS,
    /** Permanently invisible body; held weapons still show (floating weapons). */
    INVISIBLE,
    /** Laughs during fights (clowns). */
    LAUGH,
    /** Arrows are set on fire. */
    FIRE_ARROWS,
    /** Leader: boss bar, front of formation, knockback resistance. */
    LEADER;

    /** Parses a trait list; accepts the reference code's "X_ON_HIT" spellings. */
    public static EnumSet<Trait> parse(List<String> names, String owner, Logger log) {
        EnumSet<Trait> set = EnumSet.noneOf(Trait.class);
        for (String raw : names) {
            String n = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (n.endsWith("_ON_HIT")) n = n.substring(0, n.length() - "_ON_HIT".length());
            if (n.equals("BLINDNESS")) n = "BLIND";
            if (n.equals("SLOWNESS")) n = "SLOW";
            try {
                set.add(Trait.valueOf(n));
            } catch (IllegalArgumentException ex) {
                if (log != null) log.warning("Unknown trait '" + raw + "' in " + owner);
            }
        }
        return set;
    }
}
