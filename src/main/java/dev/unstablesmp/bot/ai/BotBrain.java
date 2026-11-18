package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.bot.BotGroup;
import dev.unstablesmp.bot.BotManager;
import dev.unstablesmp.bot.PvpMode;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/** Per-bot decision loop: pick a target (order, squad, retaliation, nearest), path when stuck, then the mode tactic. */
public final class BotBrain {
    private final BotManager mgr;
    private final BotCombat combat;
    private final BotMovement movement = new BotMovement();
    private final BotPathfinder paths;
    private final Map<PvpMode, Tactic> tactics = new EnumMap<>(PvpMode.class);
    private final Location bl = new Location(null, 0, 0, 0);
    private final Location tl = new Location(null, 0, 0, 0);
    private double aggroRange = 40;

    public BotBrain(BotManager mgr, BotCombat combat, BotPathfinder paths) {
        this.mgr = mgr;
        this.combat = combat;
        this.paths = paths;
        Tactic sword = new Tactic(combat);
        tactics.put(PvpMode.SWORD, sword);
        tactics.put(PvpMode.AXE, new AxeTactic(combat));
        tactics.put(PvpMode.MACE, new MaceTactic(combat));
        tactics.put(PvpMode.ELYTRA_MACE, new ElytraMaceTactic(combat));
        tactics.put(PvpMode.UHC, new UhcTactic(combat));
        tactics.put(PvpMode.NETHPOT, new NethPotTactic(combat));
        tactics.put(PvpMode.SPEAR, new SpearTactic(combat));
    }

    public void setAggroRange(double r) {
        this.aggroRange = r;
    }

    public BotCombat combat() {
        return combat;
    }

    /** Runs every tick for active bots (every few ticks for far-away ones). */
    public void tick(Bot b, int now) {
        combat.finishEating(b, now);
        Tactic tactic = tactics.get(b.mode);
        LivingEntity t = b.target;
        if (t != null && (!t.isValid() || t.isDead() || t.getWorld() != b.body.getWorld() || !mgr.isEnemy(b, t))) {
            t = b.target = null;
            b.path = null;
        }
        b.body.getLocation(bl);
        if (now >= b.nextThink) {
            b.nextThink = now + b.s.reaction + (b.active ? 0 : 3);
            think(b, tactic, now);
            t = b.target;
        }
        boolean aerial = false;
        if (t != null) {
            t.getLocation(tl);
            double dist = dist(bl, tl);
            aerial = tactic.tick(b, t, now);
            if (!aerial) {
                combat.face(b, t, bl, tl, b.aimErr);
                tactic.attackTick(b, t, now, dist);
            }
        }
        movement.apply(b, now, aerial);
    }

    private void think(Bot b, Tactic tactic, int now) {
        LivingEntity t = pickTarget(b, now);
        b.target = t;
        if (t == null) {
            idle(b, now);
            return;
        }
        t.getLocation(tl);
        double dist = dist(bl, tl);
        if (b.squadRetreatUntil > now && b.squad != null && b.squad.leader != null && b.squad.leader != b) {
            combat.eatGapple(b, now);
            if (b.healPots > 0) combat.splashHeal(b, now);
        }
        if (followPath(b, now, tl, dist)) return;
        tactic.think(b, t, now, dist, bl, tl);
    }

    private LivingEntity pickTarget(Bot b, int now) {
        if (b.forcedTarget != null) {
            if (b.forcedTarget.isValid() && !b.forcedTarget.isDead() && b.forcedTarget.getWorld() == b.body.getWorld()) return b.forcedTarget;
            b.forcedTarget = null;
        }
        if (b.squad != null && b.group.behaviour == BotGroup.Behaviour.ORGANISED) {
            if (b.squadRetreatUntil > now) return null;
            if (b.target != null) return b.target;
        }
        LivingEntity cur = b.target;
        if (cur != null && now - b.targetSince < 40) return cur;
        LivingEntity best = mgr.findTarget(b, bl, aggroRange);
        if (best != cur) b.targetSince = now;
        return best != null ? best : cur;
    }

    private void idle(Bot b, int now) {
        b.critPlanned = false;
        combat.unblock(b);
        if (b.hasSlot) {
            moveTo(b, b.slotX, b.slotZ, 1.2);
        } else if (b.hasHome) {
            double dx = b.homeX - bl.getX(), dz = b.homeZ - bl.getZ();
            if (dx * dx + dz * dz > 36) moveTo(b, b.homeX, b.homeZ, 2.5);
            else b.moveSpeed = 0;
        } else {
            b.moveSpeed = 0;
        }
        if (b.moveSpeed == 0 && ThreadLocalRandom.current().nextInt(40) == 0) b.lookYaw += ThreadLocalRandom.current().nextFloat() * 120 - 60;
        if (b.healthFraction() < 0.8 && ThreadLocalRandom.current().nextInt(10) == 0 && b.mode != PvpMode.UHC) combat.eatGapple(b, now);
    }

    private void moveTo(Bot b, double x, double z, double slack) {
        double dx = x - bl.getX(), dz = z - bl.getZ();
        double d2 = dx * dx + dz * dz;
        if (d2 < slack * slack) {
            b.moveSpeed = 0;
            return;
        }
        b.moveYaw = BotMovement.yawTo(bl.getX(), bl.getZ(), x, z);
        b.lookYaw = b.moveYaw;
        b.lookPitch = 0;
        b.moveSpeed = d2 > 64 ? BotMovement.SPRINT : BotMovement.WALK;
        b.sprinting = b.moveSpeed >= BotMovement.SPRINT;
    }

    /** When stuck or the target is up/down a level, walk an A* path first. Returns true while following one. */
    private boolean followPath(Bot b, int now, Location target, double dist) {
        double dy = target.getY() - bl.getY();
        boolean needs = (b.stuckTicks > 12 || Math.abs(dy) > 2.2) && dist > 2.5 && b.phase == 0;
        if (needs && b.path == null && now >= b.pathAt) {
            b.pathAt = now + 30;
            b.path = paths.find(bl.getWorld(), bl.getBlockX(), bl.getBlockY(), bl.getBlockZ(),
                    target.getBlockX(), target.getBlockY(), target.getBlockZ(), now);
            b.pathIndex = 0;
        }
        if (b.path == null) return false;
        if (dist < 3 || b.pathIndex * 3 >= b.path.length) {
            b.path = null;
            return false;
        }
        int i = b.pathIndex * 3;
        double wx = b.path[i] + 0.5, wy = b.path[i + 1], wz = b.path[i + 2] + 0.5;
        double dx = wx - bl.getX(), dz = wz - bl.getZ();
        if (dx * dx + dz * dz < 0.5 && Math.abs(wy - bl.getY()) < 1.2) {
            b.pathIndex++;
            return true;
        }
        b.moveYaw = BotMovement.yawTo(bl.getX(), bl.getZ(), wx, wz);
        b.lookYaw = b.moveYaw;
        b.moveSpeed = BotMovement.SPRINT;
        b.sprinting = true;
        if (wy > bl.getY() + 0.5) b.wantJump = true;
        if (b.stuckTicks > 30) {
            b.path = null; // give up and let the tactic try
            b.stuckTicks = 0;
        }
        return true;
    }

    private static double dist(Location a, Location b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
