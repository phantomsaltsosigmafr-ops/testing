package dev.unstablesmp.portal;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

/**
 * Catches whatever the spawn command creates (VexBot's bots appear during the command or a tick or two later) right
 * at the portal mouth and gives it a shove out of the portal, so bots visibly pour out instead of popping in place.
 */
public final class SpawnCapture implements Listener {

    private record Window(World world, double x, double y, double z, Vector push, int until, Set<UUID> playersBefore,
                          Breach owner) {
    }

    private record Pending(Entity entity, Vector push, int at) {
    }

    private final List<Window> windows = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private int now;

    /**
     * Opens a capture window around a spawn point for {@code ticks} ticks after this one. Captured bots count toward
     * the breach's alive cap; {@code push} (may be null) shoves them out of the portal.
     */
    public void open(World w, double x, double y, double z, Vector push, int now, int ticks, Breach owner) {
        Set<UUID> before = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) before.add(p.getUniqueId());
        windows.add(new Window(w, x, y, z, push, now + ticks, before, owner));
        this.now = now;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent e) {
        if (windows.isEmpty()) return;
        Entity ent = e.getEntity();
        if (!(ent instanceof LivingEntity)) return;
        if (ent instanceof Player p) {
            // a real player who was already online is never pushed; a bot that just "joined" is
            for (Window w : windows) if (w.playersBefore().contains(p.getUniqueId())) return;
        }
        double ex = ent.getX(), ey = ent.getY(), ez = ent.getZ();
        for (Window w : windows) {
            if (w.world() != ent.getWorld()) continue;
            double dx = ex - w.x(), dy = ey - w.y(), dz = ez - w.z();
            if (dx * dx + dy * dy + dz * dz <= 20) {
                if (w.owner() != null) w.owner().track(ent);
                if (w.push() != null) pending.add(new Pending(ent, w.push(), now + 1));
                return;
            }
        }
    }

    public void tick(int now) {
        this.now = now;
        windows.removeIf(w -> w.until() < now);
        if (pending.isEmpty()) return;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.at() > now) continue;
            it.remove();
            if (p.entity().isValid()) p.entity().setVelocity(p.push().clone());
        }
    }

    public void clear() {
        windows.clear();
        pending.clear();
    }
}
