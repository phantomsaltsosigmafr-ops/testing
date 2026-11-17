package dev.unstablesmp.faction;

import dev.unstablesmp.UnstableSMP;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;

/**
 * Building and breaking: pillar up to targets above, dig down to targets below, bridge gaps, break through walls
 * when stuck, tower over unbreakable walls, and Fortify (raise a wall in front of the formation). Every edit
 * respects the region guard, the hardness cap, the never-break list and event portal blocks; placed blocks are
 * tracked for Clear Builds and event factions log what they break so the world can be restored.
 */
final class Engineer {
    private final UnstableSMP plugin;
    private final UnitManager mgr;

    Engineer(UnstableSMP plugin, UnitManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    void engineer(Unit u, double destX, double destY, double destZ, boolean chasing, int now) {
        Faction f = u.f;
        boolean canBuild = f.build && u.t.has(Trait.BUILDER);
        boolean canBreak = f.breakBlocks && u.t.has(Trait.BREAKER);
        if (!canBuild && !canBreak) return;
        Mob m = u.mob;
        Location ml = m.getLocation();

        double moved = 1;
        if (u.hasLast) {
            double dx = ml.getX() - u.lastX, dy = ml.getY() - u.lastY, dz = ml.getZ() - u.lastZ;
            moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        double hx = destX - ml.getX(), hz = destZ - ml.getZ();
        double hdist = Math.sqrt(hx * hx + hz * hz);
        double dy = destY - ml.getY();
        if ((hdist > 1.8 || Math.abs(dy) > 1.5) && moved < 0.3) u.stuck++;
        else u.stuck = 0;
        if (now < u.cdBuild) return;

        Block feet = ml.getBlock();
        // Pillar up toward a target above.
        if (chasing && canBuild && dy > 1.6 && hdist < 3.5 && m.isOnGround()) {
            Block head2 = feet.getRelative(0, 2, 0);
            if (head2.isPassable()) {
                if (feet.isPassable() && !feet.isLiquid() && canPlace(feet)) {
                    m.teleport(ml.clone().add(0, 1.05, 0));
                    place(u, feet);
                    u.cdBuild = now + 8;
                    return;
                }
            } else if (canBreak && breakable(head2)) {
                breakBlock(u, head2);
                u.cdBuild = now + 8;
                return;
            }
        }
        // Dig down toward a target below.
        if (chasing && canBreak && dy < -2.5 && hdist < 1.6) {
            Block below = feet.getRelative(0, -1, 0);
            if (breakable(below)) {
                breakBlock(u, below);
                u.cdBuild = now + 10;
                return;
            }
        }
        if (u.stuck < 2) return;
        int fx = 0, fz = 0;
        if (Math.abs(hx) > Math.abs(hz)) fx = hx > 0 ? 1 : -1;
        else fz = hz > 0 ? 1 : -1;
        Block ff = feet.getRelative(fx, 0, fz), fh = feet.getRelative(fx, 1, fz);
        Block fg = feet.getRelative(fx, -1, fz), fg2 = feet.getRelative(fx, -2, fz);
        if (canBreak && !fh.isPassable() && breakable(fh)) {
            breakBlock(u, fh);
        } else if (canBreak && !ff.isPassable() && breakable(ff)) {
            breakBlock(u, ff);
        } else if (canBuild && fg.isPassable() && fg2.isPassable() && canPlace(fg)) {
            place(u, fg); // bridge the gap
        } else if (canBuild && !ff.isPassable() && !fh.isPassable() && feet.getRelative(0, 2, 0).isPassable()
                && m.isOnGround() && canPlace(feet)) {
            m.teleport(ml.clone().add(0, 1.05, 0)); // can't break it: tower over it
            place(u, feet);
        } else {
            return;
        }
        u.stuck = 1;
        u.cdBuild = now + 10;
    }

    void fortify(Unit u, int now) {
        if (!u.hasSlot || now < u.cdBuild) return;
        Mob m = u.mob;
        Location ml = m.getLocation();
        double dx = ml.getX() - u.slotX, dz = ml.getZ() - u.slotZ;
        if (dx * dx + dz * dz > 2.6) return;
        Faction f = u.f;
        double yaw = Math.toRadians(f.anchorYaw);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        int half = (int) Math.round(f.spacing / 2.0);
        World w = m.getWorld();
        for (int h = 0; h <= 2; h++) {
            for (int r = -half; r <= half; r++) {
                int bx = (int) Math.floor(u.slotX + fx * 2.5 + rx * r);
                int bz = (int) Math.floor(u.slotZ + fz * 2.5 + rz * r);
                Block b = w.getBlockAt(bx, (int) Math.floor(u.slotY) + h, bz);
                if (b.isPassable() && !b.isLiquid() && canPlace(b)) {
                    place(u, b);
                    u.cdBuild = now + 6;
                    return;
                }
            }
        }
    }

    private boolean canPlace(Block b) {
        if (plugin.portalBlocks().isProtected(b)) return false;
        if (!b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 0.45, 0.45, 0.45,
                e -> e instanceof org.bukkit.entity.Player).isEmpty()) {
            return false; // never entomb a player
        }
        return plugin.regions().canUnitBuild(b.getLocation());
    }

    private void place(Unit u, Block b) {
        Faction f = u.f;
        Material mat = mgr.buildMaterial(f);
        f.placed.add(BlockRecord.of(b, b.getBlockData()));
        if (f.placed.size() > mgr.s.maxPlacedPerFaction) f.placed.remove(0);
        b.setType(mat, true);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 0.8f, 0.9f);
    }

    boolean breakable(Block b) {
        Material ty = b.getType();
        if (ty.isAir() || b.isPassable() || b.isLiquid()) return false;
        float h = ty.getHardness();
        if (h < 0 || h > mgr.s.maxHardness) return false;
        if (mgr.s.neverBreak.contains(ty)) return false;
        if (plugin.portalBlocks().isProtected(b)) return false;
        return plugin.regions().canUnitBreak(b.getLocation());
    }

    private void breakBlock(Unit u, Block b) {
        Faction f = u.f;
        World w = b.getWorld();
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        w.spawnParticle(Particle.BLOCK, c, 20, 0.3, 0.3, 0.3, 0.0, b.getBlockData());
        w.playSound(c, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.6f, 1.3f);
        if (f.logBroken) {
            f.broken.add(BlockRecord.of(b, b.getBlockData()));
            b.setType(Material.AIR, true);
        } else if (mgr.s.dropBroken) {
            b.breakNaturally();
        } else {
            b.setType(Material.AIR, true);
        }
    }

    /** Removes blocks this faction placed that are still its build block. Returns how many were removed. */
    int clearBuilds(Faction f) {
        int n = 0;
        for (int i = f.placed.size() - 1; i >= 0; i--) {
            BlockRecord r = f.placed.get(i);
            Block b = r.block();
            if (b.getType() == f.buildBlock) {
                if (f.logBroken && r.before() != null) b.setBlockData(r.before(), false);
                else b.setType(Material.AIR, false);
                n++;
            }
        }
        f.placed.clear();
        return n;
    }

    /** Puts back every block an event faction broke, newest first. Returns how many were restored. */
    int restoreBroken(Faction f) {
        int n = 0;
        for (int i = f.broken.size() - 1; i >= 0; i--) {
            BlockRecord r = f.broken.get(i);
            Block b = r.block();
            if (b.isPassable() || b.getType() == f.buildBlock) {
                b.setBlockData(r.before(), false);
                n++;
            }
        }
        f.broken.clear();
        return n;
    }
}
