package dev.unstablesmp.gui;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.bot.BotGroup;
import dev.unstablesmp.bot.BotSpawner;
import dev.unstablesmp.bot.PvpMode;
import dev.unstablesmp.bot.SkillTier;
import dev.unstablesmp.core.Messages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Bot spawner: pick a group, PvP style, skill tier and amount, then spawn in front of you or clear bots. */
final class BotMenu extends Menu {
    private static final Map<UUID, String[]> PICKS = new HashMap<>(); // group, mode, tier, count
    private static final Material[] MODE_ICONS = {Material.DIAMOND_SWORD, Material.DIAMOND_AXE, Material.MACE, Material.ELYTRA,
        Material.GOLDEN_APPLE, Material.SPLASH_POTION, Material.NETHERITE_SPEAR};
    private final GuiService gui;

    BotMenu(UnstableSMP plugin, GuiService gui, Player viewer) {
        super(plugin, viewer, 6, plugin.messages().get("gui.bots-title"));
        this.gui = gui;
    }

    private String[] picks() {
        return PICKS.computeIfAbsent(viewer.getUniqueId(), k -> new String[] {"null", "random", "random", "10"});
    }

    @Override
    protected void render() {
        String[] pk = picks();
        int i = 0;
        for (BotGroup g : plugin.bots().groups().values()) {
            if (i >= 9) break;
            boolean sel = g.id.equals(pk[0]);
            set(i++, Icons.glint(Icons.icon(sel ? Material.PLAYER_HEAD : Material.SKELETON_SKULL, g.displayMini.isEmpty() ? g.id : g.displayMini,
                    "Behaviour: <white>" + g.behaviour, "Tiers: <white>" + g.tierSpec, "<yellow>Click to select"), sel), (p, c) -> {
                pk[0] = g.id;
                refresh();
            });
        }
        int s = 9;
        for (PvpMode m : PvpMode.values()) {
            boolean sel = m.id().equals(pk[1]);
            set(s, Icons.glint(Icons.icon(MODE_ICONS[m.ordinal() % MODE_ICONS.length], "<aqua>" + m.id(), "<yellow>Click to select"), sel), (p, c) -> {
                pk[1] = m.id();
                refresh();
            });
            s++;
        }
        set(16, Icons.glint(Icons.icon(Material.COMPASS, "<aqua>random / group default", "Each bot picks its own style"), pk[1].equals("random")), (p, c) -> {
            pk[1] = "random";
            refresh();
        });
        s = 18;
        for (SkillTier t : SkillTier.values()) {
            boolean sel = t.name().equals(pk[2]);
            set(s++, Icons.glint(Icons.icon(t.high() ? Material.EMERALD : Material.REDSTONE, (t.ordinal() < 4 ? "<green>" : t.ordinal() < 8 ? "<yellow>" : "<red>") + t.name(),
                    "Reaction " + t.stats().reaction + " ticks", "Aim error " + Math.round(t.stats().aimError) + "°"), sel), (p, c) -> {
                pk[2] = t.name();
                refresh();
            });
        }
        set(30, Icons.glint(Icons.icon(Material.ENDER_EYE, "<yellow>random / group default", "Uses the group's tier pool"), pk[2].equals("random")), (p, c) -> {
            pk[2] = "random";
            refresh();
        });
        int count = Integer.parseInt(pk[3]);
        set(37, Icons.icon(Material.REDSTONE, "<red>Amount -", "Shift: -50"), (p, c) -> {
            pk[3] = Integer.toString(Math.max(1, count - (c.isShiftClick() ? 50 : 5)));
            refresh();
        });
        set(38, Icons.icon(Material.PAPER, "<white>Amount: <yellow>" + count), null);
        set(39, Icons.icon(Material.GLOWSTONE_DUST, "<green>Amount +", "Shift: +50"), (p, c) -> {
            pk[3] = Integer.toString(Math.min(plugin.bots().maxTotal(), count + (c.isShiftClick() ? 50 : 5)));
            refresh();
        });
        set(41, Icons.icon(Material.SPAWNER, "<green><bold>SPAWN", pk[0] + " / " + pk[1] + " / " + pk[2] + " x" + count, "In front of you"), (p, c) -> spawn(p, pk, count));
        set(43, Icons.icon(Material.LAVA_BUCKET, "<red>Clear this group", "Right-click: clear ALL bots"), (p, c) -> {
            int n = plugin.bots().clear(b -> c.isRightClick() || b.group.id.equals(pk[0]), false);
            plugin.messages().send(p, "bot.cleared", Messages.p("count", Integer.toString(n)));
            refresh();
        });
        set(45, Icons.icon(Material.ARROW, "<gray>Back"), (p, c) -> new MainMenu(plugin, gui, p).open());
        set(49, Icons.icon(Material.BOOK, "<white>Bots alive: <yellow>" + plugin.bots().count() + "<gray>/" + plugin.bots().maxTotal()), null);
        set(53, Icons.icon(Material.TARGET, "<gold>Send this group at me", "Every bot of the group targets you"), (p, c) -> {
            for (Bot b : plugin.bots().all()) if (b.group.id.equals(pk[0])) b.forcedTarget = p;
        });
    }

    private void spawn(Player p, String[] pk, int count) {
        BotGroup g = plugin.bots().group(pk[0]);
        if (g == null) return;
        var l = p.getLocation();
        double yaw = Math.toRadians(l.getYaw());
        var at = dev.unstablesmp.core.Ground.groundAt(l.getWorld(), l.getX() - Math.sin(yaw) * 8, l.getY(), l.getZ() + Math.cos(yaw) * 8);
        at.setDirection(l.toVector().subtract(at.toVector()));
        BotSpawner.Request r = new BotSpawner.Request();
        r.group = g;
        r.at = at;
        r.mode = pk[1].equals("random") ? null : PvpMode.parse(pk[1]);
        r.tier = pk[2].equals("random") ? null : SkillTier.parse(pk[2]);
        r.announce = count <= 30;
        List<Bot> made = new ArrayList<>(plugin.botSpawner().spawnBatch(r, count, true, 3 + Math.sqrt(count)));
        plugin.messages().send(p, "bot.spawned", Messages.p("count", Integer.toString(made.size())), Messages.p("group", g.id),
                Messages.p("total", Integer.toString(plugin.bots().count())));
        refresh();
    }
}
