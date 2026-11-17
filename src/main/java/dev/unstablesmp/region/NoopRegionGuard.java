package dev.unstablesmp.region;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Used when WorldGuard isn't installed: only the world's own PvP setting is respected. */
public final class NoopRegionGuard implements RegionGuard {
    @Override
    public boolean canUnitBuild(Location at) {
        return true;
    }

    @Override
    public boolean canUnitBreak(Location at) {
        return true;
    }

    @Override
    public boolean canPvp(Player attacker, Location at) {
        return worldPvp(at);
    }

    /** The world's pvp game rule (1.21.9+ replaced World#getPVP with a game rule). */
    static boolean worldPvp(Location at) {
        if (at.getWorld() == null) return true;
        Boolean v = at.getWorld().getGameRuleValue(org.bukkit.GameRules.PVP);
        return v == null || v;
    }

    @Override
    public boolean canRunEvent(Location at) {
        return true;
    }

    @Override
    public String name() {
        return "none";
    }
}
