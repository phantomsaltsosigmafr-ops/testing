package dev.unstablesmp.faction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/** Adventure boss bars for faction leaders, shown to players within range. Updated once a second. */
final class LeaderBars {
    private final UnitManager mgr;
    private final Map<Unit, Set<UUID>> viewers = new HashMap<>();
    private final Location ml = new Location(null, 0, 0, 0);
    private final Location pl = new Location(null, 0, 0, 0);

    LeaderBars(UnitManager mgr) {
        this.mgr = mgr;
    }

    void tick(int now) {
        double range = mgr.s.leaderBarRange;
        double rSq = range * range;
        for (Unit u : mgr.all()) {
            if (u.removed || !u.t.leader) continue;
            Mob m = u.mob;
            if (u.bar == null) {
                u.bar = BossBar.bossBar(m.customName() != null ? m.customName() : u.t.display,
                        1f, u.f.eventHostile ? BossBar.Color.RED : BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
            }
            u.bar.progress((float) Math.max(0, Math.min(1, m.getHealth() / UnitBrain.maxHealth(m))));
            Set<UUID> seen = viewers.computeIfAbsent(u, k -> new HashSet<>());
            m.getLocation(ml);
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean near = p.getWorld() == m.getWorld() && p.getLocation(pl).distanceSquared(ml) <= rSq;
                if (near && seen.add(p.getUniqueId())) p.showBossBar(u.bar);
                else if (!near && seen.remove(p.getUniqueId())) p.hideBossBar(u.bar);
            }
        }
    }

    void remove(Unit u) {
        Set<UUID> seen = viewers.remove(u);
        if (seen == null || u.bar == null) return;
        for (UUID id : seen) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(u.bar);
        }
    }

    void clear() {
        for (Unit u : new java.util.ArrayList<>(viewers.keySet())) remove(u);
        viewers.clear();
    }
}
