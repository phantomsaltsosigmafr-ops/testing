package dev.unstablesmp.ability.effects;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.combat.Caster;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * Base for ability effects that live for a while (zones, gavels, chains, meteors, portals). Ticked by the
 * {@link EffectManager} from the shared ticker; every spawned display is registered so {@link #end()} can always
 * clean up.
 */
public abstract class TimedEffect {
    protected final UnstableSMP plugin;
    protected final Caster caster;
    protected final World world;
    protected final int startTick;
    protected final List<Entity> entities = new ArrayList<>();
    private boolean ended;

    protected TimedEffect(UnstableSMP plugin, Caster caster, World world) {
        this.plugin = plugin;
        this.caster = caster;
        this.world = world;
        this.startTick = org.bukkit.Bukkit.getCurrentTick();
    }

    public Caster caster() {
        return caster;
    }

    protected int age(int now) {
        return now - startTick;
    }

    /** Advances the effect. Return false when finished. */
    public abstract boolean tick(int now);

    /** Called when the effect is removed by Order or another disruption. Default: just end. */
    public boolean disruptible() {
        return false;
    }

    /** Location test for disruption (Order). */
    public boolean isNear(World w, double x, double y, double z, double r) {
        return false;
    }

    protected <T extends Entity> T keep(T e) {
        if (e != null) entities.add(e);
        return e;
    }

    /** Removes every display this effect spawned. Safe to call twice. */
    public void end() {
        if (ended) return;
        ended = true;
        onEnd();
        for (Entity e : entities) plugin.tracker().remove(e);
        entities.clear();
    }

    protected void onEnd() {
    }
}
