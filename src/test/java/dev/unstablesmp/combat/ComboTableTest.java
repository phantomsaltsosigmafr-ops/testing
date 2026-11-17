package dev.unstablesmp.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ComboTableTest {

    private static ComboTable table(String yaml) throws InvalidConfigurationException {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        ComboTable t = new ComboTable();
        t.load(y.getConfigurationSection("combos"), null);
        return t;
    }

    @Test
    void matchingCombosMultiplyAndCap() throws InvalidConfigurationException {
        ComboTable t = table("""
                combos:
                  max-total-multiplier: 2.0
                  list:
                    a: {mark: NULL_BRAND, sources: [arrowhead_dive, rain], multiplier: 1.6}
                    b: {mark: SENTENCED, sources: [arrowhead_dive], multiplier: 1.5}
                    bad: {mark: NOT_A_MARK, sources: [rain], multiplier: 9}
                """);
        assertEquals(2, t.all().size(), "invalid combos are skipped");
        EnumSet<Mark> marks = EnumSet.of(Mark.NULL_BRAND);
        List<ComboTable.Combo> hits = new ArrayList<>();
        assertEquals(1.6, t.multiplier("arrowhead_dive", marks::contains, hits), 1e-9);
        assertEquals(1, hits.size());
        assertEquals(1.0, t.multiplier("judgment", marks::contains, null), 1e-9);
        marks.add(Mark.SENTENCED);
        assertEquals(2.0, t.multiplier("arrowhead_dive", marks::contains, null), 1e-9, "1.6 * 1.5 capped at 2.0");
    }

    @Test
    void defaultConfigCombosAllLoad() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            y.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        ComboTable t = new ComboTable();
        t.load(y.getConfigurationSection("combos"), null);
        assertEquals(y.getConfigurationSection("combos.list").getKeys(false).size(), t.all().size());
        assertEquals(1.6, t.multiplier("arrowhead_dive", m -> m == Mark.NULL_BRAND, null), 1e-9, "Null Brand + Arrowhead Dive");
    }
}
