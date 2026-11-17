package dev.unstablesmp.ability.effects;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.combat.DamageService;
import dev.unstablesmp.core.DisplayFactory;
import dev.unstablesmp.core.Transforms;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Cindercrest: Ember Crest. Either follows the caster leaving burning tiles where they walk, or (wave mode) races
 * forward in a line. Tiles are flat full-bright magma displays (no real fire, so it's safe in claims).
 */
public final class EmberTrailEffect extends TimedEffect {
    private static final class Tile {
        final BlockDisplay display;
        final double x, y, z;
        final int until;

        Tile(BlockDisplay display, double x, double y, double z, int until) {
            this.display = display;
            this.x = x;
            this.y = y;
            this.z = z;
            this.until = until;
        }
    }

    private final Player follow;
    private final int duration;
    private final int tileLife;
    private final double damage;
    private final int fireTicks;
    private final int maxTiles;
    private final Material tileBlock;
    private final List<Tile> tiles = new ArrayList<>();
    private final Map<UUID, Integer> hitCooldown = new HashMap<>();
    private final Quaternionf identity = new Quaternionf();
    private final Vector3f v = new Vector3f();
    // wave mode
    private final double wx, wz;
    private double cursorX, cursorY, cursorZ;
    private final int waveLength;
    private double lastX = Double.NaN, lastZ;

    /** Follow mode. */
    public EmberTrailEffect(UnstableSMP plugin, Caster caster, Player follow, int durationTicks, int tileLife,
                            double damage, int fireTicks, int maxTiles, Material tileBlock) {
        super(plugin, caster, follow.getWorld());
        this.follow = follow;
        this.duration = durationTicks;
        this.tileLife = tileLife;
        this.damage = damage;
        this.fireTicks = fireTicks;
        this.maxTiles = maxTiles;
        this.tileBlock = tileBlock;
        this.wx = 0;
        this.wz = 0;
        this.waveLength = 0;
        world.playSound(follow.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.7f);
    }

    /** Wave mode: a line of embers racing from {@code from} along (dirX, dirZ). */
    public EmberTrailEffect(UnstableSMP plugin, Caster caster, Location from, double dirX, double dirZ, int length,
                            int tileLife, double damage, int fireTicks, Material tileBlock) {
        super(plugin, caster, from.getWorld());
        this.follow = null;
        this.duration = length + tileLife;
        this.tileLife = tileLife;
        this.damage = damage;
        this.fireTicks = fireTicks;
        this.maxTiles = length + 2;
        this.tileBlock = tileBlock;
        double l = Math.sqrt(dirX * dirX + dirZ * dirZ);
        this.wx = l < 1e-4 ? 1 : dirX / l;
        this.wz = l < 1e-4 ? 0 : dirZ / l;
        this.waveLength = length;
        this.cursorX = from.getX();
        this.cursorY = from.getY();
        this.cursorZ = from.getZ();
        world.playSound(from, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.6f);
    }

    @Override
    public boolean tick(int now) {
        int age = age(now);
        if (follow != null) {
            boolean present = follow.isValid() && follow.getWorld() == world;
            Location l = present && age < duration && age % 2 == 0 ? follow.getLocation() : null;
            boolean grounded = l != null && !l.getWorld().getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() - 0.2), l.getBlockZ()).isPassable();
            if (grounded) {
                double dx = l.getX() - lastX, dz = l.getZ() - lastZ;
                if (Double.isNaN(lastX) || dx * dx + dz * dz > 0.7) {
                    lastX = l.getX();
                    lastZ = l.getZ();
                    addTile(l.getX(), l.getBlockY(), l.getZ(), now);
                }
            }
        } else if (age < waveLength) {
            cursorX += wx;
            cursorZ += wz;
            Location g = dev.unstablesmp.core.Ground.groundAt(world, cursorX, cursorY + 1, cursorZ);
            if (Math.abs(g.getY() - cursorY) <= 3) {
                cursorY = g.getY();
                addTile(cursorX, (int) Math.floor(cursorY), cursorZ, now);
                if (age % 3 == 0) world.playSound(g, Sound.BLOCK_FIRE_AMBIENT, 1f, 1.2f);
            }
        }
        if (!expire(now) && age >= duration) return false;
        if (tiles.isEmpty()) return true;
        if (age % 4 == 0) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (Tile t : tiles) {
                world.spawnParticle(Particle.FLAME, t.x + rnd.nextDouble(-0.35, 0.35), t.y + 0.1, t.z + rnd.nextDouble(-0.35, 0.35), 1, 0, 0.02, 0, 0.01);
                if (rnd.nextInt(6) == 0) world.spawnParticle(Particle.LAVA, t.x, t.y + 0.1, t.z, 1, 0, 0, 0, 0);
                else world.spawnParticle(Particle.SMALL_FLAME, t.x, t.y + 0.15, t.z, 1, 0.2, 0.05, 0.2, 0.005);
            }
        }
        if (age % 5 == 0) burn(now);
        return true;
    }

    private void addTile(double x, int blockY, double z, int now) {
        if (tiles.size() >= maxTiles || !plugin.tracker().canSpawnDisplays(caster.id(), 1)) return;
        Location at = new Location(world, x, blockY + 0.01, z);
        BlockDisplay d = plugin.displays().block(at, tileBlock, caster.id(), b -> {
            b.setTransformation(Transforms.boxAt(identity, 0, 0.03f, 0, 0.9f, 0.05f, 0.9f, v));
            DisplayFactory.fullBright(b);
        });
        keep(d);
        tiles.add(new Tile(d, x, blockY, z, now + tileLife));
    }

    /** Removes expired tiles; returns true if any remain. */
    private boolean expire(int now) {
        for (int i = tiles.size() - 1; i >= 0; i--) {
            Tile t = tiles.get(i);
            if (now >= t.until) {
                plugin.tracker().remove(t.display);
                entities.remove(t.display);
                tiles.remove(i);
            }
        }
        return !tiles.isEmpty();
    }

    private void burn(int now) {
        Tile first = tiles.get(0), last = tiles.get(tiles.size() - 1);
        double cx = (first.x + last.x) / 2, cy = (first.y + last.y) / 2, cz = (first.z + last.z) / 2;
        double span = Math.sqrt((first.x - last.x) * (first.x - last.x) + (first.z - last.z) * (first.z - last.z)) / 2 + 2;
        for (LivingEntity e : world.getNearbyLivingEntities(new Location(world, cx, cy, cz), span, 4)) {
            if (!plugin.targeting().canHarm(caster, e)) continue;
            Integer cd = hitCooldown.get(e.getUniqueId());
            if (cd != null && cd > now) continue;
            Location l = e.getLocation();
            for (Tile t : tiles) {
                double dx = l.getX() - t.x, dz = l.getZ() - t.z, dy = l.getY() - t.y;
                if (Math.abs(dx) < 0.8 && Math.abs(dz) < 0.8 && dy >= -0.2 && dy < 1.4) {
                    e.setFireTicks(Math.max(e.getFireTicks(), fireTicks));
                    plugin.damage().deal(e, damage, caster, DamageService.Kind.FIRE, "ember_crest");
                    hitCooldown.put(e.getUniqueId(), now + 10);
                    break;
                }
            }
        }
    }
}
