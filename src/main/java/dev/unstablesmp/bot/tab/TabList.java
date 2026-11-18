package dev.unstablesmp.bot.tab;

import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.core.Ticker;
import org.bukkit.entity.Player;

/** Puts bots in every player's tab list (fake player-info entries). */
public interface TabList extends Ticker.Tickable {
    void add(Bot b);

    void remove(Bot b);

    /** Sends every current bot entry to a player who just joined. */
    void sendAll(Player p);

    /** Removes every bot entry from everyone (plugin disable / reload). */
    void clearAll();

    boolean enabled();

    /** Used when server internals aren't available: bots simply aren't listed. */
    final class Noop implements TabList {
        @Override
        public void add(Bot b) {
        }

        @Override
        public void remove(Bot b) {
        }

        @Override
        public void sendAll(Player p) {
        }

        @Override
        public void clearAll() {
        }

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void tick(int now) {
        }

        @Override
        public String name() {
            return "tablist";
        }
    }
}
