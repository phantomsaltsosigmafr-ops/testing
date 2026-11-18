package dev.unstablesmp.bot.ai;

import dev.unstablesmp.bot.Bot;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

/**
 * Base fighting style (pure sword PvP). Subclasses add their mode's specials. {@link #think} runs at the bot's
 * reaction rate; {@link #attackTick} and {@link #tick} run every tick so good bots hit on the exact tick their
 * cooldown is ready or their crit lands.
 */
public class Tactic {
    protected final BotCombat combat;

    public Tactic(BotCombat combat) {
        this.combat = combat;
    }

    protected static boolean roll(double p) {
        return ThreadLocalRandom.current().nextDouble() < p;
    }

    protected ItemStack weapon(Bot b) {
        return b.kit.primary(b.mode);
    }

    /** Decision step (reaction-rate). */
    public void think(Bot b, LivingEntity t, int now, double dist, Location bl, Location tl) {
        combat.totem(b, now);
        if (heal(b, t, dist, now)) {
            retreat(b, bl, tl, now);
            return;
        }
        if (now < b.eatUntil) {
            retreat(b, bl, tl, now);
            return;
        }
        if (special(b, t, dist, now, bl, tl)) return;
        if (dist > 14 && roll(b.s.pearl * 0.25)) combat.pearl(b, tl, now);
        approach(b, t, dist, now, bl, tl);
        if (b.kit.shield && dist < 4.2 && b.charge(now) < 0.5 && now >= b.blockUntil && roll(b.s.block * 0.35)) {
            combat.block(b, now, 5 + ThreadLocalRandom.current().nextInt(8));
        }
    }

    /** Mode-specific actions. Return true if they took over this think. */
    protected boolean special(Bot b, LivingEntity t, double dist, int now, Location bl, Location tl) {
        return false;
    }

    /** Every tick. Return true if this mode is steering the velocity itself (aerial phases). */
    public boolean tick(Bot b, LivingEntity t, int now) {
        return false;
    }

    /** Heal if low. Default: golden apples (eat when there's some space or when very low). */
    protected boolean heal(Bot b, LivingEntity t, double dist, int now) {
        if (now - b.lastHurt < b.s.healDelay) return false;
        double hp = b.healthFraction();
        if (hp > b.s.healAt || b.eating != null) return false;
        if (dist < 3.2 && hp > b.s.healAt * 0.6 && roll(b.s.combo)) return false; // keep trading if winning the exchange
        return combat.eatGapple(b, now);
    }

    protected void retreat(Bot b, Location bl, Location tl, int now) {
        float away = BotMovement.yawTo(tl.getX(), tl.getZ(), bl.getX(), bl.getZ());
        if (now >= b.strafeSwitch) {
            b.strafeDir = -b.strafeDir;
            b.strafeSwitch = now + 15 + ThreadLocalRandom.current().nextInt(20);
        }
        b.moveYaw = away + b.strafeDir * 35;
        b.moveSpeed = BotMovement.SPRINT;
        b.sprinting = true;
    }

    /** Spacing, strafing, crit-jump setup and w-tap. */
    protected void approach(Bot b, LivingEntity t, double dist, int now, Location bl, Location tl) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        b.aimErr = r.nextGaussian() * b.s.aimError * 0.5;
        float to = BotMovement.yawTo(bl.getX(), bl.getZ(), tl.getX(), tl.getZ());
        double charge = b.charge(now);
        boolean ready = charge >= b.s.attackCharge;
        double ideal = ready ? b.s.reach - 0.35 : b.s.reach + 0.5 * b.s.strafe;
        double strafeW = dist < 5 ? b.s.strafe : 0;
        if (now >= b.strafeSwitch) {
            if (r.nextBoolean()) b.strafeDir = -b.strafeDir;
            b.strafeSwitch = now + 8 + r.nextInt(25);
        }
        float yaw = to;
        double speed;
        if (dist > ideal + 0.25) {
            yaw += (float) (b.strafeDir * strafeW * 30);
            speed = r.nextDouble() < b.s.sprint ? BotMovement.SPRINT : BotMovement.WALK;
        } else if (dist < ideal - 0.7 && b.s.strafe > 0.4) {
            yaw += 180 - b.strafeDir * 35;
            speed = BotMovement.WALK * 0.85;
        } else {
            yaw += (float) (b.strafeDir * 90 * strafeW);
            speed = 0.05 + BotMovement.WALK * strafeW;
        }
        if (now < b.wtapUntil) speed = 0; // w-tap: release forward to reset sprint
        b.sprinting = speed >= BotMovement.SPRINT;
        b.moveYaw = yaw;
        b.moveSpeed = speed;
        if (b.body.isOnGround() && !b.critPlanned && dist < b.s.reach + 1.4 && charge > 0.4 && charge < 0.75 && roll(b.s.critRate)) {
            b.wantJump = true;
            b.critPlanned = true;
            b.critJumpTick = now;
        }
    }

    /** Every tick: swing on the right tick (crit on the way down, or when the cooldown hits the tier threshold). */
    public void attackTick(Bot b, LivingEntity t, int now, double dist) {
        if (now < b.eatUntil || b.eating != null) return;
        double reach = b.s.reach + t.getWidth() * 0.5 + 0.3;
        if (dist > reach + 0.2) {
            if (b.critPlanned && now - b.critJumpTick > 14) b.critPlanned = false;
            return;
        }
        double charge = b.charge(now);
        boolean ground = b.body.isOnGround();
        boolean falling = !ground && b.body.getVelocity().getY() < -0.03;
        boolean go;
        if (b.critPlanned) {
            go = (falling && charge >= 0.9) || now - b.critJumpTick > 14;
        } else {
            go = charge >= b.s.attackCharge && (b.s.reaction <= 2 || (now + b.id) % Math.max(1, b.s.reaction / 2) == 0);
        }
        if (!go) return;
        combat.unblock(b);
        ItemStack w = weapon(b);
        combat.hold(b, w);
        combat.swing(b, t, dist, now, w, 0, b.mode.id());
        b.critPlanned = false;
    }
}
