package dev.unstablesmp.totem;

import dev.unstablesmp.Settings;
import dev.unstablesmp.Text;
import dev.unstablesmp.portal.Fx;
import dev.unstablesmp.portal.InvasionManager;
import dev.unstablesmp.portal.SpawnCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The Totem of Null: a totem of undying that still saves you, but when it pops a wave rolls out from you and every
 * NULL bot the invasions poured out dies, nearest first. Identified by a PDC tag, so renaming it doesn't matter.
 */
public final class TotemOfNull implements Listener {
    private final Plugin plugin;
    private final InvasionManager invasions;
    private final NamespacedKey key;

    public TotemOfNull(Plugin plugin, InvasionManager invasions) {
        this.plugin = plugin;
        this.invasions = invasions;
        this.key = new NamespacedKey(plugin, "totem_of_null");
    }

    private Settings s() {
        return invasions.settings();
    }

    /** A fresh Totem of Null. */
    public ItemStack create(int amount) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.mm(s().totemName));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : s().totemLore) lore.add(Text.mm(line));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.setEnchantmentGlintOverride(s().totemGlint);
        if (!s().totemItemModel.isBlank()) {
            NamespacedKey model = NamespacedKey.fromString(s().totemItemModel.trim());
            if (model != null) {
                try {
                    meta.setItemModel(model); // 1.21.4+; older servers just keep the normal totem look
                } catch (NoSuchMethodError ignored) {
                    // not available on this server version
                }
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    public boolean is(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING || !item.hasItemMeta()) return false;
        Byte b = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPop(EntityResurrectEvent e) {
        EquipmentSlot hand = e.getHand();
        LivingEntity holder = e.getEntity();
        if (hand == null || holder.getEquipment() == null || !is(holder.getEquipment().getItem(hand))) return;
        pop(holder);
    }

    /** The totem broke: shockwave at the holder, then every NULL dies in a wave rolling outward. */
    public int pop(LivingEntity holder) {
        Location at = holder.getLocation();
        Fx.play(s().effect("totem-pop"), at.getWorld(), at.getX(), at.getY() + 1, at.getZ(), 1, 0, 0, 1);
        int radius = s().totemKillRadius;
        List<Entity> victims = new ArrayList<>();
        for (Entity n : invasions.pouredBots()) {
            if (n == holder || !n.isValid() || n.isDead()) continue;
            if (radius > 0 && (n.getWorld() != at.getWorld() || n.getLocation().distanceSquared(at) > (double) radius * radius)) continue;
            victims.add(n);
        }
        invasions.killWave(victims, at, s().totemWaveSpeed);
        String who = holder instanceof Player p ? p.getName() : holder.getType().name().toLowerCase();
        Map<String, String> values = Map.of("x", Integer.toString(at.getBlockX()), "y", Integer.toString(at.getBlockY()),
                "z", Integer.toString(at.getBlockZ()), "world", at.getWorld().getName(), "player", who);
        for (String t : s().totemKillCommands) {
            String cmd = SpawnCommand.format(t, values);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Totem command failed: /" + cmd + " (" + ex + ")");
            }
        }
        if (s().totemClosePortals) {
            invasions.stop(at.getWorld(), false);
        } else if (s().totemQuietSeconds > 0) {
            for (var inv : invasions.invasions()) {
                if (radius == 0 || inv.world == at.getWorld()) inv.quiet(s().totemQuietSeconds * 20);
            }
        }
        plugin.getLogger().info("Totem of Null popped by " + who + ": " + victims.size() + " NULL wiped out.");
        return victims.size();
    }
}
