package dev.unstablesmp.faction;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/** Runtime state for one spawned faction unit. Times are server ticks. */
public final class Unit {
    public final Mob mob;
    public final UnitTemplate t;
    public final Faction f;
    public final int order;

    public LivingEntity combatTarget;
    /** Last known position (cheap primitive copy for stuck detection). */
    public double lastX, lastY, lastZ;
    public boolean hasLast;
    public int stuck;
    public BossBar bar;
    public boolean raged;
    public boolean removed;

    // slot (written by FormationPlanner)
    public double slotX, slotY, slotZ;
    public float slotYaw;
    public boolean hasSlot;
    /** For flyers: halo index, count and radius. */
    public int haloIndex, haloCount;
    public double haloRadius;

    public int cdCrit, cdLeap, cdBlink, cdSummon, cdRetreat, cdStrafeFlip, cdBuild, cdLaugh;
    public int retreatUntil, orbitUntil, diveUntil, flickerUntil;
    public int strafeDir = 1;

    public Unit(Mob mob, UnitTemplate t, Faction f, int order) {
        this.mob = mob;
        this.t = t;
        this.f = f;
        this.order = order;
    }

    public boolean retreating(int now) {
        return now < retreatUntil;
    }
}
