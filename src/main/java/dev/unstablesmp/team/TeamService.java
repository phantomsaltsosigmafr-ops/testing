package dev.unstablesmp.team;

import dev.unstablesmp.core.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Teams shared by players and bots, backed by main-scoreboard teams (name colour, prefix, no friendly fire).
 * A player on team "null" fights alongside NULL bots; bots attack everyone not on their team.
 */
public final class TeamService {
    public record Def(String id, Component name, NamedTextColor color, Component prefix, Material icon, boolean friendlyFire) {
    }

    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<UUID, String> playerTeams = new HashMap<>();
    private boolean hostileToTeamless = true;

    public void reload(ConfigurationSection c) {
        defs.clear();
        if (c != null) {
            hostileToTeamless = c.getBoolean("bots-attack-teamless-players", true);
            ConfigurationSection list = c.getConfigurationSection("list");
            if (list != null) {
                for (String id : list.getKeys(false)) {
                    ConfigurationSection t = list.getConfigurationSection(id);
                    if (t == null) continue;
                    NamedTextColor col = NamedTextColor.NAMES.valueOr(t.getString("color", "white").toLowerCase(Locale.ROOT), NamedTextColor.WHITE);
                    Material icon = Material.matchMaterial(t.getString("icon", "WHITE_WOOL"));
                    defs.put(id.toLowerCase(Locale.ROOT), new Def(id.toLowerCase(Locale.ROOT), Text.mm(t.getString("name", id)), col,
                            Text.mm(t.getString("prefix", "")), icon == null ? Material.WHITE_WOOL : icon, t.getBoolean("friendly-fire", false)));
                }
            }
        }
        for (Def d : defs.values()) ensure(d.id);
    }

    public Map<String, Def> defs() {
        return defs;
    }

    public List<String> ids() {
        return new ArrayList<>(defs.keySet());
    }

    public Def def(String id) {
        return id == null ? null : defs.get(id.toLowerCase(Locale.ROOT));
    }

    private static Scoreboard board() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private static String sbName(String id) {
        return "uss_" + id;
    }

    /** Makes sure the scoreboard team exists (bot groups create theirs on demand). */
    public Team ensure(String id) {
        if (id == null) return null;
        String key = id.toLowerCase(Locale.ROOT);
        Team t = board().getTeam(sbName(key));
        if (t == null) t = board().registerNewTeam(sbName(key));
        Def d = defs.get(key);
        if (d != null) {
            t.color(d.color);
            t.prefix(d.prefix);
            t.displayName(d.name);
            t.setAllowFriendlyFire(d.friendlyFire);
        } else {
            t.setAllowFriendlyFire(false);
        }
        return t;
    }

    public void join(Player p, String id) {
        leave(p);
        String key = id.toLowerCase(Locale.ROOT);
        playerTeams.put(p.getUniqueId(), key);
        ensure(key).addPlayer(p);
    }

    public void leave(Player p) {
        String old = playerTeams.remove(p.getUniqueId());
        if (old != null) {
            Team t = board().getTeam(sbName(old));
            if (t != null) t.removePlayer(p);
        }
    }

    public String teamOf(Player p) {
        return playerTeams.get(p.getUniqueId());
    }

    public void addEntity(String id, Entity e) {
        Team t = ensure(id);
        if (t != null) t.addEntity(e);
    }

    public void removeEntity(String id, Entity e) {
        Team t = board().getTeam(sbName(id.toLowerCase(Locale.ROOT)));
        if (t != null) t.removeEntity(e);
    }

    /** Should a bot on {@code botTeam} attack this player? */
    public boolean botHostileTo(String botTeam, Player p) {
        String pt = playerTeams.get(p.getUniqueId());
        if (pt == null) return hostileToTeamless;
        return !pt.equalsIgnoreCase(botTeam);
    }

    public boolean sameTeam(Player a, Player b) {
        String ta = playerTeams.get(a.getUniqueId());
        return ta != null && ta.equals(playerTeams.get(b.getUniqueId()));
    }

    public void forget(UUID id) {
        playerTeams.remove(id);
    }
}
