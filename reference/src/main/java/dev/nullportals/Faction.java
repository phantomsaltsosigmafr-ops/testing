package dev.nullportals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

/** A commandable army (or the hostile null swarm of a portal event). */
final class Faction {
    enum Mode { PASSIVE, DEFEND, AGGRESSIVE }

    // ---- definition
    String id;
    String display;          // with § colour codes
    char colorCode = 'f';
    Color color = Color.fromRGB(0xFFFFFF);
    String buildBlock = "COBBLED_DEEPSLATE";
    String leaderTemplate = "";
    final List<String> army = new ArrayList<>();      // template ids
    final List<Integer> weights = new ArrayList<>();
    boolean alwaysHostile;   // portal-event nulls: attack every player and every faction

    // ---- live orders
    Mode mode = Mode.DEFEND;
    UUID focus;              // horn target
    Formation formation = Formation.GRID;
    double spacing = 2.0;
    boolean follow = true;
    UUID commander;
    Location anchor;
    float anchorYaw;
    boolean attackOutsiders = false;
    boolean fightFactions = true;
    boolean build = true;
    boolean breakBlocks = true;
    boolean fortify = false;
    boolean showNames = true;
    boolean glow = false;
    final Set<UUID> members = new HashSet<>();
    final Map<UUID, Long> grudges = new HashMap<>();

    // ---- world edits (for clearing / restoring)
    final List<Block> placed = new ArrayList<>();
    final List<Object[]> broken = new ArrayList<>(); // {Block, BlockData}
    boolean logBroken;

    static Faction load(String id, ConfigurationSection c) {
        Faction f = new Faction();
        f.id = id;
        f.display = NullPortalsPlugin.color(c.getString("name", id));
        String code = c.getString("chat-color", "f");
        f.colorCode = code.isEmpty() ? 'f' : code.charAt(code.length() - 1);
        f.color = parseColor(c.getString("armor-color", "#FFFFFF"));
        f.buildBlock = c.getString("build-block", "COBBLED_DEEPSLATE");
        f.leaderTemplate = c.getString("leader", "");
        for (String s : c.getStringList("army")) {
            String[] p = s.split(":");
            f.army.add(p[0].trim());
            int w = 1;
            if (p.length > 1) {
                try {
                    w = Math.max(1, Integer.parseInt(p[1].trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            f.weights.add(w);
        }
        f.formation = parseFormation(c.getString("formation", "GRID"));
        f.spacing = c.getDouble("spacing", 2.0);
        f.attackOutsiders = c.getBoolean("attack-outsiders", false);
        return f;
    }

    /** Copy used for portal events so their nulls never take orders from a player's horn. */
    Faction eventCopy(String newId) {
        Faction f = new Faction();
        f.id = newId;
        f.display = display;
        f.colorCode = colorCode;
        f.color = color;
        f.buildBlock = buildBlock;
        f.leaderTemplate = leaderTemplate;
        f.army.addAll(army);
        f.weights.addAll(weights);
        f.alwaysHostile = true;
        f.mode = Mode.AGGRESSIVE;
        f.follow = false;
        f.showNames = false;
        f.logBroken = true;
        return f;
    }

    String pickArmy(Random rnd) {
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

    boolean hasGrudge(UUID id, long now) {
        Long until = grudges.get(id);
        if (until == null) return false;
        if (until < now) {
            grudges.remove(id);
            return false;
        }
        return true;
    }

    static Color parseColor(String hex) {
        try {
            String h = hex.trim();
            if (h.startsWith("#")) h = h.substring(1);
            return Color.fromRGB(Integer.parseInt(h, 16) & 0xFFFFFF);
        } catch (Exception e) {
            return Color.fromRGB(0xFFFFFF);
        }
    }

    static Formation parseFormation(String s) {
        try {
            return Formation.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return Formation.GRID;
        }
    }
}
