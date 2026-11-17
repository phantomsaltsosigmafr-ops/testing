package dev.unstablesmp.swarm;

import dev.unstablesmp.faction.Faction;
import java.util.logging.Logger;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;

/** Snapshot of config.yml -> swarm. */
public final class SwarmSettings {
    public int size = 24;
    public double shardRatio = 0.35;
    public int lifetimeTicks = 1800;
    public double leashRange = 48;
    public int targetMemoryTicks = 400;
    public double cubeHp = 6;
    public double shardHp = 3;
    public float cubeScale = 0.5f;
    public float shardScale = 0.9f;
    public BlockData bodyBlock = Material.OBSIDIAN.createBlockData();
    public BlockData faceBlock = Material.PURPLE_CONCRETE.createBlockData();
    public Material shardItem = Material.NETHERITE_SWORD;
    public Color glowColor = Color.fromRGB(0xA020F0);
    public boolean glow = true;
    public boolean hitboxes = true;
    public float viewRange = 1.0f;

    public int moveInterval = 2;
    public double maxSpeed = 0.8;
    public double maxForce = 0.14;
    public double diveSpeed = 1.5;
    public double slotGain = 0.35;
    public double sepRadius = 1.0;
    public double sepWeight = 0.9;
    public double aliWeight = 0.08;
    public double cohWeight = 0.02;
    public double neighbourRadius = 3.0;
    public double wander = 0.03;

    public double contactRadius = 1.4;
    public double contactDamage = 1.0;
    public double diveDamage = 2.5;
    public int damageInterval = 10;
    public double maxDamagePerHit = 9;
    public int botHitCooldown = 12;
    public int harassInterval = 30;
    public int harassCount = 2;

    public int trailEvery = 6;
    public int ambientSoundEvery = 70;

    public int diveStrikeCount = 8;
    public int arrowheadAssembleTicks = 24;
    public int arrowheadReturnTicks = 60;
    public int domeAssembleTicks = 16;
    public int domeMaxTicks = 120;
    public double domePush = 0.55;
    public int rainDropInterval = 3;
    public double projectileBotDamage = 4;

    public int portalLingerTicks = 100;

    public FormationParams formations = new FormationParams();

    public static SwarmSettings load(ConfigurationSection c, Logger log) {
        SwarmSettings s = new SwarmSettings();
        if (c == null) return s;
        s.size = Math.max(1, c.getInt("size", s.size));
        s.shardRatio = clamp(c.getDouble("shard-ratio", s.shardRatio), 0, 1);
        s.lifetimeTicks = Math.max(0, c.getInt("lifetime-seconds", 90) * 20);
        s.leashRange = c.getDouble("leash-range", s.leashRange);
        s.targetMemoryTicks = Math.max(20, c.getInt("target-memory-seconds", 20) * 20);
        s.cubeHp = c.getDouble("bot.cube-hp", s.cubeHp);
        s.shardHp = c.getDouble("bot.shard-hp", s.shardHp);
        s.cubeScale = (float) c.getDouble("bot.cube-scale", s.cubeScale);
        s.shardScale = (float) c.getDouble("bot.shard-scale", s.shardScale);
        s.bodyBlock = block(c.getString("bot.body-block"), Material.OBSIDIAN, log);
        s.faceBlock = block(c.getString("bot.face-block"), Material.PURPLE_CONCRETE, log);
        Material shard = Material.matchMaterial(c.getString("bot.shard-item", "NETHERITE_SWORD"));
        s.shardItem = shard != null && shard.isItem() ? shard : Material.NETHERITE_SWORD;
        s.glowColor = Color.fromRGB(Faction.parseRgb(c.getString("bot.glow-color", "#A020F0")));
        s.glow = c.getBoolean("bot.glow", true);
        s.hitboxes = c.getBoolean("bot.hitboxes", true);
        s.viewRange = (float) c.getDouble("bot.view-range", 1.0);

        s.moveInterval = Math.max(1, c.getInt("movement.update-interval-ticks", s.moveInterval));
        s.maxSpeed = c.getDouble("movement.max-speed", s.maxSpeed);
        s.maxForce = c.getDouble("movement.max-force", s.maxForce);
        s.diveSpeed = c.getDouble("movement.dive-speed", s.diveSpeed);
        s.slotGain = c.getDouble("movement.slot-gain", s.slotGain);
        s.sepRadius = c.getDouble("movement.separation-radius", s.sepRadius);
        s.sepWeight = c.getDouble("movement.separation-weight", s.sepWeight);
        s.aliWeight = c.getDouble("movement.alignment-weight", s.aliWeight);
        s.cohWeight = c.getDouble("movement.cohesion-weight", s.cohWeight);
        s.neighbourRadius = c.getDouble("movement.neighbour-radius", s.neighbourRadius);
        s.wander = c.getDouble("movement.wander", s.wander);

        s.contactRadius = c.getDouble("damage.contact-radius", s.contactRadius);
        s.contactDamage = c.getDouble("damage.contact", s.contactDamage);
        s.diveDamage = c.getDouble("damage.dive", s.diveDamage);
        s.damageInterval = Math.max(1, c.getInt("damage.apply-interval-ticks", s.damageInterval));
        s.maxDamagePerHit = c.getDouble("damage.max-per-hit", s.maxDamagePerHit);
        s.botHitCooldown = Math.max(1, c.getInt("damage.bot-hit-cooldown-ticks", s.botHitCooldown));
        s.harassInterval = Math.max(5, c.getInt("damage.harass-interval-ticks", s.harassInterval));
        s.harassCount = Math.max(0, c.getInt("damage.harass-count", s.harassCount));
        s.projectileBotDamage = c.getDouble("damage.projectile-vs-bot", s.projectileBotDamage);

        s.trailEvery = Math.max(1, c.getInt("effects.trail-every-ticks", s.trailEvery));
        s.ambientSoundEvery = Math.max(10, c.getInt("effects.ambient-sound-every-ticks", s.ambientSoundEvery));

        s.diveStrikeCount = Math.max(1, c.getInt("dive-strike-count", s.diveStrikeCount));
        s.arrowheadAssembleTicks = Math.max(5, c.getInt("formations.arrowhead-dive.assemble-ticks", s.arrowheadAssembleTicks));
        s.arrowheadReturnTicks = Math.max(10, c.getInt("formations.arrowhead-dive.return-ticks", s.arrowheadReturnTicks));
        s.domeAssembleTicks = Math.max(0, c.getInt("formations.dome-cage.assemble-ticks", s.domeAssembleTicks));
        s.domeMaxTicks = Math.max(20, c.getInt("formations.dome-cage.max-seconds", 6) * 20);
        s.domePush = c.getDouble("formations.dome-cage.push", s.domePush);
        s.rainDropInterval = Math.max(1, c.getInt("formations.rain.drop-interval-ticks", s.rainDropInterval));
        s.portalLingerTicks = Math.max(0, c.getInt("portal-linger-seconds", 5) * 20);
        s.formations = FormationParams.load(c.getConfigurationSection("formations"));
        return s;
    }

    private static BlockData block(String name, Material def, Logger log) {
        if (name == null) return def.createBlockData();
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isBlock()) {
            log.warning("swarm: '" + name + "' is not a block, using " + def);
            return def.createBlockData();
        }
        return m.createBlockData();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
