package dev.nullportals;

import org.bukkit.configuration.file.FileConfiguration;

/** Snapshot of config values for one portal event. */
final class Settings {
    int radius;
    int maxPortals;
    int portalsPerWave;
    int waveIntervalTicks;
    int minHeight;
    int maxHeight;
    int minSpacing;
    int durationTicks;
    int closePerStep;
    boolean allowTeleport;
    boolean protectBlocks;

    String spawnMode;          // VEX or COMMAND
    int spawnIntervalTicks;
    double spawnChance;
    int maxAlive;
    boolean despawnOnEnd;

    double targetRange;
    String eventFaction;
    int commanders;
    int commanderAfterTicks;
    boolean eventBuild;
    boolean eventBreak;
    boolean restoreWorld;

    String command;
    String runAs;              // CONSOLE or PLAYER
    boolean pullToPortal;
    int captureWindowTicks;

    static Settings from(FileConfiguration c) {
        Settings s = new Settings();
        s.radius = Math.max(4, c.getInt("portals.radius", 40));
        s.maxPortals = Math.max(1, c.getInt("portals.max-portals", 60));
        s.portalsPerWave = Math.max(1, c.getInt("portals.portals-per-wave", 3));
        s.waveIntervalTicks = Math.max(1, c.getInt("portals.wave-interval-ticks", 10));
        s.minHeight = Math.max(0, c.getInt("portals.min-height-above-ground", 2));
        s.maxHeight = Math.max(s.minHeight, c.getInt("portals.max-height-above-ground", 18));
        s.minSpacing = Math.max(0, c.getInt("portals.min-spacing", 4));
        s.durationTicks = c.getInt("portals.duration-seconds", 120) * 20;
        s.closePerStep = Math.max(1, c.getInt("portals.close-per-step", 2));
        s.allowTeleport = c.getBoolean("portals.allow-teleport", false);
        s.protectBlocks = c.getBoolean("portals.protect-blocks", true);

        s.spawnMode = c.getString("spawning.mode", "NULLS").trim().toUpperCase();
        s.spawnIntervalTicks = Math.max(1, c.getInt("spawning.interval-ticks", 40));
        s.spawnChance = c.getDouble("spawning.chance-per-portal", 0.25);
        s.maxAlive = Math.max(0, c.getInt("spawning.max-alive", 40));
        s.despawnOnEnd = c.getBoolean("spawning.despawn-on-end", true);

        s.targetRange = c.getDouble("spawning.target-range", 64);
        s.eventFaction = c.getString("spawning.faction", "null");
        s.commanders = Math.max(0, c.getInt("spawning.null-commanders", 1));
        s.commanderAfterTicks = c.getInt("spawning.null-commander-after-seconds", 25) * 20;
        s.eventBuild = c.getBoolean("spawning.nulls-build", true);
        s.eventBreak = c.getBoolean("spawning.nulls-break", true);
        s.restoreWorld = c.getBoolean("spawning.restore-world-after", true);

        s.command = c.getString("command.command", "");
        s.runAs = c.getString("command.run-as", "CONSOLE").trim().toUpperCase();
        s.pullToPortal = c.getBoolean("command.pull-spawned-to-portal", true);
        s.captureWindowTicks = Math.max(0, c.getInt("command.capture-window-ticks", 5));
        return s;
    }
}
