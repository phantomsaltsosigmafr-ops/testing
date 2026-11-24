package dev.unstablesmp;

import dev.unstablesmp.command.UssCommand;
import dev.unstablesmp.life.BanditDirector;
import dev.unstablesmp.life.LifeConfig;
import dev.unstablesmp.life.LifeDirector;
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
    private LifeDirector residents;
    private BanditDirector bandits;
    private LifeConfig life;
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
        life = LifeConfig.load(getConfig());
        residents = new LifeDirector(getDataFolder(), getLogger(), life);
        bandits = new BanditDirector(getDataFolder(), getLogger(), residents, life);
        getServer().getPluginManager().registerEvents(residents, this);
        residents.nullSource(invasions::pouredBots, () -> {
            java.util.List<org.bukkit.Location> out = new java.util.ArrayList<>();
            for (dev.unstablesmp.portal.Invasion i : invasions.invasions()) out.add(new org.bukkit.Location(i.world, i.cx, i.cy, i.cz));
            return out;
        });
        UssCommand cmd = new UssCommand(this);
        var uss = Objects.requireNonNull(getCommand("uss"), "plugin.yml is missing the uss command");
        uss.setExecutor(cmd);
        uss.setTabCompleter(cmd);

        task = getServer().getScheduler().runTaskTimer(this, () -> {
            invasions.run();
            residents.tick(invasions.now());
            bandits.tick(invasions.now());
        }, 1L, 1L);
        if (life.autoStart) getServer().getScheduler().runTaskLater(this, residents::start, 200L);
        getLogger().info("UnstableSMP ready: /uss start opens a NULL portal invasion (spawn command: "
                + (settings.commands.isEmpty() ? "none" : "/" + settings.commands.get(0)) + ").");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (residents != null) residents.shutdown();
        if (invasions != null) invasions.shutdown();
        else if (journal != null) journal.restoreAll();
        if (journal != null) journal.close();
    }

    /** Re-reads config.yml. A running invasion finishes with the settings it started with. */
    public void reload() {
        reloadConfig();
        invasions.settings(Settings.load(getConfig(), getLogger()));
        life = LifeConfig.load(getConfig());
        residents.config(life);
        bandits.config(life);
    }

    public InvasionManager invasions() {
        return invasions;
    }

    public TotemOfNull totem() {
        return totem;
    }

    public LifeDirector residents() {
        return residents;
    }

    public BanditDirector bandits() {
        return bandits;
    }

    public LifeConfig life() {
        return life;
    }

    /** Residents, bandits, and any fake player (VexBot's own bots have no real connection). */
    public boolean isBot(org.bukkit.entity.Player p) {
        return residents.isResident(p) || bandits.isBandit(p) || p.getAddress() == null;
    }
}
