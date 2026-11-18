package dev.unstablesmp;

import dev.unstablesmp.command.UssCommand;
import dev.unstablesmp.portal.BlockJournal;
import dev.unstablesmp.portal.BreachManager;
import dev.unstablesmp.portal.PortalProtection;
import dev.unstablesmp.portal.Spots;
import java.io.File;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * UnstableSMP: NULL portal breaches. Portals tear open around a point and VexBot's bots pour out of them
 * ({@code vexbot nullportalspawn x y z <number>} at each portal mouth, over and over). One repeating task, every
 * block restored afterwards, crash-safe.
 */
public final class UnstableSMP extends JavaPlugin {
    private BlockJournal journal;
    private BreachManager breaches;
    private Spots spots;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        journal = new BlockJournal(new File(getDataFolder(), "restore.log"), getLogger());
        migrateOldRestoreFile();
        int healed = journal.restoreLeftovers();
        if (healed > 0) getLogger().info("Restored " + healed + " blocks left by a breach that didn't close (crash or kill).");

        Settings settings = Settings.load(getConfig(), getLogger());
        breaches = new BreachManager(this, journal, settings);
        spots = new Spots(new File(getDataFolder(), "spots.yml"), getLogger());
        spots.load();

        getServer().getPluginManager().registerEvents(new PortalProtection(breaches), this);
        getServer().getPluginManager().registerEvents(breaches.capture(), this);
        UssCommand cmd = new UssCommand(this);
        var uss = Objects.requireNonNull(getCommand("uss"), "plugin.yml is missing the uss command");
        uss.setExecutor(cmd);
        uss.setTabCompleter(cmd);

        task = getServer().getScheduler().runTaskTimer(this, breaches, 1L, 1L);
        getLogger().info("UnstableSMP ready: /uss start opens a NULL breach (spawn command: "
                + (settings.commands.isEmpty() ? "none" : "/" + settings.commands.get(0)) + ").");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (breaches != null) breaches.shutdown();
        else if (journal != null) journal.restoreAll();
        if (journal != null) journal.close();
    }

    /** 2.0 betas kept a restore.yml; nothing reads it now, so drop it to avoid confusion. */
    private void migrateOldRestoreFile() {
        File old = new File(getDataFolder(), "restore.yml");
        if (old.isFile() && !old.delete()) getLogger().warning("Could not delete old " + old);
    }

    /** Re-reads config.yml and spots.yml. Running breaches finish with the settings they started with. */
    public void reload() {
        reloadConfig();
        breaches.settings(Settings.load(getConfig(), getLogger()));
        spots.load();
    }

    public BreachManager breaches() {
        return breaches;
    }

    public Spots spots() {
        return spots;
    }
}
