package dev.unstablesmp.region;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * WorldGuard-backed rules. Only loaded when WorldGuard is installed (this class references WorldGuard types).
 * <ul>
 *   <li>{@code uss-events} (default allow): deny it to stop events starting or placing portals in a region.</li>
 *   <li>{@code uss-unit-griefing} (default deny inside regions): units only build/break inside a region when the
 *       region sets it to allow. Outside any region they follow the global BUILD / BLOCK_BREAK / BLOCK_PLACE flags.</li>
 *   <li>PvP abilities follow the normal {@code pvp} flag.</li>
 * </ul>
 */
public final class WorldGuardRegionGuard implements RegionGuard {
    private static StateFlag eventsFlag;
    private static StateFlag griefFlag;

    /** Must run in onLoad, before WorldGuard enables. */
    public static void registerFlags(Logger log) {
        FlagRegistry reg = WorldGuard.getInstance().getFlagRegistry();
        eventsFlag = register(reg, "uss-events", true, log);
        griefFlag = register(reg, "uss-unit-griefing", false, log);
    }

    private static StateFlag register(FlagRegistry reg, String name, boolean def, Logger log) {
        try {
            StateFlag f = new StateFlag(name, def);
            reg.register(f);
            return f;
        } catch (FlagConflictException | IllegalStateException ex) {
            Flag<?> existing = reg.get(name);
            if (existing instanceof StateFlag sf) return sf;
            log.warning("WorldGuard flag '" + name + "' conflicts with another plugin's flag; ignoring it.");
            return null;
        }
    }

    /** Factory so callers only see the RegionGuard type (keeps WorldGuard classes out of their bytecode). */
    public static RegionGuard create() {
        return new WorldGuardRegionGuard();
    }

    private RegionQuery query() {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
    }

    private boolean unitEdit(Location at, StateFlag vanilla) {
        ApplicableRegionSet set = query().getApplicableRegions(BukkitAdapter.adapt(at));
        if (set.queryState(null, Flags.BUILD) == StateFlag.State.DENY && griefState(set) != StateFlag.State.ALLOW) return false;
        if (set.queryState(null, vanilla) == StateFlag.State.DENY) return false;
        if (set.size() == 0) return true;
        return griefState(set) == StateFlag.State.ALLOW;
    }

    private static StateFlag.State griefState(ApplicableRegionSet set) {
        return griefFlag == null ? null : set.queryState(null, griefFlag);
    }

    @Override
    public boolean canUnitBuild(Location at) {
        return unitEdit(at, Flags.BLOCK_PLACE);
    }

    @Override
    public boolean canUnitBreak(Location at) {
        return unitEdit(at, Flags.BLOCK_BREAK);
    }

    @Override
    public boolean canPvp(Player attacker, Location at) {
        if (!NoopRegionGuard.worldPvp(at)) return false;
        RegionQuery q = query();
        if (attacker != null) {
            return q.testState(BukkitAdapter.adapt(at), WorldGuardPlugin.inst().wrapPlayer(attacker), Flags.PVP);
        }
        return q.queryState(BukkitAdapter.adapt(at), null, Flags.PVP) != StateFlag.State.DENY;
    }

    @Override
    public boolean canRunEvent(Location at) {
        if (eventsFlag == null) return true;
        return query().queryState(BukkitAdapter.adapt(at), null, eventsFlag) != StateFlag.State.DENY;
    }

    @Override
    public String name() {
        return "WorldGuard";
    }
}
