package dev.unstablesmp.faction;

import dev.unstablesmp.core.Text;
import java.util.EnumSet;
import java.util.Locale;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;

/** A unit type from config.yml -> templates. */
public final class UnitTemplate {
    public enum ArmorTier { NONE, LEATHER, CHAINMAIL, IRON, GOLDEN, DIAMOND, NETHERITE }

    public final String id;
    public String nameMini;
    public Component display;
    public EntityType entity = EntityType.ZOMBIE;
    public double health = 20;
    public double damage = 4;
    public double speed = 0.25;
    public double scale = 1.0;
    public Material weapon = Material.IRON_SWORD;
    public ArmorTier armor = ArmorTier.LEATHER;
    /** -1 = use the faction colour (leather only). */
    public int armorColor = -1;
    /** "" = armour helmet, "WITHER_SKULL", "SKELETON_SKULL", "ZOMBIE_HEAD", "CREEPER_HEAD" or a Minecraft username. */
    public String head = "";
    /** Null = no trail. */
    public Particle trail;
    public String summon = "";
    public int summonCount = 2;
    public boolean leader;
    public boolean flying;
    public EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);

    public UnitTemplate(String id) {
        this.id = id;
    }

    public boolean has(Trait t) {
        return traits.contains(t);
    }

    public boolean ranged() {
        return weapon == Material.BOW || weapon == Material.CROSSBOW;
    }

    public static UnitTemplate load(String id, ConfigurationSection c, Logger log) {
        UnitTemplate t = new UnitTemplate(id);
        t.nameMini = c.getString("name", id);
        t.display = Text.mm(t.nameMini);
        String ent = c.getString("entity", "ZOMBIE").toUpperCase(Locale.ROOT);
        try {
            EntityType type = EntityType.valueOf(ent);
            Class<?> cls = type.getEntityClass();
            if (cls == null || !Mob.class.isAssignableFrom(cls)) throw new IllegalArgumentException();
            t.entity = type;
        } catch (IllegalArgumentException ex) {
            log.warning("Template " + id + ": entity '" + ent + "' is not a mob, using ZOMBIE");
        }
        t.health = Math.max(1, c.getDouble("health", 20));
        t.damage = Math.max(0, c.getDouble("damage", 4));
        t.speed = Math.max(0.05, c.getDouble("speed", 0.25));
        t.scale = Math.max(0.2, Math.min(4.0, c.getDouble("scale", 1.0)));
        String weapon = c.getString("weapon", "IRON_SWORD");
        if (weapon.isBlank() || weapon.equalsIgnoreCase("NONE")) t.weapon = Material.AIR;
        else t.weapon = Faction.material(weapon, Material.IRON_SWORD, false, log);
        try {
            String tier = c.getString("armor", "LEATHER").trim().toUpperCase(Locale.ROOT);
            t.armor = ArmorTier.valueOf(tier.equals("GOLD") ? "GOLDEN" : tier);
        } catch (IllegalArgumentException ex) {
            log.warning("Template " + id + ": unknown armor tier, using LEATHER");
        }
        String ac = c.getString("armor-color", "");
        t.armorColor = ac.isBlank() ? -1 : Faction.parseRgb(ac);
        t.head = c.getString("head", "").trim();
        String trail = c.getString("trail", "");
        if (!trail.isBlank()) {
            try {
                Particle p = Particle.valueOf(trail.trim().toUpperCase(Locale.ROOT));
                t.trail = p.getDataType() == Void.class || p == Particle.DUST || p == Particle.DRAGON_BREATH ? p : null;
                if (t.trail == null) log.warning("Template " + id + ": particle " + trail + " needs data, ignoring");
            } catch (IllegalArgumentException ex) {
                log.warning("Template " + id + ": unknown particle " + trail);
            }
        }
        t.summon = c.getString("summon", "");
        t.summonCount = Math.max(1, c.getInt("summon-count", 2));
        t.leader = c.getBoolean("leader", false);
        t.traits = Trait.parse(c.getStringList("traits"), "template " + id, log);
        if (t.leader) t.traits.add(Trait.LEADER);
        t.flying = c.getBoolean("flying", t.entity == EntityType.VEX);
        return t;
    }
}
