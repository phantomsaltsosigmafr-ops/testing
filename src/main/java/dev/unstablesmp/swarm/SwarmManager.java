package dev.unstablesmp.swarm;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.core.Hitboxes;
import dev.unstablesmp.core.Ticker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Owns every Vexbot swarm. Ticked once per server tick by the shared {@link Ticker}: steering, combat and
 * rendering for all swarms happen here, with reused arrays and no per-tick allocation in the hot loops.
 */
public final class SwarmManager implements Ticker.Tickable {
    private final UnstableSMP plugin;
    private final SwarmRenderer renderer;
    private final SwarmCombat combat;
    final SwarmProjectiles projectiles;
    private final List<Swarm> swarms = new ArrayList<>();
    private final Map<UUID, Swarm> byOwner = new HashMap<>();
    private final List<Vexbot> graveyard = new ArrayList<>();
    SwarmSettings s = new SwarmSettings();

    public SwarmManager(UnstableSMP plugin) {
        this.plugin = plugin;
        this.renderer = new SwarmRenderer(plugin.displays(), plugin.hitboxes());
        this.combat = new SwarmCombat(plugin);
        this.projectiles = new SwarmProjectiles(plugin, this);
        configure(s);
    }

    public void configure(SwarmSettings settings) {
        this.s = settings;
        renderer.configure(settings);
        combat.configure(settings);
    }

    public SwarmSettings settings() {
        return s;
    }

    List<Swarm> all() {
        return swarms;
    }

    public Swarm swarmOf(UUID owner) {
        return byOwner.get(owner);
    }

    public int swarmCount() {
        return swarms.size();
    }

    public int botCount() {
        int n = 0;
        for (Swarm sw : swarms) n += sw.bots.size();
        return n;
    }

    // ================================================================== lifecycle

    /** Summons (or tops up) a player's swarm. Returns the number of bots spawned, or -1 if the display cap stopped it. */
    public int summon(Player p, Caster caster) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        Swarm sw = byOwner.get(p.getUniqueId());
        if (sw == null) {
            sw = new Swarm(caster, false);
            sw.formationSince = now;
            swarms.add(sw);
            byOwner.put(p.getUniqueId(), sw);
        }
        sw.expireAt = s.lifetimeTicks > 0 ? now + s.lifetimeTicks : 0;
        Location l = p.getLocation();
        int missing = s.size - sw.aliveCount();
        int spawned = spawnBots(sw, missing, l.getX(), l.getY() + p.getEyeHeight() + 0.5, l.getZ());
        if (sw.bots.isEmpty()) {
            dismiss(sw, false);
            return -1;
        }
        p.getWorld().playSound(l, Sound.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE, 1f, 1.4f);
        p.getWorld().playSound(l, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.6f);
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, l.getX(), l.getY() + 2, l.getZ(), 80, 1.2, 1.0, 1.2, 0.1);
        return missing > 0 && spawned == 0 ? -1 : spawned;
    }

    /** Spawns up to {@code count} bots at a point. Returns how many were spawned (caps may stop it early). */
    public int spawnBots(Swarm sw, int count, double x, double y, double z) {
        World w = sw.anchored ? sw.anchorWorld : sw.caster.entity() != null ? sw.caster.entity().getWorld() : null;
        if (w == null) return 0;
        int now = org.bukkit.Bukkit.getCurrentTick();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            boolean shard = rnd.nextDouble() < s.shardRatio;
            double ox = rnd.nextDouble(-1.2, 1.2), oy = rnd.nextDouble(-0.4, 0.8), oz = rnd.nextDouble(-1.2, 1.2);
            final Swarm owner = sw;
            final Vexbot[] holder = new Vexbot[1];
            Hitboxes.Target hit = new Hitboxes.Target() {
                @Override
                public boolean canBeHitBy(Player p) {
                    return canDamageBots(owner, p);
                }

                @Override
                public void hit(Player p, double damage) {
                    if (holder[0] != null) damageBot(owner, holder[0], damage, p);
                }
            };
            Vexbot b = renderer.spawn(w, x + ox, y + oy, z + oz, shard, sw.ownerId, hit, plugin.tracker());
            if (b == null) break;
            holder[0] = b;
            b.lastTransformTick = now;
            b.vy = 0.15;
            sw.bots.add(b);
            spawned++;
        }
        if (spawned > 0) sw.reassign = true;
        return spawned;
    }

    /** Creates a swarm anchored at a point (swarm portals). It stays alive while empty until released. */
    public Swarm createAnchored(Caster caster, Location at) {
        Swarm sw = new Swarm(caster, true);
        sw.anchorWorld = at.getWorld();
        sw.anchorX = at.getX();
        sw.anchorY = at.getY();
        sw.anchorZ = at.getZ();
        sw.scratch.setWorld(at.getWorld());
        sw.keepWhenEmpty = true;
        sw.formationSince = org.bukkit.Bukkit.getCurrentTick();
        swarms.add(sw);
        return sw;
    }

    /** Lets an anchored swarm linger for a while, then fade out. */
    public void release(Swarm sw, int lingerTicks) {
        sw.keepWhenEmpty = false;
        sw.expireAt = org.bukkit.Bukkit.getCurrentTick() + Math.max(1, lingerTicks);
    }

    public boolean dismiss(UUID owner) {
        Swarm sw = byOwner.get(owner);
        if (sw == null) return false;
        dismiss(sw, true);
        return true;
    }

    public void dismiss(Swarm sw, boolean effects) {
        swarms.remove(sw);
        if (!sw.anchored && sw.ownerId != null && byOwner.get(sw.ownerId) == sw) byOwner.remove(sw.ownerId);
        int now = org.bukkit.Bukkit.getCurrentTick();
        for (Vexbot b : sw.bots) {
            if (effects && b.root.isValid()) {
                if (b.state != Vexbot.DYING) renderer.startDying(b, now, true);
                graveyard.add(b);
            } else {
                renderer.removeNow(b);
            }
        }
        sw.bots.clear();
    }

    public void removeAll() {
        for (Swarm sw : swarms) {
            for (Vexbot b : sw.bots) renderer.removeNow(b);
            sw.bots.clear();
        }
        for (Vexbot b : graveyard) renderer.removeNow(b);
        graveyard.clear();
        swarms.clear();
        byOwner.clear();
        projectiles.clear();
    }

    // ================================================================== orders

    public void setTarget(Swarm sw, LivingEntity t) {
        combat.setTarget(sw, t, org.bukkit.Bukkit.getCurrentTick());
    }

    public void setFormation(Swarm sw, SwarmFormation f, int now) {
        SwarmFormation old = sw.formation;
        if (old != SwarmFormation.ARROWHEAD_DIVE && old != SwarmFormation.DOME_CAGE) sw.returnFormation = old;
        sw.formation = f;
        sw.formationSince = now;
        sw.reassign = true;
        sw.arrowLaunched = false;
        sw.cageActive = false;
        sw.nextRainDrop = now + 20;
        if (f == SwarmFormation.DOME_CAGE) combat.beginCage(sw, now);
        if (old == SwarmFormation.ARROWHEAD_DIVE) {
            for (Vexbot b : sw.bots) if (b.state == Vexbot.DIVE) b.state = Vexbot.FOLLOW;
        }
    }

    public void recall(Swarm sw) {
        combat.clearTarget(sw);
        setFormation(sw, SwarmFormation.HALO, org.bukkit.Bukkit.getCurrentTick());
        sw.returnFormation = SwarmFormation.HALO;
    }

    /** Sends {@code count} bots to dive at {@code t}. Returns how many dived. */
    public int diveStrike(Swarm sw, LivingEntity t, int count) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        combat.setTarget(sw, t, now);
        combat.validateTarget(sw, now);
        if (sw.target == null) return 0;
        return combat.launchNearest(sw, count, "dive_strike", now);
    }

    /** Whole-swarm arrowhead dive on one target. */
    public boolean arrowhead(Swarm sw, LivingEntity t) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        combat.setTarget(sw, t, now);
        if (sw.target == null) return false;
        setFormation(sw, SwarmFormation.ARROWHEAD_DIVE, now);
        return true;
    }

    /** Silences enemy swarms near a point (Law: Order). Returns how many swarms were affected. */
    public int silenceNear(Caster caster, World w, double x, double y, double z, double radius, int ticks) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        int n = 0;
        double rSq = radius * radius;
        for (Swarm sw : swarms) {
            if (sw.world != w || isFriendly(sw, caster)) continue;
            double dx = sw.ownerX - x, dy = sw.ownerY - y, dz = sw.ownerZ - z;
            double cx = sw.centroidX - x, cy = sw.centroidY - y, cz = sw.centroidZ - z;
            if (dx * dx + dy * dy + dz * dz > rSq && cx * cx + cy * cy + cz * cz > rSq) continue;
            sw.silencedUntil = Math.max(sw.silencedUntil, now + ticks);
            combat.clearTarget(sw);
            n++;
        }
        return n;
    }

    private boolean isFriendly(Swarm sw, Caster other) {
        if (other.id() != null && other.id().equals(sw.ownerId)) return true;
        if (other.faction() != null && other.faction() == sw.caster.faction()) return true;
        return other.faction() != null && sw.ownerId != null && other.faction().members.contains(sw.ownerId);
    }

    boolean canDamageBots(Swarm sw, Player p) {
        if (p.getUniqueId().equals(sw.ownerId)) return false;
        if (p.getGameMode() == GameMode.CREATIVE) return true;
        return plugin.targeting().canHarm(sw.caster, p);
    }

    void damageBot(Swarm sw, Vexbot b, double dmg, Player by) {
        if (b.state == Vexbot.DYING) return;
        b.hp -= dmg;
        World w = sw.world != null ? sw.world : b.root.getWorld();
        w.spawnParticle(Particle.CRIT, b.x, b.y + 0.3, b.z, 6, 0.2, 0.2, 0.2, 0.1);
        w.playSound(b.root.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.9f, 1.2f);
        if (b.hp <= 0) {
            renderer.startDying(b, org.bukkit.Bukkit.getCurrentTick(), true);
            sw.reassign = true;
            if (by != null) plugin.actionBar().flash(by, plugin.messages().get("swarm.bot-destroyed"), 20);
        } else {
            // knock it back a little so hits feel physical
            b.vx += (b.x - (by != null ? by.getLocation().getX() : b.x)) * 0.15;
            b.vz += (b.z - (by != null ? by.getLocation().getZ() : b.z)) * 0.15;
        }
    }

    /** Is player p held in a dome cage and trying to teleport out? */
    public boolean cageBlocksTeleport(Player p, Location to) {
        for (Swarm sw : swarms) if (combat.blocksTeleport(sw, p, to)) return true;
        return false;
    }

    // ================================================================== tick

    @Override
    public void tick(int now) {
        for (int i = swarms.size() - 1; i >= 0; i--) {
            Swarm sw = swarms.get(i);
            if (!tickSwarm(sw, now)) dismiss(sw, true);
        }
        for (int i = graveyard.size() - 1; i >= 0; i--) {
            Vexbot b = graveyard.get(i);
            if (now >= b.stateUntil || !b.root.isValid()) {
                renderer.removeNow(b);
                graveyard.set(i, graveyard.get(graveyard.size() - 1));
                graveyard.remove(graveyard.size() - 1);
            }
        }
        projectiles.tick(now);
    }

    private boolean tickSwarm(Swarm sw, int now) {
        if (sw.expireAt > 0 && now >= sw.expireAt) return false;
        double yawRad;
        if (sw.anchored) {
            sw.world = sw.anchorWorld;
            sw.ownerX = sw.anchorX;
            sw.ownerY = sw.anchorY;
            sw.ownerZ = sw.anchorZ;
            sw.ownerEyeY = sw.anchorY;
            yawRad = 0;
        } else {
            LivingEntity owner = sw.caster.entity();
            if (owner == null || !owner.isValid()) return false;
            owner.getLocation(sw.scratch);
            sw.world = sw.scratch.getWorld();
            sw.ownerX = sw.scratch.getX();
            sw.ownerY = sw.scratch.getY();
            sw.ownerZ = sw.scratch.getZ();
            sw.ownerEyeY = sw.ownerY + owner.getEyeHeight();
            yawRad = Math.toRadians(sw.scratch.getYaw());
        }

        // Drop dead bots (swap-remove).
        List<Vexbot> bots = sw.bots;
        for (int i = bots.size() - 1; i >= 0; i--) {
            Vexbot b = bots.get(i);
            boolean dead = b.state == Vexbot.DYING ? now >= b.stateUntil : !b.root.isValid();
            if (dead) {
                renderer.removeNow(b);
                bots.set(i, bots.get(bots.size() - 1));
                bots.remove(bots.size() - 1);
                sw.reassign = true;
            }
        }
        int n = bots.size();
        if (n == 0) return sw.keepWhenEmpty;

        combat.validateTarget(sw, now);
        boolean silenced = sw.silenced(now);
        SwarmFormation f = silenced ? SwarmFormation.HALO : sw.formation;

        double fx, fz;
        if (sw.target != null) {
            fx = sw.tX - sw.ownerX;
            fz = sw.tZ - sw.ownerZ;
            double len = Math.sqrt(fx * fx + fz * fz);
            if (len < 0.2) {
                fx = -Math.sin(yawRad);
                fz = Math.cos(yawRad);
            } else {
                fx /= len;
                fz /= len;
            }
        } else {
            fx = -Math.sin(yawRad);
            fz = Math.cos(yawRad);
        }
        double ax = sw.ownerX, ay = sw.ownerY, az = sw.ownerZ;
        if (f.targetAnchored && sw.target != null) {
            if (f == SwarmFormation.DOME_CAGE && sw.cageActive) {
                ax = sw.cageX;
                ay = sw.cageY;
                az = sw.cageZ;
            } else {
                ax = sw.tX;
                ay = sw.tY;
                az = sw.tZ;
            }
        }

        sw.ensureCapacity(n);
        f.layout(n, now, ax, ay, az, fx, fz, now - sw.formationSince, s.formations, sw.sx, sw.sy, sw.sz);
        if (sw.reassign) {
            for (int i = 0; i < n; i++) {
                Vexbot b = bots.get(i);
                sw.px[i] = b.x;
                sw.py[i] = b.y;
                sw.pz[i] = b.z;
            }
            SlotAssigner.assign(n, sw.px, sw.py, sw.pz, sw.sx, sw.sy, sw.sz, sw.slotOfBot, sw.taken);
            for (int i = 0; i < n; i++) bots.get(i).slot = sw.slotOfBot[i];
            sw.reassign = false;
        }

        float baseYaw = (float) Math.atan2(fx, fz);
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < n; i++) {
            Vexbot b = bots.get(i);
            if (b.state == Vexbot.DIVE) {
                combat.diveStep(sw, b, now);
            } else if (b.state == Vexbot.FOLLOW) {
                int slot = b.slot >= 0 && b.slot < n ? b.slot : i;
                SwarmSteering.step(b, bots, sw.sx[slot], sw.sy[slot], sw.sz[slot], s.maxSpeed, s, now);
            }
            if (sw.target != null) b.yaw = (float) Math.atan2(sw.tX - b.x, sw.tZ - b.z);
            else b.yaw = (float) ((baseYaw + now * 0.03 + b.phase) % (Math.PI * 2));
            cx += b.x;
            cy += b.y;
            cz += b.z;
        }
        sw.centroidX = cx / n;
        sw.centroidY = cy / n;
        sw.centroidZ = cz / n;
        double rMax = 0;
        for (int i = 0; i < n; i++) {
            Vexbot b = bots.get(i);
            double dx = b.x - sw.centroidX, dy = b.y - sw.centroidY, dz = b.z - sw.centroidZ;
            rMax = Math.max(rMax, dx * dx + dy * dy + dz * dz);
        }
        sw.radiusSq = rMax;

        combat.afterMove(sw, f, now);

        // Owner teleported far away: snap the swarm back instead of flying across the map.
        if (!sw.anchored) {
            double dx = sw.centroidX - sw.ownerX, dz = sw.centroidZ - sw.ownerZ;
            double leash = s.leashRange * 1.5;
            if (dx * dx + dz * dz > leash * leash) snapTo(sw, sw.ownerX, sw.ownerEyeY + 1, sw.ownerZ);
        }

        render(sw, now);
        return true;
    }

    private void snapTo(Swarm sw, double x, double y, double z) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Vexbot b : sw.bots) {
            b.x = x + rnd.nextDouble(-1.5, 1.5);
            b.y = y + rnd.nextDouble(0, 1.5);
            b.z = z + rnd.nextDouble(-1.5, 1.5);
            b.vx = b.vy = b.vz = 0;
            b.sentX = Double.NaN;
            b.state = Vexbot.FOLLOW;
        }
        sw.reassign = true;
    }

    private void render(Swarm sw, int now) {
        List<Vexbot> bots = sw.bots;
        boolean push = now % s.moveInterval == 0;
        sw.scratch.setWorld(sw.world);
        sw.scratch.setYaw(0);
        sw.scratch.setPitch(0);
        World w = sw.world;
        for (int i = 0, n = bots.size(); i < n; i++) {
            Vexbot b = bots.get(i);
            if (b.state == Vexbot.DYING) continue;
            if (push) renderer.push(b, sw.scratch, now);
            int since = now - b.lastTransformTick;
            boolean due;
            if (!b.popped) {
                due = since >= 2;
            } else if (b.shard) {
                due = since >= (b.state == Vexbot.DIVE ? 3 : 8);
            } else {
                float d = Math.abs(wrap(b.yaw - b.lastYaw));
                due = since >= 10 || (d > 0.6f && since >= 3);
            }
            if (due) renderer.transform(b, now);
            if ((now + i) % s.trailEvery == 0) renderer.trail(w, b, i);
        }
        if ((now + (sw.ownerId == null ? 0 : sw.ownerId.hashCode())) % s.ambientSoundEvery == 0 && !bots.isEmpty()) {
            Vexbot b = bots.get(ThreadLocalRandom.current().nextInt(bots.size()));
            sw.scratch.setX(b.x);
            sw.scratch.setY(b.y);
            sw.scratch.setZ(b.z);
            if (ThreadLocalRandom.current().nextBoolean()) {
                w.playSound(sw.scratch, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.5f + ThreadLocalRandom.current().nextFloat() * 0.6f);
            } else {
                w.playSound(sw.scratch, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.6f, 0.8f);
            }
        }
    }

    private static float wrap(float a) {
        return (float) Math.IEEEremainder(a, Math.PI * 2);
    }

    @Override
    public String name() {
        return "swarms";
    }
}
