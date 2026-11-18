package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.team.TeamService;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Team select: click a team to join it (bots of that team fight with you), barrier to leave. */
final class TeamMenu extends Menu {

    TeamMenu(UnstableSMP plugin, Player viewer) {
        super(plugin, viewer, 3, plugin.messages().get("gui.teams-title"));
    }

    @Override
    protected void render() {
        String current = plugin.teams().teamOf(viewer);
        int slot = 10;
        for (TeamService.Def d : plugin.teams().defs().values()) {
            if (slot == 17) slot = 19;
            if (slot > 25) break;
            boolean in = d.id().equals(current);
            set(slot++, Icons.glint(Icons.icon(d.icon(), d.name(), in ? "<green>You are on this team" : "<yellow>Click to join"), in), (p, c) -> {
                plugin.teams().join(p, d.id());
                plugin.messages().send(p, "team.joined", Messages.p("team", d.id()));
                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                refresh();
            });
        }
        set(26, Icons.icon(Material.BARRIER, "<red>Leave team"), (p, c) -> {
            plugin.teams().leave(p);
            plugin.messages().send(p, "team.left");
            refresh();
        });
    }
}
