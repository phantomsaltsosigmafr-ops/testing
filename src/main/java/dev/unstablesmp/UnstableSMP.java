package dev.unstablesmp;

import dev.unstablesmp.ability.AbilityListener;
import dev.unstablesmp.ability.AbilityService;
import dev.unstablesmp.ability.AbilityType;
import dev.unstablesmp.ability.effects.EffectManager;
import dev.unstablesmp.combat.CombatListener;
import dev.unstablesmp.combat.CombatTagManager;
import dev.unstablesmp.combat.ComboTable;
import dev.unstablesmp.combat.DamageAttribution;
import dev.unstablesmp.combat.DamageService;
import dev.unstablesmp.combat.KillMessageListener;
import dev.unstablesmp.combat.Marks;
import dev.unstablesmp.combat.Targeting;
import dev.unstablesmp.bot.BotListener;
import dev.unstablesmp.bot.BotManager;
import dev.unstablesmp.bot.BotSpawner;
import dev.unstablesmp.bot.skin.PackServer;
import dev.unstablesmp.bot.skin.SkinService;
import dev.unstablesmp.cine.CameraService;
import dev.unstablesmp.cine.Cinematics;
import dev.unstablesmp.cine.FakeChat;
import dev.unstablesmp.cine.SceneService;
import dev.unstablesmp.command.UssCommand;
import dev.unstablesmp.core.PlayerSessionListener;
import dev.unstablesmp.core.TempBlocks;
import dev.unstablesmp.game.GameService;
import dev.unstablesmp.kit.KitService;
import dev.unstablesmp.map.MapLinkListener;
import dev.unstablesmp.map.MapService;
import dev.unstablesmp.team.TeamService;
import dev.unstablesmp.core.ActionBarService;
import dev.unstablesmp.core.DisplayFactory;
import dev.unstablesmp.core.EntityTracker;
import dev.unstablesmp.core.Hitboxes;
import dev.unstablesmp.core.Keys;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Text;
import dev.unstablesmp.core.Ticker;
import dev.unstablesmp.event.CommandSpawnCapture;
import dev.unstablesmp.event.EventManager;
import dev.unstablesmp.event.PortalBlockRegistry;
import dev.unstablesmp.faction.BattleManager;
import dev.unstablesmp.faction.FactionRegistry;
import dev.unstablesmp.faction.UnitListener;
import dev.unstablesmp.faction.UnitManager;
import dev.unstablesmp.faction.UnitSettings;
import dev.unstablesmp.gui.FactionHorn;
import dev.unstablesmp.gui.GuiService;
import dev.unstablesmp.region.NoopRegionGuard;
import dev.unstablesmp.region.RegionGuard;
import dev.unstablesmp.region.WorldGuardRegionGuard;
import dev.unstablesmp.swarm.SwarmListener;
import dev.unstablesmp.swarm.SwarmManager;
import dev.unstablesmp.swarm.SwarmSettings;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/** Unstable SMP Simulator: Vexbot swarms, ability sets, faction armies, battles and server events. */
public final class UnstableSMP extends JavaPlugin {
    private static final Set<String> SWARM_KEYS = Set.of("swarm", "vortex", "cage", "pincer", "rain", "arrowhead_dive", "dive_strike");

    private RegionGuard regions;
    private Messages messages;
    private Ticker ticker;
    private EntityTracker tracker;
    private DisplayFactory displays;
    private Hitboxes hitboxes;
    private ActionBarService actionBar;
    private FactionRegistry factions;
    private Marks marks;
    private ComboTable combos;
    private DamageAttribution attribution;
    private Targeting targeting;
    private DamageService damage;
    private UnitManager units;
    private SwarmManager swarms;
    private EffectManager effects;
    private AbilityService abilities;
    private PortalBlockRegistry portalBlocks;
    private CommandSpawnCapture commandCapture;
    private EventManager events;
    private BattleManager battles;
    private GuiService gui;
    private FactionHorn horn;
    private CombatTagManager combatTags;
    private KillMessageListener killMessages;
    private KitService kits;
    private TeamService teams;
    private PackServer pack;
    private SkinService skins;
    private BotManager bots;
    private BotSpawner botSpawner;
    private MapService maps;
    private GameService games;
    private FakeChat fakeChat;
    private CameraService camera;
    private Cinematics cinematics;
    private SceneService scenes;
    private TempBlocks tempBlocks;

    @Override
    public void onLoad() {
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                WorldGuardRegionGuard.registerFlags(getLogger());
                regions = WorldGuardRegionGuard.create();
            } catch (Throwable t) {
                getLogger().warning("WorldGuard found but its API could not be used (" + t + "); region rules disabled.");
            }
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Keys.init(this);
        if (regions == null) regions = new NoopRegionGuard();
        messages = new Messages(this);
        ticker = new Ticker(this);
        tracker = new EntityTracker();
        displays = new DisplayFactory(tracker);
        hitboxes = new Hitboxes();
        actionBar = new ActionBarService();
        factions = new FactionRegistry(getDataFolder(), getLogger());
        marks = new Marks();
        combos = new ComboTable();
        attribution = new DamageAttribution();
        targeting = new Targeting(this);
        damage = new DamageService(this);
        units = new UnitManager(this);
        swarms = new SwarmManager(this);
        effects = new EffectManager();
        abilities = new AbilityService(this);
        portalBlocks = new PortalBlockRegistry();
        commandCapture = new CommandSpawnCapture(this);
        events = new EventManager(this);
        battles = new BattleManager(this);
        gui = new GuiService(this);
        horn = new FactionHorn(this);
        combatTags = new CombatTagManager(this);
        killMessages = new KillMessageListener(this);
        kits = new KitService(getDataFolder(), getLogger());
        teams = new TeamService();
        pack = new PackServer(getDataFolder(), getLogger());
        skins = new SkinService(pack);
        bots = new BotManager(this);
        botSpawner = new BotSpawner(this, bots);
        maps = new MapService(getDataFolder(), getLogger());
        games = new GameService(this);
        fakeChat = new FakeChat();
        camera = new CameraService(this);
        cinematics = new Cinematics(this);
        scenes = new SceneService(this);
        tempBlocks = new TempBlocks();

        reloadAll();
        factions.loadMembers();

        int swept = tracker.sweepAll();
        if (swept > 0) getLogger().info("Removed " + swept + " leftover entities from a previous run.");

        PluginManager pm = getServer().getPluginManager();
        for (Listener l : List.of(tracker, hitboxes, new CombatListener(this, combatTags), killMessages,
                new SwarmListener(this, swarms), new AbilityListener(this), abilities.cinder(),
                new UnitListener(this, units), portalBlocks, commandCapture, gui, horn,
                new BotListener(this, bots), new MapLinkListener(this), games, new PlayerSessionListener(this))) {
            pm.registerEvents(l, this);
        }

        actionBar.add(combatTags);
        actionBar.add(abilities);
        ticker.add(tracker);
        ticker.add(swarms);
        ticker.add(effects);
        ticker.add(units);
        ticker.add(events);
        ticker.add(battles);
        ticker.add(bots);
        ticker.add(games);
        ticker.add(tempBlocks);
        ticker.add(camera);
        ticker.add(cinematics);
        ticker.add(scenes);
        ticker.add(combatTags);
        ticker.add(actionBar);
        ticker.add(new Ticker.Tickable() {
            @Override
            public void tick(int now) {
                if (now % 200 != 0) return;
                marks.prune(now);
                attribution.prune(now);
            }

            @Override
            public String name() {
                return "maintenance";
            }
        });
        ticker.start();

        registerCommand("uss", "Unstable SMP Simulator: GUI, abilities, formations, events and battles", List.of(), new UssCommand(this));
        getLogger().info("UnstableSMP enabled (regions: " + regions.name() + ", " + factions.all().size() + " factions, "
                + AbilityType.values().length + " abilities).");
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.stop();
        if (scenes != null) scenes.stopAll();
        if (camera != null) camera.stopAll();
        if (games != null) games.stopAll();
        if (bots != null) bots.removeAll();
        if (tempBlocks != null) tempBlocks.restoreAll();
        if (pack != null) pack.stop();
        if (events != null) events.stopAll();
        if (battles != null) battles.endAll();
        if (effects != null) effects.removeAll();
        if (swarms != null) swarms.removeAll();
        if (units != null) units.removeAll();
        if (tracker != null) {
            tracker.removeAll();
            tracker.sweepAll();
        }
        if (hitboxes != null) hitboxes.clear();
        if (portalBlocks != null) portalBlocks.clear();
        if (factions != null) factions.saveMembers();
    }

    /** Reloads config.yml and messages.yml into every subsystem. Live factions keep their orders. */
    public void reloadAll() {
        reloadConfig();
        FileConfiguration c = getConfig();
        messages.reload();
        tracker.reload(c.getConfigurationSection("performance"));
        displays.setViewRange((float) c.getDouble("swarm.bot.view-range", 1.0));
        factions.load(c);
        units.configure(UnitSettings.load(c.getConfigurationSection("units")));
        swarms.configure(SwarmSettings.load(c.getConfigurationSection("swarm"), getLogger()));
        effects.setMax(c.getInt("performance.max-effects", 300));
        abilities.reload(c.getConfigurationSection("abilities"));
        combos.load(c.getConfigurationSection("combos"), getLogger());
        combatTags.reload(c.getConfigurationSection("combat-tag"));
        killMessages.reload(c.getConfigurationSection("kill-messages"));
        battles.reload(c.getConfigurationSection("battles"));
        portalBlocks.configure(c.getBoolean("events.portal-invasion.portals.protect-blocks", true),
                c.getBoolean("events.portal-invasion.portals.allow-teleport", false));
        actionBar.configure(Text.mm(messages.raw("actionbar-separator")), c.getInt("performance.actionbar-interval-ticks", 5));
        kits.reload();
        teams.reload(c.getConfigurationSection("teams"));
        pack.reload(c.getConfigurationSection("resource-pack"), messages.get("pack.prompt"));
        bots.reload(c.getConfigurationSection("bots"));
        tempBlocks.setMax(c.getInt("bots.max-temp-blocks", 600));
        maps.reload();
        games.reload(c.getConfigurationSection("games"));
        camera.reload();
        scenes.reload();
    }

    /** Ability set for a damage key (ability id or swarm source), for kill-message verb fallbacks. */
    public String abilitySetOf(String key) {
        AbilityType t = AbilityType.byId(key);
        if (t != null) return t.set;
        if (SWARM_KEYS.contains(key)) return "vexbots";
        if (key.equals("portal_swarm")) return "portal";
        return null;
    }

    public KitService kits() {
        return kits;
    }

    public TeamService teams() {
        return teams;
    }

    public PackServer pack() {
        return pack;
    }

    public SkinService skins() {
        return skins;
    }

    public BotManager bots() {
        return bots;
    }

    public BotSpawner botSpawner() {
        return botSpawner;
    }

    public MapService maps() {
        return maps;
    }

    public GameService games() {
        return games;
    }

    public FakeChat fakeChat() {
        return fakeChat;
    }

    public CameraService camera() {
        return camera;
    }

    public Cinematics cinematics() {
        return cinematics;
    }

    public SceneService scenes() {
        return scenes;
    }

    public TempBlocks tempBlocks() {
        return tempBlocks;
    }

    public RegionGuard regions() {
        return regions;
    }

    public Messages messages() {
        return messages;
    }

    public Ticker ticker() {
        return ticker;
    }

    public EntityTracker tracker() {
        return tracker;
    }

    public DisplayFactory displays() {
        return displays;
    }

    public Hitboxes hitboxes() {
        return hitboxes;
    }

    public ActionBarService actionBar() {
        return actionBar;
    }

    public FactionRegistry factions() {
        return factions;
    }

    public Marks marks() {
        return marks;
    }

    public ComboTable combos() {
        return combos;
    }

    public DamageAttribution attribution() {
        return attribution;
    }

    public Targeting targeting() {
        return targeting;
    }

    public DamageService damage() {
        return damage;
    }

    public UnitManager units() {
        return units;
    }

    public SwarmManager swarms() {
        return swarms;
    }

    public EffectManager effects() {
        return effects;
    }

    public AbilityService abilities() {
        return abilities;
    }

    public PortalBlockRegistry portalBlocks() {
        return portalBlocks;
    }

    public CommandSpawnCapture commandCapture() {
        return commandCapture;
    }

    public EventManager events() {
        return events;
    }

    public BattleManager battles() {
        return battles;
    }

    public GuiService gui() {
        return gui;
    }

    public FactionHorn horn() {
        return horn;
    }

    public CombatTagManager combatTags() {
        return combatTags;
    }
}
