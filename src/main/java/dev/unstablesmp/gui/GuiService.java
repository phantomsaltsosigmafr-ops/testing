package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Ground;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.faction.Faction;
import dev.unstablesmp.faction.UnitTemplate;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Per-player GUI state (selected faction, battle picks) plus shared spawn helpers and click routing. */
public final class GuiService implements Listener {
    private final UnstableSMP plugin;
    private final Map<UUID, String> selected = new HashMap<>();
    final Map<UUID, String> battleA = new HashMap<>();
    final Map<UUID, String> battleB = new HashMap<>();
    final Map<UUID, Integer> battleSize = new HashMap<>();
    private final Random rnd = new Random();

    public GuiService(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player p) {
        new MainMenu(plugin, this, p).open();
    }

    public void select(Player p, Faction f) {
        selected.put(p.getUniqueId(), f.id);
    }

    public Faction selected(Player p) {
        Faction f = plugin.factions().get(selected.get(p.getUniqueId()));
        if (f == null) f = plugin.factions().factionOf(p.getUniqueId());
        if (f == null && !plugin.factions().all().isEmpty()) f = plugin.factions().all().iterator().next();
        if (f != null) selected.put(p.getUniqueId(), f.id);
        return f;
    }

    // ------------------------------------------------------------------ spawning helpers

    static Location inFront(Player p, double dist) {
        Location l = p.getLocation();
        double yaw = Math.toRadians(l.getYaw());
        Location at = Ground.groundAt(l.getWorld(), l.getX() - Math.sin(yaw) * dist, l.getY(), l.getZ() + Math.cos(yaw) * dist);
        at.setYaw(l.getYaw());
        return at;
    }

    /** Spawns n of a template for the player's faction in front of them; the player becomes commander + member. */
    public int spawnFor(Player p, Faction f, UnitTemplate t, int n) {
        int ok = 0;
        for (int i = 0; i < n; i++) {
            Location at = inFront(p, 3 + rnd.nextDouble() * 2);
            at.add(rnd.nextDouble() * 3 - 1.5, t.flying ? 2 : 0, rnd.nextDouble() * 3 - 1.5);
            if (plugin.units().spawn(f, t, at) != null) ok++;
        }
        if (f.commander == null) f.commander = p.getUniqueId();
        if (!f.members.contains(p.getUniqueId())) plugin.factions().join(f, p.getUniqueId());
        if (ok < n) plugin.messages().send(p, "faction.cap", Messages.p("spawned", Integer.toString(ok)), Messages.p("asked", Integer.toString(n)));
        return ok;
    }

    public int spawnArmy(Player p, Faction f, int n) {
        int ok = 0;
        for (int i = 0; i < n; i++) {
            UnitTemplate t = plugin.factions().template(f.pickArmy(rnd));
            if (t != null) ok += spawnFor(p, f, t, 1);
        }
        return ok;
    }

    // ------------------------------------------------------------------ click routing

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        menu.handle(e.getRawSlot(), e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
