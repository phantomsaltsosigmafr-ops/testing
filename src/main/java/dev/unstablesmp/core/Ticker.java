package dev.unstablesmp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The single repeating task that drives every swarm, effect, unit, event, battle and HUD update.
 * Each phase is timed so {@code /uss status} can show where tick time goes.
 */
public final class Ticker implements Runnable {

    /** Something updated once per server tick by the {@link Ticker}. */
    public interface Tickable {
        void tick(int now);

        String name();
    }

    private final Plugin plugin;
    private final List<Tickable> phases = new ArrayList<>();
    private double[] avgNanos = new double[0];
    private final int[] lastErrorTick = new int[64];
    private BukkitTask task;

    public Ticker(Plugin plugin) {
        this.plugin = plugin;
    }

    public void add(Tickable t) {
        phases.add(t);
        avgNanos = new double[phases.size()];
    }

    public void start() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    @Override
    public void run() {
        int now = Bukkit.getCurrentTick();
        for (int i = 0; i < phases.size(); i++) {
            Tickable t = phases.get(i);
            long start = System.nanoTime();
            try {
                t.tick(now);
            } catch (Throwable ex) {
                // Never let one subsystem kill the shared task; log at most once every 10 seconds per phase.
                int slot = i % lastErrorTick.length;
                if (now - lastErrorTick[slot] > 200) {
                    lastErrorTick[slot] = now;
                    plugin.getLogger().log(Level.WARNING, "Error while ticking " + t.name(), ex);
                }
            }
            avgNanos[i] = avgNanos[i] * 0.95 + (System.nanoTime() - start) * 0.05;
        }
    }

    /** Average milliseconds per tick for each phase, in registration order. */
    public List<String> report() {
        List<String> out = new ArrayList<>();
        double total = 0;
        for (int i = 0; i < phases.size(); i++) {
            double ms = avgNanos[i] / 1_000_000.0;
            total += ms;
            out.add(String.format(java.util.Locale.ROOT, "%s: %.3f ms", phases.get(i).name(), ms));
        }
        out.add(String.format(java.util.Locale.ROOT, "total: %.3f ms/tick", total));
        return out;
    }
}
