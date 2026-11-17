package dev.unstablesmp.ability;

import java.util.Locale;
import org.bukkit.Material;

/** Every ability item. The id is stored in the item's PersistentDataContainer and used in config and commands. */
public enum AbilityType {
    SUMMON_SWARM("summon_swarm", "vexbots", Material.ECHO_SHARD),
    CYCLE_FORMATION("cycle_formation", "vexbots", Material.AMETHYST_SHARD),
    DIVE_STRIKE("dive_strike", "vexbots", Material.PRISMARINE_SHARD),
    RECALL("recall", "vexbots", Material.RECOVERY_COMPASS),
    NULL_STEP("null_step", "null", Material.PHANTOM_MEMBRANE),
    NULL_FIELD("null_field", "null", Material.INK_SAC),
    NULL_BRAND("null_brand", "null", Material.NETHERITE_SCRAP),
    PORTAL_SWARM("portal_swarm", "portal", Material.POPPED_CHORUS_FRUIT),
    JUDGMENT("judgment", "law", Material.GOLD_INGOT),
    SENTENCE("sentence", "law", Material.IRON_CHAIN),
    ORDER("order", "law", Material.GOLD_NUGGET),
    EMBER_CREST("ember_crest", "cindercrest", Material.BLAZE_POWDER),
    ASHFALL("ashfall", "cindercrest", Material.MAGMA_CREAM),
    PHOENIX_BURST("phoenix_burst", "cindercrest", Material.BLAZE_ROD);

    public static final String[] SETS = {"vexbots", "null", "portal", "law", "cindercrest"};

    public final String id;
    public final String set;
    public final Material defaultMaterial;

    AbilityType(String id, String set, Material defaultMaterial) {
        this.id = id;
        this.set = set;
        this.defaultMaterial = defaultMaterial;
    }

    public String permission() {
        return "uss.ability." + set;
    }

    public static AbilityType byId(String id) {
        if (id == null) return null;
        String n = id.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (AbilityType t : values()) if (t.id.equals(n)) return t;
        return null;
    }

    public static boolean isSet(String s) {
        for (String set : SETS) if (set.equalsIgnoreCase(s)) return true;
        return false;
    }
}
