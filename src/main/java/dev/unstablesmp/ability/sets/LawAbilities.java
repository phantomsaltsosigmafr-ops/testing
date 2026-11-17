package dev.unstablesmp.ability.sets;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.ability.AbilityService;
import dev.unstablesmp.ability.AbilityType;
import dev.unstablesmp.ability.Aim;
import dev.unstablesmp.ability.effects.ChainsEffect;
import dev.unstablesmp.ability.effects.GavelEffect;
import dev.unstablesmp.ability.effects.RingPulseEffect;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.combat.Mark;
import dev.unstablesmp.core.Ground;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** LAW: Judgment (gavel slam), Sentence (chains), Order (silence enemy swarms and abilities). Gold and white. */
public final class LawAbilities {
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(0xFFC83D), 1.8f);
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.WHITE, 1.5f);
    private final UnstableSMP plugin;

    public LawAbilities(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void register(AbilityService s) {
        s.register(AbilityType.JUDGMENT, this::judgment);
        s.register(AbilityType.SENTENCE, this::sentence);
        s.register(AbilityType.ORDER, this::order);
    }

    private Location aimGround(Player p, double range) {
        LivingEntity t = Aim.entity(p, range);
        Location at = t != null ? t.getLocation() : Aim.block(p, range);
        if (at == null) return null;
        return Ground.groundAt(at.getWorld(), at.getX(), at.getY() + 0.5, at.getZ());
    }

    /** RC: one giant gavel. Sneak: Verdict, three smaller gavels in quick succession around the spot. */
    private boolean judgment(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        Location at = aimGround(p, cfg.getDouble("range", 30));
        if (at == null) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.no-target"), 30);
            return false;
        }
        Material head = AbilityService.mat(cfg, "head-block", Material.GOLD_BLOCK);
        Material handle = AbilityService.mat(cfg, "handle-block", Material.QUARTZ_PILLAR);
        Material band = AbilityService.mat(cfg, "band-block", Material.WHITE_CONCRETE);
        Location me = p.getLocation();
        float yaw = (float) Math.atan2(at.getX() - me.getX(), at.getZ() - me.getZ());
        double radius = cfg.getDouble("radius", 4), dmg = cfg.getDouble("damage", 9), kb = cfg.getDouble("knockback", 0.9);
        if (!sneak) {
            return plugin.effects().add(new GavelEffect(plugin, c, at, yaw, radius, dmg, kb, (float) cfg.getDouble("scale", 1.0), 0, head, handle, band));
        }
        double mult = cfg.getDouble("verdict-damage-multiplier", 0.45);
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            double a = Math.PI * 2 * i / 3 + yaw;
            Location spot = i == 0 ? at : Ground.groundAt(at.getWorld(), at.getX() + Math.sin(a) * 3, at.getY() + 1, at.getZ() + Math.cos(a) * 3);
            any |= plugin.effects().add(new GavelEffect(plugin, c, spot, yaw, radius * 0.7, dmg * mult, kb * 0.6, 0.6f, i * 7, head, handle, band));
        }
        return any;
    }

    /** RC: chain the target you look at. Sneak: chain every enemy right around you, briefly. */
    private boolean sentence(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        Material chain = AbilityService.mat(cfg, "chain-block", Material.IRON_CHAIN);
        int chains = cfg.getInt("chains", 4);
        if (sneak) {
            int ticks = (int) (cfg.getDouble("sneak-duration-seconds", 1.5) * 20);
            int n = 0;
            for (LivingEntity e : p.getWorld().getNearbyLivingEntities(p.getLocation(), cfg.getDouble("sneak-radius", 4))) {
                if (!plugin.targeting().canHarm(c, e) || plugin.marks().has(e, Mark.SENTENCED, Bukkit.getCurrentTick())) continue;
                plugin.effects().add(new ChainsEffect(plugin, c, e, ticks, chains, chain));
                n++;
            }
            if (n == 0) plugin.actionBar().flash(p, plugin.messages().get("ability.no-target"), 30);
            return n > 0;
        }
        LivingEntity t = Aim.entity(p, cfg.getDouble("range", 20));
        if (t == null || !plugin.targeting().canHarm(c, t)) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.no-target"), 30);
            return false;
        }
        if (plugin.marks().has(t, Mark.SENTENCED, Bukkit.getCurrentTick())) return false;
        plugin.effects().add(new ChainsEffect(plugin, c, t, (int) (cfg.getDouble("duration-seconds", 3) * 20), chains, chain));
        if (t instanceof Player victim) plugin.messages().send(victim, "ability.you-are-sentenced", Messages.c("caster", c.name()));
        plugin.actionBar().flash(p, plugin.messages().get("ability.sentenced", Messages.c("target", t.name())), 40);
        return true;
    }

    /** RC: Order around you. Sneak: Order where you look (smaller). Silences enemy abilities, swarms and portals. */
    private boolean order(Player p, Caster c, boolean sneak, ConfigurationSection cfg) {
        Location at = sneak ? Aim.point(p, cfg.getDouble("sneak-range", 20)) : p.getLocation();
        double r = sneak ? cfg.getDouble("sneak-radius", 6) : cfg.getDouble("radius", 10);
        int ticks = (int) (cfg.getDouble("silence-seconds", 6) * 20);
        int now = Bukkit.getCurrentTick();
        int players = 0;
        for (Player o : at.getWorld().getNearbyPlayers(at, r)) {
            if (!plugin.targeting().isEnemyPlayer(c, o) || !plugin.targeting().canHarm(c, o)) continue;
            plugin.marks().apply(o, Mark.SILENCED, now, ticks);
            plugin.messages().send(o, "ability.you-are-silenced", Messages.c("caster", c.name()), Messages.p("seconds", Text.seconds(ticks)));
            players++;
        }
        int swarms = plugin.swarms().silenceNear(c, at.getWorld(), at.getX(), at.getY(), at.getZ(), r, ticks);
        UUID me = p.getUniqueId();
        int portals = plugin.effects().disrupt(at.getWorld(), at.getX(), at.getY(), at.getZ(), r,
                e -> e.caster().id() == null || (!me.equals(e.caster().id())
                        && (c.faction() == null || !c.faction().members.contains(e.caster().id()))));
        plugin.effects().add(new RingPulseEffect(plugin, c, at, r, 10, GOLD, WHITE, Particle.END_ROD));
        at.getWorld().playSound(at, Sound.BLOCK_BELL_USE, 2f, 1.2f);
        at.getWorld().playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.5f, 1.4f);
        plugin.actionBar().flash(p, plugin.messages().get("ability.order", Messages.p("players", Integer.toString(players)),
                Messages.p("swarms", Integer.toString(swarms)), Messages.p("effects", Integer.toString(portals))), 50);
        return true;
    }
}
