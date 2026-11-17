package dev.unstablesmp.ability;

import dev.unstablesmp.core.Keys;
import dev.unstablesmp.core.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Builds ability items and identifies them by PersistentDataContainer (never by name). Each item can use a
 * resource-pack item model ({@code item-model}) and/or custom-model-data floats.
 */
public final class AbilityItems {
    private ConfigurationSection root;

    public void reload(ConfigurationSection abilities) {
        this.root = abilities;
    }

    public ConfigurationSection cfg(AbilityType t) {
        ConfigurationSection c = root == null ? null : root.getConfigurationSection(t.id);
        return c != null ? c : new org.bukkit.configuration.MemoryConfiguration();
    }

    public ItemStack create(AbilityType t) {
        ConfigurationSection c = cfg(t);
        Material mat = Material.matchMaterial(c.getString("material", t.defaultMaterial.name()));
        if (mat == null || !mat.isItem()) mat = t.defaultMaterial;
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(Text.item(c.getString("name", "<light_purple>" + Text.pretty(t.id))));
        List<Component> lore = new ArrayList<>();
        for (String line : c.getStringList("lore")) lore.add(Text.item(line));
        lore.add(Component.empty());
        String rc = c.getString("right-click", "");
        String src = c.getString("sneak-right-click", "");
        if (!rc.isEmpty()) lore.add(Text.item("<yellow>Right-click</yellow> <gray>» " + rc));
        if (!src.isEmpty()) lore.add(Text.item("<yellow>Sneak + Right-click</yellow> <gray>» " + src));
        double cd = c.getDouble("cooldown-seconds", 0), scd = c.getDouble("sneak-cooldown-seconds", cd);
        if (cd > 0 || scd > 0) {
            lore.add(Text.item(String.format(Locale.ROOT, "<dark_gray>Cooldown: %.0fs / sneak %.0fs", cd, scd)));
        }
        lore.add(Text.item("<dark_gray>" + Text.pretty(t.set) + " ability"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(Keys.ABILITY, PersistentDataType.STRING, t.id);
        meta.setEnchantmentGlintOverride(c.getBoolean("glint", true));
        meta.setMaxStackSize(1);
        String model = c.getString("item-model", "");
        if (!model.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(model.trim().toLowerCase(Locale.ROOT));
            if (key != null) meta.setItemModel(key);
        }
        List<Float> floats = new ArrayList<>();
        for (Double d : c.getDoubleList("custom-model-data")) floats.add(d.floatValue());
        if (floats.isEmpty() && c.isDouble("custom-model-data")) floats.add((float) c.getDouble("custom-model-data"));
        if (floats.isEmpty() && c.isInt("custom-model-data") && c.getInt("custom-model-data") != 0) floats.add((float) c.getInt("custom-model-data"));
        if (!floats.isEmpty()) {
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setFloats(floats);
            meta.setCustomModelDataComponent(cmd);
        }
        is.setItemMeta(meta);
        return is;
    }

    /** The ability an item carries, or null. */
    public static AbilityType of(ItemStack is) {
        if (is == null || is.getType().isAir() || !is.hasItemMeta()) return null;
        String id = is.getItemMeta().getPersistentDataContainer().get(Keys.ABILITY, PersistentDataType.STRING);
        return AbilityType.byId(id);
    }
}
