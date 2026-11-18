package dev.unstablesmp;

import dev.unstablesmp.command.UssCommand;
import dev.unstablesmp.portal.BlockJournal;
import dev.unstablesmp.portal.InvasionManager;
import dev.unstablesmp.portal.PortalProtection;
import dev.unstablesmp.totem.TotemOfNull;
import java.io.File;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * UnstableSMP: the NULL portal invasion. Dozens of floating nether portals tear open over an area and VexBot's bots
 * pour out of them ({@code vexbot nullportalspawn x y z <number>} at random portals, round after round). One
 * repeating task, every block restored afterwards, crash-safe.
 */
public final class UnstableSMP extends JavaPlugin {
    private BlockJournal journal;
    private InvasionManager invasions;
    private TotemOfNull totem;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        journal = new BlockJournal(new File(getDataFolder(), "restore.log"), getLogger());
        int healed = journal.restoreLeftovers();
        if (healed > 0) getLogger().info("Restored " + healed + " blocks left by an invasion that didn't close (crash or kill).");

        Settings settings = Settings.load(getConfig(), getLogger());
        invasions = new InvasionManager(this, journal, settings);

        getServer().getPluginManager().registerEvents(new PortalProtection(invasions), this);
        getServer().getPluginManager().registerEvents(invasions.capture(), this);
        totem = new TotemOfNull(this, invasions);
        getServer().getPluginManager().registerEvents(totem, this);
        UssCommand cmd = new UssCommand(this);
        var uss = Objects.requireNonNull(getCommand("uss"), "plugin.yml is missing the uss command");
        uss.setExecutor(cmd);
        uss.setTabCompleter(cmd);

        task = getServer().getScheduler().runTaskTimer(this, invasions, 1L, 1L);
        getLogger().info("UnstableSMP ready: /uss start opens a NULL portal invasion (spawn command: "
                + (settings.commands.isEmpty() ? "none" : "/" + settings.commands.get(0)) + ").");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (invasions != null) invasions.shutdown();
        else if (journal != null) journal.restoreAll();
        if (journal != null) journal.close();
    }

    /** Re-reads config.yml. A running invasion finishes with the settings it started with. */
    public void reload() {
        reloadConfig();
        invasions.settings(Settings.load(getConfig(), getLogger()));
    }

    public InvasionManager invasions() {
        return invasions;
    }

    public TotemOfNull totem() {
        return totem;
    }
}
