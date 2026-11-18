package dev.unstablesmp.portal;

import dev.unstablesmp.Settings;
import dev.unstablesmp.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * The NULL portal invasion: dozens of floating nether portals tear open in waves over an area, VexBot's bots pour out
 * of them ({@code vexbot nullportalspawn x y z <n>} at random portals every round), then the portals collapse and the
 * sky is empty again. No boss bar, no titles: just the portals, the sounds and the bots.
 */
public final class Invasion {
    public enum Phase { RUNNING, CLOSING, DONE }

    /** Per-invasion numbers (config defaults, overridable from the command). */
    public static final class Options {
        public int radius;
        public int maxPortals;
        public int perWave;
        public int durationSeconds;
        public double chance;
        public int interval;
        public Settings.IntRange count;
        public int maxAlive;
        public int maxTotal;

        public static Options defaults(Settings s) {
            Options o = new Options();
            o.radius = s.radius;
            o.maxPortals = s.maxPortals;
            o.perWave = s.portalsPerWave;
            o.durationSeconds = s.durationSeconds;
            o.chance = s.chance;
            o.interval = s.spawnInterval;
            o.count = s.count;
            o.maxAlive = s.maxAlive;
            o.maxTotal = s.maxTotal;
            return o;
        }
    }

    private final InvasionManager mgr;
    private final Plugin plugin;
    private final Settings s;
    public final int id;
    public final World world;
    public final double cx, cy, cz;
    private final UUID starter;
    public final Options o;
    private final List<FloatingPortal> portals = new ArrayList<>();
    private final List<Entity> poured = new ArrayList<>();
    private final Set<Chunk> chunks = new HashSet<>();

    private Phase phase = Phase.RUNNING;
    private int age;
    private int spawned;
    private boolean leaderDone;
    private int quietUntil;
    private boolean commandsOk = true;
    private boolean warnedMissing;

    Invasion(InvasionManager mgr, Plugin plugin, Settings s, int id, World world, double cx, double cy, double cz,
             UUID starter, Options o) {
        this.mgr = mgr;
        this.plugin = plugin;
        this.s = s;
        this.id = id;
        this.world = world;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.starter = starter;
        this.o = o;
    }

    public Phase phase() {
        return phase;
    }

    public int portalCount() {
        return portals.size();
    }

    public int spawned() {
        return spawned;
    }

    public boolean live() {
        return phase == Phase.RUNNING;
    }

    void track(Entity e) {
        poured.add(e);
        mgr.trackPoured(e);
    }

    /** Poured bots still alive (those the capture saw spawn). */
    public int alive() {
        poured.removeIf(e -> !e.isValid() || e.isDead());
        return poured.size();
    }

    // ------------------------------------------------------------------ start

    void begin() {
        if (s.loadChunks) {
            int r = (o.radius >> 4) + 1;
            int ccx = (int) Math.floor(cx) >> 4, ccz = (int) Math.floor(cz) >> 4;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    Chunk c = world.getChunkAt(ccx + dx, ccz + dz);
                    if (chunks.add(c)) c.addPluginChunkTicket(plugin);
                }
            }
        }
        for (String c : s.commands) commandsOk &= SpawnCommand.available(c);
        Fx.play(s.effect("start"), world, cx, cy, cz, 1, 0, 0, 1);
        runCommands(s.startCommands, cx, cy, cz);
    }

    /** Warns the starter (and the console) once if the spawn command doesn't exist, so nothing would pour out. */
    public void warnIfMissing(CommandSender to) {
        if (commandsOk || warnedMissing) return;
        warnedMissing = true;
        String label = s.commands.isEmpty() ? "(none)" : "/" + SpawnCommand.label(s.commands.get(0));
        Component msg = Text.prefixed(s, "missing-command", Text.ph("command", label));
        if (to != null) to.sendMessage(msg);
        if (!(to instanceof ConsoleCommandSender)) Bukkit.getConsoleSender().sendMessage(msg);
    }

    // ------------------------------------------------------------------ tick

    void tick() {
        age++;
        switch (phase) {
            case RUNNING -> running();
            case CLOSING -> closing();
            case DONE -> { }
        }
    }

    private void running() {
        if (portals.size() < o.maxPortals && age % s.waveInterval == 0) {
            for (int i = 0; i < o.perWave && portals.size() < o.maxPortals; i++) openRandomPortal();
        }
        if (age % o.interval == 0) spawnRound();
        int durationTicks = o.durationSeconds * 20;
        if (!leaderDone && !portals.isEmpty() && age >= durationTicks * s.leaderAtPercent / 100) leader();
        if (age % 4 == 0) for (FloatingPortal p : portals) play("ambient", p, p.centreX(), p.centreY(), p.centreZ());
        if (age % 40 == 0 && !portals.isEmpty()) {
            FloatingPortal p = portals.get(ThreadLocalRandom.current().nextInt(portals.size()));
            play("ambient-sound", p, p.centreX(), p.centreY(), p.centreZ());
        }
        if (age >= durationTicks) beginClose();
    }

    private void openRandomPortal() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double minSq = (double) s.minSpacing * s.minSpacing;
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(r.nextDouble()) * o.radius;
            int x = (int) Math.floor(cx + Math.cos(ang) * dist);
            int z = (int) Math.floor(cz + Math.sin(ang) * dist);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int ground = world.getEnvironment() == World.Environment.NETHER
                    ? netherGround(x, (int) Math.floor(cy), z) : world.getHighestBlockYAt(x, z);
            if (ground == Integer.MIN_VALUE) continue;
            int y = ground + 1 + s.minHeight + r.nextInt(s.maxHeight - s.minHeight + 1);
            boolean tooClose = false;
            for (FloatingPortal p : portals) {
                double dx = p.centreX() - x, dy = p.centreY() - y, dz = p.centreZ() - z;
                if (dx * dx + dy * dy + dz * dz < minSq) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;
            FloatingPortal p = FloatingPortal.tryBuild(world, x, y, z, r.nextBoolean(), s, mgr.journal());
            if (p == null) continue;
            portals.add(p);
            play("portal-open", p, p.centreX(), p.centreY(), p.centreZ());
            return;
        }
    }

    /** In the Nether (roof overhead) look down from the centre height for open floor. */
    private int netherGround(int x, int nearY, int z) {
        for (int y = Math.min(world.getMaxHeight() - 4, nearY + 8); y > world.getMinHeight() + 1; y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid() && world.getBlockAt(x, y + 1, z).getType().isAir()
                    && world.getBlockAt(x, y + 2, z).getType().isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** The portals stop pouring for a while (after a Totem of Null wipes the NULL out). */
    public void quiet(int ticks) {
        quietUntil = Math.max(quietUntil, age + ticks);
    }

    private void spawnRound() {
        if (portals.isEmpty() || !commandsOk || s.commands.isEmpty() || age < quietUntil) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int alive = o.maxAlive > 0 ? alive() : 0;
        List<FloatingPortal> order = new ArrayList<>(portals);
        Collections.shuffle(order, r);
        for (FloatingPortal p : order) {
            int left = o.maxTotal > 0 ? o.maxTotal - spawned : Integer.MAX_VALUE;
            if (o.maxAlive > 0) left = Math.min(left, o.maxAlive - alive);
            if (left <= 0) return;
            if (r.nextDouble() >= o.chance) continue;
            int n = Math.max(1, Math.min(o.count.random(), left));
            pour(p, n, s.commands);
            alive += n;
        }
    }

    /** Runs the spawn command(s) at a portal's opening and shoves whatever comes out toward the centre. */
    private void pour(FloatingPortal p, int count, List<String> templates) {
        int[] b = p.spawnBlock();
        double x = b[0] + 0.5, y = b[1], z = b[2] + 0.5;
        int side = p.sideToward(cx, cz);
        String facing = p.alongX ? (side > 0 ? "south" : "north") : (side > 0 ? "east" : "west");
        Map<String, String> values = SpawnCommand.values(b[0], b[1], b[2], x, y, z, count, world.getName(),
                portals.indexOf(p) + 1, id, facing);
        CommandSender sender = Bukkit.getConsoleSender();
        if (s.runAs == Settings.RunAs.STARTER && starter != null) {
            Player pl = Bukkit.getPlayer(starter);
            if (pl != null) sender = pl;
        }
        Vector push = s.push ? new Vector(p.nx() * side * s.pushSpeed, s.pushUp, p.nz() * side * s.pushSpeed) : null;
        mgr.capture().open(world, x, y, z, push, mgr.now(), s.captureTicks, this);
        for (String t : templates) {
            String cmd = SpawnCommand.format(t, values);
            try {
                Bukkit.dispatchCommand(sender, cmd);
            } catch (RuntimeException ex) {
                mgr.logOnce("Spawn command failed: /" + cmd + " (" + ex + ")");
            }
        }
        spawned += count;
        play("spawn", p, p.centreX(), p.centreY(), p.centreZ());
    }

    /** Once per invasion, part-way through: the leader comes through a random portal (leader.commands). */
    private void leader() {
        leaderDone = true;
        if (s.leaderCommands.isEmpty()) return;
        FloatingPortal p = portals.get(ThreadLocalRandom.current().nextInt(portals.size()));
        play("leader", p, p.centreX(), p.centreY(), p.centreZ());
        pour(p, 1, s.leaderCommands);
    }

    // ------------------------------------------------------------------ closing

    void beginClose() {
        if (phase != Phase.RUNNING) return;
        phase = Phase.CLOSING;
        Fx.play(s.effect("closing"), world, cx, cy, cz, 1, 0, 0, 1);
    }

    private void closing() {
        if (age % 2 == 0) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < s.closePerStep && !portals.isEmpty(); i++) {
                FloatingPortal p = portals.remove(r.nextInt(portals.size()));
                p.destroy(mgr.journal());
                play("portal-close", p, p.centreX(), p.centreY(), p.centreZ());
            }
        }
        if (portals.isEmpty()) finish();
    }

    /** Ends the invasion: portals collapse a few at a time, or all vanish now. */
    void stop(boolean immediate) {
        if (!immediate) {
            beginClose();
            return;
        }
        for (FloatingPortal p : portals) p.destroy(mgr.journal());
        portals.clear();
        finish();
    }

    private void finish() {
        if (phase == Phase.DONE) return;
        phase = Phase.DONE;
        runCommands(s.endCommands, cx, cy, cz);
        for (Chunk c : chunks) c.removePluginChunkTicket(plugin);
        chunks.clear();
    }

    // ------------------------------------------------------------------ helpers

    private void play(String effect, FloatingPortal p, double x, double y, double z) {
        Fx.play(s.effect(effect), world, x, y, z, p.ax(), p.az(), p.nx(), p.nz());
    }

    /** Console commands with {x} {y} {z} {world} {portals} {invasion} filled in. */
    private void runCommands(List<String> templates, double x, double y, double z) {
        if (templates.isEmpty()) return;
        Map<String, String> values = Map.of("x", Integer.toString((int) Math.floor(x)), "y", Integer.toString((int) Math.floor(y)),
                "z", Integer.toString((int) Math.floor(z)), "world", world.getName(),
                "portals", Integer.toString(portals.size()), "invasion", Integer.toString(id), "breach", Integer.toString(id));
        for (String t : templates) {
            String cmd = SpawnCommand.format(t, values);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                mgr.logOnce("Invasion command failed: /" + cmd + " (" + ex + ")");
            }
        }
    }
}
