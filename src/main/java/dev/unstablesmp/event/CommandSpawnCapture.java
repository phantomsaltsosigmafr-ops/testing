package dev.unstablesmp.event;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Keys;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * VexBot compatibility (spawn-mode: COMMAND). Runs a console (or player) command per spawn with
 * {x} {y} {z} {world} {target} placeholders, and captures whatever living entity gets added to the world while
 * the command runs (or within the capture window after), pulling it to the portal.
 * <p>Never registers /vexbot or /vex: the command text is only dispatched.
 */
public final class CommandSpawnCapture implements Listener {
    private final UnstableSMP plugin;
    private List<Entity> sink;
    private Location target;
    private boolean pull;
    private int untilTick = -1;
    private boolean dispatching;

    public CommandSpawnCapture(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    /** Settings for one event's command spawning. */
    public record Settings(String command, boolean runAsPlayer, boolean pullToSpawn, int captureWindowTicks) {
    }

    public static String fill(String cmd, Location at, String target, String starter, int n) {
        String out = cmd
                .replace("{world}", at.getWorld().getName())
                .replace("{x}", fmt(at.getX()))
                .replace("{y}", fmt(at.getY()))
                .replace("{z}", fmt(at.getZ()))
                .replace("{bx}", Integer.toString(at.getBlockX()))
                .replace("{by}", Integer.toString(at.getBlockY()))
                .replace("{bz}", Integer.toString(at.getBlockZ()))
                .replace("{n}", Integer.toString(n))
                .replace("{target}", target == null ? "" : target)
                .replace("{starter}", starter == null ? "" : starter);
        return out.startsWith("/") ? out.substring(1) : out;
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    /** Dispatches the spawn command; anything spawned is added to {@code into}. */
    public boolean dispatch(Settings s, String cmd, Location at, List<Entity> into) {
        sink = into;
        target = at.clone();
        pull = s.pullToSpawn();
        untilTick = Bukkit.getCurrentTick() + s.captureWindowTicks();
        CommandSender as = Bukkit.getConsoleSender();
        if (s.runAsPlayer()) {
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player p : at.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(at);
                if (d < best) {
                    best = d;
                    nearest = p;
                }
            }
            if (nearest != null) as = nearest;
        }
        dispatching = true;
        try {
            return Bukkit.dispatchCommand(as, cmd);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Spawn command failed: /" + cmd + " -> " + ex.getMessage());
            return false;
        } finally {
            dispatching = false;
        }
    }

    /** Stops capturing into a list (event ended). */
    public void release(List<Entity> list) {
        if (sink == list) {
            sink = null;
            untilTick = -1;
        }
    }

    @EventHandler
    public void onEntityAdd(EntityAddToWorldEvent e) {
        if (sink == null) return;
        if (!dispatching && Bukkit.getCurrentTick() > untilTick) return;
        Entity ent = e.getEntity();
        if (!(ent instanceof LivingEntity)) return;
        if (ent.getScoreboardTags().contains(Keys.ENTITY_TAG)) return;
        if (sink.contains(ent)) return;
        // Players (fake-player bots) are only captured while the command itself runs, so a real player who
        // happens to join during the window is never grabbed.
        if (ent instanceof Player && !dispatching) return;
        final List<Entity> into = sink;
        final Location to = target.clone();
        into.add(ent);
        if (pull) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ent.isValid()) ent.teleport(to);
            });
        }
    }
}
