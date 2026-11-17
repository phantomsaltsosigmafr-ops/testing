package dev.unstablesmp.faction;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Ticker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Registry of every live faction unit plus the orders API. Ticked by the shared {@link Ticker}: movement runs
 * every 2 ticks, and each unit's brain runs every 10 ticks, spread evenly across ticks by unit order.
 */
public final class UnitManager implements Ticker.Tickable {
    private final UnstableSMP plugin;
    private final Map<UUID, Unit> byId = new HashMap<>();
    private final List<Unit> list = new ArrayList<>();
    private final Map<Faction, int[]> counts = new HashMap<>();
    private final UnitSpawner spawner;
    final FormationPlanner planner;
    final UnitBrain brain;
    final UnitMovement movement;
    final Engineer engineer;
    final LeaderBars bars;
    final HeadProfiles heads;
    UnitSettings s = new UnitSettings();
    private int orderCounter;
    private boolean internalTarget;

    public UnitManager(UnstableSMP plugin) {
        this.plugin = plugin;
        this.heads = new HeadProfiles(plugin);
        this.spawner = new UnitSpawner(plugin, heads);
        this.planner = new FormationPlanner();
        this.engineer = new Engineer(plugin, this);
        this.movement = new UnitMovement(this);
        this.brain = new UnitBrain(plugin, this);
        this.bars = new LeaderBars(this);
        heads.onResolved(this::refreshHeads);
    }

    public void configure(UnitSettings settings) {
        this.s = settings;
        for (UnitTemplate t : plugin.factions().templates()) {
            String h = t.head;
            if (!h.isEmpty() && !h.contains("_")) heads.prefetch(h);
        }
    }

    public UnitSettings settings() {
        return s;
    }

    UnstableSMP plugin() {
        return plugin;
    }

    // ================================================================== registry

    public Unit spawn(Faction f, UnitTemplate t, Location at) {
        if (f == null || t == null || at.getWorld() == null) return null;
        if (count(f) >= s.maxPerFaction || list.size() >= s.maxTotal || !plugin.tracker().canSpawnMobs(1)) return null;
        Mob m = spawner.spawn(f, t, at);
        if (m == null) return null;
        Unit u = new Unit(m, t, f, orderCounter++);
        byId.put(m.getUniqueId(), u);
        list.add(u);
        counts.computeIfAbsent(f, k -> new int[1])[0]++;
        if (f.anchor == null) {
            f.anchor = at.clone();
            f.anchorYaw = at.getYaw();
        }
        return u;
    }

    public int count(Faction f) {
        int[] c = counts.get(f);
        return c == null ? 0 : c[0];
    }

    public int total() {
        return list.size();
    }

    public List<Unit> unitsOf(Faction f) {
        List<Unit> l = new ArrayList<>();
        for (Unit u : list) if (u.f == f && !u.removed) l.add(u);
        return l;
    }

    List<Unit> all() {
        return list;
    }

    public Unit unit(Entity e) {
        return e == null ? null : byId.get(e.getUniqueId());
    }

    public boolean isUnit(Entity e) {
        return e != null && byId.containsKey(e.getUniqueId());
    }

    void remove(Unit u) {
        if (u.removed) return;
        u.removed = true;
        byId.remove(u.mob.getUniqueId());
        int[] c = counts.get(u.f);
        if (c != null && --c[0] <= 0) counts.remove(u.f);
        bars.remove(u);
        plugin.tracker().release(u.mob);
    }

    private void refreshHeads(String name) {
        for (Unit u : list) {
            if (!u.removed && u.t.head.equalsIgnoreCase(name) && u.mob.isValid()) {
                ItemStack helmet = spawner.helmet(u.f, u.t);
                u.mob.getEquipment().setHelmet(helmet);
            }
        }
    }

    public void removeAll() {
        for (Unit u : list) {
            remove(u);
            u.mob.remove();
        }
        list.clear();
        byId.clear();
        counts.clear();
        bars.clear();
    }

    // ================================================================== orders

    public void standDown(Faction f, Player by) {
        f.focus = null;
        f.mode = Faction.Mode.PASSIVE;
        f.follow = true;
        if (by != null) f.commander = by.getUniqueId();
        for (Unit u : list) {
            if (u.f != f || u.removed) continue;
            u.combatTarget = null;
            u.retreatUntil = 0;
            u.orbitUntil = 0;
            u.diveUntil = 0;
            setTargetSafe(u.mob, null);
        }
    }

    public void focus(Faction f, LivingEntity target) {
        f.focus = target.getUniqueId();
        int now = org.bukkit.Bukkit.getCurrentTick();
        for (Unit u : list) {
            if (u.f != f || u.removed || u.retreating(now)) continue;
            u.combatTarget = target;
            setTargetSafe(u.mob, target);
        }
    }

    public void march(Faction f, Location to, float yaw) {
        f.follow = false;
        f.anchor = to.clone();
        f.anchorYaw = yaw;
        f.focus = null;
    }

    public void hold(Faction f, Location at) {
        march(f, at, at.getYaw());
    }

    public void teleportTo(Faction f, Location to) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Unit u : unitsOf(f)) {
            Location l = to.clone().add(rnd.nextDouble(-2, 2), u.t.flying ? 3 : 0, rnd.nextDouble(-2, 2));
            u.mob.teleport(l);
        }
    }

    public void disband(Faction f) {
        for (Unit u : unitsOf(f)) {
            u.mob.getWorld().spawnParticle(Particle.LARGE_SMOKE, u.mob.getLocation(), 10, 0.2, 0.5, 0.2, 0.02);
            remove(u);
            u.mob.remove();
        }
    }

    public int clearBuilds(Faction f) {
        return engineer.clearBuilds(f);
    }

    public int restoreBroken(Faction f) {
        return engineer.restoreBroken(f);
    }

    public void applyGlow(Faction f) {
        for (Unit u : unitsOf(f)) u.mob.setGlowing(f.glow);
    }

    public void applyNames(Faction f) {
        for (Unit u : unitsOf(f)) u.mob.setCustomNameVisible(f.showNames || u.t.leader);
    }

    void setTargetSafe(Mob m, LivingEntity t) {
        internalTarget = true;
        try {
            m.setTarget(t);
        } finally {
            internalTarget = false;
        }
    }

    boolean internalTarget() {
        return internalTarget;
    }

    Material buildMaterial(Faction f) {
        return f.buildBlock;
    }

    // ================================================================== tick

    @Override
    public void tick(int now) {
        if (list.isEmpty()) return;
        if (now % 10 == 0) planner.plan(list, now);
        boolean moveTick = (now & 1) == 0;
        int bucket = (now >> 1) % 5;
        for (int i = 0, n = list.size(); i < n; i++) {
            Unit u = list.get(i);
            if (u.removed) continue;
            if (!u.mob.isValid()) {
                remove(u);
                continue;
            }
            if (!moveTick) continue;
            try {
                movement.tick(u, now);
                if (u.order % 5 == bucket) brain.tick(u, now);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Unit tick error (" + u.t.id + "): " + ex);
            }
        }
        if (now % 20 == 0) bars.tick(now);
        // Compact removed units (swap-remove keeps it O(n)).
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).removed) {
                list.set(i, list.get(list.size() - 1));
                list.remove(list.size() - 1);
            }
        }
    }

    @Override
    public String name() {
        return "units";
    }
}
