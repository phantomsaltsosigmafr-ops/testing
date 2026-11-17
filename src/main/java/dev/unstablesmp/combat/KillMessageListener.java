package dev.unstablesmp.combat;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.faction.Faction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** "X was SMASHED by Y" death messages with verb pools per ability, ability set and faction. */
public final class KillMessageListener implements Listener {
    private final UnstableSMP plugin;
    private boolean enabled = true;
    private boolean styleVanillaPvp = true;
    private final List<String> defaults = new ArrayList<>();
    private final List<String> pvp = new ArrayList<>();
    private final Map<String, List<String>> byKey = new HashMap<>();

    public KillMessageListener(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigurationSection c) {
        defaults.clear();
        pvp.clear();
        byKey.clear();
        if (c == null) return;
        enabled = c.getBoolean("enabled", true);
        styleVanillaPvp = c.getBoolean("style-vanilla-pvp", true);
        plugin.attribution().setWindow(c.getInt("window-seconds", 10) * 20);
        defaults.addAll(c.getStringList("default"));
        pvp.addAll(c.getStringList("pvp"));
        if (defaults.isEmpty()) defaults.add("SMASHED");
        ConfigurationSection keys = c.getConfigurationSection("verbs");
        if (keys != null) {
            for (String k : keys.getKeys(false)) byKey.put(k.toLowerCase(java.util.Locale.ROOT), keys.getStringList(k));
        }
    }

    private String verbFor(String key) {
        List<String> pool = byKey.get(key);
        if ((pool == null || pool.isEmpty()) && key.startsWith("faction:")) {
            Faction f = plugin.factions().get(key.substring("faction:".length()));
            if (f != null && !f.killVerbs.isEmpty()) pool = f.killVerbs;
        }
        if (pool == null || pool.isEmpty()) {
            String set = plugin.abilitySetOf(key);
            if (set != null) pool = byKey.get(set);
        }
        if (pool == null || pool.isEmpty()) pool = defaults;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (!enabled) return;
        Player victim = e.getEntity();
        int now = Bukkit.getCurrentTick();
        DamageAttribution.Hit hit = plugin.attribution().get(victim.getUniqueId(), now);
        plugin.attribution().clear(victim.getUniqueId());
        String verb;
        Component killer;
        if (hit != null) {
            verb = verbFor(hit.key());
            killer = hit.killer();
        } else if (styleVanillaPvp && victim.getKiller() != null) {
            List<String> pool = pvp.isEmpty() ? defaults : pvp;
            verb = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            killer = victim.getKiller().name();
        } else {
            return;
        }
        e.deathMessage(plugin.messages().get("kill.format",
                Messages.c("victim", victim.name()), Messages.p("verb", verb), Messages.c("killer", killer)));
    }
}
