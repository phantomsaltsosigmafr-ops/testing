package dev.unstablesmp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.unstablesmp.ability.AbilityType;
import dev.unstablesmp.event.EventType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Cross-checks the shipped config.yml and messages.yml. */
class DefaultConfigTest {

    private static YamlConfiguration load(String name) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        try (InputStream in = DefaultConfigTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            y.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return y;
    }

    @Test
    void factionsReferenceExistingTemplates() throws Exception {
        YamlConfiguration c = load("config.yml");
        ConfigurationSection templates = c.getConfigurationSection("templates");
        ConfigurationSection factions = c.getConfigurationSection("factions");
        assertEquals(List.of("null", "jamatop", "law", "cindercrest", "clownpierce", "bat", "invismafia"),
                new ArrayList<>(factions.getKeys(false)));
        for (String id : factions.getKeys(false)) {
            ConfigurationSection f = factions.getConfigurationSection(id);
            assertTrue(templates.contains(f.getString("leader")), id + " leader template");
            assertTrue(templates.getBoolean(f.getString("leader") + ".leader"), id + " leader is flagged leader");
            for (String entry : f.getStringList("army")) {
                assertTrue(templates.contains(entry.split(":")[0]), id + " army entry " + entry);
            }
        }
        for (String t : templates.getKeys(false)) {
            String summon = templates.getString(t + ".summon", "");
            if (!summon.isEmpty()) assertTrue(templates.contains(summon), t + " summons " + summon);
        }
        assertEquals("Clownpierce", templates.getString("clownpierce.head"));
    }

    @Test
    void battlePresetsAndEventsAreValid() throws Exception {
        YamlConfiguration c = load("config.yml");
        List<String> presets = c.getStringList("battles.presets");
        assertEquals(List.of("null:law", "clownpierce:law", "null:clownpierce", "cindercrest:bat", "invismafia:law", "jamatop:null"), presets);
        for (String p : presets) {
            for (String side : p.split(":")) assertTrue(c.contains("factions." + side), p);
        }
        for (EventType t : EventType.values()) {
            ConfigurationSection e = c.getConfigurationSection("events." + t.configKey);
            assertNotNull(e, t.configKey);
            assertTrue(c.contains("factions." + e.getString("faction")), t + " faction");
            assertTrue(e.contains("command.command"), t + " has a COMMAND spawn-mode block");
        }
    }

    @Test
    void everyAbilityHasConfig() throws Exception {
        YamlConfiguration c = load("config.yml");
        for (AbilityType t : AbilityType.values()) {
            ConfigurationSection a = c.getConfigurationSection("abilities." + t.id);
            assertNotNull(a, t.id);
            assertTrue(a.contains("name") && a.contains("lore") && a.contains("cooldown-seconds"), t.id);
            assertTrue(a.contains("item-model") && a.contains("right-click") && a.contains("sneak-right-click"), t.id);
        }
    }

    @Test
    void everyMessageParsesWithNoUnresolvedTags() throws Exception {
        YamlConfiguration m = load("messages.yml");
        // Resolve every placeholder name the plugin passes to some sample text.
        TagResolver placeholders = new TagResolver() {
            @Override
            public Tag resolve(String name, ArgumentQueue arguments, Context ctx) {
                return PLACEHOLDERS.contains(name) ? Tag.preProcessParsed("X") : null;
            }

            @Override
            public boolean has(String name) {
                return PLACEHOLDERS.contains(name);
            }
        };
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Object> e : m.getValues(true).entrySet()) {
            if (!(e.getValue() instanceof String raw)) continue;
            if (e.getKey().startsWith("usage.") || e.getKey().equals("help")) continue; // literal <args> on purpose
            String text = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(raw, placeholders));
            Matcher left = Pattern.compile("<[a-z_/!#:]").matcher(text);
            if (left.find()) problems.add(e.getKey() + " -> " + text);
        }
        assertTrue(problems.isEmpty(), "unresolved tags: " + problems);
        assertFalse(m.getString("combat.actionbar").isEmpty());
    }

    private static final List<String> PLACEHOLDERS = List.of("prefix", "player", "count", "ability", "faction", "formation", "seconds",
            "verb", "victim", "killer", "combo", "target", "caster", "players", "swarms", "effects", "hp", "max", "owner", "spawned",
            "asked", "x", "y", "z", "mode", "leader", "side_a", "side_b", "size", "winner", "loser", "event", "starter", "time", "alive",
            "main", "sneak");
}
