package dev.unstablesmp.portal;

import dev.unstablesmp.Settings;
import dev.unstablesmp.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * One NULL breach: a warning rumble, then portals tear open one after another and bots pour out of each on a pulse
 * that speeds up and grows over the breach, then everything collapses and the world heals.
 */
public final class Breach {
    public enum Phase { WARNING, ACTIVE, CLOSING, DONE }

    /** Per-breach numbers (config defaults, overridable from the command). */
    public static final class Options {
        public int portals;
        public Settings.IntRange radius;
        public int durationSeconds;
        public int warningSeconds;
        public Settings.IntRange countStart;
        public Settings.IntRange countEnd;
        public int intervalStart;
        public int intervalEnd;
        public int maxTotal;
        public int maxAlive;

        public static Options defaults(Settings s) {
            Options o = new Options();
            o.portals = s.portals;
            o.radius = s.radius;
            o.durationSeconds = s.durationSeconds;
            o.warningSeconds = s.warningSeconds;
            o.countStart = s.countStart;
            o.countEnd = s.countEnd;
            o.intervalStart = s.intervalStart;
            o.intervalEnd = s.intervalEnd;
            o.maxTotal = s.maxTotal;
            o.maxAlive = s.maxAlive;
            return o;
        }
    }

    private final BreachManager mgr;
    private final Settings s;
    public final int id;
    public final World world;
    public final double cx, cy, cz;
    private final UUID starter;
    public final Options o;
    final List<NullPortal> portals = new ArrayList<>();

    private Phase phase = Phase.WARNING;
    private int created;
    private int activeAt;
    private int activeStart;
    private int endAt;
    private int spawned;
    private boolean commandsOk = true;
    private boolean warnedMissing;
    private final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    /** Bots this breach poured out (as far as the capture saw them), for the alive cap and /uss status. */
    private final List<org.bukkit.entity.Entity> poured = new ArrayList<>();

    Breach(BreachManager mgr, Settings s, int id, World world, double cx, double cy, double cz, UUID starter, Options o) {
        this.mgr = mgr;
        this.s = s;
        this.id = id;
        this.world = world;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.starter = starter;
        this.o = o;
        this.bar = BossBar.bossBar(Component.empty(), 1f, s.barColor, s.barOverlay);
    }

    public Phase phase() {
        return phase;
    }

    public int spawned() {
        return spawned;
    }

    void track(org.bukkit.entity.Entity e) {
        poured.add(e);
    }

    /** Poured bots still alive (only those the capture saw spawn). */
    public int alive() {
        poured.removeIf(e -> !e.isValid() || e.isDead());
        return poured.size();
    }

    public List<NullPortal> portals() {
        return portals;
    }

    public boolean live() {
        return phase == Phase.WARNING || phase == Phase.ACTIVE;
    }

    // ------------------------------------------------------------------ setup

    void begin(int now) {
        created = now;
        activeAt = now + o.warningSeconds * 20;
        for (String c : s.commands) commandsOk &= SpawnCommand.available(c);
        refreshViewers();
        if (o.warningSeconds > 0) {
            title("warning-title", "warning-subtitle");
            Fx.play(s.effect("warning-start"), world, cx, cy + 1, cz, 1, 0, 0, 1);
        }
        updateBar(now);
    }

    void addPortal(NullPortal p, int now) {
        portals.add(p);
        if (phase == Phase.ACTIVE) p.openAt = now;
        else if (phase == Phase.WARNING) p.openAt = activeAt + (portals.size() - 1) * s.openStagger;
    }

    /** Called right after starting: warns the starter at once if the spawn command doesn't exist. */
    public void warnIfMissingNow(CommandSender to) {
        warnIfMissing(to);
    }

    /** Tells whoever started it (and the console) that nothing will pour out because the spawn command is missing. */
    void warnIfMissing(CommandSender to) {
        if (commandsOk || warnedMissing) return;
        warnedMissing = true;
        String label = s.commands.isEmpty() ? "(none)" : "/" + SpawnCommand.label(s.commands.get(0));
        Component msg = Text.prefixed(s, "missing-command", Text.ph("command", label));
        if (to != null) to.sendMessage(msg);
        if (!(to instanceof org.bukkit.command.ConsoleCommandSender)) Bukkit.getConsoleSender().sendMessage(msg);
    }

    // ------------------------------------------------------------------ tick

    void tick(int now) {
        if ((now - created) % 10 == 0) refreshViewers();
        switch (phase) {
            case WARNING -> {
                int age = now - created;
                if (age % 10 == 0) {
                    for (NullPortal p : portals) {
                        Location f = p.front();
                        p.play("warning-ground", f.getX(), f.getY(), f.getZ());
                    }
                }
                if (age % 20 == 0) Fx.play(s.effect("warning-pulse"), world, cx, cy + 1, cz, 1, 0, 0, 1);
                if (now >= activeAt) activate(now);
            }
            case ACTIVE -> {
                tickPortals(now);
                pulses(now);
                if (now >= endAt) beginClose(now);
            }
            case CLOSING -> {
                tickPortals(now);
                boolean all = true;
                for (NullPortal p : portals) all &= p.closed();
                if (all) finish();
            }
            case DONE -> { }
        }
        if ((now - created) % 5 == 0) updateBar(now);
    }

    private void tickPortals(int now) {
        for (NullPortal p : portals) p.tick(now);
    }

    /** Runs breach.start-commands / end-commands as the console ({x} {y} {z} {world} {portals} {breach}). */
    private void runCommands(List<String> templates) {
        if (templates.isEmpty()) return;
        var values = java.util.Map.of("x", Integer.toString((int) Math.floor(cx)), "y", Integer.toString((int) Math.floor(cy)),
                "z", Integer.toString((int) Math.floor(cz)), "world", world.getName(),
                "portals", Integer.toString(portals.size()), "breach", Integer.toString(id));
        for (String t : templates) {
            String cmd = SpawnCommand.format(t, values);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                mgr.logOnce("Breach command failed: /" + cmd + " (" + ex + ")");
            }
        }
    }

    private void activate(int now) {
        phase = Phase.ACTIVE;
        runCommands(s.startCommands);
        activeStart = now;
        endAt = now + o.durationSeconds * 20;
        for (int i = 0; i < portals.size(); i++) portals.get(i).openAt = Math.max(now, Math.min(portals.get(i).openAt, now + i * s.openStagger));
        title("breach-title", "breach-subtitle");
        if (!commandsOk) warnIfMissing(starter == null ? null : Bukkit.getPlayer(starter));
    }

    /** 0 at the start of the breach, 1 at the end: drives the pouring ramp. */
    private double progress(int now) {
        return Math.max(0, Math.min(1, (now - activeStart) / (double) Math.max(1, endAt - activeStart)));
    }

    private void pulses(int now) {
        if (!commandsOk || s.commands.isEmpty()) return;
        double t = progress(now);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int alive = o.maxAlive > 0 ? alive() : 0;
        for (NullPortal p : portals) {
            if (!p.open() || now < p.nextPulse) continue;
            int left = o.maxTotal > 0 ? o.maxTotal - spawned : Integer.MAX_VALUE;
            if (left <= 0) return;
            if (o.maxAlive > 0) {
                if (alive >= o.maxAlive) {
                    p.nextPulse = now + 10; // full: wait for some to die, then keep pouring
                    continue;
                }
                left = Math.min(left, o.maxAlive - alive);
            }
            int count = Settings.IntRange.lerp(o.countStart.random(), o.countEnd.random(), t);
            count = Math.max(1, Math.min(count, left));
            pour(p, count, now);
            alive += count;
            int interval = Settings.IntRange.lerp(o.intervalStart, o.intervalEnd, t);
            p.nextPulse = now + Math.max(2, (int) Math.round(interval * r.nextDouble(0.75, 1.25)));
        }
    }

    /** One pulse: run the spawn command(s) at the portal mouth, burst effects, shove what comes out. */
    private void pour(NullPortal p, int count, int now) {
        int[] m = p.mouth();
        double x = m[0] + 0.5, y = m[1], z = m[2] + 0.5;
        Site site = p.site;
        var values = SpawnCommand.values(m[0], m[1], m[2], x, y, z, count, world.getName(), p.index + 1, id, site);
        CommandSender sender = Bukkit.getConsoleSender();
        if (s.runAs == Settings.RunAs.STARTER && starter != null) {
            Player pl = Bukkit.getPlayer(starter);
            if (pl != null) sender = pl;
        }
        Vector push = s.push ? new Vector(site.fx() * s.pushSpeed, s.pushUp, site.fz() * s.pushSpeed) : null;
        mgr.capture().open(world, x, y, z, push, now, s.captureTicks, this);
        for (String template : s.commands) {
            String cmd = SpawnCommand.format(template, values);
            try {
                Bukkit.dispatchCommand(sender, cmd);
            } catch (RuntimeException ex) {
                mgr.logOnce("Spawn command failed: /" + cmd + " (" + ex + ")");
            }
        }
        spawned += count;
        p.spawned += count;
        p.play("pulse", x, y + 0.9, z);
        double sx = p.centreX(), sz = p.centreZ();
        p.play("pulse-surface", sx + site.fx() * 0.3, p.centreY(), sz + site.fz() * 0.3);
    }

    // ------------------------------------------------------------------ ending

    void beginClose(int now) {
        if (phase == Phase.CLOSING || phase == Phase.DONE) return;
        phase = Phase.CLOSING;
        for (int i = 0; i < portals.size(); i++) portals.get(i).close(now + i * s.closeStagger);
    }

    /** Ends the breach: gracefully (portals collapse) or right now (blocks snap back). */
    void stop(int now, boolean immediate) {
        if (immediate) {
            for (NullPortal p : portals) p.restoreNow();
            finish();
            return;
        }
        if (phase == Phase.WARNING) {
            for (NullPortal p : portals) p.restoreNow();
            finish();
            return;
        }
        beginClose(now);
    }

    private void finish() {
        if (phase == Phase.DONE) return;
        boolean wasLive = phase != Phase.WARNING;
        phase = Phase.DONE;
        if (wasLive) runCommands(s.endCommands);
        for (UUID u : viewers) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
        }
        if (wasLive) title("sealed-title", "sealed-subtitle");
        viewers.clear();
    }

    // ------------------------------------------------------------------ players

    private boolean inRange(Player p) {
        if (p.getWorld() != world) return false;
        if (s.broadcastRadius <= 0) return true;
        double dx = p.getX() - cx, dz = p.getZ() - cz;
        return dx * dx + dz * dz <= (double) s.broadcastRadius * s.broadcastRadius;
    }

    private void refreshViewers() {
        if (phase == Phase.DONE) return;
        Set<UUID> now = new HashSet<>();
        for (Player p : world.getPlayers()) {
            if (!inRange(p)) continue;
            now.add(p.getUniqueId());
            if (!viewers.contains(p.getUniqueId())) p.showBossBar(bar);
        }
        for (UUID u : viewers) {
            if (now.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
        }
        viewers.clear();
        viewers.addAll(now);
    }

    private void title(String main, String sub) {
        Title t = Title.title(Text.mm(s.message(main), placeholders()), Text.mm(s.message(sub), placeholders()),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2600), Duration.ofMillis(900)));
        for (Player p : world.getPlayers()) if (inRange(p)) p.showTitle(t);
    }

    private void updateBar(int now) {
        if (phase == Phase.DONE) return;
        float progress;
        String key;
        if (phase == Phase.WARNING) {
            int total = Math.max(1, activeAt - created);
            progress = Math.max(0, Math.min(1, (activeAt - now) / (float) total));
            key = "bossbar-warning";
        } else if (phase == Phase.ACTIVE) {
            progress = Math.max(0, Math.min(1, (endAt - now) / (float) Math.max(1, o.durationSeconds * 20)));
            key = "bossbar-active";
        } else {
            progress = 0;
            key = "bossbar-closing";
        }
        bar.name(Text.mm(s.message(key), placeholders(now)));
        bar.progress(progress);
    }

    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver placeholders() {
        return placeholders(mgr.now());
    }

    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver placeholders(int now) {
        int open = 0;
        for (NullPortal p : portals) if (p.open()) open++;
        int secondsLeft = phase == Phase.WARNING ? Math.max(0, (activeAt - now + 19) / 20)
                : phase == Phase.ACTIVE ? Math.max(0, (endAt - now + 19) / 20) : 0;
        return net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                Text.ph("portals", Integer.toString(portals.size())),
                Text.ph("open", Integer.toString(open)),
                Text.ph("seconds", Integer.toString(secondsLeft)),
                Text.ph("time", String.format("%d:%02d", secondsLeft / 60, secondsLeft % 60)),
                Text.ph("spawned", Integer.toString(spawned)),
                Text.ph("alive", Integer.toString(poured.size())),
                Text.ph("max", o.maxTotal > 0 ? Integer.toString(o.maxTotal) : "∞"));
    }
}
