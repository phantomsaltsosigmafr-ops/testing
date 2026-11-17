package dev.unstablesmp.faction;

import dev.unstablesmp.core.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** A commandable army definition plus its live orders, members and world edits. */
public final class Faction {
    public enum Mode { PASSIVE, DEFEND, AGGRESSIVE }

    // ---- definition (refreshed on reload)
    public final String id;
    /** The config faction this one was copied from (itself for normal factions). */
    public String baseId;
    public String nameMini;
    public Component display;
    public TextColor textColor = TextColor.color(0xFFFFFF);
    public Color color = Color.WHITE;
    /** Dust particle in the faction colour (cached for unit trails). */
    public org.bukkit.Particle.DustOptions dust = new org.bukkit.Particle.DustOptions(Color.WHITE, 1.0f);
    public Material buildBlock = Material.COBBLED_DEEPSLATE;
    public Material icon = Material.LEATHER_CHESTPLATE;
    public String leaderTemplate = "";
    public final List<String> army = new ArrayList<>();
    public final List<Integer> weights = new ArrayList<>();
    public final List<String> killVerbs = new ArrayList<>();
    /** Portal-event copies: hostile to every player and every other faction, take no orders. */
    public boolean eventHostile;

    // ---- live orders
    public Mode mode = Mode.DEFEND;
    public UUID focus;
    public ArmyFormation formation = ArmyFormation.GRID;
    public double spacing = 2.0;
    public boolean follow = true;
    public UUID commander;
    public Location anchor;
    public float anchorYaw;
    public boolean attackOutsiders;
    public boolean fightFactions = true;
    public boolean build = true;
    public boolean breakBlocks = true;
    public boolean fortify;
    public boolean showNames = true;
    public boolean glow;
    public final Set<UUID> members = new HashSet<>();
    public final Map<UUID, Integer> grudges = new HashMap<>();

    // ---- world edits
    public final List<BlockRecord> placed = new ArrayList<>();
    public final List<BlockRecord> broken = new ArrayList<>();
    public boolean logBroken;

    /** Set while this faction is fighting a GUI/command battle. */
    public Object battle;

    public Faction(String id) {
        this.id = id;
        this.baseId = id;
    }

    public void loadDefinition(ConfigurationSection c, Logger log) {
        nameMini = c.getString("name", id);
        display = Text.mm(nameMini);
        String hex = c.getString("color", "#FFFFFF");
        int rgb = parseRgb(hex);
        textColor = TextColor.color(rgb);
        color = Color.fromRGB(rgb);
        dust = new org.bukkit.Particle.DustOptions(color, 1.0f);
        buildBlock = material(c.getString("build-block"), Material.COBBLED_DEEPSLATE, true, log);
        icon = material(c.getString("icon"), Material.LEATHER_CHESTPLATE, false, log);
        leaderTemplate = c.getString("leader", "");
        army.clear();
        weights.clear();
        for (String s : c.getStringList("army")) {
            String[] p = s.split(":");
            int w = 1;
            if (p.length > 1) {
                try {
                    w = Math.max(1, Integer.parseInt(p[1].trim()));
                } catch (NumberFormatException ignored) {
                    log.warning("Bad army weight '" + s + "' in faction " + id);
                }
            }
            army.add(p[0].trim());
            weights.add(w);
        }
        killVerbs.clear();
        killVerbs.addAll(c.getStringList("kill-verbs"));
    }

    /** Applies the configured starting orders (only on first load, so live orders survive /uss reload). */
    public void loadDefaults(ConfigurationSection c) {
        formation = ArmyFormation.parse(c.getString("formation"), ArmyFormation.GRID);
        spacing = c.getDouble("spacing", 2.0);
        attackOutsiders = c.getBoolean("attack-outsiders", false);
        mode = parseMode(c.getString("mode"), Mode.DEFEND);
    }

    /** Copy used by server events so their units never take orders from a player's horn. */
    public Faction eventCopy(String newId) {
        Faction f = new Faction(newId);
        f.baseId = baseId;
        f.nameMini = nameMini;
        f.display = display;
        f.textColor = textColor;
        f.color = color;
        f.dust = dust;
        f.buildBlock = buildBlock;
        f.icon = icon;
        f.leaderTemplate = leaderTemplate;
        f.army.addAll(army);
        f.weights.addAll(weights);
        f.killVerbs.addAll(killVerbs);
        f.eventHostile = true;
        f.mode = Mode.AGGRESSIVE;
        f.follow = false;
        f.showNames = false;
        f.logBroken = true;
        f.formation = formation;
        f.spacing = spacing;
        return f;
    }

    public String pickArmy(Random rnd) {
        if (army.isEmpty()) return null;
        int total = 0;
        for (int w : weights) total += w;
        int r = rnd.nextInt(total);
        for (int i = 0; i < army.size(); i++) {
            r -= weights.get(i);
            if (r < 0) return army.get(i);
        }
        return army.get(0);
    }

    public boolean hasGrudge(UUID player, int now) {
        Integer until = grudges.get(player);
        if (until == null) return false;
        if (until < now) {
            grudges.remove(player);
            return false;
        }
        return true;
    }

    public static int parseRgb(String hex) {
        try {
            String h = hex.trim();
            if (h.startsWith("#")) h = h.substring(1);
            return Integer.parseInt(h, 16) & 0xFFFFFF;
        } catch (RuntimeException e) {
            return 0xFFFFFF;
        }
    }

    public static Mode parseMode(String s, Mode def) {
        if (s == null) return def;
        try {
            return Mode.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    static Material material(String name, Material def, boolean mustBeBlock, Logger log) {
        if (name == null || name.isBlank()) return def;
        Material m = Material.matchMaterial(name.trim());
        if (m == null || (mustBeBlock && !m.isBlock())) {
            if (log != null) log.warning("Unknown " + (mustBeBlock ? "block" : "material") + " '" + name + "', using " + def);
            return def;
        }
        return m;
    }
}
