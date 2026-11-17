package dev.nullportals;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** A unit type from config.yml -> templates. */
final class UnitTemplate {
    String id;
    String display;
    String entity;      // ZOMBIE, HUSK, SKELETON, STRAY, WITHER_SKELETON, VEX, PIGLIN_BRUTE
    double health;
    double damage;
    double speed;
    double scale;
    String weapon;
    String armor;       // NONE, LEATHER, CHAINMAIL, IRON, GOLDEN, DIAMOND, NETHERITE
    String armorColor;  // optional hex, overrides faction colour for leather
    String head;        // "" = armor helmet, "WITHER_SKULL", "SKELETON_SKULL", or a Minecraft username for a player-skin head
    String trail;       // particle name or ""
    String summon;      // template id summoned by SUMMON trait
    int summonCount;
    boolean leader;
    final Set<String> traits = new HashSet<>();

    boolean has(String trait) {
        return traits.contains(trait);
    }

    boolean flying() {
        return "VEX".equals(entity);
    }

    boolean ranged() {
        return "SKELETON".equals(entity) || "STRAY".equals(entity);
    }

    static UnitTemplate load(String id, ConfigurationSection c) {
        UnitTemplate t = new UnitTemplate();
        t.id = id;
        t.display = c.getString("name", id);
        t.entity = c.getString("entity", "ZOMBIE").toUpperCase(Locale.ROOT);
        t.health = c.getDouble("health", 20);
        t.damage = c.getDouble("damage", 4);
        t.speed = c.getDouble("speed", 0.25);
        t.scale = c.getDouble("scale", 1.0);
        t.weapon = c.getString("weapon", "IRON_SWORD");
        t.armor = c.getString("armor", "LEATHER").toUpperCase(Locale.ROOT);
        t.armorColor = c.getString("armor-color", "");
        t.head = c.getString("head", "");
        t.trail = c.getString("trail", "");
        t.summon = c.getString("summon", "");
        t.summonCount = c.getInt("summon-count", 2);
        t.leader = c.getBoolean("leader", false);
        for (String s : c.getStringList("traits")) t.traits.add(s.toUpperCase(Locale.ROOT));
        if (t.leader) t.traits.add("LEADER");
        return t;
    }
}
