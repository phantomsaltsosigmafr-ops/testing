package dev.unstablesmp.swarm;

import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;

/**
 * One swarm unit. A cube is a BlockDisplay body (root) with a full-bright face panel and an Interaction hitbox
 * riding it as passengers, so only the root is ever teleported. A shard is a single ItemDisplay blade (+ hitbox).
 */
public final class Vexbot {
    public static final int FOLLOW = 0;
    public static final int DIVE = 1;
    public static final int DYING = 2;

    public final boolean shard;
    public final Display root;
    public final BlockDisplay face;
    public final Interaction hitbox;
    public final float phase;

    public double x, y, z;
    public double vx, vy, vz;
    public double sentX = Double.NaN, sentY, sentZ;
    public int slot = -1;
    public double hp;
    public final double maxHp;
    public int nextHitTick;

    public int state = FOLLOW;
    public int stateUntil;
    public String diveKey = "swarm";
    public boolean diveHit;

    public float yaw;
    public float lastYaw = Float.NaN;
    public int lastTransformTick;
    public boolean popped;

    Vexbot(boolean shard, Display root, BlockDisplay face, Interaction hitbox, double hp, float phase) {
        this.shard = shard;
        this.root = root;
        this.face = face;
        this.hitbox = hitbox;
        this.hp = hp;
        this.maxHp = hp;
        this.phase = phase;
    }

    public boolean alive() {
        return state != DYING && root.isValid();
    }
}
