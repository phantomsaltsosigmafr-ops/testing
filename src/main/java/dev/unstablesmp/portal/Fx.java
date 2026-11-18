package dev.unstablesmp.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/**
 * Config-driven particles and sounds. Effects are written as short strings so they're easy to tune:
 * <pre>
 *   particles: ["REVERSE_PORTAL 120 1.5 2.5 0.3 0.6"]     # name count across up out speed [extra]
 *   sounds:    ["minecraft:block.end_portal.spawn 4 0.6"]  # key volume pitch[-pitchMax]
 * </pre>
 * Offsets are in portal space (across the portal, up, out of it) and turned into world axes per portal. {@code extra}
 * is a colour ({@code #000000}) for dust/colour particles, or a block ({@code CRYING_OBSIDIAN}, or {@code ground}
 * for the block underneath) for block particles. Sounds play by key, so they work on every 1.21.x server.
 */
public final class Fx {
    private Fx() {
    }

    public record Particles(Particle particle, int count, double across, double up, double out, double speed,
                            Object data, boolean groundBlock) {
    }

    public record Sound(String key, float volume, float pitchMin, float pitchMax) {
    }

    public record Effect(List<Particles> particles, List<Sound> sounds) {
        public static final Effect EMPTY = new Effect(List.of(), List.of());

        public static Effect parse(String name, ConfigurationSection c, Logger log) {
            List<Particles> ps = new ArrayList<>();
            for (String line : c.getStringList("particles")) {
                Particles p = parseParticle(line, name, log);
                if (p != null) ps.add(p);
            }
            List<Sound> ss = new ArrayList<>();
            for (String line : c.getStringList("sounds")) {
                Sound s = parseSound(line, name, log);
                if (s != null) ss.add(s);
            }
            return new Effect(List.copyOf(ps), List.copyOf(ss));
        }

        public boolean isEmpty() {
            return particles.isEmpty() && sounds.isEmpty();
        }
    }

    static Particles parseParticle(String line, String effect, Logger log) {
        String[] t = line.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) return null;
        Particle particle;
        try {
            particle = Particle.valueOf(t[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warn(log, effect, "unknown particle '" + t[0] + "' (skipped; this server version may not have it)");
            return null;
        }
        try {
            int count = t.length > 1 ? Integer.parseInt(t[1]) : 1;
            double across = t.length > 2 ? Double.parseDouble(t[2]) : 0;
            double up = t.length > 3 ? Double.parseDouble(t[3]) : 0;
            double out = t.length > 4 ? Double.parseDouble(t[4]) : 0;
            double speed = t.length > 5 ? Double.parseDouble(t[5]) : 0;
            String extra = t.length > 6 ? t[6] : null;
            Class<?> type = particle.getDataType();
            Object data = null;
            boolean ground = false;
            if (type == Void.class) {
                data = null;
            } else if (type == Particle.DustOptions.class) {
                data = new Particle.DustOptions(color(extra, Color.BLACK), 1.6f);
            } else if (type == Particle.DustTransition.class) {
                data = new Particle.DustTransition(color(extra, Color.BLACK), Color.fromRGB(0x6A1B9A), 1.6f);
            } else if (type == Color.class) {
                data = color(extra, Color.BLACK);
            } else if (type == BlockData.class) {
                if (extra == null || extra.equalsIgnoreCase("ground")) {
                    ground = true;
                } else {
                    Material m = Material.matchMaterial(extra);
                    data = m != null && m.isBlock() ? m : Material.OBSIDIAN; // turned into BlockData when played
                }
            } else if (type == ItemStack.class) {
                Material m = extra == null ? null : Material.matchMaterial(extra);
                data = m != null && m.isItem() ? m : Material.OBSIDIAN; // turned into an ItemStack when played
            } else if (type == Float.class) {
                data = extra == null ? 0f : Float.parseFloat(extra);
            } else if (type == Integer.class) {
                data = extra == null ? 0 : Integer.parseInt(extra);
            } else {
                warn(log, effect, "particle " + particle + " needs data this plugin can't build (skipped)");
                return null;
            }
            return new Particles(particle, Math.max(0, count), across, up, out, speed, data, ground);
        } catch (NumberFormatException ex) {
            warn(log, effect, "bad numbers in '" + line + "' (skipped)");
            return null;
        }
    }

    static Sound parseSound(String line, String effect, Logger log) {
        String[] t = line.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) return null;
        try {
            float volume = t.length > 1 ? Float.parseFloat(t[1]) : 1f;
            float pMin = 1f, pMax = 1f;
            if (t.length > 2) {
                String p = t[2];
                int dash = p.indexOf('-', 1);
                pMin = Float.parseFloat(dash > 0 ? p.substring(0, dash) : p);
                pMax = dash > 0 ? Float.parseFloat(p.substring(dash + 1)) : pMin;
            }
            String key = t[0].toLowerCase(Locale.ROOT);
            if (!key.contains(":")) key = "minecraft:" + key;
            return new Sound(key, volume, Math.min(pMin, pMax), Math.max(pMin, pMax));
        } catch (NumberFormatException ex) {
            warn(log, effect, "bad numbers in '" + line + "' (skipped)");
            return null;
        }
    }

    private static Color color(String hex, Color fallback) {
        if (hex == null) return fallback;
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16) & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void warn(Logger log, String effect, String msg) {
        if (log != null) log.warning("config effects." + effect + ": " + msg);
    }

    // ------------------------------------------------------------------ playing

    /**
     * Plays an effect at a point. {@code ax/az} is the portal's across axis and {@code fx/fz} its facing, used to turn
     * portal-space offsets into world offsets (pass ax=1, az=0, fx=0, fz=1 for plain world axes).
     */
    public static void play(Effect e, World w, double x, double y, double z, int ax, int az, int fx, int fz) {
        if (e == null || w == null) return;
        for (Particles p : e.particles()) {
            double ox = Math.abs(ax) * p.across() + Math.abs(fx) * p.out();
            double oz = Math.abs(az) * p.across() + Math.abs(fz) * p.out();
            Object data = p.data();
            if (p.groundBlock()) data = groundData(w, x, y, z);
            else if (data instanceof Material m) data = p.particle().getDataType() == BlockData.class ? m.createBlockData() : new ItemStack(m);
            spawn(w, p.particle(), x, y, z, p.count(), ox, p.up(), oz, p.speed(), data);
        }
        if (e.sounds().isEmpty()) return;
        Location at = new Location(w, x, y, z);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Sound s : e.sounds()) {
            float pitch = s.pitchMin() == s.pitchMax() ? s.pitchMin() : (float) r.nextDouble(s.pitchMin(), s.pitchMax());
            w.playSound(at, s.key(), SoundCategory.MASTER, s.volume(), pitch);
        }
    }

    private static <T> void spawn(World w, Particle particle, double x, double y, double z, int count, double ox,
                                  double oy, double oz, double speed, T data) {
        w.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, data, true);
    }

    private static BlockData groundData(World w, double x, double y, double z) {
        Block b = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y) - 1, (int) Math.floor(z));
        for (int i = 0; i < 3 && b.getType().isAir(); i++) b = b.getRelative(0, -1, 0);
        return b.getType().isAir() ? Material.BLACKSTONE.createBlockData() : b.getBlockData();
    }
}
