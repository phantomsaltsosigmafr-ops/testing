package dev.unstablesmp.region;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Region rules. The no-op version allows everything; the WorldGuard version asks WorldGuard. */
public interface RegionGuard {

    /** Can a faction unit (or other plugin-driven builder) place a block here? */
    boolean canUnitBuild(Location at);

    /** Can a faction unit break the block here? */
    boolean canUnitBreak(Location at);

    /** Can {@code attacker} hurt players at this location? (Null attacker = server/event damage.) */
    boolean canPvp(Player attacker, Location at);

    /** Can a server event start (or place event blocks) here? */
    boolean canRunEvent(Location at);

    String name();
}
