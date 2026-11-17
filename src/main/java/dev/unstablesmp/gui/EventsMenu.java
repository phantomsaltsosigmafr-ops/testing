package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import dev.unstablesmp.event.EventType;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Start or stop each server event at your position. */
final class EventsMenu extends Menu {
    private final GuiService gui;

    EventsMenu(UnstableSMP plugin, GuiService gui, Player viewer) {
        super(plugin, viewer, 3, plugin.messages().get("gui.events-title"));
        this.gui = gui;
    }

    @Override
    protected void render() {
        int slot = 10;
        for (EventType t : EventType.values()) {
            boolean running = plugin.events().isRunning(t);
            String name = plugin.getConfig().getString("events." + t.configKey + ".name", t.configKey);
            set(slot, Icons.glint(Icons.icon(t.icon, name,
                    running ? "<green>Running" : "<gray>Not running",
                    running ? "<red>Click to stop" : "<yellow>Click to start here",
                    "<dark_gray>/uss event " + t.id + (running ? " stop" : "")), running), (p, c) -> {
                if (plugin.events().isRunning(t)) {
                    plugin.events().stop(t);
                    plugin.messages().send(p, "event.stopped", Messages.c("event", Text.mm(name)));
                } else {
                    String err = plugin.events().start(t, p.getLocation(), p.getName());
                    if (err != null) plugin.messages().send(p, err);
                }
                refresh();
            });
            slot += 2;
        }
        set(22, Icons.icon(Material.ARROW, "<gray>Back"), (p, c) -> new MainMenu(plugin, gui, p).open());
        set(26, Icons.icon(Material.BARRIER, "<red>Stop All Events"), (p, c) -> {
            int n = plugin.events().stopAll();
            plugin.messages().send(p, "event.stopped-all", Messages.p("count", Integer.toString(n)));
            refresh();
        });
    }
}
