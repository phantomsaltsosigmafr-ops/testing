package dev.unstablesmp.faction;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Messages;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Unit combat rules: no friendly fire (projectiles included), target filtering, crits, on-hit effects, lifesteal,
 * chaos launches, grudges, calling for help, kill attribution, clean deaths (no drops), no sun-burning / conversion.
 */
public final class UnitListener implements Listener {
    private final UnstableSMP plugin;
    private final UnitManager mgr;

    public UnitListener(UnstableSMP plugin, UnitManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Unit u = mgr.unit(e.getEntity());
        if (u == null || mgr.internalTarget()) return;
        Entity tg = e.getTarget();
        if (tg == null) return;
        Faction f = u.f;
        int now = Bukkit.getCurrentTick();
        boolean ok = tg instanceof LivingEntity le
                && !u.retreating(now)
                && now >= u.orbitUntil
                && (f.mode != Faction.Mode.PASSIVE || f.eventHostile || tg.getUniqueId().equals(f.focus))
                && (f.focus == null || tg.getUniqueId().equals(f.focus))
                && UnitBrain.attackable(le) && mgr.brain.isEnemy(f, le, now);
        if (!ok) e.setCancelled(true);
        else u.combatTarget = (LivingEntity) tg;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        Entity dmgr = e.getDamager();
        Entity attacker = e.getDamageSource().getCausingEntity();
        if (attacker == null && dmgr instanceof Projectile pr && pr.getShooter() instanceof Entity se) attacker = se;
        if (attacker == null) attacker = dmgr;
        Unit vu = mgr.unit(victim);
        Unit au = mgr.unit(attacker);

        // Friendly fire: never hurt your own faction or its members (projectiles included).
        if (au != null && vu != null && au.f == vu.f) {
            e.setCancelled(true);
            return;
        }
        if (au != null && victim instanceof Player && !au.f.eventHostile && au.f.members.contains(victim.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (vu != null && attacker instanceof Player && !vu.f.eventHostile && vu.f.members.contains(attacker.getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        int now = Bukkit.getCurrentTick();
        if (au != null && victim instanceof LivingEntity lv) onUnitHit(e, au, lv, dmgr == attacker, now);
        if (vu != null && attacker instanceof LivingEntity la && la != vu.mob) onUnitHurt(vu, la, now);
    }

    private void onUnitHit(EntityDamageByEntityEvent e, Unit au, LivingEntity lv, boolean melee, int now) {
        UnitTemplate t = au.t;
        UnitSettings s = mgr.s;
        if (melee && t.has(Trait.CRIT) && !au.mob.isOnGround() && au.mob.getVelocity().getY() < -0.05) {
            e.setDamage(e.getDamage() * s.critMultiplier);
            lv.getWorld().spawnParticle(Particle.CRIT, lv.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.2);
            lv.getWorld().playSound(lv.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
        }
        if (t.has(Trait.DARKNESS)) lv.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0));
        if (t.has(Trait.BLIND)) lv.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
        if (t.has(Trait.SLOW)) lv.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
        if (t.has(Trait.NAUSEA)) lv.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
        if (t.has(Trait.FIRE)) lv.setFireTicks(Math.max(lv.getFireTicks(), 80));
        if (t.has(Trait.LIFESTEAL)) {
            double max = UnitBrain.maxHealth(au.mob);
            au.mob.setHealth(Math.min(max, au.mob.getHealth() + e.getDamage() * s.lifesteal));
        }
        if (t.has(Trait.CHAOS) && ThreadLocalRandom.current().nextDouble() < 0.4) {
            final double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (lv.isValid()) lv.setVelocity(new Vector(Math.cos(ang) * 0.9, 0.6, Math.sin(ang) * 0.9));
            });
        }
        if (lv instanceof Player) {
            plugin.attribution().record(lv.getUniqueId(), "faction:" + au.f.baseId,
                    au.mob.customName() != null ? au.mob.customName() : au.t.display, au.mob.getUniqueId(), now);
        }
    }

    private void onUnitHurt(Unit vu, LivingEntity la, int now) {
        Faction f = vu.f;
        UnitSettings s = mgr.s;
        if (la instanceof Player p && !f.eventHostile && !f.members.contains(p.getUniqueId())) {
            f.grudges.put(p.getUniqueId(), now + s.grudgeTicks);
        }
        if ((f.mode == Faction.Mode.PASSIVE && !f.eventHostile) || f.focus != null || !mgr.brain.isEnemy(f, la, now)) return;
        // Call for help: nearby idle allies join in.
        int called = 0;
        Location vl = vu.mob.getLocation();
        double rSq = (double) s.callForHelpRange * s.callForHelpRange;
        for (Unit o : mgr.all()) {
            if (called >= s.callForHelpCount) break;
            if (o.removed || o.f != f || o == vu || o.combatTarget != null || o.retreating(now)) continue;
            if (o.mob.getWorld() != vu.mob.getWorld() || o.mob.getLocation().distanceSquared(vl) > rSq) continue;
            o.combatTarget = la;
            mgr.setTargetSafe(o.mob, la);
            called++;
        }
        if (vu.combatTarget == null) {
            vu.combatTarget = la;
            mgr.setTargetSafe(vu.mob, la);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Unit u = mgr.unit(e.getEntity());
        if (u == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        mgr.remove(u);
        if (u.t.leader) {
            Location l = u.mob.getLocation();
            var msg = plugin.messages().get("faction.leader-fallen",
                    Messages.c("leader", u.mob.customName() != null ? u.mob.customName() : u.t.display),
                    Messages.c("faction", u.f.display));
            for (Player p : u.mob.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(l) < 96 * 96) p.sendMessage(msg);
            }
        }
    }

    /** Units don't burn in the sun (plain combust events); fire from blocks/entities still applies. */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (e instanceof EntityCombustByBlockEvent || e instanceof EntityCombustByEntityEvent) return;
        if (mgr.isUnit(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (mgr.isUnit(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        Unit u = mgr.unit(e.getEntity());
        if (u != null && u.t.has(Trait.FIRE_ARROWS)) e.getProjectile().setFireTicks(200);
    }
}
