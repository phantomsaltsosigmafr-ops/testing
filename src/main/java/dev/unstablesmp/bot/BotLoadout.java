package dev.unstablesmp.bot;

import dev.unstablesmp.kit.Kit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * What a bot is carrying: worn armour, weapons it can swap between, and consumable counts. Built from a {@link Kit}
 * so players and bots share the exact same kits.
 */
public final class BotLoadout {
    public ItemStack helmet, chest, legs, boots;
    public ItemStack sword, axe, mace, spear, bow, crossbow;
    public boolean shield, rod, elytra;
    public int gapples, healPots, speedPots, strengthPots, totems, pearls, windCharges, cobwebs, lava, rockets, arrows, blocks, xp;
    public boolean offhandTotem;

    public static BotLoadout fromKit(Kit kit) {
        BotLoadout l = new BotLoadout();
        if (kit == null) return l;
        l.boots = clone(kit.armor[0]);
        l.legs = clone(kit.armor[1]);
        l.chest = clone(kit.armor[2]);
        l.helmet = clone(kit.armor[3]);
        if (l.chest != null && l.chest.getType() == Material.ELYTRA) l.elytra = true;
        scan(l, kit.offhand);
        if (kit.offhand != null && kit.offhand.getType() == Material.TOTEM_OF_UNDYING) l.offhandTotem = true;
        for (ItemStack is : kit.contents) scan(l, is);
        return l;
    }

    private static ItemStack clone(ItemStack is) {
        return is == null || is.getType().isAir() ? null : is.clone();
    }

    private static void scan(BotLoadout l, ItemStack is) {
        if (is == null || is.getType().isAir()) return;
        Material m = is.getType();
        String n = m.name();
        int a = is.getAmount();
        if (n.endsWith("_SWORD")) l.sword = better(l.sword, is);
        else if (n.endsWith("_AXE")) l.axe = better(l.axe, is);
        else if (n.endsWith("_SPEAR")) l.spear = better(l.spear, is);
        else if (m == Material.MACE) l.mace = is.clone();
        else if (m == Material.BOW) l.bow = is.clone();
        else if (m == Material.CROSSBOW) l.crossbow = is.clone();
        else if (m == Material.SHIELD) l.shield = true;
        else if (m == Material.FISHING_ROD) l.rod = true;
        else if (m == Material.ELYTRA) l.elytra = true;
        else if (m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE) l.gapples += a;
        else if (m == Material.TOTEM_OF_UNDYING) l.totems += a;
        else if (m == Material.ENDER_PEARL) l.pearls += a;
        else if (m == Material.WIND_CHARGE) l.windCharges += a;
        else if (m == Material.COBWEB) l.cobwebs += a;
        else if (m == Material.LAVA_BUCKET) l.lava += a;
        else if (m == Material.FIREWORK_ROCKET) l.rockets += a;
        else if (m == Material.ARROW || m == Material.SPECTRAL_ARROW || m == Material.TIPPED_ARROW) l.arrows += a;
        else if (m == Material.EXPERIENCE_BOTTLE) l.xp += a;
        else if (m.isBlock() && m.isSolid()) l.blocks += a;
        else if ((m == Material.SPLASH_POTION || m == Material.POTION || m == Material.LINGERING_POTION) && is.getItemMeta() instanceof PotionMeta pm) {
            PotionType t = pm.getBasePotionType();
            if (t == null) return;
            String tn = t.name();
            if (tn.contains("HEALING")) l.healPots += a;
            else if (tn.contains("SWIFTNESS")) l.speedPots += a;
            else if (tn.contains("STRENGTH")) l.strengthPots += a;
        }
    }

    private static ItemStack better(ItemStack current, ItemStack candidate) {
        if (current == null) return candidate.clone();
        return tierRank(candidate.getType()) > tierRank(current.getType()) ? candidate.clone() : current;
    }

    static int tierRank(Material m) {
        String n = m.name();
        if (n.startsWith("NETHERITE")) return 6;
        if (n.startsWith("DIAMOND")) return 5;
        if (n.startsWith("IRON")) return 4;
        if (n.startsWith("COPPER")) return 3;
        if (n.startsWith("STONE")) return 2;
        if (n.startsWith("GOLDEN")) return 1;
        return 0;
    }

    /** The weapon a bot of this mode fights with on the ground. */
    public ItemStack primary(PvpMode mode) {
        return switch (mode) {
            case AXE -> axe != null ? axe : sword;
            case SPEAR -> spear != null ? spear : sword;
            case MACE, ELYTRA_MACE -> sword != null ? sword : axe != null ? axe : mace;
            default -> sword != null ? sword : axe != null ? axe : spear != null ? spear : mace;
        };
    }

    // ------------------------------------------------------------------ weapon stats

    /** Attack damage of a melee item (vanilla values, including Sharpness). */
    public static double damage(ItemStack is) {
        if (is == null) return 1;
        Material m = is.getType();
        String n = m.name();
        double base;
        if (n.endsWith("_SWORD")) base = switch (tierRank(m)) {
            case 6 -> 8;
            case 5 -> 7;
            case 4 -> 6;
            case 3 -> 5.5;
            case 2 -> 5;
            default -> 4;
        };
        else if (n.endsWith("_AXE")) base = switch (tierRank(m)) {
            case 6 -> 10;
            case 5 -> 9;
            case 4, 3, 2 -> 9;
            default -> 7;
        };
        else if (n.endsWith("_SPEAR")) base = switch (tierRank(m)) {
            case 6 -> 6;
            case 5 -> 5;
            case 4 -> 4;
            case 3 -> 3.5;
            case 2 -> 3;
            default -> 2;
        };
        else if (m == Material.MACE) base = 6;
        else if (m == Material.TRIDENT) base = 9;
        else base = 1;
        int sharp = is.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharp > 0) base += 0.5 * sharp + 0.5;
        return base;
    }

    /** Ticks to fully charge an attack with this item (20 / attack speed). */
    public static int cooldownTicks(ItemStack is) {
        if (is == null) return 5;
        Material m = is.getType();
        String n = m.name();
        if (n.endsWith("_SWORD")) return 13;
        if (n.endsWith("_AXE")) return tierRank(m) >= 5 ? 20 : 25;
        if (n.endsWith("_SPEAR")) return 16;
        if (m == Material.MACE) return 34;
        if (m == Material.TRIDENT) return 18;
        return 5;
    }
}
