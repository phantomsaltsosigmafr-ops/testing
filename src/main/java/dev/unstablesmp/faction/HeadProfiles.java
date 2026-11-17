package dev.unstablesmp.faction;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

/**
 * Player-skin heads by Minecraft username. Profiles are completed asynchronously (Mojang lookup) once and
 * cached; heads spawned before the lookup finishes are refreshed when it does.
 */
public final class HeadProfiles {
    private final Plugin plugin;
    private final Map<String, PlayerProfile> resolved = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private Consumer<String> onResolved = name -> { };

    public HeadProfiles(Plugin plugin) {
        this.plugin = plugin;
    }

    public void onResolved(Consumer<String> callback) {
        this.onResolved = callback;
    }

    /** Starts resolving a username if we haven't already. */
    public void prefetch(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        if (resolved.containsKey(key) || !pending.add(key)) return;
        PlayerProfile profile = Bukkit.createProfile(name);
        profile.update().whenComplete((done, err) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                pending.remove(key);
                if (err != null || done == null || !done.hasTextures()) {
                    plugin.getLogger().warning("Could not fetch the skin for head '" + name + "'"
                            + (err != null ? ": " + err.getMessage() : ""));
                    return;
                }
                resolved.put(key, done);
                onResolved.accept(key);
            });
        });
    }

    public ItemStack head(String name) {
        ItemStack is = new ItemStack(Material.PLAYER_HEAD);
        if (is.getItemMeta() instanceof SkullMeta sm) {
            PlayerProfile p = resolved.get(name.toLowerCase(java.util.Locale.ROOT));
            sm.setPlayerProfile(p != null ? p : Bukkit.createProfile(name));
            is.setItemMeta(sm);
        }
        if (!resolved.containsKey(name.toLowerCase(java.util.Locale.ROOT))) prefetch(name);
        return is;
    }
}
