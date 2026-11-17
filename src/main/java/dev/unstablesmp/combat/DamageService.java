package dev.unstablesmp.combat;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Fx;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * All plugin damage goes through here: applies combo multipliers, records attribution for kill messages and
 * builds a DamageSource credited to the caster ({@code LivingEntity.damage(amount, DamageSource)}).
 */
public final class DamageService {
    public enum Kind { MELEE, MAGIC, FIRE, EXPLOSION }

    private static boolean dealing;
    private final UnstableSMP plugin;
    private final List<ComboTable.Combo> hits = new ArrayList<>(4);

    public DamageService(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    /** True while USS is applying damage (so "owner hit X" listeners don't react to our own hits). */
    public static boolean isDealing() {
        return dealing;
    }

    /**
     * Deals damage. The caller must already have checked {@link Targeting#canHarm}. Returns the damage requested
     * after combos (the game still applies armour etc.).
     */
    public double deal(LivingEntity target, double base, Caster caster, Kind kind, String key) {
        if (base <= 0 || !target.isValid()) return 0;
        int now = Bukkit.getCurrentTick();
        hits.clear();
        Marks marks = plugin.marks();
        double mult = plugin.combos().multiplier(key, m -> marks.has(target, m, now), hits);
        double amount = base * mult;
        if (!hits.isEmpty()) announceCombo(target, caster);
        plugin.attribution().record(target.getUniqueId(), key, caster.name(), caster.id(), now);
        DamageSource src = source(caster, kind);
        dealing = true;
        try {
            target.damage(amount, src);
        } finally {
            dealing = false;
        }
        return amount;
    }

    private void announceCombo(LivingEntity target, Caster caster) {
        Player p = caster.player();
        Fx.particle(target.getWorld(), Particle.ENCHANTED_HIT, target.getLocation().add(0, target.getHeight() * 0.6, 0), 14, 0.4, 0.2);
        if (p == null) return;
        for (ComboTable.Combo c : hits) {
            if (!c.message().isEmpty()) {
                plugin.actionBar().flash(p, Text.mm(c.message()), 30);
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.6f);
                break;
            }
        }
        if (plugin.getConfig().getBoolean("combos.chat-announce", false)) {
            plugin.messages().send(p, "combo.chat", Messages.p("combo", hits.get(0).id()));
        }
    }

    private static DamageSource source(Caster caster, Kind kind) {
        LivingEntity e = caster.entity();
        if (e == null || !e.isValid()) {
            DamageType t = switch (kind) {
                case FIRE -> DamageType.IN_FIRE;
                case EXPLOSION -> DamageType.EXPLOSION;
                default -> DamageType.MAGIC;
            };
            return DamageSource.builder(t).build();
        }
        boolean player = e instanceof Player;
        DamageType t = switch (kind) {
            case MELEE -> player ? DamageType.PLAYER_ATTACK : DamageType.MOB_ATTACK;
            case MAGIC -> DamageType.INDIRECT_MAGIC;
            case FIRE -> DamageType.IN_FIRE;
            case EXPLOSION -> player ? DamageType.PLAYER_EXPLOSION : DamageType.EXPLOSION;
        };
        return DamageSource.builder(t).withCausingEntity(e).withDirectEntity(e).build();
    }
}
