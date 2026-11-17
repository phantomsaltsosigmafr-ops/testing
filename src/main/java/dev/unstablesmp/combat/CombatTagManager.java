package dev.unstablesmp.combat;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.ActionBarService;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Ticker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Combat tag: "COMBAT: 15" in the action bar, logout = death, blocked teleports, pearls and commands. */
public final class CombatTagManager implements Ticker.Tickable, ActionBarService.Segment {
    private final UnstableSMP plugin;
    private final Map<UUID, Integer> until = new HashMap<>();
    /** Last player who hit each tagged player (for combat-log kill credit). */
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();

    boolean enabled = true;
    int durationTicks = 300;
    boolean tagOnPve;
    boolean killOnLogout = true;
    boolean blockEnderPearls = true;
    final List<PlayerTeleportEvent.TeleportCause> blockedCauses = new ArrayList<>();
    final List<String> blockedCommands = new ArrayList<>();

    public CombatTagManager(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigurationSection c) {
        if (c == null) return;
        enabled = c.getBoolean("enabled", true);
        durationTicks = Math.max(20, c.getInt("duration-seconds", 15) * 20);
        tagOnPve = c.getBoolean("tag-on-pve", false);
        killOnLogout = c.getBoolean("kill-on-logout", true);
        blockEnderPearls = c.getBoolean("block-ender-pearls", true);
        blockedCauses.clear();
        for (String s : c.getStringList("blocked-teleport-causes")) {
            try {
                blockedCauses.add(PlayerTeleportEvent.TeleportCause.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown teleport cause in combat-tag.blocked-teleport-causes: " + s);
            }
        }
        blockedCommands.clear();
        for (String s : c.getStringList("blocked-commands")) {
            String n = s.trim().toLowerCase(Locale.ROOT);
            if (n.startsWith("/")) n = n.substring(1);
            if (!n.isEmpty()) blockedCommands.add(n);
        }
    }

    public void tag(Player p, Player attacker) {
        if (!enabled || p.hasPermission("uss.combat.bypass")) return;
        int now = Bukkit.getCurrentTick();
        boolean was = isTagged(p.getUniqueId(), now);
        until.put(p.getUniqueId(), now + durationTicks);
        if (attacker != null) lastAttacker.put(p.getUniqueId(), attacker.getUniqueId());
        if (!was) plugin.messages().send(p, "combat.tagged", Messages.p("seconds", Integer.toString(durationTicks / 20)));
    }

    public boolean isTagged(UUID id, int now) {
        Integer t = until.get(id);
        return t != null && t > now;
    }

    public int remainingSeconds(UUID id, int now) {
        Integer t = until.get(id);
        if (t == null || t <= now) return 0;
        return (t - now + 19) / 20;
    }

    public UUID lastAttacker(UUID id) {
        return lastAttacker.get(id);
    }

    public void untag(UUID id) {
        until.remove(id);
        lastAttacker.remove(id);
    }

    @Override
    public void tick(int now) {
        if (now % 20 != 0 || until.isEmpty()) return;
        var it = until.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (en.getValue() <= now) {
                it.remove();
                lastAttacker.remove(en.getKey());
                Player p = Bukkit.getPlayer(en.getKey());
                if (p != null) plugin.messages().send(p, "combat.untagged");
            }
        }
    }

    @Override
    public Component segment(Player p, int now) {
        int s = remainingSeconds(p.getUniqueId(), now);
        if (s <= 0) return null;
        return plugin.messages().get("combat.actionbar", Messages.p("seconds", Integer.toString(s)));
    }

    @Override
    public String name() {
        return "combat-tag";
    }
}
