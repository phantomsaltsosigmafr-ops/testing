package dev.unstablesmp.event;

import java.util.Locale;
import org.bukkit.Material;

/** The four server events. {@code id} is used in commands, {@code configKey} under config.yml -> events. */
public enum EventType {
    PORTAL("portal", "portal-invasion", Material.CRYING_OBSIDIAN),
    NULL("null", "null-outbreak", Material.BLACK_CONCRETE),
    LAW("law", "law-crackdown", Material.GOLD_BLOCK),
    CINDER("cinder", "cindercrest-firestorm", Material.MAGMA_BLOCK);

    public final String id;
    public final String configKey;
    public final Material icon;

    EventType(String id, String configKey, Material icon) {
        this.id = id;
        this.configKey = configKey;
        this.icon = icon;
    }

    public static EventType parse(String s) {
        if (s == null) return null;
        String n = s.trim().toLowerCase(Locale.ROOT);
        for (EventType t : values()) {
            if (t.id.equals(n) || t.configKey.equals(n)) return t;
        }
        if (n.equals("cindercrest") || n.equals("firestorm")) return CINDER;
        if (n.equals("invasion") || n.equals("portals")) return PORTAL;
        if (n.equals("outbreak")) return NULL;
        if (n.equals("crackdown")) return LAW;
        return null;
    }
}
