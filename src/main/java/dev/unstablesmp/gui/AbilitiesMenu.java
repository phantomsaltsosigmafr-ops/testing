package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.ability.AbilityType;
import dev.unstablesmp.swarm.Swarm;
import dev.unstablesmp.swarm.SwarmFormation;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Grab any ability item, and set your swarm's formation directly. */
final class AbilitiesMenu extends Menu {
    private final GuiService gui;

    AbilitiesMenu(UnstableSMP plugin, GuiService gui, Player viewer) {
        super(plugin, viewer, 6, plugin.messages().get("gui.abilities-title"));
        this.gui = gui;
    }

    @Override
    protected void render() {
        int slot = 0;
        for (AbilityType t : AbilityType.values()) {
            if (t == AbilityType.JUDGMENT) slot = 9; // row 1: Law + Cindercrest; row 0: Vexbots, Null, Portal
            ItemStack item = plugin.abilities().items().create(t);
            set(slot++, item, (p, c) -> p.getInventory().addItem(plugin.abilities().items().create(t)));
        }
        int s = 27;
        Swarm sw = plugin.swarms().swarmOf(viewer.getUniqueId());
        for (SwarmFormation f : SwarmFormation.values()) {
            set(s++, Icons.glint(Icons.icon(f.icon, "<light_purple>Swarm: " + f.label, f.targetAnchored ? "Uses your swarm's target" : "Around you"),
                    sw != null && sw.formation == f), (p, c) -> {
                Swarm own = plugin.swarms().swarmOf(p.getUniqueId());
                if (own == null) {
                    plugin.messages().send(p, "swarm.none");
                    return;
                }
                plugin.abilities().vexbots().applyFormation(p, own, f, 40);
                refresh();
            });
        }
        set(49, Icons.icon(Material.ARROW, "<gray>Back"), (p, c) -> new MainMenu(plugin, gui, p).open());
    }
}
