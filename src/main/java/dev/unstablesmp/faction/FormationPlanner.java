package dev.unstablesmp.faction;

import dev.unstablesmp.core.Ground;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Computes each unit's formation slot every 10 ticks. Leaders go at the front, melee next, builders behind them,
 * archers in the back rows; flyers hover in a rotating halo above the middle of the formation.
 */
final class FormationPlanner {
    private static final Comparator<Unit> ROLE_ORDER = Comparator.comparingInt(FormationPlanner::role).thenComparingInt(u -> u.order);
    private static final Comparator<Unit> ORDER = Comparator.comparingInt(u -> u.order);

    private final Map<Faction, List<Unit>> ground = new HashMap<>();
    private final Map<Faction, List<Unit>> air = new HashMap<>();
    private final double[] off = new double[2];

    void plan(List<Unit> units, int now) {
        for (List<Unit> l : ground.values()) l.clear();
        for (List<Unit> l : air.values()) l.clear();
        for (Unit u : units) {
            if (u.removed) continue;
            (u.t.flying ? air : ground).computeIfAbsent(u.f, k -> new ArrayList<>()).add(u);
        }
        ground.values().removeIf(List::isEmpty);
        air.values().removeIf(List::isEmpty);
        for (Map.Entry<Faction, List<Unit>> en : ground.entrySet()) planFaction(en.getKey(), en.getValue(), air.get(en.getKey()));
        for (Map.Entry<Faction, List<Unit>> en : air.entrySet()) {
            if (!ground.containsKey(en.getKey())) planFaction(en.getKey(), List.of(), en.getValue());
        }
    }

    private void planFaction(Faction f, List<Unit> g, List<Unit> a) {
        Location anchor = anchor(f);
        if (anchor == null || anchor.getWorld() == null) {
            for (Unit u : g) u.hasSlot = false;
            if (a != null) for (Unit u : a) u.hasSlot = false;
            return;
        }
        double yaw = Math.toRadians(f.anchorYaw);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        int n = g.size();
        if (n > 1) g.sort(ROLE_ORDER);
        for (int i = 0; i < n; i++) {
            Unit u = g.get(i);
            f.formation.offset(i, n, f.spacing, off);
            double x = anchor.getX() + rx * off[0] - fx * off[1];
            double z = anchor.getZ() + rz * off[0] - fz * off[1];
            Location s = Ground.groundAt(anchor.getWorld(), x, anchor.getY(), z);
            u.slotX = s.getX();
            u.slotY = s.getY();
            u.slotZ = s.getZ();
            u.slotYaw = f.anchorYaw;
            u.hasSlot = true;
        }
        if (a == null || a.isEmpty()) return;
        if (a.size() > 1) a.sort(ORDER);
        double depth = 0;
        if (n > 0) {
            f.formation.offset(n - 1, n, f.spacing, off);
            depth = off[1] / 2;
        }
        double cx = anchor.getX() - fx * depth, cz = anchor.getZ() - fz * depth, cy = anchor.getY() + 4;
        int m = a.size();
        for (int j = 0; j < m; j++) {
            Unit u = a.get(j);
            u.slotX = cx;
            u.slotY = cy;
            u.slotZ = cz;
            u.slotYaw = f.anchorYaw;
            u.haloIndex = j;
            u.haloCount = m;
            u.haloRadius = 1.5 + m * 0.35;
            u.hasSlot = true;
        }
    }

    /** Follow mode: the formation forms up just behind the commander, facing where they face. */
    static Location anchor(Faction f) {
        if (f.follow && f.commander != null) {
            Player p = Bukkit.getPlayer(f.commander);
            if (p != null && p.isValid()) {
                Location pl = p.getLocation();
                double yaw = Math.toRadians(pl.getYaw());
                f.anchorYaw = pl.getYaw();
                f.anchor = new Location(pl.getWorld(), pl.getX() + Math.sin(yaw) * 2.5, pl.getY(), pl.getZ() - Math.cos(yaw) * 2.5);
            }
        }
        return f.anchor;
    }

    static int role(Unit u) {
        if (u.t.leader) return 0;
        if (u.t.ranged()) return 3;
        if (u.t.has(Trait.BUILDER)) return 2;
        return 1;
    }
}
