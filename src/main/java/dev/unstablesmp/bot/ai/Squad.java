package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.bot.BotManager;
import dev.unstablesmp.faction.ArmyFormation;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/**
 * Organised fighting (NULL's war machine): members march in formation behind their leader, the squad picks one
 * focus target (the leader's, or the weakest enemy), no enemy gets more than {@code maxPerTarget} attackers, and
 * hurt members rotate to the back to heal before re-engaging.
 */
public final class Squad {
    public final String id;
    public final List<Bot> members = new ArrayList<>();
    public Bot leader;
    public LivingEntity focus;
    public ArmyFormation formation = ArmyFormation.WEDGE;
    public double spacing = 2.2;
    public int maxPerTarget = 3;
    public double engageRange = 30;
    /** Optional march order (block coords); null = follow the leader. */
    public Location march;
    private final Map<LivingEntity, int[]> assigned = new IdentityHashMap<>();
    private final List<LivingEntity> enemies = new ArrayList<>();
    private final double[] off = new double[2];
    private final Location scratch = new Location(null, 0, 0, 0);

    public Squad(String id) {
        this.id = id;
    }

    public boolean alive() {
        return !members.isEmpty();
    }

    /** Re-plans targets and formation slots. Called every 10 ticks. */
    public void plan(BotManager mgr, int now) {
        members.removeIf(b -> !b.valid());
        if (members.isEmpty()) return;
        if (leader == null || !leader.valid()) {
            leader = members.get(0);
            for (Bot b : members) if (b.tier.ordinal() < leader.tier.ordinal() || b.leader) leader = b;
        }
        double cx = 0, cz = 0;
        for (Bot b : members) {
            b.body.getLocation(scratch);
            cx += scratch.getX();
            cz += scratch.getZ();
        }
        cx /= members.size();
        cz /= members.size();
        leader.body.getLocation(scratch);
        enemies.clear();
        mgr.enemiesNear(leader, scratch.getWorld(), cx, scratch.getY(), cz, engageRange, enemies);

        if (!enemies.isEmpty()) {
            if (leader.target != null && leader.target.isValid() && enemies.contains(leader.target)) focus = leader.target;
            else if (focus == null || !focus.isValid() || focus.isDead() || !enemies.contains(focus)) focus = weakest(enemies);
            assigned.clear();
            for (Bot b : members) {
                if (b.forcedTarget != null && b.forcedTarget.isValid()) continue;
                if (b.healthFraction() < 0.3 && b != leader && (b.gapples > 0 || b.healPots > 0)) {
                    b.target = null; // rotate out: fall back behind the leader and heal
                    b.squadRetreatUntil = now + 50;
                    continue;
                }
                LivingEntity pick = null;
                int[] fc = assigned.get(focus);
                if (fc == null || fc[0] < maxPerTarget) pick = focus;
                if (pick == null) {
                    double best = Double.MAX_VALUE;
                    b.body.getLocation(scratch);
                    for (LivingEntity e : enemies) {
                        int[] c = assigned.get(e);
                        if (c != null && c[0] >= maxPerTarget) continue;
                        double d = e.getLocation().distanceSquared(scratch);
                        if (d < best) {
                            best = d;
                            pick = e;
                        }
                    }
                }
                if (pick == null) pick = focus;
                assigned.computeIfAbsent(pick, k -> new int[1])[0]++;
                b.target = pick;
                b.targetSince = now;
            }
            for (Bot b : members) b.hasSlot = false;
            return;
        }

        // No enemies: hold formation around the leader (or march point), facing the march direction.
        focus = null;
        double ax = march != null ? march.getX() : scratch.getX();
        double az = march != null ? march.getZ() : scratch.getZ();
        float yaw = march != null ? BotMovement.yawTo(cx, cz, march.getX(), march.getZ()) : scratch.getYaw();
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad), rx = -Math.cos(rad), rz = -Math.sin(rad);
        int n = members.size();
        int i = 0;
        for (Bot b : members) {
            if (b == leader && march == null) {
                b.hasSlot = false;
                continue;
            }
            formation.offset(i++, n, spacing, off);
            b.slotX = ax + rx * off[0] - fx * (off[1] + (march == null ? 2 : 0));
            b.slotZ = az + rz * off[0] - fz * (off[1] + (march == null ? 2 : 0));
            b.hasSlot = true;
            if (b.target != null && (!b.target.isValid() || b.target.isDead())) b.target = null;
        }
    }

    private static LivingEntity weakest(List<LivingEntity> list) {
        LivingEntity best = null;
        double hp = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            if (e.getHealth() < hp) {
                hp = e.getHealth();
                best = e;
            }
        }
        return best;
    }
}
