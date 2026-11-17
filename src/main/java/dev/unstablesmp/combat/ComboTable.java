package dev.unstablesmp.combat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Combo table from config.yml -> combos. A combo fires when damage from one of its sources hits a target that
 * carries its mark, e.g. Null Brand + Arrowhead Dive. Multipliers of every matching combo stack.
 */
public final class ComboTable {

    public record Combo(String id, Mark mark, Set<String> sources, double multiplier, String message) {
    }

    private final List<Combo> combos = new ArrayList<>();
    private double maxMultiplier = 3.0;

    public void load(ConfigurationSection root, Logger log) {
        combos.clear();
        if (root == null) return;
        maxMultiplier = Math.max(1.0, root.getDouble("max-total-multiplier", 3.0));
        ConfigurationSection list = root.getConfigurationSection("list");
        if (list == null) return;
        for (String id : list.getKeys(false)) {
            ConfigurationSection c = list.getConfigurationSection(id);
            if (c == null) continue;
            Mark mark;
            try {
                mark = Mark.valueOf(c.getString("mark", "").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                if (log != null) log.warning("Combo " + id + " has an unknown mark: " + c.getString("mark"));
                continue;
            }
            Set<String> sources = new HashSet<>();
            for (String s : c.getStringList("sources")) sources.add(s.trim().toLowerCase(Locale.ROOT));
            double mult = Math.max(1.0, c.getDouble("multiplier", 1.25));
            combos.add(new Combo(id, mark, sources, mult, c.getString("message", "")));
        }
    }

    /** Total multiplier for damage from {@code source} against a target; matching combos are added to {@code hits}. */
    public double multiplier(String source, Predicate<Mark> targetHas, List<Combo> hits) {
        double m = 1.0;
        for (Combo c : combos) {
            if (!c.sources.contains(source)) continue;
            if (!targetHas.test(c.mark)) continue;
            m *= c.multiplier;
            if (hits != null) hits.add(c);
        }
        return Math.min(m, maxMultiplier);
    }

    public List<Combo> all() {
        return combos;
    }
}
