package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.faction.Faction;
import dev.unstablesmp.faction.Trait;
import dev.unstablesmp.faction.UnitTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Pick exactly which unit to spawn: left click 1, right click 5, shift 10. */
final class SpawnMenu extends Menu {
    private final GuiService gui;

    SpawnMenu(UnstableSMP plugin, GuiService gui, Player viewer) {
        super(plugin, viewer, 6, plugin.messages().get("gui.spawn-title"));
        this.gui = gui;
    }

    private List<UnitTemplate> list(Faction f) {
        List<UnitTemplate> l = new ArrayList<>();
        UnitTemplate lt = plugin.factions().template(f.leaderTemplate);
        if (lt != null) l.add(lt);
        for (String id : f.army) {
            UnitTemplate t = plugin.factions().template(id);
            if (t != null && !l.contains(t)) l.add(t);
        }
        for (UnitTemplate t : plugin.factions().templates()) if (!l.contains(t)) l.add(t);
        return l;
    }

    @Override
    protected void render() {
        Faction f = gui.selected(viewer);
        if (f == null) return;
        List<UnitTemplate> l = list(f);
        for (int i = 0; i < l.size() && i < 45; i++) {
            UnitTemplate t = l.get(i);
            boolean own = t.id.equals(f.leaderTemplate) || f.army.contains(t.id);
            Material egg = t.leader ? Material.NETHER_STAR : Material.matchMaterial(t.entity.name() + "_SPAWN_EGG");
            if (egg == null) egg = Material.ZOMBIE_SPAWN_EGG;
            List<String> traits = new ArrayList<>();
            for (Trait tr : t.traits) traits.add(tr.name().toLowerCase(Locale.ROOT));
            ItemStack is = Icons.icon(egg, t.display.colorIfAbsent(f.textColor),
                    "Health <white>" + (int) t.health + "</white>  Damage <white>" + t.damage + "</white>  " + t.entity.name().toLowerCase(Locale.ROOT),
                    "Traits: <white>" + String.join(", ", traits),
                    own ? "<green>Part of this faction's army" : "<dark_gray>(from another army - joins this faction)",
                    "<yellow>Left 1 · Right 5 · Shift 10");
            set(i, Icons.glint(is, own), (p, c) -> gui.spawnFor(p, f, t, amount(c)));
        }
        set(49, Icons.icon(Material.ARROW, "<gray>Back"), (p, c) -> new MainMenu(plugin, gui, p).open());
    }
}
