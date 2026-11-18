package dev.unstablesmp.bot;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.bot.ai.BotBrain;
import dev.unstablesmp.bot.ai.BotCombat;
import dev.unstablesmp.bot.ai.BotPathfinder;
import dev.unstablesmp.bot.ai.Squad;
import dev.unstablesmp.core.Ticker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Every player-bot. Ticked once per server tick: rebuilds the target grid every 5 ticks, plans squads every 10, and
 * runs each bot's brain + movement. Bots far from any player think less often (LOD) so 500-1000 bots stay cheap.
 */
public final class BotManager implements Ticker.Tickable {
    private final UnstableSMP plugin;
    private final List<Bot> bots = new ArrayList<>();
    private final Map<UUID, Bot> byEntity = new HashMap<>();
    private final Map<String, BotGroup> groups = new LinkedHashMap<>();
    private final Map<String, Squad> squads = new HashMap<>();
    private final Map<UUID, Bot> pearls = new HashMap<>();
    private final Set<String> takenNames = new HashSet<>();
    private final SpatialGrid grid = new SpatialGrid();
    private final List<LivingEntity> scratch = new ArrayList<>();
    private final Location loc = new Location(null, 0, 0, 0);
    private final Location loc2 = new Location(null, 0, 0, 0);
    private final BotCombat combat;
    private final BotPathfinder paths = new BotPathfinder();
    private final BotBrain brain;
    private int nextId;

    int maxTotal = 1000;
    double lodRange = 64;
    double regenPerSecond = 0.5;
    boolean joinMessages = true;
    boolean leaveMessages = true;
    boolean deathMessages = true;
    boolean collide = false;
    boolean dropItems = false;
    double chatChance = 1.0;

    public BotManager(UnstableSMP plugin) {
        this.plugin = plugin;
        this.combat = new BotCombat(plugin);
        this.brain = new BotBrain(this, combat, paths);
    }

    public void reload(ConfigurationSection c) {
        groups.clear();
        if (c == null) return;
        SkillTier.load(c.getConfigurationSection("tiers"));
        maxTotal = Math.max(1, c.getInt("max-total", 1000));
        lodRange = c.getDouble("lod-range", 64);
        regenPerSecond = c.getDouble("natural-regen-per-second", 0.5);
        joinMessages = c.getBoolean("immersion.join-messages", true);
        leaveMessages = c.getBoolean("immersion.leave-messages", true);
        deathMessages = c.getBoolean("immersion.death-messages", true);
        chatChance = c.getDouble("immersion.chat-chance-multiplier", 1.0);
        collide = c.getBoolean("collide", false);
        dropItems = c.getBoolean("drop-items", false);
        brain.setAggroRange(c.getDouble("aggro-range", 40));
        paths.configure(c.getInt("pathfinding.node-budget-per-tick", 4000), c.getInt("pathfinding.max-nodes", 600));
        ConfigurationSection gs = c.getConfigurationSection("groups");
        if (gs != null) {
            for (String id : gs.getKeys(false)) {
                ConfigurationSection g = gs.getConfigurationSection(id);
                if (g != null) groups.put(id.toLowerCase(Locale.ROOT), BotGroup.load(id.toLowerCase(Locale.ROOT), g));
            }
        }
        if (!groups.containsKey("random")) groups.put("random", new BotGroup("random"));
    }

    // ------------------------------------------------------------------ registry

    public Map<String, BotGroup> groups() {
        return groups;
    }

    public BotGroup group(String id) {
        return id == null ? null : groups.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Bot> all() {
        return bots;
    }

    public int count() {
        return bots.size();
    }

    public int maxTotal() {
        return maxTotal;
    }

    public Set<String> takenNames() {
        return takenNames;
    }

    public Bot bot(Entity e) {
        return e == null ? null : byEntity.get(e.getUniqueId());
    }

    public boolean isBot(Entity e) {
        return e != null && byEntity.containsKey(e.getUniqueId());
    }

    public BotCombat combat() {
        return combat;
    }

    void register(Bot b) {
        bots.add(b);
        byEntity.put(b.body.getUniqueId(), b);
        takenNames.add(b.plainName);
        plugin.tabList().add(b);
        b.lastX = b.body.getLocation().getX();
        b.lastZ = b.body.getLocation().getZ();
    }

    int nextId() {
        return ++nextId;
    }

    public Squad squad(String id) {
        return squads.computeIfAbsent(id, Squad::new);
    }

    public Map<String, Squad> squads() {
        return squads;
    }

    /** Removes a bot (death, clear, unload). {@code announce} sends the fake leave message. */
    public void remove(Bot b, boolean announce) {
        if (b.removed) return;
        b.removed = true;
        byEntity.remove(b.body.getUniqueId());
        takenNames.remove(b.plainName);
        plugin.tabList().remove(b);
        if (b.squad != null) b.squad.members.remove(b);
        plugin.teams().removeEntity(b.team, b.body);
        if (announce && leaveMessages) plugin.fakeChat().broadcast(plugin.fakeChat().leaveLine(b.name));
        plugin.tracker().remove(b.body);
    }

    /** Removes every bot matching the filter. Returns how many. */
    public int clear(Predicate<Bot> filter, boolean announce) {
        int n = 0;
        for (Bot b : new ArrayList<>(bots)) {
            if (!b.removed && filter.test(b)) {
                remove(b, announce);
                n++;
            }
        }
        compact();
        return n;
    }

    public void removeAll() {
        plugin.tabList().clearAll();
        for (Bot b : bots) {
            b.removed = true;
            plugin.tracker().remove(b.body);
        }
        bots.clear();
        byEntity.clear();
        takenNames.clear();
        squads.clear();
        pearls.clear();
    }

    private void compact() {
        bots.removeIf(b -> b.removed);
    }

    public void trackPearl(EnderPearl p, Bot b) {
        pearls.put(p.getUniqueId(), b);
    }

    public Bot pearlOwner(Entity projectile) {
        return pearls.remove(projectile.getUniqueId());
    }

    // ------------------------------------------------------------------ enemies

    public boolean isEnemy(Bot b, LivingEntity e) {
        if (e == null || e == b.body || !e.isValid() || e.isDead()) return false;
        Bot o = byEntity.get(e.getUniqueId());
        if (o != null) return !o.team.equalsIgnoreCase(b.team);
        if (e instanceof Player p) {
            GameMode gm = p.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
            return plugin.teams().botHostileTo(b.team, p);
        }
        return b.forcedTarget == e;
    }

    /** Nearest enemy within range (skilled bots prefer a low-health target that's close). */
    public LivingEntity findTarget(Bot b, Location at, double range) {
        scratch.clear();
        grid.query(at.getWorld(), at.getX(), at.getZ(), range, scratch);
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE, r2 = range * range;
        boolean picky = b.tier.skill() > 0.6;
        for (int i = 0, n = scratch.size(); i < n; i++) {
            LivingEntity e = scratch.get(i);
            if (!isEnemy(b, e)) continue;
            e.getLocation(loc2);
            double dx = loc2.getX() - at.getX(), dy = loc2.getY() - at.getY(), dz = loc2.getZ() - at.getZ();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;
            double score = d2;
            if (picky && d2 < 64) score *= 0.5 + e.getHealth() / 40.0;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    public void enemiesNear(Bot b, World w, double x, double y, double z, double range, List<LivingEntity> out) {
        scratch.clear();
        grid.query(w, x, z, range, scratch);
        double r2 = range * range;
        for (int i = 0, n = scratch.size(); i < n; i++) {
            LivingEntity e = scratch.get(i);
            if (!isEnemy(b, e)) continue;
            e.getLocation(loc2);
            double dx = loc2.getX() - x, dz = loc2.getZ() - z;
            if (dx * dx + dz * dz <= r2) out.add(e);
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick(int now) {
        if (bots.isEmpty()) return;
        if (now % 5 == 0) rebuildGrid();
        if (now % 10 == 0) {
            for (Squad s : squads.values()) s.plan(this, now);
            squads.values().removeIf(s -> !s.alive());
        }
        boolean regen = now % 20 == 0 && regenPerSecond > 0;
        boolean removedAny = false;
        for (int i = 0, n = bots.size(); i < n; i++) {
            Bot b = bots.get(i);
            if (b.removed) {
                removedAny = true;
                continue;
            }
            if (!b.body.isValid()) {
                remove(b, false);
                removedAny = true;
                continue;
            }
            if (!b.active && ((now + b.id) & 3) != 0) continue;
            try {
                brain.tick(b, now);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Bot " + b.plainName + " tick error: " + ex);
            }
            if (regen && b.mode != PvpMode.UHC && now - b.lastHurt > 80) heal(b, regenPerSecond);
            if (regen && !b.body.hasPotionEffect(org.bukkit.potion.PotionEffectType.LUCK)) BotSpawner.keepTicking(b.body);
        }
        if (removedAny) compact();
    }

    private static void heal(Bot b, double amount) {
        AttributeInstance a = b.body.getAttribute(Attribute.MAX_HEALTH);
        double max = a == null ? 20 : a.getValue();
        if (b.body.getHealth() < max) b.body.setHealth(Math.min(max, b.body.getHealth() + amount));
    }

    private void rebuildGrid() {
        grid.clear();
        for (Bot b : bots) {
            if (b.removed || !b.body.isValid()) continue;
            b.body.getLocation(loc);
            grid.add(b.body, loc.getWorld(), loc.getX(), loc.getZ());
        }
        double lod2 = lodRange * lodRange;
        for (Bot b : bots) b.active = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getLocation(loc);
            GameMode gm = p.getGameMode();
            if (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) grid.add(p, loc.getWorld(), loc.getX(), loc.getZ());
            for (Bot b : bots) {
                if (b.active || b.removed) continue;
                b.body.getLocation(loc2);
                if (loc2.getWorld() != loc.getWorld()) continue;
                double dx = loc2.getX() - loc.getX(), dz = loc2.getZ() - loc.getZ();
                if (dx * dx + dz * dz < lod2) b.active = true;
            }
        }
    }

    @Override
    public String name() {
        return "bots";
    }
}
