package dev.unstablesmp.ability.sets;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.ability.AbilityItems;
import dev.unstablesmp.ability.AbilityService;
import dev.unstablesmp.ability.AbilityType;
import dev.unstablesmp.ability.Aim;
import dev.unstablesmp.ability.effects.AshfallEffect;
import dev.unstablesmp.ability.effects.EmberTrailEffect;
import dev.unstablesmp.ability.effects.RingPulseEffect;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.combat.DamageService;
import dev.unstablesmp.core.Messages;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/** CINDERCREST: Ember Crest (burning trail), Ashfall (meteor rain), Phoenix Burst (low-HP explosion, once per life). */
public final class CinderAbilities implements Listener {
    private static final Particle.DustOptions EMBER = new Particle.DustOptions(Color.fromRGB(0xFF7A1A), 2.0f);
    private static final Particle.DustOptions ASH = new Particle.DustOptions(Color.fromRGB(0x3A3A3A), 1.6f);
    private final UnstableSMP plugin;
    private final Set<UUID> phoenixSpent = new HashSet<>();

    public CinderAbilities(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void register(AbilityService s) {
        s.register(AbilityType.EMBER_CREST, this::ember);
        s.register(AbilityType.ASHFALL, this::ashfall);
        s.register(AbilityType.PHOENIX_BURST, this::phoenixManual);
    }

    public boolean phoenixSpent(UUID id) {
        return phoenixSpent.contains(id);
    }

    /** RC: leave a burning trail for a while (and move faster). Sneak: send a line of embers racing forward. */
    private boolean ember(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        Material tile = AbilityService.mat(cfg, "tile-block", Material.MAGMA_BLOCK);
        int life = (int) (cfg.getDouble("tile-seconds", 3) * 20);
        double dmg = cfg.getDouble("damage", 1.5);
        int fire = cfg.getInt("fire-ticks", 60);
        if (sneak) {
            Location l = p.getLocation();
            double yaw = Math.toRadians(l.getYaw());
            return plugin.effects().add(new EmberTrailEffect(plugin, c, l, -Math.sin(yaw), Math.cos(yaw),
                    cfg.getInt("wave-length", 14), life, dmg, fire, tile));
        }
        int dur = (int) (cfg.getDouble("duration-seconds", 6) * 20);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, dur, cfg.getInt("speed-level", 1) - 1, false, true));
        return plugin.effects().add(new EmberTrailEffect(plugin, c, p, dur, life, dmg, fire, cfg.getInt("max-tiles", 30), tile));
    }

    /** RC: meteor rain on the spot you look at. Sneak: a few huge meteors, tightly grouped. */
    private boolean ashfall(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        double range = cfg.getDouble("range", 35);
        LivingEntity t = Aim.entity(p, range);
        Location at = t != null ? t.getLocation() : Aim.block(p, range);
        if (at == null) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.no-target"), 30);
            return false;
        }
        Material block = AbilityService.mat(cfg, "meteor-block", Material.MAGMA_BLOCK);
        AshfallEffect e = sneak
                ? new AshfallEffect(plugin, c, at, cfg.getInt("sneak-meteors", 4), 1.5, 8, cfg.getInt("flight-ticks", 16),
                cfg.getDouble("sneak-radius", 3.2), cfg.getDouble("sneak-damage", 7), cfg.getInt("fire-ticks", 60), 1.8f, block)
                : new AshfallEffect(plugin, c, at, cfg.getInt("meteors", 10), cfg.getDouble("spread", 5), cfg.getInt("spacing-ticks", 4),
                cfg.getInt("flight-ticks", 16), cfg.getDouble("radius", 2.6), cfg.getDouble("damage", 5), cfg.getInt("fire-ticks", 60),
                (float) cfg.getDouble("meteor-size", 1.2), block);
        return plugin.effects().add(e);
    }

    /** RC: show status. Sneak: detonate manually when under the manual threshold (uses the once-per-life charge). */
    private boolean phoenixManual(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        if (phoenixSpent.contains(p.getUniqueId())) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.phoenix-spent", Messages.c("ability", plugin.abilities().name(AbilityType.PHOENIX_BURST))), 40);
            return false;
        }
        if (!sneak) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.phoenix-ready", Messages.c("ability", plugin.abilities().name(AbilityType.PHOENIX_BURST))), 40);
            return false;
        }
        double max = maxHealth(p);
        if (p.getHealth() > max * cfg.getDouble("manual-threshold", 0.5)) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.phoenix-too-healthy"), 40);
            return false;
        }
        burst(p, c, cfg);
        return true;
    }

    private static double maxHealth(Player p) {
        AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
        return ai == null ? 20 : ai.getValue();
    }

    private boolean carries(Player p, boolean hotbarOnly) {
        PlayerInventory inv = p.getInventory();
        if (AbilityItems.of(inv.getItemInOffHand()) == AbilityType.PHOENIX_BURST) return true;
        int end = hotbarOnly ? 9 : inv.getStorageContents().length;
        ItemStack[] items = inv.getStorageContents();
        for (int i = 0; i < end && i < items.length; i++) {
            if (AbilityItems.of(items[i]) == AbilityType.PHOENIX_BURST) return true;
        }
        return false;
    }

    /** Passive trigger: damage that would drop the holder to the threshold sets off the burst (once per life). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || phoenixSpent.contains(p.getUniqueId())) return;
        ConfigurationSection cfg = plugin.abilities().cfg(AbilityType.PHOENIX_BURST);
        double after = p.getHealth() - e.getFinalDamage();
        if (after > cfg.getDouble("trigger-health", 6)) return;
        if (!p.hasPermission(AbilityType.PHOENIX_BURST.permission())) return;
        if (!carries(p, cfg.getBoolean("require-hotbar", false))) return;
        Caster c = plugin.targeting().casterOf(p);
        if (after <= 0 && cfg.getBoolean("cheat-death", true)) {
            e.setCancelled(true);
            p.setHealth(Math.max(1.0, Math.min(p.getHealth(), 1.0)));
            burst(p, c, cfg);
        } else {
            phoenixSpent.add(p.getUniqueId()); // reserve now so a second hit this tick can't double-trigger
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isValid() && !p.isDead()) burst(p, c, cfg);
            });
        }
    }

    private void burst(Player p, Caster c, ConfigurationSection cfg) {
        phoenixSpent.add(p.getUniqueId());
        Location l = p.getLocation();
        World w = p.getWorld();
        double r = cfg.getDouble("radius", 5);
        double dmg = cfg.getDouble("damage", 8);
        double kb = cfg.getDouble("knockback", 1.2);
        for (LivingEntity e : w.getNearbyLivingEntities(l, r)) {
            if (!plugin.targeting().canHarm(c, e)) continue;
            plugin.damage().deal(e, dmg, c, DamageService.Kind.EXPLOSION, "phoenix_burst");
            e.setFireTicks(Math.max(e.getFireTicks(), cfg.getInt("fire-ticks", 100)));
            Vector away = e.getLocation().toVector().subtract(l.toVector()).setY(0);
            if (away.lengthSquared() < 1e-3) away = new Vector(1, 0, 0);
            e.setVelocity(away.normalize().multiply(kb).setY(0.6));
        }
        p.setHealth(Math.min(maxHealth(p), Math.max(p.getHealth(), cfg.getDouble("heal-to", 8))));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, (int) (cfg.getDouble("regen-seconds", 5) * 20), 1, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, (int) (cfg.getDouble("fire-resistance-seconds", 10) * 20), 0, false, true));
        p.setFireTicks(0);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, l, 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.FLAME, l.getX(), l.getY() + 1, l.getZ(), 120, 0.6, 1.0, 0.6, 0.25);
        w.spawnParticle(Particle.LAVA, l.getX(), l.getY() + 1, l.getZ(), 30, 1.0, 0.6, 1.0, 0);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, l.getX(), l.getY() + 1.5, l.getZ(), 60, 0.5, 1.0, 0.5, 0.4);
        w.playSound(l, Sound.ITEM_TOTEM_USE, 1f, 1.4f);
        w.playSound(l, Sound.ENTITY_BLAZE_DEATH, 1.5f, 0.6f);
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.8f);
        plugin.effects().add(new RingPulseEffect(plugin, c, l, r, 8, EMBER, ASH, Particle.FLAME));
        Bukkit.getServer().sendMessage(plugin.messages().get("ability.phoenix-broadcast", Messages.c("player", p.name())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        phoenixSpent.remove(e.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        phoenixSpent.remove(e.getPlayer().getUniqueId());
    }
}
