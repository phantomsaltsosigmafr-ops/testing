package dev.unstablesmp.event;

import dev.unstablesmp.UnstableSMP;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Portal Invasion (ported from the reference NullEvent): dozens of floating obsidian-framed nether portals open in
 * waves over an area, Nulls and Wraiths pour out, a Null Commander arrives mid-event, then the portals collapse
 * and the world is restored. Portals never teleport anyone and can't be broken.
 */
final class PortalInvasionEvent extends ServerEvent {
    private final List<Portal> portals = new ArrayList<>();
    private final int radius;
    private final int maxPortals;
    private final int portalsPerWave;
    private final int waveInterval;
    private final int minHeight;
    private final int maxHeight;
    private final int minSpacing;
    private final int closePerStep;
    private final int spawnInterval;
    private final double spawnChance;
    private final int commanders;
    private final int commanderAtTick;
    private int commandersSpawned;

    PortalInvasionEvent(UnstableSMP plugin, ConfigurationSection cfg, Location center, String starter) {
        super(plugin, EventType.PORTAL, cfg, center, starter);
        radius = Math.max(4, cfg.getInt("portals.radius", 40));
        maxPortals = Math.max(1, cfg.getInt("portals.max-portals", 60));
        portalsPerWave = Math.max(1, cfg.getInt("portals.portals-per-wave", 3));
        waveInterval = Math.max(1, cfg.getInt("portals.wave-interval-ticks", 10));
        minHeight = Math.max(0, cfg.getInt("portals.min-height-above-ground", 2));
        maxHeight = Math.max(minHeight, cfg.getInt("portals.max-height-above-ground", 18));
        minSpacing = Math.max(0, cfg.getInt("portals.min-spacing", 4));
        closePerStep = Math.max(1, cfg.getInt("portals.close-per-step", 2));
        spawnInterval = Math.max(1, cfg.getInt("spawn-interval-ticks", 40));
        spawnChance = cfg.getDouble("spawn-chance-per-portal", 0.25);
        commanders = Math.max(0, cfg.getInt("commanders", 1));
        int pct = Math.max(0, Math.min(100, cfg.getInt("commander-at-percent", 40)));
        commanderAtTick = durationTicks > 0 ? durationTicks * pct / 100 : cfg.getInt("commander-after-seconds", 25) * 20;
    }

    int portalCount() {
        return portals.size();
    }

    @Override
    protected void onStart() {
        world.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, 3f, 0.5f);
        world.playSound(center, Sound.AMBIENT_CAVE, 3f, 0.6f);
    }

    @Override
    protected void onRunning() {
        if (portals.size() < maxPortals && tick % waveInterval == 0) {
            for (int i = 0; i < portalsPerWave && portals.size() < maxPortals; i++) openRandomPortal();
        }
        if (tick % spawnInterval == 0) spawnRound();
        if (commandersSpawned < commanders && tick >= commanderAtTick && !portals.isEmpty() && tick % 40 == 0) {
            commandersSpawned++;
            Portal p = portals.get(rnd.nextInt(portals.size()));
            world.spawnParticle(Particle.REVERSE_PORTAL, p.center, 200, 1, 2, 1, 0.5);
            world.spawnParticle(Particle.SQUID_INK, p.center, 60, 1, 2, 1, 0.1);
            spawnLeader(p.center);
        }
        if (tick % 4 == 0) ambient();
    }

    @Override
    protected void onBeginClose() {
        world.playSound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2f, 0.5f);
    }

    @Override
    protected boolean onClosing() {
        if (tick % 2 == 0) {
            for (int i = 0; i < closePerStep && !portals.isEmpty(); i++) {
                closePortal(portals.remove(rnd.nextInt(portals.size())), true);
            }
        }
        return portals.isEmpty();
    }

    @Override
    protected void onFinish(boolean natural) {
        for (Portal p : portals) closePortal(p, false);
        portals.clear();
    }

    private void openRandomPortal() {
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(rnd.nextDouble()) * radius;
            int x = center.getBlockX() + (int) Math.round(Math.cos(ang) * dist);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(ang) * dist);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int ground = world.getHighestBlockYAt(x, z);
            int y = ground + 1 + minHeight + rnd.nextInt(maxHeight - minHeight + 1);
            if (y < world.getMinHeight() || y + 5 >= world.getMaxHeight()) continue;
            Location probe = new Location(world, x, y, z);
            if (!plugin.regions().canRunEvent(probe)) continue;
            boolean tooClose = false;
            double minSq = (double) minSpacing * minSpacing;
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
            plugin.portalBlocks().register(p.allBlocks());
            world.spawnParticle(Particle.REVERSE_PORTAL, p.center, 80, 0.6, 1.2, 0.6, 0.2);
            world.spawnParticle(Particle.LARGE_SMOKE, p.center, 20, 0.6, 1.2, 0.6, 0.02);
            world.playSound(p.center, Sound.BLOCK_PORTAL_TRIGGER, 0.6f, 0.6f + rnd.nextFloat() * 0.6f);
            world.playSound(p.center, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.5f);
            return;
        }
    }

    private void closePortal(Portal p, boolean effects) {
        plugin.portalBlocks().unregister(p.allBlocks());
        p.destroy();
        if (effects) {
            world.spawnParticle(Particle.LARGE_SMOKE, p.center, 25, 0.5, 1.2, 0.5, 0.03);
            world.spawnParticle(Particle.SQUID_INK, p.center, 15, 0.5, 1.2, 0.5, 0.05);
            world.playSound(p.center, Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
        }
    }

    private void spawnRound() {
        if (portals.isEmpty()) return;
        int alive = alive();
        List<Portal> order = new ArrayList<>(portals);
        Collections.shuffle(order, rnd);
        for (Portal p : order) {
            if (alive >= maxAlive) return;
            if (rnd.nextDouble() >= spawnChance) continue;
            world.spawnParticle(Particle.SQUID_INK, p.center, 20, 0.3, 0.8, 0.3, 0.05);
            world.spawnParticle(Particle.REVERSE_PORTAL, p.center, 40, 0.3, 1.0, 0.3, 0.3);
            world.playSound(p.center, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
            if (spawnAt(p.center, true)) alive++;
        }
    }

    private void ambient() {
        for (Portal p : portals) world.spawnParticle(Particle.PORTAL, p.center, 6, 0.5, 1.0, 0.5, 0.5);
        if (!portals.isEmpty() && tick % 40 == 0) {
            Portal p = portals.get(rnd.nextInt(portals.size()));
            world.playSound(p.center, Sound.BLOCK_PORTAL_AMBIENT, 0.8f, 0.5f + rnd.nextFloat() * 0.5f);
        }
    }
}
