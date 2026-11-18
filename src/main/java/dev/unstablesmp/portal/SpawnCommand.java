package dev.unstablesmp.portal;

import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;

/**
 * Builds the spawn command for one pulse, e.g. {@code vexbot nullportalspawn {x} {y} {z} {count}}.
 * Placeholders: {x} {y} {z} (block), {xd} {yd} {zd} (exact, one decimal), {count}, {world}, {portal}, {breach},
 * {facing} (north/south/east/west), {yaw} (degrees bots should face).
 */
public final class SpawnCommand {
    private SpawnCommand() {
    }

    public static String format(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > i) {
                    String v = values.get(template.substring(i + 1, end).toLowerCase(Locale.ROOT));
                    if (v != null) {
                        out.append(v);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    public static Map<String, String> values(int x, int y, int z, double xd, double yd, double zd, int count, String world,
                                             int portal, int breach, Site site) {
        float yaw = site.fx() == 1 ? -90f : site.fx() == -1 ? 90f : site.fz() == 1 ? 0f : 180f;
        return Map.ofEntries(
                Map.entry("x", Integer.toString(x)), Map.entry("y", Integer.toString(y)), Map.entry("z", Integer.toString(z)),
                Map.entry("xd", one(xd)), Map.entry("yd", one(yd)), Map.entry("zd", one(zd)),
                Map.entry("count", Integer.toString(count)), Map.entry("world", world),
                Map.entry("portal", Integer.toString(portal)), Map.entry("breach", Integer.toString(breach)),
                Map.entry("facing", site.facingName()), Map.entry("yaw", one(yaw)));
    }

    private static String one(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** The command's label ({@code vexbot} for {@code /vexbot nullportalspawn ...}), lower case, without a namespace. */
    public static String label(String command) {
        String c = command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        int space = c.indexOf(' ');
        String label = (space < 0 ? c : c.substring(0, space)).toLowerCase(Locale.ROOT);
        return label;
    }

    /** Whether some plugin (or the game) provides the command right now. */
    public static boolean available(String command) {
        String label = label(command);
        if (label.isEmpty()) return false;
        Command cmd = Bukkit.getCommandMap().getCommand(label);
        return cmd != null;
    }
}
