package dev.unstablesmp.bot;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.bot.ai.Squad;
import dev.unstablesmp.core.EntityTracker;
import dev.unstablesmp.core.Text;
import dev.unstablesmp.kit.Kit;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/** Creates bots: name, skin, tier, mode, kit, health, team, squad, and the fake "joined the game" line. */
public final class BotSpawner {

    /** Everything optional except group and location. */
    public static final class Request {
        public BotGroup group;
        public Location at;
        public PvpMode mode;
        public SkillTier tier;
        public String team;
        public String kit;
        public String skin;
        public String name;
        public BotGroup.Member member;
        public Squad squad;
        public String map;
        public LivingEntity target;
        public boolean home = true;
        public boolean announce = true;
    }

    private final UnstableSMP plugin;
    private final BotManager mgr;

    public BotSpawner(UnstableSMP plugin, BotManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    public Bot spawn(Request r) {
        if (mgr.count() >= mgr.maxTotal() || r.at == null || r.at.getWorld() == null || r.group == null) return null;
        BotGroup g = r.group;
        BotGroup.Member mem = r.member;
        String plain;
        String displayMini;
        if (r.name != null) {
            plain = r.name;
            displayMini = g.nameFormat.replace("<name>", r.name);
        } else if (mem != null) {
            plain = mem.name();
            displayMini = mem.display();
        } else {
            String[] n = g.pickName(mgr.takenNames());
            plain = n[0];
            displayMini = n[1];
        }
        SkillTier tier = r.tier != null ? r.tier : mem != null ? SkillTier.pick(mem.tier()) : g.pickTier();
        PvpMode mode = r.mode != null ? r.mode : mem != null && !mem.mode().isBlank() ? PvpMode.parse(mem.mode()) : g.pickMode();
        if (mode == null) mode = PvpMode.SWORD;
        String kitName = r.kit != null ? r.kit : mem != null && !mem.kit().isBlank() ? mem.kit() : !g.kit.isBlank() ? g.kit : mode.id();
        Kit kit = plugin.kits().get(kitName);
        if (kit == null) kit = plugin.kits().get(mode.id());
        BotLoadout load = BotLoadout.fromKit(kit);
        // Modes that need gear the group kit lacks borrow it from the mode's default kit.
        BotLoadout modeKit = BotLoadout.fromKit(plugin.kits().get(mode.id()));
        fill(load, modeKit, mode);
        String skinSpec = r.skin != null ? r.skin : mem != null ? mem.skin() : g.pickSkin();
        ResolvableProfile profile = plugin.skins().profile(skinSpec, plain, g.fallbackSkin);
        Component display = Text.mm(displayMini);
        double health = mem != null ? mem.health() : g.health;
        double scale = mem != null ? mem.scale() : g.scale;
        String team = r.team != null ? r.team : g.team;
        final PvpMode fm = mode;
        final BotLoadout fl = load;
        Mannequin body = r.at.getWorld().spawn(r.at, Mannequin.class, m -> {
            plugin.tracker().track(m, null, EntityTracker.Kind.BOT);
            m.setProfile(profile);
            m.customName(display);
            m.setCustomNameVisible(true);
            m.setDescription(null);
            m.setImmovable(false);
            m.setCollidable(mgr.collide);
            m.setInvulnerable(false);
            setAttr(m, Attribute.MAX_HEALTH, health);
            m.setHealth(health);
            if (Math.abs(scale - 1) > 0.01) setAttr(m, Attribute.SCALE, scale);
            equip(m, fl, fm);
            keepTicking(m);
        });
        Bot b = new Bot(mgr.nextId(), body, g, tier, mode, load, team, display, plain, mem != null && mem.leader());
        b.spawnTick = org.bukkit.Bukkit.getCurrentTick();
        b.cooldownFull = BotLoadout.cooldownTicks(load.primary(mode));
        b.lookYaw = r.at.getYaw();
        b.moveYaw = r.at.getYaw();
        if (r.home) {
            b.homeX = r.at.getX();
            b.homeY = r.at.getY();
            b.homeZ = r.at.getZ();
            b.hasHome = true;
        }
        b.map = r.map;
        b.forcedTarget = r.target;
        mgr.register(b);
        plugin.teams().addEntity(team, body);
        if (r.squad != null) {
            b.squad = r.squad;
            r.squad.members.add(b);
            if (b.leader) r.squad.leader = b;
        }
        if (r.announce && mgr.joinMessages) plugin.fakeChat().broadcast(plugin.fakeChat().joinLine(display));
        if (!g.spawnLines.isEmpty() && ThreadLocalRandom.current().nextDouble() < g.chatChance * mgr.chatChance * 0.5) {
            say(b, g.spawnLines.get(ThreadLocalRandom.current().nextInt(g.spawnLines.size())));
        }
        return b;
    }

    /** Spawns a whole group batch: leader + elites first (if requested), then {@code count} rank-and-file. */
    public List<Bot> spawnBatch(Request base, int count, boolean withLeaders, double spread) {
        List<Bot> out = new ArrayList<>();
        BotGroup g = base.group;
        Squad squad = g.behaviour == BotGroup.Behaviour.ORGANISED
                ? mgr.squad(g.id + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt())) : null;
        if (squad != null) {
            squad.maxPerTarget = 3;
            squad.formation = dev.unstablesmp.faction.ArmyFormation.WEDGE;
        }
        List<BotGroup.Member> named = new ArrayList<>();
        if (withLeaders) {
            if (g.leader != null) named.add(g.leader);
            named.addAll(g.elites);
        }
        int squadFill = 0;
        for (int i = 0; i < named.size() + count; i++) {
            Request r = copy(base);
            r.member = i < named.size() ? named.get(i) : null;
            if (r.member != null && mgr.takenNames().contains(r.member.name())) continue;
            if (squad != null) {
                if (squadFill >= g.squadSize && r.member == null) {
                    squad = mgr.squad(g.id + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt()));
                    squadFill = 0;
                }
                r.squad = squad;
                squadFill++;
            }
            r.at = jitter(base.at, spread);
            Bot b = spawn(r);
            if (b == null) break;
            out.add(b);
        }
        return out;
    }

    private static Request copy(Request o) {
        Request r = new Request();
        r.group = o.group;
        r.at = o.at;
        r.mode = o.mode;
        r.tier = o.tier;
        r.team = o.team;
        r.kit = o.kit;
        r.skin = o.skin;
        r.name = o.name;
        r.map = o.map;
        r.target = o.target;
        r.home = o.home;
        r.announce = o.announce;
        return r;
    }

    private static Location jitter(Location at, double spread) {
        if (spread <= 0) return at.clone();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location l = at.clone().add(rnd.nextDouble(-spread, spread), 0, rnd.nextDouble(-spread, spread));
        return dev.unstablesmp.core.Ground.groundAt(l.getWorld(), l.getX(), at.getY() + 1, l.getZ()).setDirection(at.getDirection());
    }

    public void say(Bot b, String message) {
        plugin.fakeChat().broadcast(plugin.fakeChat().chatLine(b.name, Text.mm(message)));
    }

    private static void fill(BotLoadout l, BotLoadout m, PvpMode mode) {
        if (l.sword == null) l.sword = m.sword;
        if (mode == PvpMode.AXE && l.axe == null) l.axe = m.axe;
        if ((mode == PvpMode.MACE || mode == PvpMode.ELYTRA_MACE) && l.mace == null) {
            l.mace = m.mace;
            if (l.windCharges == 0) l.windCharges = m.windCharges;
        }
        if (mode == PvpMode.ELYTRA_MACE && !l.elytra) {
            l.elytra = true;
            l.chest = m.chest;
            if (l.rockets == 0) l.rockets = m.rockets;
        }
        if (mode == PvpMode.SPEAR && l.spear == null) l.spear = m.spear;
        if (mode == PvpMode.UHC && l.bow == null) {
            l.bow = m.bow;
            l.arrows = Math.max(l.arrows, m.arrows);
            l.rod = l.rod || m.rod;
        }
        if (mode == PvpMode.NETHPOT && l.healPots == 0) {
            l.healPots = m.healPots;
            l.speedPots = m.speedPots;
            l.strengthPots = m.strengthPots;
            l.totems = Math.max(l.totems, m.totems);
        }
    }

    /**
     * Spigot/Paper "entity activation range" freezes entities with no player nearby unless they are immune; any
     * active potion effect grants immunity. A hidden, harmless Luck effect keeps every bot fully ticking so fights
     * between bots carry on even when no player is watching.
     */
    static void keepTicking(LivingEntity e) {
        e.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.LUCK,
                org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, true, false, false));
    }

    private static void setAttr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    private static void equip(Mannequin m, BotLoadout l, PvpMode mode) {
        EntityEquipment eq = m.getEquipment();
        eq.setHelmet(l.helmet);
        eq.setChestplate(l.chest);
        eq.setLeggings(l.legs);
        eq.setBoots(l.boots);
        eq.setItemInMainHand(l.primary(mode));
        if (l.offhandTotem) eq.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        else if (l.shield) eq.setItemInOffHand(new ItemStack(Material.SHIELD));
    }
}
