package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.faction.Faction;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Battles page: pick side A (left click) and side B (right click), army size, start, presets, end all. */
final class BattleMenu extends Menu {
    private final GuiService gui;

    BattleMenu(UnstableSMP plugin, GuiService gui, Player viewer) {
        super(plugin, viewer, 6, plugin.messages().get("gui.battle-title"));
        this.gui = gui;
    }

    @Override
    protected void render() {
        UUID id = viewer.getUniqueId();
        String a = gui.battleA.get(id), b = gui.battleB.get(id);
        int size = gui.battleSize.getOrDefault(id, plugin.battles().defaultSize());
        int i = 0;
        for (Faction x : plugin.factions().all()) {
            if (i >= 18) break;
            String side = x.id.equals(a) ? "<red><bold>SIDE A" : x.id.equals(b) ? "<blue><bold>SIDE B" : "";
            set(i++, Icons.faction(x, x.id.equals(a) || x.id.equals(b), "Left-click: side A", "Right-click: side B", side), (p, c) -> {
                if (c.isRightClick()) gui.battleB.put(id, x.id);
                else gui.battleA.put(id, x.id);
                refresh();
            });
        }
        Faction fa = plugin.factions().get(a), fb = plugin.factions().get(b);
        int max = plugin.battles().maxSize();
        set(20, Icons.icon(Material.REDSTONE, "<red>Army size -", "Per side: " + size, "Shift: -10"), (p, c) -> {
            gui.battleSize.put(id, Math.max(1, size - (c.isShiftClick() ? 10 : 2)));
            refresh();
        });
        set(22, Icons.icon(Material.PAPER, "<white>Army size per side: <yellow>" + size, "Leaders are added on top", "Max " + max), null);
        set(24, Icons.icon(Material.GLOWSTONE_DUST, "<green>Army size +", "Per side: " + size, "Shift: +10"), (p, c) -> {
            gui.battleSize.put(id, Math.min(max, size + (c.isShiftClick() ? 10 : 2)));
            refresh();
        });
        set(31, Icons.icon(Material.TNT, "<red><bold>START BATTLE",
                (fa == null ? "?" : fa.nameMini) + " <gray>vs " + (fb == null ? "?" : fb.nameMini),
                "Both armies spawn in front of you", "and fight until one side is wiped out"), (p, c) -> start(p, fa, fb, size));

        int s = 36;
        for (String pr : plugin.battles().presets()) {
            if (s > 44) break;
            String[] parts = pr.split(":");
            if (parts.length < 2) continue;
            Faction x = plugin.factions().get(parts[0].trim()), y = plugin.factions().get(parts[1].trim());
            if (x == null || y == null) continue;
            set(s++, Icons.icon(Material.GOLDEN_SWORD, x.nameMini + " <gray>vs " + y.nameMini, "Preset - click to start now", size + " per side"),
                    (p, c) -> start(p, x, y, size));
        }
        set(46, Icons.icon(Material.BARRIER, "<red>End All Battles", "Removes both armies of every battle"), (p, c) -> {
            int n = plugin.battles().endAll();
            plugin.messages().send(p, "battle.ended-all", Messages.p("count", Integer.toString(n)));
            refresh();
        });
        set(49, Icons.icon(Material.ARROW, "<gray>Back"), (p, c) -> new MainMenu(plugin, gui, p).open());
        set(52, Icons.icon(Material.BOOK, "<white>Running battles: <yellow>" + plugin.battles().running().size()), null);
    }

    private void start(Player p, Faction a, Faction b, int size) {
        String err = plugin.battles().start(p.getLocation(), a, b, size);
        if (err != null) {
            plugin.messages().send(p, err);
            return;
        }
        p.closeInventory();
    }
}
