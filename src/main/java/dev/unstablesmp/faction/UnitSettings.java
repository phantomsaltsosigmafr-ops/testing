package dev.unstablesmp.faction;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Snapshot of config.yml -> units. */
public final class UnitSettings {
    public double aggroRange = 24;
    public double defendRange = 14;
    public boolean teleportStragglers = true;
    public int maxPerFaction = 120;
    public int maxTotal = 300;
    public boolean attackMonsters = true;
    public int grudgeTicks = 600;
    public int callForHelpRange = 14;
    public int callForHelpCount = 6;
    public double critMultiplier = 1.5;
    public double lifesteal = 0.3;
    public double rageThreshold = 0.4;
    public double retreatThreshold = 0.3;
    public int leaderBarRange = 48;
    public double flyerSpeed = 0.6;
    public int flyerAttackCooldown = 20;

    public double maxHardness = 30;
    public boolean dropBroken;
    public int maxPlacedPerFaction = 6000;
    public final EnumSet<Material> neverBreak = EnumSet.noneOf(Material.class);

    public static UnitSettings load(ConfigurationSection c) {
        UnitSettings s = new UnitSettings();
        if (c == null) return s;
        s.aggroRange = c.getDouble("aggro-range", s.aggroRange);
        s.defendRange = c.getDouble("defend-range", s.defendRange);
        s.teleportStragglers = c.getBoolean("teleport-stragglers", true);
        s.maxPerFaction = Math.max(1, c.getInt("max-per-faction", s.maxPerFaction));
        s.maxTotal = Math.max(1, c.getInt("max-total", s.maxTotal));
        s.attackMonsters = c.getBoolean("attack-monsters", true);
        s.grudgeTicks = Math.max(20, c.getInt("grudge-seconds", 30) * 20);
        s.callForHelpRange = Math.max(1, c.getInt("call-for-help.range", s.callForHelpRange));
        s.callForHelpCount = Math.max(0, c.getInt("call-for-help.max-helpers", s.callForHelpCount));
        s.critMultiplier = c.getDouble("crit-multiplier", s.critMultiplier);
        s.lifesteal = c.getDouble("lifesteal-fraction", s.lifesteal);
        s.rageThreshold = c.getDouble("rage-threshold", s.rageThreshold);
        s.retreatThreshold = c.getDouble("retreat-threshold", s.retreatThreshold);
        s.leaderBarRange = Math.max(8, c.getInt("leader-bossbar-range", s.leaderBarRange));
        s.flyerSpeed = c.getDouble("flyers.dive-speed", s.flyerSpeed);
        s.flyerAttackCooldown = Math.max(5, c.getInt("flyers.attack-cooldown-ticks", s.flyerAttackCooldown));
        s.maxHardness = c.getDouble("building.max-break-hardness", s.maxHardness);
        s.dropBroken = c.getBoolean("building.drop-broken-blocks", false);
        s.maxPlacedPerFaction = Math.max(100, c.getInt("building.max-tracked-placed", s.maxPlacedPerFaction));
        List<String> never = c.getStringList("building.never-break");
        for (Material m : Material.values()) {
            String name = m.name();
            if (name.startsWith("LEGACY_") || !m.isBlock()) continue;
            for (String n : never) {
                if (name.contains(n.trim().toUpperCase(Locale.ROOT))) {
                    s.neverBreak.add(m);
                    break;
                }
            }
        }
        return s;
    }
}
