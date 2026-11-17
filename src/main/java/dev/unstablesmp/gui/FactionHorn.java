package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Keys;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import dev.unstablesmp.faction.Faction;
import dev.unstablesmp.faction.Unit;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The Faction Horn (goat horn tagged by PDC). Right-click an entity: focus it (it glows briefly). Right-click a
 * block: march there. Right-click the sky: rally on me. Left-click: STAND DOWN. Sneak + right-click: GUI.
 */
public final class FactionHorn implements Listener {
    private final UnstableSMP plugin;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public FactionHorn(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public ItemStack create(Faction f) {
        ItemStack is = new ItemStack(Material.GOAT_HORN);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(Text.item(f.display.append(Component.text(" Horn"))));
        meta.lore(Text.lore(List.of(
                "<yellow>Right-click entity</yellow> <gray>» focus it",
                "<yellow>Right-click block</yellow> <gray>» march there",
                "<yellow>Right-click sky</yellow> <gray>» rally on me",
                "<yellow>Left-click</yellow> <gray>» <white><bold>STAND DOWN",
                "<yellow>Sneak + right-click</yellow> <gray>» command menu")));
        meta.getPersistentDataContainer().set(Keys.HORN, PersistentDataType.STRING, f.id);
        meta.setEnchantmentGlintOverride(true);
        is.setItemMeta(meta);
        return is;
    }

    private static String factionOf(ItemStack it) {
        if (it == null || it.getType() != Material.GOAT_HORN || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.HORN, PersistentDataType.STRING);
    }

    private Faction use(Player p, String fid) {
        if (!p.hasPermission("uss.gui")) {
            plugin.messages().send(p, "no-permission");
            return null;
        }
        long t = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (last != null && t - last < 300) return null;
        lastUse.put(p.getUniqueId(), t);
        Faction f = plugin.factions().get(fid);
        if (f == null) {
            plugin.messages().send(p, "faction.horn-unknown");
            return null;
        }
        if (!f.members.contains(p.getUniqueId())) plugin.factions().join(f, p.getUniqueId());
        f.commander = p.getUniqueId();
        return f;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        String fid = factionOf(e.getItem());
        if (fid == null) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        Player p = e.getPlayer();
        Action a = e.getAction();
        if (a == Action.PHYSICAL) return;
        Faction f = use(p, fid);
        if (f == null) return;
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            standDown(p, f);
            return;
        }
        if (p.isSneaking()) {
            plugin.gui().select(p, f);
            plugin.gui().openMain(p);
            return;
        }
        Entity target = p.getTargetEntity(96);
        if (target instanceof LivingEntity le && target != p) {
            focus(p, f, le);
            return;
        }
        Block b = p.getTargetBlockExact(160);
        if (b != null) {
            Location to = b.getLocation().add(0.5, 1, 0.5);
            plugin.units().march(f, to, p.getLocation().getYaw());
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 3f, 1f);
            plugin.messages().send(p, "faction.march", Messages.c("faction", f.display),
                    Messages.p("x", Integer.toString(to.getBlockX())), Messages.p("y", Integer.toString(to.getBlockY())),
                    Messages.p("z", Integer.toString(to.getBlockZ())));
        } else {
            f.follow = true;
            f.focus = null;
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 3f, 1.3f);
            plugin.messages().send(p, "faction.rally", Messages.c("faction", f.display));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        String fid = factionOf(e.getPlayer().getInventory().getItemInMainHand());
        if (fid == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Faction f = use(p, fid);
        if (f == null) return;
        if (p.isSneaking()) {
            plugin.gui().select(p, f);
            plugin.gui().openMain(p);
        } else if (e.getRightClicked() instanceof LivingEntity le) {
            focus(p, f, le);
        }
    }

    /** Left-clicking an entity with the horn is also STAND DOWN (and never hurts it). */
    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        String fid = factionOf(e.getPlayer().getInventory().getItemInMainHand());
        if (fid == null) return;
        e.setCancelled(true);
        Faction f = use(e.getPlayer(), fid);
        if (f != null) standDown(e.getPlayer(), f);
    }

    private void standDown(Player p, Faction f) {
        plugin.units().standDown(f, p);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_7, 3f, 1f);
        plugin.messages().send(p, "faction.stand-down", Messages.c("faction", f.display));
    }

    private void focus(Player p, Faction f, LivingEntity le) {
        Unit tu = plugin.units().unit(le);
        if (tu != null && tu.f == f) {
            plugin.messages().send(p, "faction.own-unit");
            return;
        }
        if (le instanceof Player other && f.members.contains(other.getUniqueId())) {
            plugin.messages().send(p, "faction.own-member");
            return;
        }
        plugin.units().focus(f, le);
        p.getWorld().playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 4f, 1f);
        plugin.messages().send(p, "faction.focus", Messages.c("faction", f.display), Messages.c("target", le.name()));
        if (!le.isGlowing()) {
            le.setGlowing(true);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (le.isValid() && !plugin.units().isUnit(le)) le.setGlowing(false);
                else if (le.isValid()) {
                    Unit u = plugin.units().unit(le);
                    le.setGlowing(u != null && u.f.glow);
                }
            }, plugin.getConfig().getLong("factions-settings.focus-glow-ticks", 60L));
        }
    }
}
