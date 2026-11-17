package dev.unstablesmp.ability.effects;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.core.DisplayFactory;
import dev.unstablesmp.core.Hitboxes;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Transforms;
import dev.unstablesmp.swarm.Swarm;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Portal Swarm: a rotating ring of obsidian displays around a live nether-portal surface that pours waves of
 * Vexbots out for a while. Enemies can smash it (Interaction hitbox with HP); Law's Order collapses it.
 */
public final class SwarmPortalEffect extends TimedEffect implements Hitboxes.Target {
    private final Location center;
    private final float yaw;
    private final int duration;
    private final int waveInterval;
    private final int perWave;
    private final int maxBots;
    private final List<BlockDisplay> ring = new ArrayList<>();
    private final BlockDisplay surface;
    private final Interaction hitbox;
    private final Swarm swarm;
    private final Quaternionf base = new Quaternionf();
    private final Quaternionf q = new Quaternionf();
    private final Vector3f c = new Vector3f();
    private final Vector3f v = new Vector3f();
    private double hp;
    private final double maxHp;
    private int collapseAt = -1;
    private int spawnedBots;

    public SwarmPortalEffect(UnstableSMP plugin, Caster caster, Location center, float yawRad, int durationTicks,
                             int waveInterval, int perWave, int maxBots, double hp, int ringBlocks) {
        super(plugin, caster, center.getWorld());
        this.center = center.clone();
        this.yaw = yawRad;
        this.duration = durationTicks;
        this.waveInterval = Math.max(5, waveInterval);
        this.perWave = perWave;
        this.maxBots = maxBots;
        this.hp = hp;
        this.maxHp = hp;
        base.rotationY(yawRad);
        java.util.UUID owner = caster.id();
        for (int i = 0; i < ringBlocks && plugin.tracker().canSpawnDisplays(owner, 1); i++) {
            Material m = i % 2 == 0 ? Material.OBSIDIAN : Material.CRYING_OBSIDIAN;
            ring.add(keep(plugin.displays().block(center, m, owner, d -> {
                d.setInterpolationDuration(10);
                d.setTransformation(Transforms.collapsed(0, 0, 0));
                DisplayFactory.glow(d, Color.fromRGB(0x7A2BD9));
            })));
        }
        BlockData portal = Material.NETHER_PORTAL.createBlockData();
        surface = plugin.tracker().canSpawnDisplays(owner, 1) ? keep(plugin.displays().block(center, portal, owner, d -> {
            d.setInterpolationDuration(12);
            d.setTransformation(Transforms.collapsed(0, 0, 0));
            DisplayFactory.fullBright(d);
        })) : null;
        Location hbLoc = center.clone().add(0, -1.9, 0);
        hitbox = plugin.tracker().canSpawnDisplays(owner, 1) ? keep(plugin.displays().hitbox(hbLoc, 3.2f, 3.8f, owner)) : null;
        if (hitbox != null) plugin.hitboxes().register(hitbox, this);
        swarm = plugin.swarms().createAnchored(caster, center);
        world.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, 1.5f, 1.4f);
        world.playSound(center, Sound.BLOCK_PORTAL_TRIGGER, 1.2f, 0.7f);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 120, 1.0, 1.6, 1.0, 0.3);
    }

    @Override
    public boolean tick(int now) {
        int age = age(now);
        if (collapseAt >= 0) {
            if (now - collapseAt == 0) shrink();
            return now - collapseAt < 12;
        }
        if (age >= duration || (caster.entity() != null && !caster.entity().isValid())) {
            collapse(false);
            return true;
        }
        if (age % 10 == 1) spin(age);
        if (age % 3 == 0) {
            world.spawnParticle(Particle.PORTAL, center, 10, 0.8, 1.3, 0.8, 0.6);
            world.spawnParticle(Particle.REVERSE_PORTAL, center, 4, 0.6, 1.0, 0.6, 0.05);
        }
        if (age % 40 == 0) world.playSound(center, Sound.BLOCK_PORTAL_AMBIENT, 0.9f, 0.6f);
        if (age >= 10 && age % waveInterval == 0 && spawnedBots < maxBots) {
            int n = Math.min(perWave, maxBots - spawnedBots);
            int made = plugin.swarms().spawnBots(swarm, n, center.getX(), center.getY(), center.getZ());
            spawnedBots += made;
            if (made > 0) {
                world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
                world.spawnParticle(Particle.DRAGON_BREATH, center.getX(), center.getY(), center.getZ(), 20, 0.5, 0.8, 0.5, 0.02, 1.0f);
            }
        }
        return true;
    }

    private void spin(int age) {
        double spin = age * 0.03;
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            BlockDisplay d = ring.get(i);
            if (!d.isValid()) continue;
            double a = Math.PI * 2 * i / n + spin;
            c.set((float) (Math.cos(a) * 1.75), (float) (Math.sin(a) * 2.3), 0f);
            base.transform(c);
            q.set(base).rotateZ((float) a);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(age < 5 ? 12 : 10);
            d.setTransformation(Transforms.boxAt(q, c.x, c.y, c.z, 0.62f, 0.62f, 0.62f, v));
        }
        if (surface != null && surface.isValid() && age < 5) {
            surface.setInterpolationDelay(0);
            surface.setTransformation(Transforms.box(base, 0, 0, 0, 2.6f, 3.9f, 1.0f, v));
        }
    }

    private void shrink() {
        for (BlockDisplay d : ring) {
            if (!d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(10);
            d.setTransformation(Transforms.collapsed(0, 0, 0));
        }
        if (surface != null && surface.isValid()) {
            surface.setInterpolationDelay(0);
            surface.setInterpolationDuration(10);
            surface.setTransformation(Transforms.collapsed(0, 0, 0));
        }
    }

    /** Starts the collapse animation; the portal's swarm lingers a moment and fades. */
    public void collapse(boolean destroyed) {
        if (collapseAt >= 0) return;
        collapseAt = Bukkit.getCurrentTick();
        plugin.hitboxes().unregister(hitbox);
        plugin.swarms().release(swarm, destroyed ? 20 : plugin.swarms().settings().portalLingerTicks);
        world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.5f);
        world.playSound(center, Sound.ENTITY_ENDER_EYE_DEATH, 1.5f, 0.6f);
        world.spawnParticle(Particle.LARGE_SMOKE, center, 40, 0.8, 1.4, 0.8, 0.03);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 80, 0.8, 1.4, 0.8, 0.4);
    }

    @Override
    protected void onEnd() {
        plugin.hitboxes().unregister(hitbox);
        if (collapseAt < 0) plugin.swarms().release(swarm, 1);
    }

    // ------------------------------------------------------------------ hitbox

    @Override
    public boolean canBeHitBy(Player p) {
        if (collapseAt >= 0 || p.getUniqueId().equals(caster.id())) return false;
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        return plugin.targeting().canHarm(caster, p);
    }

    @Override
    public void hit(Player p, double damage) {
        hp -= damage;
        world.spawnParticle(Particle.CRIT, p.getEyeLocation().add(p.getLocation().getDirection().multiply(1.5)), 10, 0.3, 0.3, 0.3, 0.1);
        world.playSound(center, Sound.BLOCK_DEEPSLATE_HIT, 1f, 0.7f);
        plugin.actionBar().flash(p, plugin.messages().get("portal.hit",
                Messages.p("hp", String.format(java.util.Locale.ROOT, "%.0f", Math.max(0, hp))),
                Messages.p("max", String.format(java.util.Locale.ROOT, "%.0f", maxHp))), 30);
        if (hp <= 0) {
            collapse(true);
            Bukkit.getServer().sendMessage(plugin.messages().get("portal.destroyed", Messages.c("player", p.name()), Messages.c("owner", caster.name())));
        }
    }

    @Override
    public boolean disruptible() {
        return true;
    }

    @Override
    public boolean isNear(org.bukkit.World w, double x, double y, double z, double r) {
        if (w != world) return false;
        double dx = center.getX() - x, dy = center.getY() - y, dz = center.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }
}
