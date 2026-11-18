package dev.unstablesmp.portal;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Works out which bots came out of the NULL portals, so the alive cap and the Totem of Null know them. VexBot's bots
 * are fake players that "join" while its portal animates, often a few seconds after the command and not exactly on
 * the spot, sometimes spawned elsewhere and teleported in. So each spawn spot is watched for a while, and anything new
 * is followed for a few seconds to see whether it shows up at a spot.
 */
public final class SpawnCapture implements Listener {

    private record Window(World world, double x, double y, double z, double r2, int until, Invasion owner) {
    }

    private static final class Candidate {
        final Entity entity;
        final int until;

        Candidate(Entity entity, int until) {
            this.entity = entity;
            this.until = until;
        }
    }

    private final List<Window> windows = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private int now;

    /** Watches a spawn spot for {@code ticks} ticks: new bots within {@code radius} count as that invasion's. */
    public void open(World w, double x, double y, double z, double radius, int now, int ticks, Invasion owner) {
        windows.add(new Window(w, x, y, z, radius * radius, now + ticks, owner));
        this.now = now;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent e) {
        consider(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        consider(e.getPlayer());
    }

    private void consider(Entity ent) {
        if (windows.isEmpty() || !(ent instanceof LivingEntity)) return;
        if (ent instanceof Player p && !looksLikeBot(p)) return;
        for (Candidate c : candidates) if (c.entity == ent) return;
        candidates.add(new Candidate(ent, now + 60));
    }

    /**
     * A real player joining mid-invasion is never taken for a NULL: bots are fake players (no real connection) or
     * brand-new names that have never played on the server before.
     */
    static boolean looksLikeBot(Player p) {
        return p.getAddress() == null || !p.hasPlayedBefore();
    }

    public void tick(int now) {
        this.now = now;
        windows.removeIf(w -> w.until() < now);
        if (candidates.isEmpty()) return;
        for (Iterator<Candidate> it = candidates.iterator(); it.hasNext(); ) {
            Candidate c = it.next();
            Entity e = c.entity;
            if (!e.isValid() || e.isDead() || c.until < now) {
                it.remove();
                continue;
            }
            Window w = windowAt(e);
            if (w != null) {
                if (w.owner() != null) w.owner().track(e);
                it.remove();
            }
        }
    }

    private Window windowAt(Entity e) {
        double ex = e.getX(), ey = e.getY(), ez = e.getZ();
        for (Window w : windows) {
            if (w.world() != e.getWorld()) continue;
            double dx = ex - w.x(), dy = ey - w.y(), dz = ez - w.z();
            if (dx * dx + dy * dy + dz * dz <= w.r2()) return w;
        }
        return null;
    }

    public void clear() {
        windows.clear();
        candidates.clear();
    }
}
