package dev.unstablesmp.ability.effects;

import dev.unstablesmp.core.Ticker;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.World;

/** Ticks every active ability effect from the shared ticker. */
public final class EffectManager implements Ticker.Tickable {
    private final List<TimedEffect> effects = new ArrayList<>();
    private final List<TimedEffect> adding = new ArrayList<>();
    private boolean ticking;
    private int maxEffects = 300;

    public void setMax(int max) {
        this.maxEffects = Math.max(10, max);
    }

    /** Adds an effect; returns false (and ends it) if the server effect cap is reached. */
    public boolean add(TimedEffect e) {
        if (effects.size() + adding.size() >= maxEffects) {
            e.end();
            return false;
        }
        if (ticking) adding.add(e);
        else effects.add(e);
        return true;
    }

    public int count() {
        return effects.size();
    }

    /** Ends disruptible effects near a point that match the filter (Law: Order). Returns how many ended. */
    public int disrupt(World w, double x, double y, double z, double r, Predicate<TimedEffect> filter) {
        int n = 0;
        for (TimedEffect e : effects) {
            if (e.disruptible() && e.isNear(w, x, y, z, r) && filter.test(e)) {
                if (e instanceof SwarmPortalEffect p) p.collapse(true);
                else e.end();
                n++;
            }
        }
        return n;
    }

    public <T extends TimedEffect> List<T> ofType(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (TimedEffect e : effects) if (type.isInstance(e)) out.add(type.cast(e));
        return out;
    }

    @Override
    public void tick(int now) {
        ticking = true;
        try {
            for (int i = effects.size() - 1; i >= 0; i--) {
                TimedEffect e = effects.get(i);
                boolean alive;
                try {
                    alive = e.tick(now);
                } catch (RuntimeException ex) {
                    alive = false;
                    org.bukkit.Bukkit.getLogger().warning("[UnstableSMP] effect " + e.getClass().getSimpleName() + " failed: " + ex);
                }
                if (!alive) {
                    e.end();
                    effects.set(i, effects.get(effects.size() - 1));
                    effects.remove(effects.size() - 1);
                }
            }
        } finally {
            ticking = false;
        }
        if (!adding.isEmpty()) {
            effects.addAll(adding);
            adding.clear();
        }
    }

    public void removeAll() {
        for (TimedEffect e : effects) e.end();
        for (TimedEffect e : adding) e.end();
        effects.clear();
        adding.clear();
    }

    @Override
    public String name() {
        return "effects";
    }
}
