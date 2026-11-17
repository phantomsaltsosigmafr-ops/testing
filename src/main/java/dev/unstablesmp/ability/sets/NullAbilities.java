package dev.unstablesmp.ability.sets;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.ability.AbilityService;
import dev.unstablesmp.ability.AbilityType;
import dev.unstablesmp.ability.Aim;
import dev.unstablesmp.ability.effects.BrandEffect;
import dev.unstablesmp.ability.effects.NullFieldEffect;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.core.Fx;
import dev.unstablesmp.core.Ground;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.swarm.Swarm;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** NULL: Null Step, Null Field, Null Brand. Black/white glitch visuals. */
public final class NullAbilities {
    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(0x050505), 1.4f);
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.WHITE, 1.1f);
    private final UnstableSMP plugin;

    public NullAbilities(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void register(AbilityService s) {
        s.register(AbilityType.NULL_STEP, this::step);
        s.register(AbilityType.NULL_FIELD, this::field);
        s.register(AbilityType.NULL_BRAND, this::brand);
    }

    /** RC: blink forward along your view. Sneak: blink backward. Never lands you inside blocks or over a void. */
    private boolean step(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        double range = sneak ? cfg.getDouble("sneak-range", 6) : cfg.getDouble("range", 9);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        if (sneak) dir.multiply(-1).setY(Math.max(-0.2, Math.min(0.2, dir.getY())));
        dir.normalize();
        World w = p.getWorld();
        RayTraceResult hit = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double max = hit == null ? range : Math.max(0, hit.getHitPosition().distance(eye.toVector()) - 0.8);
        int maxDrop = cfg.getInt("max-drop", 6);
        Location dest = null;
        double eyeH = p.getEyeHeight();
        for (double d = max; d >= 1.5; d -= 0.5) {
            double x = eye.getX() + dir.getX() * d, y = eye.getY() + dir.getY() * d - eyeH, z = eye.getZ() + dir.getZ() * d;
            dest = Ground.safeLanding(w, x, y, z, maxDrop);
            if (dest != null) break;
        }
        if (dest == null) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.no-safe-spot"), 30);
            return false;
        }
        Location from = p.getLocation();
        dest.setYaw(from.getYaw());
        dest.setPitch(from.getPitch());
        glitch(w, from.clone().add(0, 1, 0));
        trail(w, from.clone().add(0, 1, 0), dest.clone().add(0, 1, 0));
        p.setFallDistance(0);
        p.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        glitch(w, dest.clone().add(0, 1, 0));
        w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.8f);
        w.playSound(dest, Sound.BLOCK_SCULK_SENSOR_CLICKING, 1f, 1.5f);
        w.playSound(dest, Sound.ENTITY_ALLAY_HURT, 0.6f, 0.5f);
        return true;
    }

    private void glitch(World w, Location l) {
        Fx.dust(w, l.getX(), l.getY(), l.getZ(), 18, 0.35, BLACK);
        Fx.dust(w, l.getX(), l.getY(), l.getZ(), 10, 0.45, WHITE);
        w.spawnParticle(Particle.SQUID_INK, l, 12, 0.3, 0.5, 0.3, 0.05);
        w.spawnParticle(Particle.WHITE_ASH, l, 20, 0.4, 0.6, 0.4, 0.02);
    }

    private void trail(World w, Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.1) return;
        d.multiply(1 / len);
        for (double t = 0; t < len; t += 0.4) {
            Fx.dust(w, a.getX() + d.getX() * t, a.getY() + d.getY() * t, a.getZ() + d.getZ() * t, 1, 0.05, ((int) (t * 2.5)) % 2 == 0 ? BLACK : WHITE);
        }
    }

    /** RC: Null Field where you look. Sneak: centred on yourself. */
    private boolean field(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        Location at;
        if (sneak) {
            at = p.getLocation();
        } else {
            double range = cfg.getDouble("range", 20);
            at = Aim.block(p, range);
            if (at == null) {
                Location pt = Aim.point(p, range);
                at = Ground.groundAt(p.getWorld(), pt.getX(), pt.getY(), pt.getZ());
            }
        }
        NullFieldEffect e = new NullFieldEffect(plugin, c, at, cfg.getDouble("radius", 6),
                (int) (cfg.getDouble("duration-seconds", 8) * 20), Math.max(0, cfg.getInt("slowness-level", 2) - 1),
                cfg.getDouble("damage-per-pulse", 0), cfg.getInt("glitch-cubes", 8));
        return plugin.effects().add(e);
    }

    /** RC: brand the target you look at (it takes bonus swarm damage). Sneak: brand every enemy around you. */
    private boolean brand(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        Material sigil = AbilityService.mat(cfg, "sigil-item", Material.ECHO_SHARD);
        if (sneak) {
            double r = cfg.getDouble("sneak-radius", 6);
            int ticks = (int) (cfg.getDouble("sneak-duration-seconds", 5) * 20);
            int n = 0;
            for (LivingEntity e : p.getWorld().getNearbyLivingEntities(p.getLocation(), r)) {
                if (!plugin.targeting().canHarm(c, e)) continue;
                plugin.effects().add(new BrandEffect(plugin, c, e, ticks, sigil));
                notifyBranded(e);
                n++;
            }
            if (n == 0) {
                plugin.actionBar().flash(p, plugin.messages().get("ability.no-target"), 30);
                return false;
            }
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 1.6f);
            return true;
        }
        LivingEntity t = Aim.entity(p, cfg.getDouble("range", 30));
        if (t == null || !plugin.targeting().canHarm(c, t)) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.no-target"), 30);
            return false;
        }
        plugin.effects().add(new BrandEffect(plugin, c, t, (int) (cfg.getDouble("duration-seconds", 10) * 20), sigil));
        notifyBranded(t);
        Swarm sw = plugin.swarms().swarmOf(p.getUniqueId());
        if (sw != null) plugin.swarms().setTarget(sw, t);
        p.getWorld().playSound(t.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 1.8f);
        plugin.actionBar().flash(p, plugin.messages().get("ability.branded", Messages.c("target", t.name())), 40);
        return true;
    }

    private void notifyBranded(LivingEntity e) {
        if (e instanceof Player victim) plugin.messages().send(victim, "ability.you-are-branded");
    }
}
