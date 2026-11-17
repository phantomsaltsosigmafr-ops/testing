package dev.unstablesmp.swarm;

import dev.unstablesmp.combat.Caster;
import java.util.ArrayList;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;

/** One swarm: a player's main swarm, or a temporary swarm poured out of a portal. */
public final class Swarm {
    public final Caster caster;
    public final UUID ownerId;
    /** Portal swarms are anchored to a point instead of following their owner. */
    public final boolean anchored;
    public final ArrayList<Vexbot> bots = new ArrayList<>();

    public SwarmFormation formation = SwarmFormation.HALO;
    SwarmFormation returnFormation = SwarmFormation.HALO;
    int formationSince;
    boolean reassign = true;

    LivingEntity target;
    int targetSeen;
    /** Target feet position and height, cached once per tick. */
    double tX, tY, tZ, tH;
    final Location targetScratch = new Location(null, 0, 0, 0);

    // Dome cage
    double cageX, cageY, cageZ;
    boolean cageActive;
    int cageUntil;

    // Arrowhead dive / rain / harass bookkeeping
    boolean arrowLaunched;
    int arrowLaunchTick;
    int rainCursor;
    int nextRainDrop;
    int nextHarass;

    int silencedUntil;
    int expireAt;
    boolean keepWhenEmpty;

    World anchorWorld;
    double anchorX, anchorY, anchorZ;

    // Owner/centre snapshot for this tick
    World world;
    double ownerX, ownerY, ownerZ, ownerEyeY;
    double centroidX, centroidY, centroidZ, radiusSq;

    // Pending aggregated damage
    double pending;
    String pendingKey = "swarm";
    int pendingPriority;

    // Reused arrays
    double[] sx = new double[0], sy = new double[0], sz = new double[0];
    double[] px = new double[0], py = new double[0], pz = new double[0];
    int[] slotOfBot = new int[0];
    boolean[] taken = new boolean[0];
    final Location scratch = new Location(null, 0, 0, 0);

    Swarm(Caster caster, boolean anchored) {
        this.caster = caster;
        this.ownerId = caster.id();
        this.anchored = anchored;
    }

    void ensureCapacity(int n) {
        if (sx.length >= n) return;
        int cap = Math.max(n, sx.length * 2);
        sx = new double[cap];
        sy = new double[cap];
        sz = new double[cap];
        px = new double[cap];
        py = new double[cap];
        pz = new double[cap];
        slotOfBot = new int[cap];
        taken = new boolean[cap];
    }

    public LivingEntity target() {
        return target;
    }

    public int aliveCount() {
        int n = 0;
        for (Vexbot b : bots) if (b.state != Vexbot.DYING) n++;
        return n;
    }

    public boolean silenced(int now) {
        return now < silencedUntil;
    }

    void addDamage(double amount, String key, int priority) {
        pending += amount;
        if (priority >= pendingPriority) {
            pendingPriority = priority;
            pendingKey = key;
        }
    }
}
