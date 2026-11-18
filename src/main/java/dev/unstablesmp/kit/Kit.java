package dev.unstablesmp.kit;

import java.util.Arrays;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** A saved inventory: 36 storage slots, 4 armour slots (boots..helmet) and the offhand. */
public final class Kit {
    public final String name;
    public final ItemStack[] contents;
    public final ItemStack[] armor;
    public ItemStack offhand;

    public Kit(String name, ItemStack[] contents, ItemStack[] armor, ItemStack offhand) {
        this.name = name;
        this.contents = Arrays.copyOf(contents, 36);
        this.armor = Arrays.copyOf(armor, 4);
        this.offhand = offhand;
    }

    public static Kit capture(String name, Player p) {
        PlayerInventory inv = p.getInventory();
        return new Kit(name, clone(inv.getStorageContents()), clone(inv.getArmorContents()), inv.getItemInOffHand().clone());
    }

    private static ItemStack[] clone(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[i] == null ? null : in[i].clone();
        return out;
    }

    /** Replaces the player's inventory with this kit (and heals them so every round starts fair). */
    public void apply(Player p, boolean clearEffects) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setStorageContents(clone(contents));
        inv.setArmorContents(clone(armor));
        inv.setItemInOffHand(offhand == null ? null : offhand.clone());
        if (clearEffects) p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
    }

    public void save(ConfigurationSection c) {
        c.set("contents", Arrays.asList(contents));
        c.set("armor", Arrays.asList(armor));
        c.set("offhand", offhand);
    }

    public static Kit load(String name, ConfigurationSection c) {
        ItemStack[] contents = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        java.util.List<?> l = c.getList("contents");
        if (l != null) for (int i = 0; i < l.size() && i < 36; i++) if (l.get(i) instanceof ItemStack is) contents[i] = is;
        java.util.List<?> a = c.getList("armor");
        if (a != null) for (int i = 0; i < a.size() && i < 4; i++) if (a.get(i) instanceof ItemStack is) armor[i] = is;
        return new Kit(name, contents, armor, c.getItemStack("offhand"));
    }
}
