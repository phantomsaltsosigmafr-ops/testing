package dev.unstablesmp.bot.ai;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.bot.BotLoadout;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

/**
 * The bots' hands: swinging with the 1.9 cooldown, crits, sprint/w-tap knockback, axe shield breaks, sweeping,
 * blocking, healing (gapples, splash pots), totems, buffs, pearls, rods, bows, lava and cobwebs.
 */
public final class BotCombat {
    private final UnstableSMP plugin;
    private final ItemStack gapple = new ItemStack(Material.GOLDEN_APPLE);
    private final ItemStack healPot;
    private final ItemStack totem = new ItemStack(Material.TOTEM_OF_UNDYING);
    private final ItemStack shield = new ItemStack(Material.SHIELD);

    public BotCombat(UnstableSMP plugin) {
        this.plugin = plugin;
        healPot = new ItemStack(Material.SPLASH_POTION);
        if (healPot.getItemMeta() instanceof PotionMeta pm) {
            pm.setBasePotionType(PotionType.STRONG_HEALING);
            healPot.setItemMeta(pm);
        }
    }

    // ------------------------------------------------------------------ equipment

    public void hold(Bot b, ItemStack item) {
        EntityEquipment eq = b.body.getEquipment();
        ItemStack cur = eq.getItemInMainHand();
        if (item == null ? cur.getType().isAir() : cur.getType() == item.getType()) return;
        eq.setItemInMainHand(item == null ? null : item.clone());
        b.cooldownFull = BotLoadout.cooldownTicks(item);
    }

    public ItemStack held(Bot b) {
        return b.body.getEquipment().getItemInMainHand();
    }

    public void offhand(Bot b, ItemStack item) {
        b.body.getEquipment().setItemInOffHand(item == null ? null : item.clone());
    }

    // ------------------------------------------------------------------ aiming

    /** Faces the target's chest with tier-based aim error (stable for a few ticks, re-rolled on think). */
    public void face(Bot b, LivingEntity t, Location bl, Location tl, double err) {
        double dx = tl.getX() - bl.getX(), dz = tl.getZ() - bl.getZ();
        double dy = (tl.getY() + t.getHeight() * 0.6) - (bl.getY() + b.body.getEyeHeight());
        b.lookYaw = MoveMath.yaw(dx, dz) + (float) err;
        b.lookPitch = BotMovement.pitchTo(dx, dy, dz) + (float) (err * 0.4);
    }

    // ------------------------------------------------------------------ melee

    /** Swings at the target if it is in reach. Returns true if the swing connected. */
    public boolean swing(Bot b, LivingEntity t, double dist, int now, ItemStack weapon, double baseOverride, String key) {
        Mannequin m = b.body;
        m.swingMainHand();
        double charge = b.charge(now);
        b.lastSwing = now;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (dist > b.s.reach + reachBonus(t) || r.nextDouble() < b.s.miss) {
            m.getWorld().playSound(m.getLocation(), Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.6f, 1f);
            b.comboHits = 0;
            return false;
        }
        hit(b, t, now, weapon, charge, baseOverride, key);
        return true;
    }

    private static double reachBonus(LivingEntity t) {
        return t.getWidth() * 0.5 + 0.3;
    }

    public void hit(Bot b, LivingEntity t, int now, ItemStack weapon, double charge, double baseOverride, String key) {
        Mannequin m = b.body;
        World w = m.getWorld();
        double base = baseOverride > 0 ? baseOverride : BotLoadout.damage(weapon);
        double scale = 0.2 + charge * charge * 0.8;
        double dmg = base * scale;
        PotionEffect str = m.getPotionEffect(PotionEffectType.STRENGTH);
        if (str != null) dmg += 3 * (str.getAmplifier() + 1);
        PotionEffect weak = m.getPotionEffect(PotionEffectType.WEAKNESS);
        if (weak != null) dmg -= 4 * (weak.getAmplifier() + 1);
        boolean crit = charge > 0.9 && !m.isOnGround() && m.getVelocity().getY() < -0.03 && baseOverride <= 0;
        if (crit) {
            dmg *= 1.5;
            w.spawnParticle(Particle.CRIT, t.getLocation().add(0, t.getHeight() * 0.7, 0), 14, 0.3, 0.4, 0.3, 0.25);
            w.playSound(t.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
        }
        boolean sprintHit = b.sprinting && charge > 0.9 && !crit;
        boolean axe = weapon != null && weapon.getType().name().endsWith("_AXE");
        if (axe && blocking(t)) breakShield(t, now);
        Location before = t.getLocation();
        plugin.attribution().record(t.getUniqueId(), "bot", b.name, m.getUniqueId(), now);
        DamageSource src = DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(m).withDirectEntity(m).build();
        t.damage(Math.max(0.5, dmg), src);
        b.hitsLanded++;
        b.comboHits++;
        if (weapon != null) {
            int fire = weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
            if (fire > 0) t.setFireTicks(Math.max(t.getFireTicks(), 80 * fire));
            int kb = weapon.getEnchantmentLevel(Enchantment.KNOCKBACK);
            if (kb > 0 || sprintHit) {
                double rad = Math.toRadians(b.lookYaw);
                double k = (sprintHit ? 0.45 : 0) + kb * 0.5;
                Vector v = t.getVelocity();
                v.setX(v.getX() - Math.sin(rad) * k);
                v.setZ(v.getZ() + Math.cos(rad) * k);
                v.setY(Math.max(v.getY(), 0.1));
                t.setVelocity(v);
                if (sprintHit) w.playSound(before, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 1f);
            }
            if (weapon.getType().name().endsWith("_SWORD") && charge > 0.9 && !crit && !b.sprinting && m.isOnGround()) sweep(b, t, w, src, dmg);
        }
        if (!crit && !sprintHit) w.playSound(before, charge > 0.9 ? Sound.ENTITY_PLAYER_ATTACK_STRONG : Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.8f, 1f);
        if (sprintHit) {
            b.sprinting = false;
            if (ThreadLocalRandom.current().nextDouble() < b.s.wtapRate) b.wtapUntil = now + 2 + ThreadLocalRandom.current().nextInt(2);
        }
    }

    private void sweep(Bot b, LivingEntity t, World w, DamageSource src, double dmg) {
        Location c = t.getLocation();
        w.spawnParticle(Particle.SWEEP_ATTACK, c.getX(), c.getY() + 1, c.getZ(), 1, 0, 0, 0, 0);
        w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1f);
        for (LivingEntity e : w.getNearbyLivingEntities(c, 1.2, 0.5)) {
            if (e == t || e == b.body || !plugin.bots().isEnemy(b, e)) continue;
            e.damage(1.0, src);
        }
    }

    /** Is the entity holding up a shield (players, bots, anything using a shield)? */
    public static boolean blocking(LivingEntity t) {
        if (t instanceof org.bukkit.entity.HumanEntity h) return h.isBlocking();
        return t.getActiveItem().getType() == Material.SHIELD;
    }

    /** Axe vs a raised shield: shield goes on cooldown, like vanilla. */
    public void breakShield(LivingEntity t, int now) {
        if (t instanceof Player p) {
            p.setCooldown(Material.SHIELD, 100);
            p.clearActiveItem();
        } else {
            Bot other = plugin.bots().bot(t);
            if (other != null) {
                other.blockUntil = 0;
                other.body.clearActiveItem();
                other.shieldDownUntil = now + 100;
            }
        }
        t.getWorld().playSound(t.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 0.9f);
    }

    // ------------------------------------------------------------------ defence & healing

    public void block(Bot b, int now, int ticks) {
        if (!b.kit.shield || now < b.shieldDownUntil) return;
        offhand(b, shield);
        b.body.startUsingItem(EquipmentSlot.OFF_HAND);
        b.blockUntil = now + ticks;
    }

    public void unblock(Bot b) {
        if (b.blockUntil > 0) {
            b.body.clearActiveItem();
            b.blockUntil = 0;
        }
    }

    /** Starts eating a golden apple (animation); effects land when the bot finishes. */
    public boolean eatGapple(Bot b, int now) {
        if (b.gapples <= 0 || now < b.gapCd || now < b.eatUntil) return false;
        unblock(b);
        hold(b, gapple);
        b.body.startUsingItem(EquipmentSlot.HAND);
        b.eatUntil = now + 30;
        b.eating = "gapple";
        b.gapCd = now + 30;
        return true;
    }

    /** Called every tick: completes eating. */
    public void finishEating(Bot b, int now) {
        if (b.eating == null || now < b.eatUntil) return;
        b.body.clearActiveItem();
        if ("gapple".equals(b.eating)) {
            b.gapples--;
            b.body.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
            b.body.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
            b.body.getWorld().playSound(b.body.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.8f, 1f);
        }
        b.eating = null;
        hold(b, b.kit.primary(b.mode));
    }

    /** Throws a real Strength-II splash heal at its own feet (affects everyone in range, like vanilla). */
    public boolean splashHeal(Bot b, int now) {
        if (b.healPots <= 0 || now < b.potCd) return false;
        b.healPots--;
        b.potCd = now + 7;
        Mannequin m = b.body;
        b.lookPitch = 85;
        m.setRotation(b.lookYaw, 85);
        m.swingMainHand();
        ThrownPotion pot = m.launchProjectile(ThrownPotion.class, new Vector(0, -0.6, 0));
        pot.setItem(healPot.clone());
        return true;
    }

    /** Drinks/throws speed and strength when they run out (netherite pot). */
    public void buffs(Bot b, int now) {
        if (now < b.buffCd) return;
        b.buffCd = now + 40;
        Mannequin m = b.body;
        if (b.speedPots > 0 && !m.hasPotionEffect(PotionEffectType.SPEED)) {
            b.speedPots--;
            m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 1800, 1));
            m.getWorld().playSound(m.getLocation(), Sound.ENTITY_SPLASH_POTION_BREAK, 0.8f, 1f);
        }
        if (b.strengthPots > 0 && !m.hasPotionEffect(PotionEffectType.STRENGTH)) {
            b.strengthPots--;
            m.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 1800, 1));
            m.getWorld().playSound(m.getLocation(), Sound.ENTITY_SPLASH_POTION_BREAK, 0.8f, 1.1f);
        }
    }

    /** Keeps a totem in the offhand while totems remain (re-equip delay after a pop is tier-based). */
    public void totem(Bot b, int now) {
        if (b.totems <= 0 && !b.kit.offhandTotem) return;
        if (b.retotemAt >= 0 && now < b.retotemAt) return;
        ItemStack off = b.body.getEquipment().getItemInOffHand();
        if (off.getType() == Material.TOTEM_OF_UNDYING) return;
        if (now < b.blockUntil) return;
        if (b.totems > 0) {
            b.totems--;
            offhand(b, totem);
            b.retotemAt = -1;
        }
    }

    // ------------------------------------------------------------------ utility

    /** Throws an ender pearl at a point (high arc correction); the listener teleports the bot when it lands. */
    public boolean pearl(Bot b, Location to, int now) {
        if (b.pearls <= 0 || now < b.pearlCd) return false;
        Location from = b.body.getEyeLocation();
        Vector d = to.toVector().subtract(from.toVector());
        double dist = Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ());
        if (dist < 6 || dist > 45) return false;
        b.pearls--;
        b.pearlCd = now + 20;
        Vector v = new Vector(d.getX(), 0, d.getZ()).normalize().multiply(1.25);
        v.setY(Math.min(1.1, 0.25 + dist * 0.018 + d.getY() * 0.05));
        b.body.swingMainHand();
        EnderPearl ep = b.body.launchProjectile(EnderPearl.class, v);
        plugin.bots().trackPearl(ep, b);
        b.body.getWorld().playSound(from, Sound.ENTITY_ENDER_PEARL_THROW, 0.8f, 0.6f);
        return true;
    }

    /** Fishing-rod pull: a tiny hit that knocks the target back toward the combo, like a real rod. */
    public boolean rod(Bot b, LivingEntity t, double dist, int now) {
        if (!b.kit.rod || now < b.rodCd || dist < 3 || dist > 7.5) return false;
        b.rodCd = now + 30;
        b.body.swingMainHand();
        b.body.getWorld().playSound(b.body.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 0.6f, 0.6f);
        if (ThreadLocalRandom.current().nextDouble() < b.s.miss * 2) return true;
        DamageSource src = DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(b.body).withDirectEntity(b.body).build();
        t.damage(0.01, src);
        b.body.getWorld().playSound(t.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1f);
        return true;
    }

    /** Fires a real arrow with drop compensation. */
    public void shoot(Bot b, LivingEntity t, double power) {
        if (b.arrows <= 0) return;
        b.arrows--;
        Location eye = b.body.getEyeLocation();
        Location tl = t.getLocation().add(0, t.getHeight() * 0.6, 0);
        Vector d = tl.toVector().subtract(eye.toVector());
        double dist = d.length();
        double err = Math.toRadians(b.s.aimError) * 0.15;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        d.add(new Vector(r.nextGaussian() * err * dist, dist * 0.012 * dist / 20 + 0.1, r.nextGaussian() * err * dist));
        Vector v = d.normalize().multiply(3.0 * power);
        Arrow a = b.body.launchProjectile(Arrow.class, v);
        a.setDamage(power > 0.9 ? 2.5 : 1.5);
        b.body.getWorld().playSound(eye, Sound.ENTITY_ARROW_SHOOT, 1f, 1f);
    }

    public boolean lava(Bot b, LivingEntity t, int now) {
        if (b.lava <= 0 || now < b.lavaCd || !t.isOnGround()) return false;
        Block feet = t.getLocation().getBlock();
        if (!plugin.regions().canUnitBuild(feet.getLocation())) return false;
        if (!plugin.tempBlocks().place(feet, Material.LAVA, 60, now)) return false;
        b.lava--;
        b.lavaCd = now + 80;
        b.body.swingMainHand();
        b.body.getWorld().playSound(feet.getLocation(), Sound.ITEM_BUCKET_EMPTY_LAVA, 1f, 1f);
        return true;
    }

    public boolean web(Bot b, Location at, int now) {
        if (b.cobwebs <= 0 || now < b.webCd) return false;
        Block blk = at.getBlock();
        if (!plugin.regions().canUnitBuild(blk.getLocation())) return false;
        if (!plugin.tempBlocks().place(blk, Material.COBWEB, 100, now)) return false;
        b.cobwebs--;
        b.webCd = now + 60;
        b.body.swingMainHand();
        b.body.getWorld().playSound(blk.getLocation(), Sound.BLOCK_COBWEB_PLACE, 1f, 1f);
        return true;
    }

    /** Wind-charge self launch (mace style). */
    public boolean windLaunch(Bot b, LivingEntity t, int now, double up) {
        if (b.windCharges <= 0 || now < b.windCd) return false;
        b.windCharges--;
        b.windCd = now + 15;
        Mannequin m = b.body;
        Location l = m.getLocation();
        Vector v = m.getVelocity();
        Location tl = t.getLocation();
        double dx = tl.getX() - l.getX(), dz = tl.getZ() - l.getZ(), h = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
        v.setX(dx / h * Math.min(0.6, h * 0.08));
        v.setZ(dz / h * Math.min(0.6, h * 0.08));
        v.setY(up);
        m.setVelocity(v);
        m.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, l, 1, 0, 0, 0, 0);
        m.getWorld().playSound(l, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f, 1f);
        b.peakY = l.getY();
        b.fallStartY = l.getY();
        return true;
    }

    /** Vanilla mace smash bonus for a fall of {@code fall} blocks (plus Density). */
    public static double maceBonus(double fall, int density) {
        double bonus;
        if (fall <= 3) bonus = 4 * fall;
        else if (fall <= 8) bonus = 12 + 2 * (fall - 3);
        else bonus = 22 + (fall - 8);
        return bonus + density * 0.5 * fall;
    }

    /** Lands a mace smash: big damage, AoE knockback, heavy sound, no fall damage for the bot. */
    public void smash(Bot b, LivingEntity t, int now, double fall) {
        ItemStack mace = b.kit.mace != null ? b.kit.mace : new ItemStack(Material.MACE);
        int density = mace.getEnchantmentLevel(Enchantment.DENSITY);
        double dmg = 6 + maceBonus(fall, density);
        Location c = t.getLocation();
        World w = c.getWorld();
        hit(b, t, now, mace, 1.0, dmg, "mace");
        b.body.setFallDistance(0);
        w.playSound(c, fall > 5 ? Sound.ITEM_MACE_SMASH_GROUND_HEAVY : Sound.ITEM_MACE_SMASH_GROUND, 1.2f, 1f);
        w.spawnParticle(Particle.BLOCK, c.getX(), c.getY(), c.getZ(), 40, 0.8, 0.1, 0.8, 0, c.clone().add(0, -0.5, 0).getBlock().getBlockData());
        for (LivingEntity e : w.getNearbyLivingEntities(c, 3.5, 2)) {
            if (e == t || e == b.body || !plugin.bots().isEnemy(b, e)) continue;
            Vector away = e.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() < 1e-3) continue;
            e.setVelocity(away.normalize().multiply(0.7).setY(0.4));
        }
        if (mace.getEnchantmentLevel(Enchantment.WIND_BURST) > 0) {
            Vector v = b.body.getVelocity();
            v.setY(0.9 + 0.25 * mace.getEnchantmentLevel(Enchantment.WIND_BURST));
            b.body.setVelocity(v);
            w.spawnParticle(Particle.GUST_EMITTER_SMALL, b.body.getLocation(), 1, 0, 0, 0, 0);
            w.playSound(b.body.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f, 1.2f);
            b.peakY = b.body.getLocation().getY();
        }
    }

    /** Small helper so tactics don't each need trigonometry imports. */
    public static final class MoveMath {
        private MoveMath() {
        }

        public static float yaw(double dx, double dz) {
            return (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
    }
}
