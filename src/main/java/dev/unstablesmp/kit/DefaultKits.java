package dev.unstablesmp.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionType;

/**
 * Built-in kits, modelled on the usual tier-test kits (sword, axe, mace, elytra-mace, UHC, netherite pot, spear)
 * plus faction kits (null, law, bandit). Written to kits.yml on first start so they can be edited in game.
 */
public final class DefaultKits {
    private DefaultKits() {
    }

    public static Map<String, Kit> all() {
        Map<String, Kit> m = new LinkedHashMap<>();
        m.put("sword", sword());
        m.put("axe", axe());
        m.put("mace", mace());
        m.put("elytra_mace", elytraMace());
        m.put("uhc", uhc());
        m.put("nethpot", nethpot());
        m.put("spear", spear());
        m.put("null", nullKit());
        m.put("law", law());
        m.put("bandit", bandit());
        return m;
    }

    // ------------------------------------------------------------------ builders

    static ItemStack item(Material m) {
        return new ItemStack(m);
    }

    static ItemStack item(Material m, int amount) {
        return new ItemStack(m, amount);
    }

    static ItemStack ench(Material m, Object... enchLevels) {
        ItemStack is = new ItemStack(m);
        ItemMeta meta = is.getItemMeta();
        for (int i = 0; i + 1 < enchLevels.length; i += 2) {
            meta.addEnchant((Enchantment) enchLevels[i], (Integer) enchLevels[i + 1], true);
        }
        is.setItemMeta(meta);
        return is;
    }

    static ItemStack potion(Material type, PotionType pt, int amount) {
        ItemStack is = new ItemStack(type, amount);
        if (is.getItemMeta() instanceof PotionMeta pm) {
            pm.setBasePotionType(pt);
            is.setItemMeta(pm);
        }
        return is;
    }

    static ItemStack rockets(int amount) {
        ItemStack is = new ItemStack(Material.FIREWORK_ROCKET, amount);
        if (is.getItemMeta() instanceof FireworkMeta fm) {
            fm.setPower(3);
            is.setItemMeta(fm);
        }
        return is;
    }

    static ItemStack[] armorSet(String tier, int prot) {
        Material boots = Material.matchMaterial(tier + "_BOOTS"), legs = Material.matchMaterial(tier + "_LEGGINGS");
        Material chest = Material.matchMaterial(tier + "_CHESTPLATE"), helm = Material.matchMaterial(tier + "_HELMET");
        return new ItemStack[] {
            ench(boots, Enchantment.PROTECTION, prot, Enchantment.UNBREAKING, 3, Enchantment.FEATHER_FALLING, 4),
            ench(legs, Enchantment.PROTECTION, prot, Enchantment.UNBREAKING, 3),
            ench(chest, Enchantment.PROTECTION, prot, Enchantment.UNBREAKING, 3),
            ench(helm, Enchantment.PROTECTION, prot, Enchantment.UNBREAKING, 3)
        };
    }

    static ItemStack trim(ItemStack is, TrimMaterial mat, TrimPattern pattern) {
        if (is != null && is.getItemMeta() instanceof ArmorMeta am) {
            am.setTrim(new ArmorTrim(mat, pattern));
            is.setItemMeta(am);
        }
        return is;
    }

    private static Kit kit(String name, ItemStack[] armor, ItemStack offhand, ItemStack... hotbarThenRest) {
        ItemStack[] contents = new ItemStack[36];
        List<ItemStack> list = new ArrayList<>(List.of(hotbarThenRest));
        for (int i = 0; i < list.size() && i < 36; i++) contents[i] = list.get(i);
        return new Kit(name, contents, armor, offhand);
    }

    // ------------------------------------------------------------------ kits

    static Kit sword() {
        return kit("sword", armorSet("DIAMOND", 2), null,
                ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 2), item(Material.GOLDEN_APPLE, 4), item(Material.COOKED_BEEF, 32));
    }

    static Kit axe() {
        return kit("axe", armorSet("DIAMOND", 2), item(Material.SHIELD),
                ench(Material.DIAMOND_AXE, Enchantment.SHARPNESS, 1), ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 1),
                item(Material.CROSSBOW), item(Material.GOLDEN_APPLE, 4), item(Material.ARROW, 16));
    }

    static Kit mace() {
        return kit("mace", armorSet("NETHERITE", 4), item(Material.SHIELD),
                ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5),
                ench(Material.MACE, Enchantment.DENSITY, 5, Enchantment.WIND_BURST, 1),
                item(Material.WIND_CHARGE, 64), item(Material.ENDER_PEARL, 16), item(Material.GOLDEN_APPLE, 16),
                ench(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 5), item(Material.TOTEM_OF_UNDYING), item(Material.COBWEB, 16));
    }

    static Kit elytraMace() {
        ItemStack[] armor = armorSet("NETHERITE", 4);
        armor[2] = ench(Material.ELYTRA, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1);
        return kit("elytra_mace", armor, item(Material.TOTEM_OF_UNDYING),
                ench(Material.MACE, Enchantment.DENSITY, 5, Enchantment.WIND_BURST, 1), rockets(64),
                ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5), item(Material.WIND_CHARGE, 32),
                item(Material.ENDER_PEARL, 16), item(Material.GOLDEN_APPLE, 16), item(Material.TOTEM_OF_UNDYING, 3));
    }

    static Kit uhc() {
        ItemStack[] armor = armorSet("DIAMOND", 2);
        armor[1] = ench(Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 3);
        return kit("uhc", armor, item(Material.SHIELD),
                ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 3), ench(Material.DIAMOND_AXE, Enchantment.SHARPNESS, 1),
                ench(Material.BOW, Enchantment.POWER, 3), item(Material.FISHING_ROD), item(Material.LAVA_BUCKET),
                item(Material.WATER_BUCKET), item(Material.COBWEB, 8), item(Material.GOLDEN_APPLE, 6),
                item(Material.COBBLESTONE, 64), item(Material.ARROW, 32), item(Material.LAVA_BUCKET));
    }

    static Kit nethpot() {
        ItemStack[] contents = new ItemStack[36];
        contents[0] = ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2);
        contents[1] = item(Material.ENDER_PEARL, 16);
        contents[2] = item(Material.GOLDEN_APPLE, 32);
        contents[3] = potion(Material.SPLASH_POTION, PotionType.STRONG_SWIFTNESS, 1);
        contents[4] = potion(Material.SPLASH_POTION, PotionType.STRONG_STRENGTH, 1);
        contents[5] = item(Material.TOTEM_OF_UNDYING);
        contents[6] = item(Material.EXPERIENCE_BOTTLE, 64);
        for (int i = 7; i < 36; i++) contents[i] = potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING, 1);
        return new Kit("nethpot", contents, armorSet("NETHERITE", 4), item(Material.TOTEM_OF_UNDYING));
    }

    static Kit spear() {
        return kit("spear", armorSet("DIAMOND", 3), item(Material.SHIELD),
                ench(Material.NETHERITE_SPEAR, Enchantment.SHARPNESS, 3), ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 2),
                item(Material.GOLDEN_APPLE, 8), item(Material.WIND_CHARGE, 16), item(Material.ENDER_PEARL, 4));
    }

    /** NULL: black-trimmed netherite (no helmet so the black face shows), sword, axe, pearls, totems. */
    static Kit nullKit() {
        ItemStack[] armor = armorSet("NETHERITE", 4);
        for (int i = 0; i < 3; i++) trim(armor[i], TrimMaterial.NETHERITE, TrimPattern.SILENCE);
        armor[3] = null;
        return kit("null", armor, item(Material.SHIELD),
                ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5), ench(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 5),
                item(Material.ENDER_PEARL, 16), item(Material.GOLDEN_APPLE, 16), item(Material.TOTEM_OF_UNDYING, 2),
                potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING, 8), item(Material.WIND_CHARGE, 16));
    }

    /** LAW: gold-and-white enforcers. */
    static Kit law() {
        ItemStack[] armor = armorSet("IRON", 2);
        for (ItemStack is : armor) trim(is, TrimMaterial.GOLD, TrimPattern.SENTRY);
        armor[3] = ench(Material.GOLDEN_HELMET, Enchantment.PROTECTION, 2, Enchantment.UNBREAKING, 3);
        return kit("law", armor, item(Material.SHIELD),
                ench(Material.IRON_SWORD, Enchantment.SHARPNESS, 2), item(Material.IRON_AXE), item(Material.GOLDEN_APPLE, 3),
                item(Material.CROSSBOW), item(Material.ARROW, 16));
    }

    /** Bandits: mismatched leather and iron, whatever they could steal. */
    static Kit bandit() {
        ItemStack[] armor = armorSet("IRON", 1);
        ItemStack leather = item(Material.LEATHER_CHESTPLATE);
        if (leather.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(Color.fromRGB(0x5A3A22));
            leather.setItemMeta(lm);
        }
        armor[2] = leather;
        return kit("bandit", armor, null, item(Material.STONE_SWORD), item(Material.IRON_AXE), item(Material.BOW),
                item(Material.ARROW, 16), item(Material.GOLDEN_APPLE, 2), item(Material.COBWEB, 4));
    }
}
