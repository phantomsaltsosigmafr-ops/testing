package dev.unstablesmp.core;

import dev.unstablesmp.UnstableSMP;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Join/quit housekeeping: send the skin pack, end camera playback, forget transient state. */
public final class PlayerSessionListener implements Listener {
    private final UnstableSMP plugin;

    public PlayerSessionListener(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getPlayer().isOnline()) plugin.tabList().sendAll(e.getPlayer());
        });
        if (plugin.pack().active() && plugin.pack().sendOnJoin()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (e.getPlayer().isOnline()) plugin.pack().send(e.getPlayer());
            }, 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.camera().stop(e.getPlayer());
        plugin.actionBar().forget(e.getPlayer().getUniqueId());
    }
}
