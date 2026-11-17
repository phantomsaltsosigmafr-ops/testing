package dev.unstablesmp.ability;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.ability.effects.AshfallEffect;
import dev.unstablesmp.ability.effects.ChainsEffect;
import dev.unstablesmp.ability.effects.GavelEffect;
import dev.unstablesmp.ability.effects.NullFieldEffect;
import dev.unstablesmp.ability.sets.CinderAbilities;
import dev.unstablesmp.ability.sets.LawAbilities;
import dev.unstablesmp.ability.sets.NullAbilities;
import dev.unstablesmp.ability.sets.PortalSwarmAbilities;
import dev.unstablesmp.ability.sets.VexbotAbilities;
import dev.unstablesmp.combat.Caster;
import dev.unstablesmp.combat.Mark;
import dev.unstablesmp.core.ActionBarService;
import dev.unstablesmp.core.Cooldowns;
import dev.unstablesmp.core.Ground;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Registry, cast pipeline (permission, silence, world, cooldown) and action-bar cooldown display for abilities. */
public final class AbilityService implements ActionBarService.Segment {
    private final UnstableSMP plugin;
    private final AbilityItems items = new AbilityItems();
    private final Map<AbilityType, Ability> registry = new EnumMap<>(AbilityType.class);
    private final Cooldowns cooldowns = new Cooldowns();
    private final CinderAbilities cinder;
    private final VexbotAbilities vexbots;

    public AbilityService(UnstableSMP plugin) {
        this.plugin = plugin;
        vexbots = new VexbotAbilities(plugin);
        vexbots.register(this);
        new NullAbilities(plugin).register(this);
        new PortalSwarmAbilities(plugin).register(this);
        new LawAbilities(plugin).register(this);
        cinder = new CinderAbilities(plugin);
        cinder.register(this);
    }

    public void reload(ConfigurationSection abilities) {
        items.reload(abilities);
    }

    public void register(AbilityType t, Ability a) {
        registry.put(t, a);
    }

    public AbilityItems items() {
        return items;
    }

    public VexbotAbilities vexbots() {
        return vexbots;
    }

    public CinderAbilities cinder() {
        return cinder;
    }

    public ConfigurationSection cfg(AbilityType t) {
        return items.cfg(t);
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }

    /** Full cast pipeline. Returns true if the ability fired. */
    public boolean tryCast(Player p, AbilityType t, boolean sneak) {
        int now = Bukkit.getCurrentTick();
        if (!p.hasPermission(t.permission())) {
            plugin.messages().send(p, "ability.no-permission");
            return false;
        }
        List<String> disabled = plugin.getConfig().getStringList("abilities.disabled-worlds");
        if (disabled.contains(p.getWorld().getName())) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.disabled-world"), 30);
            return false;
        }
        int silenced = plugin.marks().remaining(p.getUniqueId(), Mark.SILENCED, now);
        if (silenced > 0) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.silenced", Messages.p("seconds", Text.seconds(silenced))), 30);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
            return false;
        }
        String key = sneak ? t.id + ":sneak" : t.id;
        int rem = cooldowns.remaining(p.getUniqueId(), key, now);
        if (rem > 0) {
            plugin.actionBar().flash(p, plugin.messages().get("ability.cooldown",
                    Messages.c("ability", name(t)), Messages.p("seconds", Text.seconds(rem))), 20);
            return false;
        }
        Ability a = registry.get(t);
        if (a == null) return false;
        ConfigurationSection c = items.cfg(t);
        Caster caster = plugin.targeting().casterOf(p);
        boolean ok = a.cast(p, caster, sneak, c);
        if (ok) {
            double secs = sneak ? c.getDouble("sneak-cooldown-seconds", c.getDouble("cooldown-seconds", 0)) : c.getDouble("cooldown-seconds", 0);
            cooldowns.set(p.getUniqueId(), key, now, (int) Math.round(secs * 20));
        }
        return ok;
    }

    public Component name(AbilityType t) {
        return Text.mm(items.cfg(t).getString("name", Text.pretty(t.id)));
    }

    @Override
    public Component segment(Player p, int now) {
        int silenced = plugin.marks().remaining(p.getUniqueId(), Mark.SILENCED, now);
        AbilityType t = AbilityItems.of(p.getInventory().getItemInMainHand());
        if (t == null) {
            return silenced > 0 ? plugin.messages().get("ability.silenced", Messages.p("seconds", Text.seconds(silenced))) : null;
        }
        if (t == AbilityType.PHOENIX_BURST) {
            return plugin.messages().get(cinder.phoenixSpent(p.getUniqueId()) ? "ability.phoenix-spent" : "ability.phoenix-ready",
                    Messages.c("ability", name(t)));
        }
        int a = cooldowns.remaining(p.getUniqueId(), t.id, now);
        int b = cooldowns.remaining(p.getUniqueId(), t.id + ":sneak", now);
        return plugin.messages().get("ability.actionbar", Messages.c("ability", name(t)),
                Messages.c("main", a > 0 ? plugin.messages().get("ability.cd", Messages.p("seconds", Text.seconds(a))) : plugin.messages().get("ability.ready")),
                Messages.c("sneak", b > 0 ? plugin.messages().get("ability.cd", Messages.p("seconds", Text.seconds(b))) : plugin.messages().get("ability.ready")));
    }

    /** Event strikes: a server-owned ability lands on a player (Null Outbreak, Law Crackdown, Cindercrest Firestorm). */
    public boolean serverStrike(String type, Caster caster, Player target) {
        Location at = target.getLocation();
        switch (type) {
            case "NULL_FIELD" -> {
                ConfigurationSection c = cfg(AbilityType.NULL_FIELD);
                return plugin.effects().add(new NullFieldEffect(plugin, caster, at, c.getDouble("radius", 6) * 0.8,
                        (int) (c.getDouble("duration-seconds", 8) * 15), c.getInt("slowness-level", 2) - 1, 0, 6));
            }
            case "JUDGMENT" -> {
                ConfigurationSection c = cfg(AbilityType.JUDGMENT);
                Location g = Ground.groundAt(at.getWorld(), at.getX(), at.getY(), at.getZ());
                return plugin.effects().add(new GavelEffect(plugin, caster, g, (float) Math.toRadians(-at.getYaw()), c.getDouble("radius", 4),
                        c.getDouble("damage", 9) * 0.7, c.getDouble("knockback", 0.9), 0.9f, 0,
                        mat(c, "head-block", Material.GOLD_BLOCK), mat(c, "handle-block", Material.QUARTZ_PILLAR), mat(c, "band-block", Material.WHITE_CONCRETE)));
            }
            case "ASHFALL" -> {
                ConfigurationSection c = cfg(AbilityType.ASHFALL);
                return plugin.effects().add(new AshfallEffect(plugin, caster, at, 5, 4, 5, c.getInt("flight-ticks", 16),
                        c.getDouble("radius", 2.6), c.getDouble("damage", 5) * 0.8, c.getInt("fire-ticks", 60), 1.2f,
                        mat(c, "meteor-block", Material.MAGMA_BLOCK)));
            }
            case "SENTENCE" -> {
                ConfigurationSection c = cfg(AbilityType.SENTENCE);
                return plugin.effects().add(new ChainsEffect(plugin, caster, target, (int) (c.getDouble("duration-seconds", 3) * 10), 4,
                        mat(c, "chain-block", Material.IRON_CHAIN)));
            }
            default -> {
                return false;
            }
        }
    }

    public static Material mat(ConfigurationSection c, String key, Material def) {
        Material m = Material.matchMaterial(c.getString(key, def.name()));
        return m == null ? def : m;
    }

    /** True if {@code e} is something this caster may hit (shortcut used by ability sets). */
    public boolean canHit(Caster c, LivingEntity e) {
        return e != null && plugin.targeting().canHarm(c, e);
    }
}
