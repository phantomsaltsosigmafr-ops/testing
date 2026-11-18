package dev.unstablesmp.bot;

import dev.unstablesmp.bot.skin.NameGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;

/** A bot "faction" from config.yml -> bots.groups: look, names, skill pool, PvP styles, behaviour, named members. */
public final class BotGroup {
    public enum Behaviour { ORGANISED, SWARM, DUELIST }

    /** A named member (leader, commander, elite) with its own look and skill. */
    public record Member(String name, String display, String skin, String tier, String mode, String kit, double health,
                         double scale, boolean leader) {
    }

    public final String id;
    public String displayMini = "";
    public TextColor color = TextColor.color(0xFFFFFF);
    public String team;
    public final List<String> skins = new ArrayList<>();
    public String fallbackSkin = "default:steve";
    public NameGenerator.Style nameStyle = NameGenerator.Style.GAMER;
    public final List<String> names = new ArrayList<>();
    /** MiniMessage format for generated names; {@code <name>} is replaced. */
    public String nameFormat = "<white><name>";
    public String tierSpec = "random";
    private final List<SkillTier> tierPool = new ArrayList<>();
    private final List<PvpMode> modePool = new ArrayList<>();
    public String kit = "";
    public Behaviour behaviour = Behaviour.SWARM;
    public double health = 20;
    public double scale = 1.0;
    public int squadSize = 8;
    public Member leader;
    public final List<Member> elites = new ArrayList<>();
    public final List<String> killLines = new ArrayList<>();
    public final List<String> spawnLines = new ArrayList<>();
    public double chatChance = 0.15;

    public BotGroup(String id) {
        this.id = id;
        this.team = id;
    }

    public static BotGroup load(String id, ConfigurationSection c) {
        BotGroup g = new BotGroup(id);
        g.displayMini = c.getString("name", id);
        g.color = TextColor.color(dev.unstablesmp.faction.Faction.parseRgb(c.getString("color", "#FFFFFF")));
        g.team = c.getString("team", id);
        g.skins.addAll(c.isList("skins") ? c.getStringList("skins") : List.of(c.getString("skins", "default:steve")));
        g.fallbackSkin = c.getString("fallback-skin", "default:steve");
        g.nameStyle = NameGenerator.parse(c.getString("names.style", "GAMER"));
        g.names.addAll(c.getStringList("names.list"));
        g.nameFormat = c.getString("names.format", "<white><name>");
        g.tierSpec = c.getString("tiers", "random");
        ConfigurationSection tw = c.getConfigurationSection("tier-weights");
        if (tw != null) {
            for (String k : tw.getKeys(false)) {
                SkillTier t = SkillTier.parse(k);
                if (t != null) for (int i = 0; i < Math.max(0, tw.getInt(k)); i++) g.tierPool.add(t);
            }
        }
        for (String s : c.getStringList("modes")) {
            String[] p = s.split(":");
            PvpMode m = PvpMode.parse(p[0]);
            if (m == null || p[0].equalsIgnoreCase("random")) continue;
            int w = 1;
            if (p.length > 1) {
                try {
                    w = Math.max(1, Integer.parseInt(p[1].trim()));
                } catch (NumberFormatException ignored) {
                    w = 1;
                }
            }
            for (int i = 0; i < w; i++) g.modePool.add(m);
        }
        g.kit = c.getString("kit", "");
        try {
            g.behaviour = Behaviour.valueOf(c.getString("behaviour", "SWARM").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            g.behaviour = Behaviour.SWARM;
        }
        g.health = Math.max(1, c.getDouble("health", 20));
        g.scale = Math.max(0.3, Math.min(3, c.getDouble("scale", 1.0)));
        g.squadSize = Math.max(2, c.getInt("squad-size", 8));
        ConfigurationSection l = c.getConfigurationSection("leader");
        if (l != null) g.leader = member(l, true, g);
        for (var m : c.getMapList("elites")) {
            org.bukkit.configuration.MemoryConfiguration mc = new org.bukkit.configuration.MemoryConfiguration();
            for (var e : m.entrySet()) mc.set(String.valueOf(e.getKey()), e.getValue());
            g.elites.add(member(mc, false, g));
        }
        g.killLines.addAll(c.getStringList("chat.kill"));
        g.spawnLines.addAll(c.getStringList("chat.spawn"));
        g.chatChance = c.getDouble("chat.chance", 0.15);
        return g;
    }

    private static Member member(ConfigurationSection c, boolean leader, BotGroup g) {
        String name = c.getString("name", leader ? "Leader" : "Elite");
        return new Member(name, c.getString("display", "<" + g.color.asHexString() + "><bold>" + name), c.getString("skin", "user:" + name),
                c.getString("tier", leader ? "HT1" : "HT2"), c.getString("mode", ""), c.getString("kit", ""),
                c.getDouble("health", leader ? 60 : 30), c.getDouble("scale", leader ? 1.1 : 1.0), leader);
    }

    public SkillTier pickTier() {
        if (!tierPool.isEmpty()) return tierPool.get(ThreadLocalRandom.current().nextInt(tierPool.size()));
        return SkillTier.pick(tierSpec);
    }

    public PvpMode pickMode() {
        if (modePool.isEmpty()) return PvpMode.random();
        return modePool.get(ThreadLocalRandom.current().nextInt(modePool.size()));
    }

    public String pickSkin() {
        return skins.isEmpty() ? fallbackSkin : skins.get(ThreadLocalRandom.current().nextInt(skins.size()));
    }

    /** Returns {plainProfileName, displayMiniMessage}. */
    public String[] pickName(java.util.Set<String> taken) {
        String raw = switch (nameStyle) {
            case SCRAMBLED -> NameGenerator.scrambled(6, 12);
            case UNDERSCORE -> NameGenerator.underscore();
            case LIST -> {
                String pick = null;
                for (int tries = 0; tries < 8 && !names.isEmpty(); tries++) {
                    String n = names.get(ThreadLocalRandom.current().nextInt(names.size()));
                    if (!taken.contains(n)) {
                        pick = n;
                        break;
                    }
                }
                yield pick != null ? pick : NameGenerator.gamer();
            }
            default -> NameGenerator.gamer();
        };
        return new String[] {raw, nameFormat.replace("<name>", raw.replace("<", "\\<"))};
    }
}
