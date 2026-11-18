package dev.unstablesmp.portal;

import dev.unstablesmp.Settings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.EndGateway;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * One portal. Life cycle: WAITING (rumbling site) -> OPENING (frame rises block by block) -> FILLING (the void pours
 * in row by row) -> OPEN (ambient effects, creeping corruption, bots pouring out on each pulse) -> CLOSING (void
 * collapses, frame crumbles, ground heals) -> CLOSED. Every changed block goes through the {@link BlockJournal}.
 */
public final class NullPortal {
    public enum State { WAITING, OPENING, FILLING, OPEN, CLOSING, CLOSED }

    private final Plugin plugin;
    private final Settings s;
    private final BlockJournal journal;
    private final SiteFinder finder;
    public final int index;
    public final World world;
    public final Site site;
    public final PortalShape shape;

    private State state = State.WAITING;
    int openAt = Integer.MAX_VALUE;
    int closeAt = Integer.MAX_VALUE;
    private int stateSince;
    private int step;
    int nextPulse = Integer.MAX_VALUE;
    int spawned;

    private final List<Block> built = new ArrayList<>();
    private final List<Block> surface = new ArrayList<>();
    private final List<Block> corrupted = new ArrayList<>();
    private List<Block> corruptionPlan = List.of();
    private final Set<Chunk> chunks = new HashSet<>();

    NullPortal(Plugin plugin, Settings s, BlockJournal journal, SiteFinder finder, int index, World world, Site site,
               PortalShape shape) {
        this.plugin = plugin;
        this.s = s;
        this.journal = journal;
        this.finder = finder;
        this.index = index;
        this.world = world;
        this.site = site;
        this.shape = shape;
        // Keep the portal's chunks loaded while it exists so blocks restore and spawns land even with nobody near.
        for (int a : new int[] {shape.first - 1, shape.last + 1}) {
            Chunk c = world.getChunkAt(site.worldX(a, 0) >> 4, site.worldZ(a, 0) >> 4);
            if (chunks.add(c)) c.addPluginChunkTicket(plugin);
        }
    }

    public State state() {
        return state;
    }

    public boolean open() {
        return state == State.OPEN;
    }

    public boolean closed() {
        return state == State.CLOSED;
    }

    // ------------------------------------------------------------------ positions

    public double centreX() {
        return site.x() + 0.5 + site.ax() * shape.centreAcross();
    }

    public double centreY() {
        return site.y() + 1 + shape.height / 2.0;
    }

    public double centreZ() {
        return site.z() + 0.5 + site.az() * shape.centreAcross();
    }

    /** Ground point just in front of the portal (warning cracks, corruption origin). */
    public Location front() {
        return new Location(world, centreX() + site.fx() * 1.5, site.y() + 1.05, centreZ() + site.fz() * 1.5);
    }

    /** Block a pulse spawns at: a random opening column (or the centre), {@code depth} blocks out, standing height. */
    public int[] mouth() {
        int col = s.spread ? ThreadLocalRandom.current().nextInt(shape.first, shape.last + 1) : shape.centreColumn();
        return new int[] {site.worldX(col, s.depth), site.y() + 1, site.worldZ(col, s.depth)};
    }

    void play(String effect, double x, double y, double z) {
        Fx.play(s.effect(effect), world, x, y, z, site.ax(), site.az(), site.fx(), site.fz());
    }

    void playCentre(String effect) {
        play(effect, centreX(), centreY(), centreZ());
    }

    // ------------------------------------------------------------------ life cycle

    void tick(int now) {
        switch (state) {
            case WAITING -> {
                if (now >= openAt) {
                    tear();
                    enter(State.OPENING, now);
                }
            }
            case OPENING -> buildFrame(now);
            case FILLING -> fill(now);
            case OPEN -> {
                int age = now - stateSince;
                if (age % 4 == 0) playCentre("ambient");
                if (age % 70 == 0) playCentre("ambient-hum");
                if (age % 3 == 0) corrupt();
                if (now >= closeAt) enter(State.CLOSING, now);
            }
            case CLOSING -> collapse(now);
            case CLOSED -> { }
        }
    }

    private void enter(State next, int now) {
        state = next;
        stateSince = now;
        step = 0;
    }

    /** The moment reality rips: lightning, shockwave, darkness on everyone close. */
    private void tear() {
        Location front = front();
        if (s.lightning) world.strikeLightningEffect(new Location(world, centreX(), site.y() + 1, centreZ()));
        play("tear", centreX(), centreY(), centreZ());
        play("tear-ground", front.getX(), front.getY(), front.getZ());
        if (s.darkness && s.darknessRadius > 0) {
            double r2 = (double) s.darknessRadius * s.darknessRadius;
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(front) <= r2) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 70, 0, false, false, false));
                }
            }
        }
    }

    private void buildFrame(int now) {
        List<PortalShape.Cell> frame = shape.frame;
        int perTick = Math.max(1, (int) Math.ceil(frame.size() / (double) s.buildTicks));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < perTick && step < frame.size(); i++, step++) {
            PortalShape.Cell c = frame.get(step);
            Block b = site.block(world, c.across(), c.up());
            Material m = c.kind() == PortalShape.Kind.CORNER ? s.corner : s.frame.get(r.nextInt(s.frame.size()));
            journal.set(b, m.createBlockData());
            built.add(b);
            if (step % 2 == 0) play("frame-block", b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        }
        if (step >= frame.size()) enter(State.FILLING, now);
    }

    private void fill(int now) {
        if ((now - stateSince) % 2 != 0) return;
        List<List<PortalShape.Cell>> rows = shape.surfaceRows;
        if (step < rows.size()) {
            List<PortalShape.Cell> row = rows.get(step);
            boolean topRow = step == rows.size() - 1;
            for (PortalShape.Cell c : row) {
                Block b = site.block(world, c.across(), c.up());
                journal.set(b, s.surface.createBlockData());
                surface.add(b);
                boolean beam = topRow && (s.beam == Settings.Beam.ALL || (s.beam == Settings.Beam.CENTER && c.across() == shape.centreColumn()));
                tuneGateway(b, beam);
            }
            play("surface-row", centreX(), site.y() + 1.5 + step, centreZ());
            step++;
            return;
        }
        playCentre("open");
        enter(State.OPEN, now);
        nextPulse = now + s.firstPulseDelay;
        planCorruption();
    }

    /** End gateways shoot a beam while "spawning"; only the chosen columns keep it, and none ever teleport. */
    private static void tuneGateway(Block b, boolean beam) {
        if (b.getType() != Material.END_GATEWAY) return;
        BlockState st = b.getState();
        if (st instanceof EndGateway gw) {
            gw.setAge(beam ? 0 : 200);
            gw.setExitLocation(null);
            gw.update(true, false);
        }
    }

    private void planCorruption() {
        if (!s.corruption || s.corruptionMax <= 0) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int rad = s.corruptionRadius;
        double ox = centreX() + site.fx() * 1.5, oz = centreZ() + site.fz() * 1.5;
        List<double[]> scored = new ArrayList<>();
        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > rad + 0.3) continue;
                // denser near the portal, ragged at the edge
                if (r.nextDouble() > 1.0 - (d / (rad + 1)) * 0.65) continue;
                scored.add(new double[] {Math.floor(ox) + dx, Math.floor(oz) + dz, d + r.nextDouble() * 1.8});
            }
        }
        scored.sort((a, b) -> Double.compare(a[2], b[2]));
        List<Block> plan = new ArrayList<>();
        for (double[] p : scored) {
            if (plan.size() >= s.corruptionMax) break;
            Block top = surfaceBlock((int) p[0], (int) p[1]);
            if (top != null) plan.add(top);
        }
        corruptionPlan = plan;
    }

    /** The top solid full block near the portal's ground level at (x, z), or null if it shouldn't be corrupted. */
    private Block surfaceBlock(int x, int z) {
        for (int y = site.y() + 2; y >= site.y() - 3; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (!b.getType().isSolid()) continue;
            if (!b.getRelative(0, 1, 0).isPassable()) return null;
            if (!b.getType().isOccluding() || b.getType().hasGravity() || !finder.replaceable(b)) return null;
            return b;
        }
        return null;
    }

    private void corrupt() {
        if (step >= corruptionPlan.size()) return;
        Block b = corruptionPlan.get(step++);
        if (journal.tracked(b) || !b.getType().isSolid() || !b.getRelative(0, 1, 0).isPassable()) return;
        Material m = s.corruptionBlocks.get(ThreadLocalRandom.current().nextInt(s.corruptionBlocks.size()));
        journal.set(b, m.createBlockData());
        corrupted.add(b);
        play("corrupt", b.getX() + 0.5, b.getY() + 1.05, b.getZ() + 0.5);
    }

    /** Starts the collapse at {@code at} (portals that never opened just vanish). */
    void close(int at) {
        if (state == State.WAITING) {
            restoreNow();
            return;
        }
        closeAt = Math.min(closeAt, at);
        if (state == State.OPENING || state == State.FILLING) closeAt = at; // finish building, then collapse
    }

    private void collapse(int now) {
        int age = now - stateSince;
        // 1) the void drains top row first
        if (!surface.isEmpty()) {
            if (age % 2 != 0) return;
            int rowSize = shape.width;
            for (int i = 0; i < rowSize && !surface.isEmpty(); i++) journal.restore(surface.remove(surface.size() - 1));
            play("surface-collapse", centreX(), site.y() + 1.5 + surface.size() / (double) rowSize, centreZ());
            return;
        }
        // 2) the frame crumbles from the top down
        if (!built.isEmpty()) {
            int perTick = Math.max(1, (int) Math.ceil(shape.frame.size() / (double) Math.max(1, s.buildTicks / 2)));
            for (int i = 0; i < perTick && !built.isEmpty(); i++) {
                Block b = built.remove(built.size() - 1);
                if (i == 0) play("frame-crumble", b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
                journal.restore(b);
            }
            return;
        }
        // 3) the ground heals, last corrupted first
        for (int i = 0; i < 6 && !corrupted.isEmpty(); i++) journal.restore(corrupted.remove(corrupted.size() - 1));
        if (corrupted.isEmpty()) {
            playCentre("sealed");
            finish();
        }
    }

    /** Instantly puts every block back (plugin disable, /uss stop now). */
    void restoreNow() {
        for (int i = surface.size() - 1; i >= 0; i--) journal.restore(surface.get(i));
        for (int i = built.size() - 1; i >= 0; i--) journal.restore(built.get(i));
        for (int i = corrupted.size() - 1; i >= 0; i--) journal.restore(corrupted.get(i));
        surface.clear();
        built.clear();
        corrupted.clear();
        finish();
    }

    private void finish() {
        state = State.CLOSED;
        for (Chunk c : chunks) c.removePluginChunkTicket(plugin);
        chunks.clear();
    }

    /** Whether a block position is inside this portal's opening (protection uses this for gateway teleports). */
    public boolean inOpening(int x, int y, int z) {
        if (y < site.y() + 1 || y > site.y() + shape.height) return false;
        for (int a = shape.first; a <= shape.last; a++) {
            if (site.worldX(a, 0) == x && site.worldZ(a, 0) == z) return true;
        }
        return false;
    }
}
