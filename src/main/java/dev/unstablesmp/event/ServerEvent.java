package dev.unstablesmp.event;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import dev.unstablesmp.faction.Faction;
import dev.unstablesmp.faction.Unit;
import dev.unstablesmp.faction.UnitTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Base class for server events: start/end titles and sounds, an Adventure boss-bar countdown, an event-only copy
 * of the faction (hostile to everyone, takes no orders), unit or COMMAND-mode spawning, and full cleanup.
 */
public abstract class ServerEvent {
    protected final UnstableSMP plugin;
    protected final EventType type;
    protected final ConfigurationSection cfg;
    protected final Location center;
    protected final World world;
    protected final String starter;
    protected final Random rnd = new Random();
    protected final Component name;
    protected final int durationTicks;
    protected final List<Entity> spawned = new ArrayList<>();
    protected final Faction faction;
    protected int tick;
    protected boolean closing;
    private boolean finished;
    private int commandCounter;

    private final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private final boolean serverWide;
    private final boolean despawnOnEnd;
    private final boolean restoreWorld;
    private final String spawnMode;
    private final CommandSpawnCapture.Settings commandSettings;
    protected final int maxAlive;
    protected final double targetRange;

    private static int copies;

    protected ServerEvent(UnstableSMP plugin, EventType type, ConfigurationSection cfg, Location center, String starter) {
        this.plugin = plugin;
        this.type = type;
        this.cfg = cfg;
        this.center = center.clone();
        this.world = center.getWorld();
        this.starter = starter;
        this.name = Text.mm(cfg.getString("name", type.configKey));
        this.durationTicks = Math.max(0, cfg.getInt("duration-seconds", 120) * 20);
        this.serverWide = "SERVER".equalsIgnoreCase(plugin.getConfig().getString("events.broadcast-scope", "WORLD"));
        this.despawnOnEnd = cfg.getBoolean("despawn-on-end", true);
        this.restoreWorld = cfg.getBoolean("restore-world-after", true);
        this.spawnMode = cfg.getString("spawn-mode", "FACTION").trim().toUpperCase(Locale.ROOT);
        this.maxAlive = Math.max(0, cfg.getInt("max-alive", 40));
        this.targetRange = cfg.getDouble("target-range", 64);
        this.commandSettings = new CommandSpawnCapture.Settings(
                cfg.getString("command.command", ""),
                "PLAYER".equalsIgnoreCase(cfg.getString("command.run-as", "CONSOLE")),
                cfg.getBoolean("command.pull-spawned-to-spawn-point", true),
                Math.max(0, cfg.getInt("command.capture-window-ticks", 5)));
        Faction base = plugin.factions().get(cfg.getString("faction", "null"));
        if (base != null) {
            faction = base.eventCopy(base.id + "-event-" + (++copies));
            faction.build = cfg.getBoolean("units-build", true);
            faction.breakBlocks = cfg.getBoolean("units-break", true);
        } else {
            faction = null;
            plugin.getLogger().warning("Event " + type.configKey + ": unknown faction '" + cfg.getString("faction") + "'");
        }
        this.bar = BossBar.bossBar(name, 1f, color(cfg.getString("bossbar-color", "PURPLE")), BossBar.Overlay.PROGRESS);
    }

    private static BossBar.Color color(String s) {
        try {
            return BossBar.Color.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BossBar.Color.PURPLE;
        }
    }

    public EventType type() {
        return type;
    }

    public Location center() {
        return center;
    }

    public int alive() {
        Iterator<Entity> it = spawned.iterator();
        while (it.hasNext()) {
            if (!it.next().isValid()) it.remove();
        }
        return spawned.size();
    }

    public Component displayName() {
        return name;
    }

    protected Collection<? extends Player> audience() {
        return serverWide ? Bukkit.getOnlinePlayers() : world.getPlayers();
    }

    // ------------------------------------------------------------------ lifecycle

    final void start() {
        Title title = Title.title(name, plugin.messages().get("event.start-subtitle", Messages.p("starter", starter)),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(3000), Duration.ofMillis(800)));
        Component chat = plugin.messages().get("event.start", Messages.c("event", name),
                Messages.p("x", Integer.toString(center.getBlockX())), Messages.p("z", Integer.toString(center.getBlockZ())));
        for (Player p : audience()) {
            p.showTitle(title);
            p.sendMessage(chat);
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 0.7f);
            p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 0.6f);
        }
        onStart();
    }

    final void tick(int now) {
        if (finished) return;
        tick++;
        if (!closing) {
            if (durationTicks > 0 && tick >= durationTicks) {
                closing = true;
                onBeginClose();
            } else {
                onRunning();
            }
        }
        if (closing && onClosing()) {
            finish(true);
            return;
        }
        if (tick % 10 == 0) updateBar();
    }

    private void updateBar() {
        int left = durationTicks > 0 ? Math.max(0, durationTicks - tick) / 20 : 0;
        bar.progress(durationTicks > 0 ? Math.max(0f, Math.min(1f, 1f - tick / (float) durationTicks)) : 1f);
        bar.name(plugin.messages().get(closing ? "event.bossbar-closing" : "event.bossbar", Messages.c("event", name),
                Messages.p("time", String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)),
                Messages.p("alive", Integer.toString(alive()))));
        double range = plugin.getConfig().getDouble("events.bossbar-range", 0);
        double rSq = range * range;
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean show = (serverWide || p.getWorld() == world) && (range <= 0 || (p.getWorld() == world && p.getLocation().distanceSquared(center) <= rSq));
            if (show && viewers.add(p.getUniqueId())) p.showBossBar(bar);
            else if (!show && viewers.remove(p.getUniqueId())) p.hideBossBar(bar);
        }
    }

    /** Stops immediately (command, GUI or plugin disable). */
    public final void stopNow() {
        finish(false);
    }

    /** Asks the event to start its closing sequence. */
    public final void requestClose() {
        if (!closing) {
            closing = true;
            onBeginClose();
        }
    }

    private void finish(boolean natural) {
        if (finished) return;
        finished = true;
        onFinish(natural);
        plugin.commandCapture().release(spawned);
        if (despawnOnEnd) {
            for (Entity e : spawned) {
                if (e instanceof Player || !e.isValid()) continue;
                if (natural) world.spawnParticle(Particle.LARGE_SMOKE, e.getLocation(), 10, 0.2, 0.3, 0.2, 0.02);
                e.remove();
            }
            spawned.clear();
        }
        if (faction != null) {
            if (despawnOnEnd) plugin.units().disband(faction);
            if (restoreWorld) {
                plugin.units().clearBuilds(faction);
                plugin.units().restoreBroken(faction);
            }
        }
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
        viewers.clear();
        if (natural) {
            Title title = Title.title(plugin.messages().get("event.end-title", Messages.c("event", name)),
                    plugin.messages().get("event.end-subtitle"));
            for (Player p : audience()) {
                p.showTitle(title);
                p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6f, 1.2f);
            }
        }
        Bukkit.getServer().sendMessage(plugin.messages().get("event.ended", Messages.c("event", name)));
        plugin.events().ended(this);
    }

    // ------------------------------------------------------------------ hooks

    protected abstract void onStart();

    protected abstract void onRunning();

    protected void onBeginClose() {
    }

    /** Called every tick while closing; return true when fully closed. */
    protected boolean onClosing() {
        return true;
    }

    protected void onFinish(boolean natural) {
    }

    // ------------------------------------------------------------------ helpers

    /** Spawns one army unit (or runs the COMMAND-mode spawn) at a point. */
    protected boolean spawnAt(Location at, boolean flyingOk) {
        if ("COMMAND".equals(spawnMode) && !commandSettings.command().isBlank()) {
            Player t = nearestTarget(at);
            String cmd = CommandSpawnCapture.fill(commandSettings.command(), at, t != null ? t.getName() : starter, starter, ++commandCounter);
            return plugin.commandCapture().dispatch(commandSettings, cmd, at, spawned);
        }
        if (faction == null) return false;
        UnitTemplate t = plugin.factions().template(faction.pickArmy(rnd));
        if (t == null) return false;
        Location where = t.flying && flyingOk ? at : dev.unstablesmp.core.Ground.groundAt(world, at.getX(), at.getY(), at.getZ());
        Unit u = plugin.units().spawn(faction, t, where);
        if (u == null) return false;
        spawned.add(u.mob);
        return true;
    }

    protected boolean spawnLeader(Location at) {
        if (faction == null) return false;
        UnitTemplate t = plugin.factions().template(faction.leaderTemplate);
        if (t == null) return false;
        Location g = dev.unstablesmp.core.Ground.groundAt(world, at.getX(), at.getY(), at.getZ());
        Unit u = plugin.units().spawn(faction, t, g);
        if (u == null) return false;
        spawned.add(u.mob);
        world.playSound(g, Sound.ENTITY_WITHER_SPAWN, 4f, 0.5f);
        Component msg = plugin.messages().get("event.leader-arrives", Messages.c("leader", u.mob.customName() != null ? u.mob.customName() : t.display),
                Messages.c("event", name));
        for (Player p : audience()) p.sendMessage(msg);
        return true;
    }

    protected Player nearestTarget(Location from) {
        Player best = null;
        double bestSq = targetRange * targetRange;
        for (Player p : world.getPlayers()) {
            GameMode gm = p.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) continue;
            double d = p.getLocation().distanceSquared(from);
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    /** Survival/adventure players within {@code radius} of the centre. */
    protected List<Player> playersInside(double radius) {
        List<Player> out = new ArrayList<>();
        double r = radius * radius;
        for (Player p : world.getPlayers()) {
            GameMode gm = p.getGameMode();
            if ((gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) && p.getLocation().distanceSquared(center) <= r) out.add(p);
        }
        return out;
    }
}
