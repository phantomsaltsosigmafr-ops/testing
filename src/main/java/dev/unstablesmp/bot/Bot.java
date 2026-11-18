package dev.unstablesmp.bot;

import dev.unstablesmp.bot.ai.Squad;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;

/**
 * One player-looking PvP bot. The body is a {@link Mannequin} (real player model and skin, health, equipment,
 * physics, no vanilla AI); everything it does is driven by {@link dev.unstablesmp.bot.ai.BotBrain}.
 * All timers are server ticks.
 */
public final class Bot {
    public final int id;
    public final Mannequin body;
    public final BotGroup group;
    public final SkillTier tier;
    public final SkillTier.Stats s;
    public final PvpMode mode;
    public final BotLoadout kit;
    public final String team;
    public final Component name;
    public final String plainName;
    public final boolean leader;

    // ---- target / orders
    public LivingEntity target;
    /** Explicit order target (command, duel, focus). Bots stick to it while valid. */
    public LivingEntity forcedTarget;
    public int targetSince;
    public double homeX, homeY, homeZ;
    public boolean hasHome;
    public String map;
    public Squad squad;
    public double slotX, slotZ;
    public boolean hasSlot;

    // ---- movement intent (written by the brain, applied every tick by BotMovement)
    public float moveYaw;
    public double moveSpeed;
    public boolean wantJump;
    public float lookYaw, lookPitch;
    public boolean sprinting;
    public int strafeDir = 1;
    public int strafeSwitch;
    public int stuckTicks;
    public double lastX, lastZ;

    // ---- combat state
    public int lastSwing = -1000;
    public int cooldownFull = 13;
    public int lastHurt = -1000;
    public int wtapUntil;
    public int blockUntil;
    public int eatUntil;
    public String eating;
    public int nextThink;
    public int hitsLanded;
    public int comboHits;
    public boolean jumpResetQueued;
    public int retotemAt = -1;
    public int shieldDownUntil;
    public boolean critPlanned;
    public int critJumpTick;
    public double aimErr;
    public int phaseStart;
    public int squadRetreatUntil;
    public boolean active = true;

    // ---- cooldowns (tick when usable again)
    public int pearlCd, potCd, gapCd, rodCd, webCd, lavaCd, windCd, rocketCd, bowCd, chargeCd, buffCd;

    // ---- aerial (mace / elytra mace / spear)
    public int phase;
    public double peakY;
    public double fallStartY;
    public boolean airborne;
    public int phaseUntil;

    // ---- path
    public int[] path;
    public int pathIndex;
    public int pathAt;

    // ---- consumables (decremented as used)
    public int gapples, healPots, speedPots, strengthPots, totems, pearls, windCharges, cobwebs, lava, rockets, arrows;

    /** Tab-list identity (separate from the mannequin's entity UUID). */
    public final java.util.UUID tabId = java.util.UUID.randomUUID();
    public int ping;
    public String skinSpec;

    public int kills;
    public boolean removed;
    public int spawnTick;

    public Bot(int id, Mannequin body, BotGroup group, SkillTier tier, PvpMode mode, BotLoadout kit, String team,
               Component name, String plainName, boolean leader) {
        this.id = id;
        this.body = body;
        this.group = group;
        this.tier = tier;
        this.s = tier.stats();
        this.mode = mode;
        this.kit = kit;
        this.team = team;
        this.name = name;
        this.plainName = plainName;
        this.leader = leader;
        this.gapples = kit.gapples;
        this.healPots = kit.healPots;
        this.speedPots = kit.speedPots;
        this.strengthPots = kit.strengthPots;
        this.totems = kit.totems;
        this.pearls = kit.pearls;
        this.windCharges = kit.windCharges;
        this.cobwebs = kit.cobwebs;
        this.lava = kit.lava;
        this.rockets = kit.rockets;
        this.arrows = kit.arrows;
    }

    public boolean valid() {
        return !removed && body.isValid() && !body.isDead();
    }

    /** Attack charge 0..1 like the vanilla 1.9 cooldown. */
    public double charge(int now) {
        return Math.min(1.0, (now - lastSwing) / (double) Math.max(1, cooldownFull));
    }

    public double healthFraction() {
        var a = body.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double max = a == null ? 20 : a.getValue();
        return body.getHealth() / max;
    }
}
