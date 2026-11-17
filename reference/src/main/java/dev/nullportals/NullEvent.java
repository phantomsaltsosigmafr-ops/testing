package dev.nullportals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** A running "null portal" event: portals tear open over an area and things pour out of them. */
final class NullEvent implements Runnable {
    private final NullPortalsPlugin plugin;
    private final Settings s;
    private final Location center;
    private final World world;
    private final String starterName;
    private final Random rnd = new Random();

    private final List<Portal> portals = new ArrayList<>();
    final List<Entity> spawned = new ArrayList<>();
    private BukkitTask task;
    private int tick;
    private int counter;
    private boolean closing;
    private boolean finished;
    private final Faction swarm;
    private int commandersSpawned;
    private static int eventCounter;

    NullEvent(NullPortalsPlugin plugin, Settings s, Location center, String starterName) {
        this.plugin = plugin;
        this.s = s;
        this.center = center.clone();
        this.world = center.getWorld();
        this.starterName = starterName;
        Faction base = plugin.units().factions.get(s.eventFaction);
        this.swarm = base == null ? null : base.eventCopy("null-event-" + (++eventCounter));
        if (swarm != null) {
            swarm.build = s.eventBuild;
            swarm.breakBlocks = s.eventBreak;
        }
    }

    Location center() {
        return center;
    }

    int portalCount() {
        return portals.size();
    }

    void start() {
        world.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, 3f, 0.5f);
        world.playSound(center, Sound.AMBIENT_CAVE, 3f, 0.6f);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    @Override
    public void run() {
        tick++;
        if (!closing) {
            if (s.durationTicks > 0 && tick >= s.durationTicks) {
                beginClose();
            } else {
                if (portals.size() < s.maxPortals && tick % s.waveIntervalTicks == 0) {
                    for (int i = 0; i < s.portalsPerWave && portals.size() < s.maxPortals; i++) openRandomPortal();
                }
                if (tick % s.spawnIntervalTicks == 0) spawnRound();
                if (swarm != null && commandersSpawned < s.commanders && tick >= s.commanderAfterTicks
                        && !portals.isEmpty() && tick % 40 == 0) {
                    spawnCommander();
                }
            }
        } else if (tick % 2 == 0) {
            for (int i = 0; i < s.closePerStep && !portals.isEmpty(); i++) {
                closePortal(portals.remove(rnd.nextInt(portals.size())), true);
            }
            if (portals.isEmpty()) {
                finish(true);
                return;
            }
        }
        if (tick % 4 == 0) ambientEffects();
    }

    // ---------------------------------------------------------------- portals

    private void openRandomPortal() {
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(rnd.nextDouble()) * s.radius;
            int x = center.getBlockX() + (int) Math.round(Math.cos(ang) * dist);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(ang) * dist);
            int ground = world.getHighestBlockYAt(x, z);
            int y = ground + 1 + s.minHeight + rnd.nextInt(s.maxHeight - s.minHeight + 1);
            if (y < world.getMinHeight() || y + 5 >= world.getMaxHeight()) continue;

            Location probe = new Location(world, x, y, z);
            boolean tooClose = false;
            double minSq = (double) s.minSpacing * s.minSpacing;
            for (Portal p : portals) {
                if (p.center.distanceSquared(probe) < minSq) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;

            Portal p = Portal.tryBuild(world, x, y, z, rnd.nextBoolean());
            if (p == null) continue;
            portals.add(p);
            plugin.registerBlocks(p.allBlocks());
            world.spawnParticle(Particle.REVERSE_PORTAL, p.center, 80, 0.6, 1.2, 0.6, 0.2);
            world.spawnParticle(Particle.LARGE_SMOKE, p.center, 20, 0.6, 1.2, 0.6, 0.02);
            world.playSound(p.center, Sound.BLOCK_PORTAL_TRIGGER, 0.6f, 0.6f + rnd.nextFloat() * 0.6f);
            world.playSound(p.center, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.5f);
            return;
        }
    }

    private void closePortal(Portal p, boolean effects) {
        plugin.unregisterBlocks(p.allBlocks());
        p.destroy();
        if (effects) {
            world.spawnParticle(Particle.LARGE_SMOKE, p.center, 25, 0.5, 1.2, 0.5, 0.03);
            world.spawnParticle(Particle.SQUID_INK, p.center, 15, 0.5, 1.2, 0.5, 0.05);
            world.playSound(p.center, Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
        }
    }

    private void beginClose() {
        closing = true;
        world.playSound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2f, 0.5f);
    }

    // ---------------------------------------------------------------- spawning

    private int aliveCount() {
        Iterator<Entity> it = spawned.iterator();
        while (it.hasNext()) {
            if (!it.next().isValid()) it.remove();
        }
        return spawned.size();
    }

    private void spawnRound() {
        if (portals.isEmpty()) return;
        int alive = aliveCount();
        List<Portal> order = new ArrayList<>(portals);
        Collections.shuffle(order, rnd);
        for (Portal p : order) {
            if (alive >= s.maxAlive) return;
            if (rnd.nextDouble() >= s.spawnChance) continue;
            if (spawnFrom(p)) alive++;
        }
    }

    private boolean spawnFrom(Portal p) {
        Location at = p.center.clone();
        world.spawnParticle(Particle.SQUID_INK, at, 20, 0.3, 0.8, 0.3, 0.05);
        world.spawnParticle(Particle.REVERSE_PORTAL, at, 40, 0.3, 1.0, 0.3, 0.3);
        world.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);

        if ("COMMAND".equals(s.spawnMode) && s.command != null && !s.command.isBlank()) {
            return runSpawnCommand(at);
        }
        if (swarm == null) return false;
        String id = swarm.pickArmy(rnd);
        UnitTemplate t = id == null ? null : plugin.units().templates.get(id);
        if (t == null) return false;
        Unit u = plugin.units().spawn(swarm, t, t.flying() ? at : UnitManager.groundAt(world, at.getX(), at.getY(), at.getZ()));
        if (u == null) return false;
        spawned.add(u.mob);
        return true;
    }

    private void spawnCommander() {
        UnitTemplate t = plugin.units().templates.get(swarm.leaderTemplate);
        commandersSpawned++;
        if (t == null) return;
        Portal p = portals.get(rnd.nextInt(portals.size()));
        world.playSound(p.center, Sound.ENTITY_WITHER_SPAWN, 4f, 0.5f);
        world.spawnParticle(Particle.REVERSE_PORTAL, p.center, 200, 1, 2, 1, 0.5);
        world.spawnParticle(Particle.SQUID_INK, p.center, 60, 1, 2, 1, 0.1);
        Location g = UnitManager.groundAt(world, p.center.getX(), p.center.getY(), p.center.getZ());
        Unit u = plugin.units().spawn(swarm, t, g);
        if (u != null) spawned.add(u.mob);
    }

    private boolean runSpawnCommand(Location at) {
        counter++;
        Player target = nearestTarget(at);
        String cmd = s.command
                .replace("{world}", world.getName())
                .replace("{x}", fmt(at.getX()))
                .replace("{y}", fmt(at.getY()))
                .replace("{z}", fmt(at.getZ()))
                .replace("{bx}", Integer.toString(at.getBlockX()))
                .replace("{by}", Integer.toString(at.getBlockY()))
                .replace("{bz}", Integer.toString(at.getBlockZ()))
                .replace("{n}", Integer.toString(counter))
                .replace("{target}", target != null ? target.getName() : (starterName != null ? starterName : ""))
                .replace("{starter}", starterName != null ? starterName : "");
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        return plugin.dispatchCaptured(this, cmd, at, s);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    // ---------------------------------------------------------------- behaviour

    private Player nearestTarget(Location from) {
        Player best = null;
        double bestSq = s.targetRange * s.targetRange;
        for (Player p : world.getPlayers()) {
            GameMode gm = p.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) continue;
            if (!p.isValid()) continue;
            double d = p.getLocation().distanceSquared(from);
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    private void ambientEffects() {
        for (Portal p : portals) {
            world.spawnParticle(Particle.PORTAL, p.center, 6, 0.5, 1.0, 0.5, 0.5);
        }
        if (!portals.isEmpty() && tick % 40 == 0) {
            Portal p = portals.get(rnd.nextInt(portals.size()));
            world.playSound(p.center, Sound.BLOCK_PORTAL_AMBIENT, 0.8f, 0.5f + rnd.nextFloat() * 0.5f);
        }
    }
        }
    }

    // ---------------------------------------------------------------- shutdown

    /** Stops everything right now (used by /nullportal stop and on plugin disable). */
    void stopNow() {
        for (Portal p : portals) closePortal(p, false);
        portals.clear();
        finish(false);
    }

    private void finish(boolean effects) {
        if (finished) return;
        finished = true;
        if (task != null) task.cancel();
        if (s.despawnOnEnd) {
            for (Entity e : spawned) {
                if (e instanceof Player || !e.isValid()) continue;
                if (effects) world.spawnParticle(Particle.LARGE_SMOKE, e.getLocation(), 10, 0.2, 0.3, 0.2, 0.02);
                e.remove();
            }
            spawned.clear();
        }
        if (swarm != null) {
            if (s.despawnOnEnd) plugin.units().disband(swarm);
            if (s.restoreWorld) {
                plugin.units().clearBuilds(swarm);
                plugin.units().restoreBroken(swarm);
            }
        }
        plugin.eventEnded(this);
    }
}
