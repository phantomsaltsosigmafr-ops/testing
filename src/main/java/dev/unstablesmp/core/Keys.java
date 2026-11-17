package dev.unstablesmp.core;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Every NamespacedKey and scoreboard tag the plugin uses, created once on enable. */
public final class Keys {
    /** Scoreboard tag put on every entity this plugin spawns (displays, hitboxes, mobs). */
    public static final String ENTITY_TAG = "uss_entity";

    public static NamespacedKey ABILITY;
    public static NamespacedKey HORN;
    public static NamespacedKey KIND;
    public static NamespacedKey OWNER;
    public static NamespacedKey ROOT_SPEED;
    public static NamespacedKey ROOT_JUMP;

    private Keys() {
    }

    public static void init(Plugin plugin) {
        ABILITY = new NamespacedKey(plugin, "ability");
        HORN = new NamespacedKey(plugin, "faction_horn");
        KIND = new NamespacedKey(plugin, "kind");
        OWNER = new NamespacedKey(plugin, "owner");
        ROOT_SPEED = new NamespacedKey(plugin, "sentence_root_speed");
        ROOT_JUMP = new NamespacedKey(plugin, "sentence_root_jump");
    }
}
