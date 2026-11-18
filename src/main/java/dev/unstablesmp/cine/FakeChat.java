package dev.unstablesmp.cine;

import java.util.Collection;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Vanilla-identical fake messages (they use the game's own translation keys, so they look exactly like the real
 * thing in every language): player chat, join/leave, and "X was slain by Y" death lines. Used for bots and scripted
 * scenes so recordings look like a real server.
 */
public final class FakeChat {

    public Component chatLine(Component name, Component message) {
        return Component.translatable("chat.type.text", name, message);
    }

    public Component joinLine(Component name) {
        return Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, name);
    }

    public Component leaveLine(Component name) {
        return Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, name);
    }

    public Component deathLine(Component victim, Component killer) {
        return killer == null ? Component.translatable("death.attack.generic", victim)
                : Component.translatable("death.attack.player", victim, killer);
    }

    public Component deathLine(Component victim, Component killer, Component weapon) {
        return weapon == null ? deathLine(victim, killer) : Component.translatable("death.attack.player.item", victim, killer, weapon);
    }

    public void broadcast(Component line) {
        Bukkit.getServer().sendMessage(line);
    }

    public void send(Collection<? extends Player> to, Component line) {
        for (Player p : to) p.sendMessage(line);
        Bukkit.getConsoleSender().sendMessage(line);
    }

    public void send(Audience to, Component line) {
        to.sendMessage(line);
    }
}
