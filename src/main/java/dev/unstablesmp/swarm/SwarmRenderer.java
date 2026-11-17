package dev.unstablesmp.swarm;

import dev.unstablesmp.core.DisplayFactory;
import dev.unstablesmp.core.EntityTracker;
import dev.unstablesmp.core.Fx;
import dev.unstablesmp.core.Hitboxes;
import dev.unstablesmp.core.Transforms;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Spawns Vexbot display stacks and pushes positions / transformations to clients. */
final class SwarmRenderer {
    private static final int TRANSFORM_TICKS = 10;
    private final DisplayFactory factory;
    private final Hitboxes hitboxes;
    private SwarmSettings s;
    private ItemStack shardStack;

    private final Quaternionf q = new Quaternionf();
    private final Vector3f v1 = new Vector3f();
    private final Vector3f v2 = new Vector3f();
    private final Particle.DustOptions faceDust = new Particle.DustOptions(org.bukkit.Color.fromRGB(0xB14CFF), 0.8f);

    SwarmRenderer(DisplayFactory factory, Hitboxes hitboxes) {
        this.factory = factory;
        this.hitboxes = hitboxes;
    }

    void configure(SwarmSettings settings) {
        this.s = settings;
        this.shardStack = new ItemStack(settings.shardItem);
    }

    int entityCost(boolean shard) {
        return (shard ? 1 : 2) + (s.hitboxes ? 1 : 0);
    }

    Vexbot spawn(World w, double x, double y, double z, boolean shard, UUID owner, Hitboxes.Target target, EntityTracker tracker) {
        if (!tracker.canSpawnDisplays(owner, entityCost(shard))) return null;
        Location at = new Location(w, x, y, z);
        int dur = s.moveInterval + 1;
        Display root;
        BlockDisplay face = null;
        if (shard) {
            ItemDisplay d = factory.item(at, shardStack, owner, it -> {
                it.setTeleportDuration(dur);
                it.setInterpolationDuration(8);
                it.setTransformation(Transforms.collapsed(0, 0, 0));
                if (s.glow) DisplayFactory.glow(it, s.glowColor);
            });
            root = d;
        } else {
            BlockDisplay body = factory.block(at, s.bodyBlock, owner, b -> {
                b.setTeleportDuration(dur);
                b.setInterpolationDuration(8);
                b.setTransformation(Transforms.collapsed(0, s.cubeScale / 2, 0));
                if (s.glow) DisplayFactory.glow(b, s.glowColor);
            });
            face = factory.block(at, s.faceBlock, owner, b -> {
                b.setInterpolationDuration(8);
                b.setTransformation(Transforms.collapsed(0, s.cubeScale / 2, 0));
                DisplayFactory.fullBright(b);
            });
            body.addPassenger(face);
            root = body;
        }
        Interaction hb = null;
        if (s.hitboxes) {
            float size = shard ? s.shardScale * 0.6f : s.cubeScale * 1.15f;
            hb = factory.hitbox(at, size, size, owner);
            root.addPassenger(hb);
            hitboxes.register(hb, target);
        }
        float phase = (float) (ThreadLocalRandom.current().nextDouble() * Math.PI * 2);
        Vexbot b = new Vexbot(shard, root, face, hb, shard ? s.shardHp : s.cubeHp, phase);
        b.x = x;
        b.y = y;
        b.z = z;
        return b;
    }

    /** Sends a teleport if the bot moved; passengers (face, hitbox) ride along. */
    void push(Vexbot b, Location scratch, int now) {
        double bob = b.state == Vexbot.FOLLOW ? 0.12 * Math.sin(now * 0.12 + b.phase) : 0;
        double ty = b.y + bob;
        if (!Double.isNaN(b.sentX)) {
            double dx = b.x - b.sentX, dy = ty - b.sentY, dz = b.z - b.sentZ;
            if (dx * dx + dy * dy + dz * dz < 0.0004) return;
        }
        scratch.setX(b.x);
        scratch.setY(ty);
        scratch.setZ(b.z);
        b.root.teleport(scratch); // passengers (face, hitbox) are retained by default since 1.21.10
        b.sentX = b.x;
        b.sentY = ty;
        b.sentZ = b.z;
    }

    /** Updates rotation/scale with client-side interpolation (spin, face the target, pop-in). */
    void transform(Vexbot b, int now) {
        b.lastTransformTick = now;
        b.lastYaw = b.yaw;
        b.root.setInterpolationDelay(0);
        b.root.setInterpolationDuration(b.popped ? TRANSFORM_TICKS : 8);
        b.popped = true;
        if (b.shard) {
            double dx = b.vx, dy = b.vy, dz = b.vz;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.08) {
                dx = Math.sin(b.yaw) * 0.4;
                dy = -1;
                dz = Math.cos(b.yaw) * 0.4;
                len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            q.rotationTo(0, 1, 0, (float) (dx / len), (float) (dy / len), (float) (dz / len));
            q.rotateZ((float) (Math.PI / 4)); // sword sprites point diagonally; turn the blade onto +Y
            b.root.setTransformation(Transforms.item(0, s.shardScale * 0.3f, 0, q, s.shardScale));
            return;
        }
        float sc = s.cubeScale;
        float tilt = (float) Math.max(-0.45, Math.min(0.45, -b.vy * 0.8));
        q.rotationY(b.yaw).rotateX(tilt);
        b.root.setTransformation(Transforms.boxAt(q, 0, sc / 2, 0, sc, sc, sc, v1));
        if (b.face != null) {
            b.face.setInterpolationDelay(0);
            b.face.setInterpolationDuration(TRANSFORM_TICKS);
            v2.set(0, 0.1f * sc, sc * 0.5f + 0.012f);
            q.transform(v2);
            b.face.setTransformation(Transforms.boxAt(q, v2.x, v2.y + sc / 2, v2.z, sc * 0.68f, sc * 0.26f, 0.03f, v1));
        }
    }

    void trail(World w, Vexbot b, int index) {
        double cy = b.y + (b.shard ? s.shardScale * 0.3 : s.cubeScale * 0.5);
        switch (index % 3) {
            case 0 -> w.spawnParticle(Particle.END_ROD, b.x, cy, b.z, 1, 0.05, 0.05, 0.05, 0.0);
            case 1 -> w.spawnParticle(Particle.DRAGON_BREATH, b.x, cy, b.z, 1, 0.05, 0.05, 0.05, 0.0, 1.0f);
            default -> w.spawnParticle(Particle.REVERSE_PORTAL, b.x, cy, b.z, 2, 0.08, 0.08, 0.08, 0.02);
        }
        if (!b.shard && index % 5 == 0) Fx.dust(w, b.x, cy, b.z, 1, 0.05, faceDust);
    }

    void startDying(Vexbot b, int now, boolean effects) {
        b.state = Vexbot.DYING;
        b.stateUntil = now + 7;
        hitboxes.unregister(b.hitbox);
        if (b.hitbox != null && b.hitbox.isValid()) factory.tracker().remove(b.hitbox);
        if (b.root.isValid()) {
            b.root.setInterpolationDelay(0);
            b.root.setInterpolationDuration(6);
            b.root.setTransformation(Transforms.collapsed(0, s.cubeScale / 2, 0));
        }
        if (b.face != null && b.face.isValid()) {
            b.face.setInterpolationDelay(0);
            b.face.setInterpolationDuration(6);
            b.face.setTransformation(Transforms.collapsed(0, s.cubeScale / 2, 0));
        }
        if (effects && b.root.isValid()) {
            World w = b.root.getWorld();
            w.spawnParticle(Particle.END_ROD, b.x, b.y + 0.3, b.z, 8, 0.2, 0.2, 0.2, 0.08);
            w.spawnParticle(Particle.BLOCK, b.x, b.y + 0.3, b.z, 10, 0.2, 0.2, 0.2, 0.0,
                    b.shard ? Material.AMETHYST_BLOCK.createBlockData() : s.bodyBlock);
            w.playSound(b.root.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.7f, 0.8f + (float) Math.random() * 0.4f);
        }
    }

    void removeNow(Vexbot b) {
        hitboxes.unregister(b.hitbox);
        EntityTracker t = factory.tracker();
        if (b.hitbox != null) t.remove(b.hitbox);
        if (b.face != null) t.remove(b.face);
        t.remove(b.root);
    }
}
