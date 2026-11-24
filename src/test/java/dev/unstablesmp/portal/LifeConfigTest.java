package dev.unstablesmp.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.unstablesmp.life.LifeConfig;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LifeConfigTest {

    private static LifeConfig defaults() {
        var in = Objects.requireNonNull(LifeConfigTest.class.getClassLoader().getResourceAsStream("config.yml"));
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        return LifeConfig.load(y);
    }

    @Test
    void defaultsLoad() {
        LifeConfig c = defaults();
        assertFalse(c.autoStart);
        assertTrue(c.names.size() >= 40, "plenty of names");
        for (String n : c.names) assertTrue(n.matches("[A-Za-z0-9_]{3,16}"), "valid Minecraft name: " + n);
        assertTrue(c.activities.keySet().containsAll(java.util.List.of("home", "mine", "visit", "explore", "afk")));
        assertEquals("vexbot spawn {name} {x} {y} {z}", c.joinCommands.get(0));
        assertEquals("vexbot kill {name}", c.leaveCommands.get(0));
        assertEquals("vexbot goto {name} {x} {y} {z}", c.oreCommands.get(0));
        assertEquals(4, c.crewWeights.length);
        assertTrue(c.banditNames.size() >= 10);
        assertEquals("vexbot attack {name} {target}", c.banditAttack.get(0));
        assertTrue(c.ggLines.contains("gG") && c.ggLines.contains("Gg"));
        assertEquals(-50, c.mineDepth.min());
        assertEquals(20, c.mineDepth.max());
        assertTrue(c.activities.keySet().containsAll(java.util.List.of("road", "market", "nether")));
        assertTrue(c.market && c.marketAtSpawn && c.marketStalls == 6);
        assertTrue(c.trades.size() >= 10 && c.trades.stream().allMatch(t -> t.contains("->")));
        assertTrue(c.netherLoot.contains("BLAZE_ROD:0-3"));
        assertFalse(c.warsAuto);
        assertEquals(4, c.warTnt);
        assertEquals("vexbot attack {name} {target}", c.warAttack.get(0));
        assertEquals("vexbot attack {name} {target}", c.defendCommands.get(0));
        assertTrue(c.campAuto && c.campLoot.size() >= 5);
        assertEquals(150, c.campDistance.min());
        assertEquals(30, c.growMinutes);
    }

    @Test
    void groupSizesFollowWeights() {
        java.util.Random r = new java.util.Random(1);
        int[] counts = new int[5];
        for (int i = 0; i < 10000; i++) counts[LifeConfig.pickSize(new int[] {50, 30, 15, 5}, 4, r)]++;
        assertTrue(counts[1] > counts[2] && counts[2] > counts[3] && counts[3] > counts[4], java.util.Arrays.toString(counts));
        for (int i = 0; i < 1000; i++) assertTrue(LifeConfig.pickSize(new int[] {50, 30, 15, 5}, 2, r) <= 2);
    }

    @Test
    void peakHoursWrapPastMidnight() {
        LifeConfig c = defaults();
        assertTrue(c.isPeak(18));
        assertFalse(c.isPeak(3));
        YamlConfiguration y = new YamlConfiguration();
        y.set("residents.peak-hours", "20-2");
        LifeConfig w = LifeConfig.load(y);
        assertTrue(w.isPeak(23));
        assertTrue(w.isPeak(1));
        assertFalse(w.isPeak(12));
    }
}
