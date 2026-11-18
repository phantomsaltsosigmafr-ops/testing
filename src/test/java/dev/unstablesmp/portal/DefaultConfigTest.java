package dev.unstablesmp.portal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.unstablesmp.Settings;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DefaultConfigTest {
    private static final List<String> PLACEHOLDERS = List.of("portals", "open", "seconds", "time", "spawned", "max",
            "x", "y", "z", "world", "facing", "count", "id", "phase", "name", "option", "player", "command", "alive");

    private static YamlConfiguration config() {
        var in = Objects.requireNonNull(DefaultConfigTest.class.getClassLoader().getResourceAsStream("config.yml"));
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test
    void spawnCommandAndNamesAreValid() {
        YamlConfiguration c = config();
        assertEquals(List.of("vexbot nullportalspawn {x} {y} {z} {count}"), c.getStringList("spawn.commands"));
        int[] iv = Settings.startEnd(c.get("spawn.interval-ticks"), 0, 0);
        assertEquals(60, iv[0]);
        assertEquals(24, iv[1]);
        for (String m : c.getStringList("portal.frame")) assertNotNull(Material.matchMaterial(m), m);
        for (String m : c.getStringList("portal.never-replace")) assertNotNull(Material.matchMaterial(m), m);
        for (String m : c.getStringList("portal.corruption.blocks")) assertNotNull(Material.matchMaterial(m.split(":")[0]), m);
        assertNotNull(Material.matchMaterial(c.getString("portal.surface")));
        var effects = c.getConfigurationSection("effects");
        for (String key : List.of("warning-start", "warning-pulse", "warning-ground", "tear", "tear-ground", "frame-block",
                "surface-row", "open", "ambient", "ambient-hum", "corrupt", "pulse", "pulse-surface", "surface-collapse",
                "frame-crumble", "sealed")) {
            assertTrue(effects.isConfigurationSection(key), "effect " + key + " missing");
            var e = effects.getConfigurationSection(key);
            assertFalse(e.getStringList("particles").isEmpty() && e.getStringList("sounds").isEmpty(), key + " is empty");
            for (String line : e.getStringList("particles")) {
                String name = line.trim().split("\\s+")[0];
                assertDoesNotThrow(() -> Particle.valueOf(name), "effects." + key + ": unknown particle " + name);
            }
            for (String line : e.getStringList("sounds")) {
                assertTrue(line.trim().matches("minecraft:[a-z0-9_.]+ [0-9.]+ [0-9.]+(-[0-9.]+)?"), "effects." + key + ": bad sound " + line);
            }
        }
    }

    @Test
    void everyMessageParses() {
        TagResolver.Builder b = TagResolver.builder();
        for (String p : PLACEHOLDERS) b.resolver(Placeholder.unparsed(p, "1"));
        TagResolver all = b.build();
        var section = config().getConfigurationSection("messages");
        for (String key : section.getKeys(false)) {
            for (String line : section.getString(key, "").split("\n")) {
                String plain = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(line, all));
                assertFalse(plain.contains("<"), "messages." + key + " has an unknown tag: " + plain);
            }
        }
    }

    @Test
    void ranges() {
        assertEquals(new Settings.IntRange(1, 3), Settings.IntRange.parse("1-3", null));
        assertEquals(new Settings.IntRange(1, 3), Settings.IntRange.parse("3-1", null));
        assertEquals(new Settings.IntRange(4, 4), Settings.IntRange.parse(4, null));
        assertEquals(new Settings.IntRange(2, 5), Settings.IntRange.parse(List.of(2, 5), null));
        assertEquals(50, Settings.IntRange.lerp(50, 18, 0));
        assertEquals(18, Settings.IntRange.lerp(50, 18, 1));
        assertEquals(34, Settings.IntRange.lerp(50, 18, 0.5));
        int[] se = Settings.startEnd("60-20", 1, 1);
        assertEquals(60, se[0]);
        assertEquals(20, se[1]);
    }
}
