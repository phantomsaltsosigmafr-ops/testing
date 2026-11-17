package dev.nullportals;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class NullPortalsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    static final String TAG = "nullportals_entity";
    private static final String P = "§8[§5NullPortals§8] §7";

    private final List<NullEvent> events = new ArrayList<>();
    private final Set<String> portalBlocks = new HashSet<>();

    // Capture state for COMMAND spawn mode
    private NullEvent captureEvent;
    private Location captureTarget;
    private Settings captureSettings;
    private int captureUntilTick = -1;
    private boolean captureSync;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String name : new String[] {"nullportal", "nullgui"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
        units = new UnitManager(this);
        units.load(getConfig());
        units.start();
        center = new CommandCenter(this, units);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(units, this);
        Bukkit.getPluginManager().registerEvents(center, this);
        // Clean up any nulls left over from a crash
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(TAG)) e.remove();
            }
        }
    }

    @Override
    public void onDisable() {
        for (NullEvent ev : new ArrayList<>(events)) ev.stopNow();
        events.clear();
        portalBlocks.clear();
        if (center != null) center.shutdown();
        if (units != null) units.stop();
    }

    UnitManager units() {
        return units;
    }

    static String color(String s) {
        return s == null ? "" : s.replace('&', '\u00a7');
    }

    boolean isPortalBlock(Block b) {
        return !portalBlocks.isEmpty() && portalBlocks.contains(key(b));
    }

    /** Starts a null portal event using config defaults. Returns a status message. */
    String startEvent(Location at, String starter) {
        Settings s = Settings.from(getConfig());
        NullEvent ev = new NullEvent(this, s, at, starter);
        events.add(ev);
        ev.start();
        return "Null portals are tearing open (radius " + s.radius + ", up to " + s.maxPortals + " portals).";
    }

    int stopEvents() {
        int n = events.size();
        for (NullEvent ev : new ArrayList<>(events)) ev.stopNow();
        events.clear();
        return n;
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("nullgui")) {
            if (!(sender instanceof Player pl)) {
                sender.sendMessage(P + "Players only.");
            } else if (!sender.hasPermission("nullportal.command")) {
                sender.sendMessage(P + "\u00a7cNo permission.");
            } else {
                center.openMain(pl);
            }
            return true;
        }
        if (!sender.hasPermission("nullportal.admin")) {
            sender.sendMessage(P + "§cNo permission.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();

        if (sub.equals("start") || sub.equals("at")) {
            Player anchor;
            int argStart;
            if (sub.equals("at")) {
                if (args.length < 2) {
                    sender.sendMessage(P + "Usage: /" + label + " at <player> [radius] [portals] [seconds]");
                    return true;
                }
                anchor = Bukkit.getPlayerExact(args[1]);
                if (anchor == null) {
                    sender.sendMessage(P + "§cPlayer not found: " + args[1]);
                    return true;
                }
                argStart = 2;
            } else {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(P + "From console use: /" + label + " at <player> [radius] [portals] [seconds]");
                    return true;
                }
                anchor = (Player) sender;
                argStart = 1;
            }
            Settings s = Settings.from(getConfig());
            try {
                if (args.length > argStart) s.radius = Math.max(4, Integer.parseInt(args[argStart]));
                if (args.length > argStart + 1) s.maxPortals = Math.max(1, Integer.parseInt(args[argStart + 1]));
                if (args.length > argStart + 2) s.durationTicks = Integer.parseInt(args[argStart + 2]) * 20;
            } catch (NumberFormatException ex) {
                sender.sendMessage(P + "§cNumbers only: [radius] [portals] [seconds]");
                return true;
            }
            NullEvent ev = new NullEvent(this, s, anchor.getLocation(), sender instanceof Player ? sender.getName() : anchor.getName());
            events.add(ev);
            ev.start();
            sender.sendMessage(P + "Null portals are tearing open around §f" + anchor.getName()
                    + "§7 (radius " + s.radius + ", up to " + s.maxPortals + " portals, "
                    + (s.durationTicks > 0 ? (s.durationTicks / 20) + "s" : "until stopped") + ", mode "
                    + s.spawnMode + ").");
            return true;
        }

        if (sub.equals("stop")) {
            int n = events.size();
            stopEvents();
            sender.sendMessage(P + "Stopped " + n + " event(s).");
            return true;
        }

        if (sub.equals("reload")) {
            reloadConfig();
            units.load(getConfig());
            sender.sendMessage(P + "Config reloaded (applies to new events).");
            return true;
        }

        if (sub.equals("list")) {
            if (events.isEmpty()) {
                sender.sendMessage(P + "No events running.");
            } else {
                for (NullEvent ev : events) {
                    Location c = ev.center();
                    sender.sendMessage(P + c.getWorld().getName() + " " + c.getBlockX() + " " + c.getBlockY() + " "
                            + c.getBlockZ() + " §8- §7" + ev.portalCount() + " portals, "
                            + ev.spawned.size() + " spawned");
                }
            }
            return true;
        }

        sender.sendMessage(P + "Commands:");
        sender.sendMessage("§d/" + label + " start [radius] [portals] [seconds]");
        sender.sendMessage("§d/" + label + " at <player> [radius] [portals] [seconds]");
        sender.sendMessage("§d/" + label + " stop §7| §d/" + label + " list §7| §d/" + label + " reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("nullgui")) return out;
        if (args.length == 1) {
            for (String s : new String[] {"start", "at", "stop", "list", "reload"}) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("at")) {
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            for (Player p : online) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- event bookkeeping

    void eventEnded(NullEvent ev) {
        events.remove(ev);
        if (captureEvent == ev) {
            captureEvent = null;
            captureUntilTick = -1;
        }
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    void registerBlocks(List<Block> blocks) {
        for (Block b : blocks) portalBlocks.add(key(b));
    }

    void unregisterBlocks(List<Block> blocks) {
        for (Block b : blocks) portalBlocks.remove(key(b));
    }

    private boolean nearOurPortal(Location loc) {
        if (loc == null || loc.getWorld() == null || portalBlocks.isEmpty()) return false;
        String w = loc.getWorld().getName();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 2; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (portalBlocks.contains(w + ":" + (bx + dx) + ":" + (by + dy) + ":" + (bz + dz))) return true;
        return false;
    }

    private boolean teleportAllowed() {
        return getConfig().getBoolean("portals.allow-teleport", false);
    }

    // ---------------------------------------------------------------- COMMAND mode capture

    /**
     * Runs a spawn command (e.g. a VexBot spawn command). Anything living that gets added to the world
     * while it runs, or within the capture window after, is pulled to the portal and counted as a null.
     */
    boolean dispatchCaptured(NullEvent ev, String cmd, Location portal, Settings s) {
        captureEvent = ev;
        captureTarget = portal.clone();
        captureSettings = s;
        captureUntilTick = Bukkit.getCurrentTick() + s.captureWindowTicks;
        CommandSender as = Bukkit.getConsoleSender();
        if ("PLAYER".equals(s.runAs)) {
            Player starter = null;
            for (Player p : portal.getWorld().getPlayers()) {
                if (starter == null || p.getLocation().distanceSquared(portal) < starter.getLocation().distanceSquared(portal)) {
                    starter = p;
                }
            }
            if (starter != null) as = starter;
        }
        captureSync = true;
        boolean ok;
        try {
            ok = Bukkit.dispatchCommand(as, cmd);
        } catch (Exception ex) {
            getLogger().warning("Spawn command failed: /" + cmd + " -> " + ex.getMessage());
            ok = false;
        } finally {
            captureSync = false;
        }
        return ok;
    }

    @EventHandler
    public void onEntityAdd(EntityAddToWorldEvent e) {
        if (captureEvent == null) return;
        if (!captureSync && Bukkit.getCurrentTick() > captureUntilTick) return;
        Entity ent = e.getEntity();
        if (!(ent instanceof LivingEntity)) return;
        if (ent.getScoreboardTags().contains(TAG)) return;
        if (captureEvent.spawned.contains(ent)) return;
        // Players (e.g. fake-player bots) are only captured while the command itself is running,
        // so a real player who happens to join during the window is never grabbed.
        if (ent instanceof Player && !captureSync) return;

        final NullEvent ev = captureEvent;
        final Location to = captureTarget.clone();
        final boolean pull = captureSettings.pullToPortal;
        ev.spawned.add(ent);
        if (pull) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (ent.isValid()) ent.teleport(to);
            });
        }
    }

    // ---------------------------------------------------------------- protection

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (!teleportAllowed() && nearOurPortal(e.getFrom())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (!teleportAllowed() && nearOurPortal(e.getFrom())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (getConfig().getBoolean("portals.protect-blocks", true) && portalBlocks.contains(key(e.getBlock()))) {
            e.setCancelled(true);
        }
    }
}
