package dev.unstablesmp.gui;

import dev.unstablesmp.core.Text;
import dev.unstablesmp.faction.Faction;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/** GUI icon builders (Adventure names and lore, no italics). */
public final class Icons {
    private Icons() {
    }

    public static ItemStack icon(Material m, String name, String... lore) {
        return icon(m, Text.item(name), lore);
    }

    public static ItemStack icon(Material m, Component name, String... lore) {
        ItemStack is = new ItemStack(m);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(Text.item(name));
        List<Component> l = new ArrayList<>(lore.length);
        for (String s : lore) l.add(Text.item("<gray>" + s));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        is.setItemMeta(meta);
        return is;
    }

    public static ItemStack glint(ItemStack is, boolean on) {
        ItemMeta meta = is.getItemMeta();
        meta.setEnchantmentGlintOverride(on ? Boolean.TRUE : null);
        is.setItemMeta(meta);
        return is;
    }

    public static String onOff(boolean b) {
        return b ? "<green>ON" : "<red>OFF";
    }

    public static ItemStack faction(Faction f, boolean selected, String... lore) {
        ItemStack is = icon(f.icon, f.display, lore);
        if (is.getItemMeta() instanceof LeatherArmorMeta lam) {
            lam.setColor(f.color);
            is.setItemMeta(lam);
        }
        return glint(is, selected);
    }
}
