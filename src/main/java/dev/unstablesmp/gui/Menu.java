package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** A chest GUI page: slots map to click handlers; {@link #render()} redraws everything. */
public abstract class Menu implements InventoryHolder {

    @FunctionalInterface
    public interface Click {
        void click(Player p, ClickType type);
    }

    protected final UnstableSMP plugin;
    protected final Player viewer;
    private final Inventory inv;
    private final Click[] handlers;

    protected Menu(UnstableSMP plugin, Player viewer, int rows, Component title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inv = Bukkit.createInventory(this, rows * 9, title);
        this.handlers = new Click[rows * 9];
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    protected abstract void render();

    public final void open() {
        clear();
        render();
        viewer.openInventory(inv);
    }

    public final void refresh() {
        clear();
        render();
    }

    private void clear() {
        inv.clear();
        java.util.Arrays.fill(handlers, null);
    }

    protected void set(int slot, ItemStack item, Click click) {
        if (slot < 0 || slot >= handlers.length) return;
        inv.setItem(slot, item);
        handlers[slot] = click;
    }

    void handle(int slot, ClickType type) {
        if (slot < 0 || slot >= handlers.length || handlers[slot] == null) return;
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
        handlers[slot].click(viewer, type);
    }

    protected static int amount(ClickType t) {
        return t.isShiftClick() ? 10 : t.isRightClick() ? 5 : 1;
    }
}
