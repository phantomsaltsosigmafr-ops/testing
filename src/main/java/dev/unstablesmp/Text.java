package dev.unstablesmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** MiniMessage helpers. Placeholders are written {@code <name>} in config.yml messages. */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component mm(String raw, TagResolver... resolvers) {
        return MM.deserialize(raw == null ? "" : raw, resolvers);
    }

    public static TagResolver ph(String name, String value) {
        return Placeholder.unparsed(name, value);
    }

    /** A chat message: {@code messages.prefix} + {@code messages.<key>}. */
    public static Component prefixed(Settings s, String key, TagResolver... resolvers) {
        return mm(s.message("prefix") + s.message(key), resolvers);
    }
}
