package dev.unstablesmp.event;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.core.Fx;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Null Outbreak, Law Crackdown and Cindercrest Firestorm: themed waves of that faction's units closing in on the
 * area, a leader mid-event, and the faction's abilities striking players (Null Fields, Judgment gavels, Ashfall).
 */
final class FactionWaveEvent extends ServerEvent {
    private final double radiusMin;
    private final double radiusMax;
    private final int waveInterval;
    private final int unitsPerWave;
    private final int leaderAtTick;
    private final String strikeType;
    private final int strikeInterval;
    private final int strikesPer;
    private final double strikeRange;
    private final Particle.DustOptions themeDust;
    private final Particle themeParticle;
    private final Sound waveSound;
    private boolean leaderSpawned;
    private Caster strikeCaster;

    FactionWaveEvent(UnstableSMP plugin, EventType type, ConfigurationSection cfg, Location center, String starter) {
        super(plugin, type, cfg, center, starter);
        radiusMin = cfg.getDouble("spawn-radius-min", 16);
        radiusMax = Math.max(radiusMin + 1, cfg.getDouble("spawn-radius-max", 32));
        waveInterval = Math.max(20, cfg.getInt("wave-interval-seconds", 8) * 20);
        unitsPerWave = Math.max(1, cfg.getInt("units-per-wave", 6));
        int pct = Math.max(0, Math.min(100, cfg.getInt("leader-at-percent", 50)));
        leaderAtTick = durationTicks > 0 ? durationTicks * pct / 100 : 1200;
        strikeType = cfg.getString("strikes.type", "NONE").trim().toUpperCase(Locale.ROOT);
        strikeInterval = Math.max(20, cfg.getInt("strikes.interval-seconds", 6) * 20);
        strikesPer = Math.max(0, cfg.getInt("strikes.per-strike", 2));
        strikeRange = cfg.getDouble("strikes.range", radiusMax + 10);
        int rgb = dev.unstablesmp.faction.Faction.parseRgb(cfg.getString("theme-color", "#FFFFFF"));
        themeDust = new Particle.DustOptions(Color.fromRGB(rgb), 1.6f);
        themeParticle = switch (type) {
            case LAW -> Particle.END_ROD;
            case CINDER -> Particle.ASH;
            default -> Particle.SQUID_INK;
        };
        waveSound = switch (type) {
            case LAW -> Sound.BLOCK_BELL_USE;
            case CINDER -> Sound.ENTITY_BLAZE_SHOOT;
            default -> Sound.BLOCK_SCULK_SHRIEKER_SHRIEK;
        };
    }

    @Override
    protected void onStart() {
        strikeCaster = Caster.server(name, faction);
        wave();
    }

    @Override
    protected void onRunning() {
        if (tick % waveInterval == 0) wave();
        if (!leaderSpawned && tick >= leaderAtTick) {
            leaderSpawned = true;
            Location at = ring();
            world.spawnParticle(themeParticle, at, 120, 1, 2, 1, 0.1);
            spawnLeader(at);
        }
        if (strikesPer > 0 && !strikeType.equals("NONE") && tick % strikeInterval == 0) strikes();
        if (tick % 10 == 0) ambient();
    }

    private Location ring() {
        double ang = rnd.nextDouble() * Math.PI * 2;
        double dist = radiusMin + rnd.nextDouble() * (radiusMax - radiusMin);
        double x = center.getX() + Math.cos(ang) * dist, z = center.getZ() + Math.sin(ang) * dist;
        return dev.unstablesmp.core.Ground.groundAt(world, x, world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1, z);
    }

    private void wave() {
        int alive = alive();
        Location gate = ring();
        if (!plugin.regions().canRunEvent(gate)) return;
        world.spawnParticle(themeParticle, gate, 60, 1.2, 1.5, 1.2, 0.05);
        Fx.dust(world, gate.getX(), gate.getY() + 1, gate.getZ(), 40, 1.2, themeDust);
        world.playSound(gate, waveSound, 2.5f, 0.6f);
        for (int i = 0; i < unitsPerWave && alive < maxAlive; i++) {
            Location at = gate.clone().add(rnd.nextDouble() * 4 - 2, 0, rnd.nextDouble() * 4 - 2);
            if (spawnAt(at.add(0, 0.1, 0), false)) alive++;
        }
    }

    private void strikes() {
        List<Player> players = playersInside(strikeRange);
        Collections.shuffle(players, rnd);
        int n = 0;
        for (Player p : players) {
            if (n >= strikesPer) break;
            if (!plugin.regions().canPvp(null, p.getLocation())) continue;
            if (plugin.abilities().serverStrike(strikeType, strikeCaster, p)) n++;
        }
    }

    private void ambient() {
        for (Player p : playersInside(radiusMax + 8)) {
            Location l = p.getLocation();
            for (int i = 0; i < 3; i++) {
                double x = l.getX() + rnd.nextDouble() * 16 - 8, z = l.getZ() + rnd.nextDouble() * 16 - 8;
                world.spawnParticle(themeParticle, x, l.getY() + rnd.nextDouble() * 6, z, 2, 0.2, 0.2, 0.2, 0.01);
            }
        }
    }
}
