package dev.unstablesmp.core;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** messages.yml loader. Every value is MiniMessage; {@code <prefix>} is available everywhere. */
public final class Messages {
    private final JavaPlugin plugin;
    private YamlConfiguration yml = new YamlConfiguration();
    private TagResolver prefix = TagResolver.empty();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        yml = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                yml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("Could not read default messages.yml: " + ex.getMessage());
        }
        prefix = Placeholder.parsed("prefix", yml.getString("prefix", ""));
    }

    public String raw(String key) {
        return yml.getString(key, "<red>missing message: " + key);
    }

    public Component get(String key, TagResolver... resolvers) {
        String raw = raw(key);
        if (raw.isEmpty()) return Component.empty();
        return Text.MM.deserialize(raw, TagResolver.resolver(TagResolver.resolver(resolvers), prefix));
    }

    public void send(Audience to, String key, TagResolver... resolvers) {
        if (to == null || raw(key).isEmpty()) return;
        to.sendMessage(get(key, resolvers));
    }

    /** Placeholder helpers so callers don't import MiniMessage classes everywhere. */
    public static TagResolver p(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
    }

    public static TagResolver c(String name, Component value) {
        return Placeholder.component(name, value == null ? Component.empty() : value);
    }
}
