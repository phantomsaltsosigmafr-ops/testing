package dev.unstablesmp.faction;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.EntityTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Vex;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Creates and equips faction units: attributes, names, armour, skin heads, caps. */
final class UnitSpawner {
    private final UnstableSMP plugin;
    private final HeadProfiles heads;

    UnitSpawner(UnstableSMP plugin, HeadProfiles heads) {
        this.plugin = plugin;
        this.heads = heads;
    }

    Mob spawn(Faction f, UnitTemplate t, Location at) {
        World w = at.getWorld();
        if (w == null) return null;
        Class<? extends Entity> cls = t.entity.getEntityClass();
        if (cls == null || !Mob.class.isAssignableFrom(cls)) return null;
        Class<? extends Mob> mobClass = cls.asSubclass(Mob.class);
        Mob m = w.spawn(at, mobClass, false, x -> configure(x, f, t));
        // Flyers are fully plugin-driven (halo, orbit, dive, melee): vanilla vex goals would fight our steering.
        if (t.flying) Bukkit.getMobGoals().removeAllGoals(m);
        w.spawnParticle(Particle.LARGE_SMOKE, at, 12, 0.3, 0.8, 0.3, 0.02);
        if (t.leader) {
            w.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.7f);
            w.spawnParticle(Particle.REVERSE_PORTAL, at, 80, 0.5, 1.5, 0.5, 0.3);
        }
        return m;
    }

    Component name(Faction f, UnitTemplate t) {
        Component n = t.display.colorIfAbsent(f.textColor);
        return t.leader ? n.decorate(TextDecoration.BOLD) : n;
    }

    private void configure(Mob m, Faction f, UnitTemplate t) {
        plugin.tracker().track(m, null, EntityTracker.Kind.MOB);
        m.customName(name(f, t));
        m.setCustomNameVisible(f.showNames || t.leader);
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        attr(m, Attribute.MAX_HEALTH, t.health);
        attr(m, Attribute.ATTACK_DAMAGE, t.damage);
        if (!t.flying) attr(m, Attribute.MOVEMENT_SPEED, t.speed);
        attr(m, Attribute.FOLLOW_RANGE, 48);
        if (Math.abs(t.scale - 1.0) > 0.01) attr(m, Attribute.SCALE, t.scale);
        if (t.leader) attr(m, Attribute.KNOCKBACK_RESISTANCE, 0.6);
        m.setHealth(t.health);
        equip(m, f, t);
        if (t.has(Trait.INVISIBLE)) m.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
        if (t.has(Trait.FIRE) || t.has(Trait.FIRE_ARROWS)) {
            m.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        }
        if (m instanceof Vex v) v.setLimitedLifetime(false);
        if (f.glow) m.setGlowing(true);
    }

    static void attr(Mob m, Attribute a, double v) {
        AttributeInstance ai = m.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    private void equip(Mob m, Faction f, UnitTemplate t) {
        EntityEquipment eq = m.getEquipment();
        Color col = t.armorColor >= 0 ? Color.fromRGB(t.armorColor) : f.color;
        boolean armor = t.armor != UnitTemplate.ArmorTier.NONE;
        if (armor) {
            eq.setChestplate(armorPiece(t.armor, "CHESTPLATE", col));
            eq.setLeggings(armorPiece(t.armor, "LEGGINGS", col));
            eq.setBoots(armorPiece(t.armor, "BOOTS", col));
        }
        eq.setHelmet(helmet(f, t));
        eq.setItemInMainHand(t.weapon == Material.AIR ? null : new ItemStack(t.weapon));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) continue;
            try {
                eq.setDropChance(slot, 0f);
            } catch (IllegalArgumentException ignored) {
                // slot not supported by this mob
            }
        }
    }

    ItemStack helmet(Faction f, UnitTemplate t) {
        String head = t.head;
        if (head.equalsIgnoreCase("WITHER_SKULL")) return new ItemStack(Material.WITHER_SKELETON_SKULL);
        if (head.equalsIgnoreCase("SKELETON_SKULL")) return new ItemStack(Material.SKELETON_SKULL);
        if (head.equalsIgnoreCase("ZOMBIE_HEAD")) return new ItemStack(Material.ZOMBIE_HEAD);
        if (head.equalsIgnoreCase("CREEPER_HEAD")) return new ItemStack(Material.CREEPER_HEAD);
        if (head.equalsIgnoreCase("CARVED_PUMPKIN")) return new ItemStack(Material.CARVED_PUMPKIN);
        if (!head.isEmpty()) return heads.head(head);
        if (t.armor == UnitTemplate.ArmorTier.NONE) return null;
        return armorPiece(t.armor, "HELMET", t.armorColor >= 0 ? Color.fromRGB(t.armorColor) : f.color);
    }

    private static ItemStack armorPiece(UnitTemplate.ArmorTier tier, String slot, Color c) {
        Material mat = Material.matchMaterial(tier.name() + "_" + slot);
        if (mat == null) return null;
        ItemStack is = new ItemStack(mat);
        if (tier == UnitTemplate.ArmorTier.LEATHER && is.getItemMeta() instanceof LeatherArmorMeta lam) {
            lam.setColor(c);
            is.setItemMeta(lam);
        }
        return is;
    }
}
