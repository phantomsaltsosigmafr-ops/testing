package dev.nullportals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Vex;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** Owns every spawned unit: spawning, formations, target selection, fighting skills, building and breaking. */
final class UnitManager implements Runnable, Listener {
    private final NullPortalsPlugin plugin;
    final Map<String, UnitTemplate> templates = new LinkedHashMap<>();
    final Map<String, Faction> factions = new LinkedHashMap<>();
    final Map<UUID, Unit> units = new HashMap<>();
    private final Map<Unit, Location> slots = new HashMap<>();
    private final Random rnd = new Random();
    private BukkitTask task;
    private long now;
    private long orderCounter;
    private boolean internalTarget;

    // settings
    private double maxHardness = 30;
    private final List<String> breakBlacklist = new ArrayList<>();
    private boolean dropBroken = false;
    private double aggroRange = 24;
    private double defendRange = 14;
    private boolean teleportStragglers = true;
    private int maxPerFaction = 120;

    UnitManager(NullPortalsPlugin plugin) {
        this.plugin = plugin;
    }

    long now() {
        return now;
    }

    // ================================================================ config

    void load(ConfigurationSection root) {
        templates.clear();
        ConfigurationSection ts = root.getConfigurationSection("templates");
        if (ts != null) {
            for (String id : ts.getKeys(false)) {
                ConfigurationSection c = ts.getConfigurationSection(id);
                if (c != null) templates.put(id, UnitTemplate.load(id, c));
            }
        }
        ConfigurationSection fs = root.getConfigurationSection("factions");
        if (fs != null) {
            for (String id : fs.getKeys(false)) {
                ConfigurationSection c = fs.getConfigurationSection(id);
                if (c == null) continue;
                Faction fresh = Faction.load(id, c);
                Faction live = factions.get(id);
                if (live == null) {
                    factions.put(id, fresh);
                } else { // keep live orders, refresh the definition
                    live.display = fresh.display;
                    live.colorCode = fresh.colorCode;
                    live.color = fresh.color;
                    live.buildBlock = fresh.buildBlock;
                    live.leaderTemplate = fresh.leaderTemplate;
                    live.army.clear();
                    live.army.addAll(fresh.army);
                    live.weights.clear();
                    live.weights.addAll(fresh.weights);
                }
            }
        }
        maxHardness = root.getDouble("units.max-break-hardness", 30);
        dropBroken = root.getBoolean("units.drop-broken-blocks", false);
        aggroRange = root.getDouble("units.aggro-range", 24);
        defendRange = root.getDouble("units.defend-range", 14);
        teleportStragglers = root.getBoolean("units.teleport-stragglers", true);
        maxPerFaction = root.getInt("units.max-per-faction", 120);
        breakBlacklist.clear();
        for (String s : root.getStringList("units.never-break")) breakBlacklist.add(s.toUpperCase(Locale.ROOT));
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 2L, 2L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (Unit u : new ArrayList<>(units.values())) {
            if (u.bar != null) u.bar.removeAll();
            u.mob.remove();
        }
        units.clear();
        slots.clear();
    }

    // ================================================================ spawning

    Unit spawn(Faction f, UnitTemplate t, Location at) {
        if (f == null || t == null || at.getWorld() == null) return null;
        if (count(f) >= maxPerFaction) return null;
        World w = at.getWorld();
        Mob m;
        String e = t.entity;
        if (e.equals("VEX")) m = w.spawn(at, Vex.class, false, x -> configure(x, f, t));
        else if (e.equals("SKELETON")) m = w.spawn(at, Skeleton.class, false, x -> configure(x, f, t));
        else if (e.equals("STRAY")) m = w.spawn(at, Stray.class, false, x -> configure(x, f, t));
        else if (e.equals("WITHER_SKELETON")) m = w.spawn(at, WitherSkeleton.class, false, x -> configure(x, f, t));
        else if (e.equals("HUSK")) m = w.spawn(at, Husk.class, false, x -> configure(x, f, t));
        else m = w.spawn(at, Zombie.class, false, x -> configure(x, f, t));

        Unit u = new Unit(m, t, f, orderCounter++);
        units.put(m.getUniqueId(), u);
        if (f.anchor == null) {
            f.anchor = at.clone();
            f.anchorYaw = at.getYaw();
        }
        w.spawnParticle(Particle.LARGE_SMOKE, at, 12, 0.3, 0.8, 0.3, 0.02);
        if (t.leader) {
            w.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.7f);
            w.spawnParticle(Particle.REVERSE_PORTAL, at, 80, 0.5, 1.5, 0.5, 0.3);
        }
        return u;
    }

    private void configure(Mob m, Faction f, UnitTemplate t) {
        String name = "§" + f.colorCode + (t.leader ? "§l" : "") + NullPortalsPlugin.color(t.display);
        m.setCustomName(name);
        m.setCustomNameVisible(f.showNames || t.leader);
        m.setPersistent(false);
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        attr(m, Attribute.MAX_HEALTH, t.health);
        attr(m, Attribute.ATTACK_DAMAGE, t.damage);
        if (!t.flying()) attr(m, Attribute.MOVEMENT_SPEED, t.speed);
        attr(m, Attribute.FOLLOW_RANGE, 48);
        if (Math.abs(t.scale - 1.0) > 0.01) attr(m, Attribute.SCALE, t.scale);
        if (t.leader) attr(m, Attribute.KNOCKBACK_RESISTANCE, 0.6);
        m.setHealth(t.health);
        equip(m, f, t);
        if (t.has("INVISIBLE")) m.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, -1, 0, false, false));
        if (t.has("FIRE_ON_HIT") || t.has("FIRE_ARROWS")) {
            m.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, -1, 0, false, false));
        }
        if (m instanceof Vex v) v.setLimitedLifetime(false);
        m.addScoreboardTag(NullPortalsPlugin.TAG);
        if (f.glow) m.setGlowing(true);
    }

    private static void attr(Mob m, Attribute a, double v) {
        AttributeInstance ai = m.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    private void equip(Mob m, Faction f, UnitTemplate t) {
        EntityEquipment eq = m.getEquipment();
        if (eq == null) return;
        Color col = t.armorColor.isEmpty() ? f.color : Faction.parseColor(t.armorColor);
        boolean armor = !t.armor.equals("NONE");
        if (armor) {
            eq.setChestplate(armorPiece(t.armor, "CHESTPLATE", col));
            eq.setLeggings(armorPiece(t.armor, "LEGGINGS", col));
            eq.setBoots(armorPiece(t.armor, "BOOTS", col));
        }
        String head = t.head == null ? "" : t.head.trim();
        if (head.equalsIgnoreCase("WITHER_SKULL")) {
            eq.setHelmet(item("WITHER_SKELETON_SKULL"));
        } else if (head.equalsIgnoreCase("SKELETON_SKULL")) {
            eq.setHelmet(item("SKELETON_SKULL"));
        } else if (!head.isEmpty()) {
            eq.setHelmet(playerHead(head));
        } else if (armor) {
            eq.setHelmet(armorPiece(t.armor, "HELMET", col));
        }
        if (!t.weapon.isEmpty() && !t.weapon.equalsIgnoreCase("NONE")) eq.setItemInMainHand(item(t.weapon));
    }

    static ItemStack item(String name) {
        Material mat = Material.matchMaterial(name);
        return mat == null ? null : new ItemStack(mat);
    }

    private static ItemStack armorPiece(String tier, String slot, Color c) {
        ItemStack is = item(tier + "_" + slot);
        if (is == null) return null;
        if (tier.equals("LEATHER")) {
            ItemMeta meta = is.getItemMeta();
            if (meta instanceof LeatherArmorMeta lam) {
                lam.setColor(c);
                is.setItemMeta(lam);
            }
        }
        return is;
    }

    private static ItemStack playerHead(String name) {
        ItemStack is = item("PLAYER_HEAD");
        if (is == null) return null;
        try {
            ItemMeta meta = is.getItemMeta();
            if (meta instanceof SkullMeta sm) {
                sm.setPlayerProfile(Bukkit.createProfile(name));
                is.setItemMeta(sm);
            }
        } catch (Exception ignored) {
        }
        return is;
    }

    int count(Faction f) {
        int n = 0;
        for (Unit u : units.values()) if (u.f == f) n++;
        return n;
    }

    List<Unit> unitsOf(Faction f) {
        List<Unit> l = new ArrayList<>();
        for (Unit u : units.values()) if (u.f == f) l.add(u);
        return l;
    }

    // ================================================================ orders

    void standDown(Faction f, Player by) {
        f.focus = null;
        f.mode = Faction.Mode.PASSIVE;
        f.follow = true;
        if (by != null) f.commander = by.getUniqueId();
        for (Unit u : unitsOf(f)) {
            u.combatTarget = null;
            u.retreatUntil = 0;
            u.orbitUntil = 0;
            u.diveUntil = 0;
            setTargetSafe(u.mob, null);
        }
    }

    void focus(Faction f, LivingEntity target) {
        f.focus = target.getUniqueId();
        for (Unit u : unitsOf(f)) {
            if (u.retreating(now)) continue;
            u.combatTarget = target;
            setTargetSafe(u.mob, target);
        }
    }

    void march(Faction f, Location to, float yaw) {
        f.follow = false;
        f.anchor = to.clone();
        f.anchorYaw = yaw;
        f.focus = null;
        recomputeSlots();
    }

    void hold(Faction f, Location at) {
        march(f, at, at.getYaw());
    }

    void teleportTo(Faction f, Location to) {
        for (Unit u : unitsOf(f)) {
            Location l = to.clone().add(rnd.nextDouble() * 4 - 2, u.t.flying() ? 3 : 0, rnd.nextDouble() * 4 - 2);
            u.mob.teleport(l);
        }
    }

    void disband(Faction f) {
        for (Unit u : unitsOf(f)) {
            u.mob.getWorld().spawnParticle(Particle.LARGE_SMOKE, u.mob.getLocation(), 10, 0.2, 0.5, 0.2, 0.02);
            remove(u);
            u.mob.remove();
        }
    }

    void clearBuilds(Faction f) {
        Material mat = buildMaterial(f);
        for (Block b : f.placed) {
            if (b.getType() == mat) b.setType(Material.AIR, false);
        }
        f.placed.clear();
    }

    void restoreBroken(Faction f) {
        for (int i = f.broken.size() - 1; i >= 0; i--) {
            Object[] rec = f.broken.get(i);
            Block b = (Block) rec[0];
            if (b.isPassable() || b.getType() == buildMaterial(f)) b.setBlockData((BlockData) rec[1], false);
        }
        f.broken.clear();
    }

    void applyGlow(Faction f) {
        for (Unit u : unitsOf(f)) u.mob.setGlowing(f.glow);
    }

    void applyNames(Faction f) {
        for (Unit u : unitsOf(f)) u.mob.setCustomNameVisible(f.showNames || u.t.leader);
    }

    private void remove(Unit u) {
        units.remove(u.mob.getUniqueId());
        slots.remove(u);
        if (u.bar != null) u.bar.removeAll();
    }

    boolean isUnit(Entity e) {
        return e != null && units.containsKey(e.getUniqueId());
    }

    Unit unit(Entity e) {
        return e == null ? null : units.get(e.getUniqueId());
    }

    // ================================================================ main loop

    @Override
    public void run() {
        now += 2;
        if (units.isEmpty()) return;
        if (now % 10 == 0) recomputeSlots();
        List<Unit> list = new ArrayList<>(units.values());
        for (Unit u : list) {
            if (!u.mob.isValid()) {
                remove(u);
                continue;
            }
            try {
                Location slot = slots.get(u);
                if (u.t.flying() && slot != null) slot = haloSlot(u, slot);
                movement(u, slot);
                if (((now / 2) + u.order) % 5 == 0) brain(u, slot);
            } catch (Exception ex) {
                plugin.getLogger().warning("Unit tick error: " + ex);
            }
        }
    }

    // ---------------------------------------------------------------- formations

    private void recomputeSlots() {
        slots.clear();
        Map<Faction, List<Unit>> byFaction = new HashMap<>();
        for (Unit u : units.values()) byFaction.computeIfAbsent(u.f, k -> new ArrayList<>()).add(u);
        for (Map.Entry<Faction, List<Unit>> en : byFaction.entrySet()) {
            Faction f = en.getKey();
            Location a = anchor(f);
            if (a == null) continue;
            double yaw = Math.toRadians(f.anchorYaw);
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);

            List<Unit> ground = new ArrayList<>();
            List<Unit> air = new ArrayList<>();
            for (Unit u : en.getValue()) (u.t.flying() ? air : ground).add(u);
            ground.sort(Comparator.comparingInt((Unit u) -> role(u)).thenComparingLong(u -> u.order));
            air.sort(Comparator.comparingLong(u -> u.order));

            int n = ground.size();
            for (int i = 0; i < n; i++) {
                double[] o = f.formation.offset(i, n, f.spacing);
                double x = a.getX() + rx * o[0] - fx * o[1];
                double z = a.getZ() + rz * o[0] - fz * o[1];
                Location s = groundAt(a.getWorld(), x, a.getY(), z);
                s.setYaw(f.anchorYaw);
                slots.put(ground.get(i), s);
            }
            // Fliers hover in a slowly turning halo above the middle of the formation.
            double depth = n == 0 ? 0 : f.formation.offset(n - 1, n, f.spacing)[1] / 2;
            Location centre = new Location(a.getWorld(), a.getX() - fx * depth, a.getY() + 4, a.getZ() - fz * depth);
            for (int j = 0; j < air.size(); j++) {
                Location s = centre.clone();
                s.setYaw((float) (j * 360.0 / air.size()));
                s.setPitch((float) (1.5 + air.size() * 0.35)); // radius smuggled in pitch
                slots.put(air.get(j), s);
            }
        }
    }

    private Location haloSlot(Unit u, Location centre) {
        double r = centre.getPitch();
        double ang = Math.toRadians(centre.getYaw()) + now * 0.02;
        return new Location(centre.getWorld(), centre.getX() + Math.cos(ang) * r,
                centre.getY() + Math.sin(now * 0.05 + u.order) * 0.5, centre.getZ() + Math.sin(ang) * r);
    }

    private static int role(Unit u) {
        if (u.t.leader) return 0;
        if (u.t.ranged()) return 3;
        if (u.t.has("BUILDER")) return 2;
        return 1;
    }

    private Location anchor(Faction f) {
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

    static Location groundAt(World w, double x, double yHint, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(yHint);
        for (int dy = 3; dy >= -8; dy--) {
            Block floor = w.getBlockAt(bx, by + dy - 1, bz);
            if (!floor.isPassable() && w.getBlockAt(bx, by + dy, bz).isPassable()
                    && w.getBlockAt(bx, by + dy + 1, bz).isPassable()) {
                return new Location(w, bx + 0.5, by + dy, bz + 0.5);
            }
        }
        return new Location(w, bx + 0.5, w.getHighestBlockYAt(bx, bz) + 1, bz + 0.5);
    }

    // ---------------------------------------------------------------- movement (every 2 ticks)

    private void movement(Unit u, Location slot) {
        Mob m = u.mob;
        if (u.flickerUntil != 0 && now >= u.flickerUntil) {
            m.setInvisible(false);
            u.flickerUntil = 0;
        }
        if (!u.t.trail.isEmpty() && now % 4 == 0) {
            try {
                m.getWorld().spawnParticle(Particle.valueOf(u.t.trail.toUpperCase(Locale.ROOT)), m.getLocation().add(0, 0.8, 0), 2, 0.2, 0.4, 0.2, 0.0);
            } catch (Exception ignored) {
            }
        }
        if (u.t.flying()) {
            flyMove(u, slot);
            return;
        }
        LivingEntity tgt = u.combatTarget;
        if (tgt == null || !tgt.isValid() || u.retreating(now) || tgt.getWorld() != m.getWorld()) return;
        fightMove(u, tgt);
    }

    private void flyMove(Unit u, Location slot) {
        Mob m = u.mob;
        Location ml = m.getLocation();
        LivingEntity tgt = u.combatTarget;
        if (tgt != null && tgt.isValid() && tgt.getWorld() == m.getWorld()) {
            if (u.t.has("ORBIT") && now < u.orbitUntil) {
                Location c = tgt.getLocation().add(0, 2.2, 0);
                double ang = Math.atan2(ml.getZ() - c.getZ(), ml.getX() - c.getX()) + 0.4;
                double r = 4.5;
                Vector want = new Vector(c.getX() + Math.cos(ang) * r, c.getY() + Math.sin(now * 0.15 + u.order) * 0.8,
                        c.getZ() + Math.sin(ang) * r);
                Vector v = want.subtract(ml.toVector()).multiply(0.3);
                if (v.length() > 0.7) v = v.normalize().multiply(0.7);
                m.setVelocity(v);
            }
            return; // diving: vanilla vex charge AI does the rest
        }
        if (slot == null) return;
        if (slot.getWorld() != m.getWorld()) {
            if (teleportStragglers) m.teleport(slot);
            return;
        }
        Vector d = slot.toVector().subtract(ml.toVector());
        double len = d.length();
        if (len > 64 && teleportStragglers) {
            m.teleport(slot);
        } else if (len > 0.6) {
            m.setVelocity(d.normalize().multiply(Math.min(0.5, len * 0.15)));
        }
    }

    /** Fighting skills for ground units: strafing, crit-jumps, gap-closing leaps, kiting. */
    private void fightMove(Unit u, LivingEntity tgt) {
        Mob m = u.mob;
        Location ml = m.getLocation(), tl = tgt.getLocation();
        double dx = tl.getX() - ml.getX(), dz = tl.getZ() - ml.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        if (h < 0.01) return;
        double nx = dx / h, nz = dz / h;
        boolean ground = m.isOnGround();
        Vector v = m.getVelocity();

        if (u.t.ranged()) {
            if (u.t.has("KITE") && h < 5 && ground && now % 10 == 0) m.setVelocity(new Vector(-nx * 0.4, v.getY(), -nz * 0.4));
            return;
        }
        if (u.t.has("LEAP") && h > 4 && h < 9 && ground && now >= u.cdLeap && Math.abs(tl.getY() - ml.getY()) < 3) {
            m.setVelocity(new Vector(nx * 0.85, 0.42, nz * 0.85));
            u.cdLeap = now + 70 + rnd.nextInt(40);
            return;
        }
        if (u.t.has("CRIT") && h < 3.2 && ground && now >= u.cdCrit) {
            m.setVelocity(new Vector(v.getX() + nx * 0.08, 0.42, v.getZ() + nz * 0.08));
            u.cdCrit = now + 16 + rnd.nextInt(10);
            return;
        }
        if (u.t.has("STRAFE") && h < 4.5 && ground && now % 6 == 0) {
            if (now >= u.cdStrafeFlip) {
                if (rnd.nextBoolean()) u.strafeDir = -u.strafeDir;
                u.cdStrafeFlip = now + 20 + rnd.nextInt(30);
            }
            double px = -nz * 0.16 * u.strafeDir, pz = nx * 0.16 * u.strafeDir;
            m.setVelocity(new Vector(v.getX() + px, v.getY(), v.getZ() + pz));
        }
    }

    // ---------------------------------------------------------------- brain (every 10 ticks per unit)

    private void brain(Unit u, Location slot) {
        Mob m = u.mob;
        Faction f = u.f;
        if (u.t.leader) leaderTick(u);

        double max = maxHealth(m);
        if (u.t.has("RETREAT") && !u.t.leader && !u.retreating(now) && now >= u.cdRetreat
                && u.combatTarget != null && m.getHealth() < max * 0.3) {
            u.retreatUntil = now + 70;
            u.cdRetreat = now + 400;
            m.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 1));
            u.combatTarget = null;
            setTargetSafe(m, null);
        }
        if (u.retreating(now)) {
            if (!u.t.flying()) goToSlot(u, slot, 1.4);
            u.lastPos = m.getLocation();
            return;
        }

        LivingEntity desired = chooseTarget(u, slot);
        u.combatTarget = desired;

        boolean orbiting = false;
        if (desired != null && u.t.has("ORBIT")) {
            if (now >= u.diveUntil) {
                u.orbitUntil = now + 30 + rnd.nextInt(40);
                u.diveUntil = u.orbitUntil + 50 + rnd.nextInt(30);
            }
            orbiting = now < u.orbitUntil;
        }
        LivingEntity mobTarget = orbiting ? null : desired;
        if (m.getTarget() != mobTarget) setTargetSafe(m, mobTarget);
        if (!orbiting && desired != null && m instanceof Vex v) v.setCharging(true);

        if (desired == null) {
            if (!u.t.flying()) {
                goToSlot(u, slot, 1.0);
                if (f.fortify && u.t.has("BUILDER") && f.build) fortify(u, slot);
                if (slot != null) engineer(u, slot, false);
            }
        } else {
            abilities(u, desired);
            if (!u.t.flying()) engineer(u, desired.getLocation(), true);
        }
        u.lastPos = m.getLocation();
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance ai = e.getAttribute(Attribute.MAX_HEALTH);
        return ai == null ? 20 : ai.getValue();
    }

    private LivingEntity chooseTarget(Unit u, Location slot) {
        Faction f = u.f;
        Mob m = u.mob;
        if (f.focus != null) {
            Entity e = Bukkit.getEntity(f.focus);
            if (e instanceof LivingEntity le && le.isValid() && le.getWorld() == m.getWorld() && attackable(le)) return le;
            f.focus = null;
        }
        if (f.mode == Faction.Mode.PASSIVE && !f.alwaysHostile) return null;

        boolean defend = f.mode == Faction.Mode.DEFEND && !f.alwaysHostile;
        Location centre = (defend && slot != null && slot.getWorld() == m.getWorld()) ? slot : m.getLocation();
        double range = defend ? defendRange : aggroRange;

        LivingEntity cur = u.combatTarget;
        if (cur != null && cur.isValid() && cur.getWorld() == m.getWorld() && attackable(cur) && isEnemy(f, cur)
                && cur.getLocation().distanceSquared(centre) < (range + 8) * (range + 8)) {
            return cur;
        }
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : m.getWorld().getNearbyEntities(centre, range, Math.min(range, 12), range)) {
            if (!(e instanceof LivingEntity le) || e == m) continue;
            if (!attackable(le) || !isEnemy(f, le)) continue;
            double d = le.getLocation().distanceSquared(m.getLocation());
            if (d < bestSq) {
                bestSq = d;
                best = le;
            }
        }
        return best;
    }

    private static boolean attackable(LivingEntity e) {
        if (!e.isValid()) return false;
        if (e instanceof Player p) {
            GameMode gm = p.getGameMode();
            return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
        }
        return true;
    }

    boolean isEnemy(Faction f, LivingEntity e) {
        if (e == null) return false;
        Unit o = units.get(e.getUniqueId());
        if (o != null) {
            if (o.f == f) return false;
            return f.alwaysHostile || o.f.alwaysHostile || (f.fightFactions && o.f.fightFactions);
        }
        if (e instanceof Player p) {
            if (f.alwaysHostile) return true;
            if (f.members.contains(p.getUniqueId())) return false;
            return f.attackOutsiders || f.hasGrudge(p.getUniqueId(), now);
        }
        if (f.alwaysHostile) return false;
        return e instanceof Monster;
    }

    void setTargetSafe(Mob m, LivingEntity t) {
        internalTarget = true;
        try {
            m.setTarget(t);
        } finally {
            internalTarget = false;
        }
    }

    private void goToSlot(Unit u, Location slot, double speed) {
        if (slot == null) return;
        Mob m = u.mob;
        if (slot.getWorld() != m.getWorld()) {
            if (teleportStragglers) m.teleport(slot);
            return;
        }
        double d = Math.sqrt(slot.distanceSquared(m.getLocation()));
        if (d > 48 && teleportStragglers) {
            m.teleport(slot);
        } else if (d > 1.4) {
            m.getPathfinder().moveTo(slot, d > 10 ? Math.max(speed, 1.35) : speed);
        } else {
            m.getPathfinder().stopPathfinding();
            m.setRotation(u.f.anchorYaw, 0f);
        }
    }

    // ---------------------------------------------------------------- abilities

    private void abilities(Unit u, LivingEntity tgt) {
        Mob m = u.mob;
        UnitTemplate t = u.t;
        double dist = Math.sqrt(m.getLocation().distanceSquared(tgt.getLocation()));

        if (t.has("BLINK") && now >= u.cdBlink && dist > 4 && dist < 26 && rnd.nextDouble() < 0.5) {
            if (blinkBehind(u, tgt)) u.cdBlink = now + 140 + rnd.nextInt(100);
        }
        if (t.has("SUMMON") && !t.summon.isEmpty() && now >= u.cdSummon && dist < 30) {
            u.cdSummon = now + 300 + rnd.nextInt(120);
            UnitTemplate st = templates.get(t.summon);
            if (st != null) {
                m.getWorld().playSound(m.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 0.6f);
                for (int i = 0; i < t.summonCount; i++) {
                    Location at = m.getLocation().add(rnd.nextDouble() * 3 - 1.5, st.flying() ? 2 : 0.2, rnd.nextDouble() * 3 - 1.5);
                    Unit s = spawn(u.f, st, at);
                    if (s != null) {
                        s.combatTarget = tgt;
                        setTargetSafe(s.mob, tgt);
                    }
                }
            }
        }
        if (t.has("FLICKER") && !t.has("INVISIBLE") && u.flickerUntil == 0 && rnd.nextDouble() < 0.08) {
            m.setInvisible(true);
            u.flickerUntil = now + 8;
            m.getWorld().spawnParticle(Particle.SQUID_INK, m.getLocation().add(0, 0.8, 0), 6, 0.2, 0.4, 0.2, 0.02);
        }
        if (t.has("LAUGH") && now >= u.cdLaugh && rnd.nextDouble() < 0.35) {
            m.getWorld().playSound(m.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 1.2f, 1.3f + rnd.nextFloat() * 0.3f);
            u.cdLaugh = now + 60 + rnd.nextInt(80);
        }
    }

    private boolean blinkBehind(Unit u, LivingEntity tgt) {
        Mob m = u.mob;
        Location tl = tgt.getLocation();
        Vector back = tl.getDirection().setY(0);
        if (back.length() < 0.01) back = new Vector(rnd.nextDouble() - 0.5, 0, rnd.nextDouble() - 0.5);
        back = back.normalize().multiply(-2.2);
        Location dest = tl.clone().add(back);
        if (u.t.flying()) {
            dest.add(0, 1.5, 0);
        } else {
            Location g = groundAt(dest.getWorld(), dest.getX(), dest.getY(), dest.getZ());
            if (Math.abs(g.getY() - tl.getY()) > 3) return false;
            dest = g;
        }
        dest.setDirection(tl.toVector().subtract(dest.toVector()));
        World w = m.getWorld();
        Location from = m.getLocation().add(0, 1, 0);
        w.spawnParticle(Particle.SQUID_INK, from, 15, 0.3, 0.6, 0.3, 0.05);
        w.spawnParticle(Particle.REVERSE_PORTAL, from, 30, 0.3, 0.6, 0.3, 0.2);
        w.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
        m.teleport(dest);
        w.spawnParticle(Particle.SQUID_INK, dest.clone().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.05);
        w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
        return true;
    }

    private void leaderTick(Unit u) {
        Mob m = u.mob;
        double max = maxHealth(m);
        if (u.bar == null) {
            u.bar = Bukkit.createBossBar(m.getCustomName() == null ? u.t.display : m.getCustomName(),
                    u.f.alwaysHostile ? BarColor.RED : BarColor.PURPLE, BarStyle.SEGMENTED_10, new BarFlag[0]);
        }
        u.bar.setProgress(Math.max(0, Math.min(1, m.getHealth() / max)));
        Location ml = m.getLocation();
        for (Player p : new ArrayList<>(u.bar.getPlayers())) {
            if (p.getWorld() != m.getWorld() || p.getLocation().distanceSquared(ml) > 48 * 48) u.bar.removePlayer(p);
        }
        for (Player p : m.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(ml) <= 48 * 48 && !u.bar.getPlayers().contains(p)) u.bar.addPlayer(p);
        }
        if (!u.raged && u.t.has("RAGE") && m.getHealth() < max * 0.4) {
            u.raged = true;
            m.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, -1, 0, false, true));
            m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, -1, 0, false, true));
            m.getWorld().playSound(ml, Sound.ENTITY_RAVAGER_ROAR, 2.5f, 0.5f);
            m.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, ml.clone().add(0, 2, 0), 12, 0.6, 0.6, 0.6, 0.1);
        }
    }

    // ---------------------------------------------------------------- building / breaking

    private Material buildMaterial(Faction f) {
        Material mat = Material.matchMaterial(f.buildBlock);
        return mat == null ? Material.COBBLESTONE : mat;
    }

    private void engineer(Unit u, Location dest, boolean chasing) {
        Faction f = u.f;
        boolean canBuild = f.build && u.t.has("BUILDER");
        boolean canBreak = f.breakBlocks && u.t.has("BREAKER");
        if (!canBuild && !canBreak) return;
        Mob m = u.mob;
        Location ml = m.getLocation();
        if (dest.getWorld() != m.getWorld()) return;

        double moved = (u.lastPos != null && u.lastPos.getWorld() == m.getWorld()) ? Math.sqrt(u.lastPos.distanceSquared(ml)) : 1;
        double hx = dest.getX() - ml.getX(), hz = dest.getZ() - ml.getZ();
        double hdist = Math.sqrt(hx * hx + hz * hz);
        double dy = dest.getY() - ml.getY();
        if ((hdist > 1.8 || Math.abs(dy) > 1.5) && moved < 0.3) u.stuck++;
        else u.stuck = 0;
        if (now < u.cdBuild) return;

        Block feet = ml.getBlock();
        // Pillar up towards a target above us.
        if (chasing && canBuild && dy > 1.6 && hdist < 3.5 && m.isOnGround()) {
            Block head2 = feet.getRelative(0, 2, 0);
            if (head2.isPassable()) {
                if (feet.isPassable() && !feet.isLiquid()) {
                    m.teleport(ml.clone().add(0, 1.05, 0));
                    place(u, feet);
                    u.cdBuild = now + 8;
                    return;
                }
            } else if (canBreak && breakable(head2)) {
                breakBlock(u, head2);
                u.cdBuild = now + 8;
                return;
            }
        }
        // Dig down towards a target below us.
        if (chasing && canBreak && dy < -2.5 && hdist < 1.6) {
            Block below = feet.getRelative(0, -1, 0);
            if (breakable(below)) {
                breakBlock(u, below);
                u.cdBuild = now + 10;
                return;
            }
        }
        if (u.stuck < 2) return;
        int fx = 0, fz = 0;
        if (Math.abs(hx) > Math.abs(hz)) fx = hx > 0 ? 1 : -1;
        else fz = hz > 0 ? 1 : -1;
        Block ff = feet.getRelative(fx, 0, fz), fh = feet.getRelative(fx, 1, fz);
        Block fg = feet.getRelative(fx, -1, fz), fg2 = feet.getRelative(fx, -2, fz);
        if (canBreak && !fh.isPassable() && breakable(fh)) {
            breakBlock(u, fh);
        } else if (canBreak && !ff.isPassable() && breakable(ff)) {
            breakBlock(u, ff);
        } else if (canBuild && fg.isPassable() && fg2.isPassable()) {
            place(u, fg); // bridge over the gap
        } else if (canBuild && !ff.isPassable() && !fh.isPassable() && feet.getRelative(0, 2, 0).isPassable() && m.isOnGround()) {
            m.teleport(ml.clone().add(0, 1.05, 0)); // can't break it: tower over it
            place(u, feet);
        } else {
            return;
        }
        u.stuck = 1;
        u.cdBuild = now + 10;
    }

    private void fortify(Unit u, Location slot) {
        if (slot == null || now < u.cdBuild || slot.getWorld() != u.mob.getWorld()) return;
        if (slot.distanceSquared(u.mob.getLocation()) > 2.6) return;
        Faction f = u.f;
        double yaw = Math.toRadians(f.anchorYaw);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        int half = (int) Math.round(f.spacing / 2.0);
        World w = slot.getWorld();
        for (int h = 0; h <= 2; h++) {
            for (int r = -half; r <= half; r++) {
                int bx = (int) Math.floor(slot.getX() + fx * 2.5 + rx * r);
                int bz = (int) Math.floor(slot.getZ() + fz * 2.5 + rz * r);
                Block b = w.getBlockAt(bx, slot.getBlockY() + h, bz);
                if (b.isPassable() && !b.isLiquid()) {
                    place(u, b);
                    u.cdBuild = now + 6;
                    return;
                }
            }
        }
    }

    private void place(Unit u, Block b) {
        if (plugin.isPortalBlock(b)) return;
        Faction f = u.f;
        b.setType(buildMaterial(f), true);
        f.placed.add(b);
        if (f.placed.size() > 6000) f.placed.remove(0);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 0.8f, 0.9f);
    }

    private boolean breakable(Block b) {
        Material ty = b.getType();
        if (ty.isAir() || b.isPassable() || b.isLiquid()) return false;
        float h = ty.getHardness();
        if (h < 0 || h > maxHardness) return false;
        String name = ty.name();
        for (String bl : breakBlacklist) if (name.contains(bl)) return false;
        return !plugin.isPortalBlock(b);
    }

    private void breakBlock(Unit u, Block b) {
        Faction f = u.f;
        World w = b.getWorld();
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        w.spawnParticle(Particle.BLOCK, c, 20, 0.3, 0.3, 0.3, 0.0, b.getBlockData());
        w.playSound(c, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.6f, 1.3f);
        if (f.logBroken) {
            f.broken.add(new Object[] {b, b.getBlockData()});
            b.setType(Material.AIR, true);
        } else if (dropBroken) {
            b.breakNaturally();
        } else {
            b.setType(Material.AIR, true);
        }
    }

    // ================================================================ combat events

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Unit u = units.get(e.getEntity().getUniqueId());
        if (u == null || internalTarget) return;
        Entity tg = e.getTarget();
        if (tg == null) return;
        Faction f = u.f;
        boolean ok = tg instanceof LivingEntity le
                && !u.retreating(now)
                && now >= u.orbitUntil
                && (f.mode != Faction.Mode.PASSIVE || f.alwaysHostile || tg.getUniqueId().equals(f.focus))
                && (f.focus == null || tg.getUniqueId().equals(f.focus))
                && attackable(le) && isEnemy(f, le);
        if (!ok) e.setCancelled(true);
        else u.combatTarget = (LivingEntity) tg;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        Entity dmgr = e.getDamager();
        Entity attacker = dmgr;
        if (dmgr instanceof Projectile pr) {
            ProjectileSource src = pr.getShooter();
            if (src instanceof Entity se) attacker = se;
        }
        Unit vu = units.get(victim.getUniqueId());
        Unit au = attacker == null ? null : units.get(attacker.getUniqueId());

        if (au != null && vu != null && au.f == vu.f) {
            e.setCancelled(true);
            return;
        }
        if (au != null && victim instanceof Player && !au.f.alwaysHostile && au.f.members.contains(victim.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (vu != null && attacker instanceof Player && !vu.f.alwaysHostile && vu.f.members.contains(attacker.getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        if (au != null && victim instanceof LivingEntity lv) {
            UnitTemplate t = au.t;
            boolean melee = dmgr == attacker;
            if (melee && t.has("CRIT") && !au.mob.isOnGround() && au.mob.getVelocity().getY() < -0.05) {
                e.setDamage(e.getDamage() * 1.5);
                victim.getWorld().spawnParticle(Particle.CRIT, lv.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.2);
                victim.getWorld().playSound(lv.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
            }
            if (t.has("DARKNESS_ON_HIT")) lv.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0));
            if (t.has("BLIND_ON_HIT")) lv.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
            if (t.has("SLOW_ON_HIT")) lv.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
            if (t.has("NAUSEA_ON_HIT")) lv.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
            if (t.has("FIRE_ON_HIT")) lv.setFireTicks(80);
            if (t.has("LIFESTEAL")) {
                double max = maxHealth(au.mob);
                au.mob.setHealth(Math.min(max, au.mob.getHealth() + e.getDamage() * 0.3));
            }
            if (t.has("CHAOS") && rnd.nextDouble() < 0.4) {
                final double ang = rnd.nextDouble() * Math.PI * 2;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (lv.isValid()) lv.setVelocity(new Vector(Math.cos(ang) * 0.9, 0.6, Math.sin(ang) * 0.9));
                });
            }
        }

        if (vu != null && attacker instanceof LivingEntity la && la != vu.mob) {
            Faction f = vu.f;
            if (attacker instanceof Player p && !f.alwaysHostile) f.grudges.put(p.getUniqueId(), now + 600);
            if ((f.mode != Faction.Mode.PASSIVE || f.alwaysHostile) && f.focus == null && isEnemy(f, la)) {
                int called = 0;
                Location vl = vu.mob.getLocation();
                for (Unit o : units.values()) {
                    if (o.f != f || o == vu || o.combatTarget != null || o.retreating(now)) continue;
                    if (o.mob.getWorld() != vu.mob.getWorld() || o.mob.getLocation().distanceSquared(vl) > 14 * 14) continue;
                    o.combatTarget = la;
                    setTargetSafe(o.mob, la);
                    if (++called >= 6) break;
                }
                if (vu.combatTarget == null) {
                    vu.combatTarget = la;
                    setTargetSafe(vu.mob, la);
                }
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Unit u = units.get(e.getEntity().getUniqueId());
        if (u == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        remove(u);
        if (u.t.leader) {
            String msg = NullPortalsPlugin.PREFIX + u.mob.getCustomName() + " §7has fallen!";
            for (Player p : u.mob.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(u.mob.getLocation()) < 96 * 96) p.sendMessage(msg);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (isUnit(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (isUnit(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        Unit u = units.get(e.getEntity().getUniqueId());
        if (u != null && u.t.has("FIRE_ARROWS")) e.getProjectile().setFireTicks(200);
    }
}
