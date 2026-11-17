package dev.nullportals;

import org.bukkit.Location;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/** Runtime state for one spawned unit. */
final class Unit {
    final Mob mob;
    final UnitTemplate t;
    final Faction f;
    final long order;

    LivingEntity combatTarget;
    Location lastPos;
    int stuck;
    BossBar bar;
    boolean raged;

    long cdCrit, cdLeap, cdBlink, cdSummon, cdRetreat, cdStrafeFlip, cdBuild, cdLaugh;
    long retreatUntil, orbitUntil, diveUntil, flickerUntil;
    int strafeDir = 1;

    Unit(Mob mob, UnitTemplate t, Faction f, long order) {
        this.mob = mob;
        this.t = t;
        this.f = f;
        this.order = order;
    }

    boolean retreating(long now) {
        return now < retreatUntil;
    }
}
