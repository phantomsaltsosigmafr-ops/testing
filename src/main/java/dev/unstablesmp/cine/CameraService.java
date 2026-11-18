package dev.unstablesmp.cine;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.EntityTracker;
import dev.unstablesmp.core.Ticker;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Cinematic camera paths. Record keyframes where you stand (/uss cam add [ticks]), save them, and play them for any
 * players: they're put in spectator mode riding an invisible display camera that glides along a Catmull-Rom spline
 * (client-interpolated, so it is buttery smooth), then are put back exactly where they were.
 */
public final class CameraService implements Ticker.Tickable {
    public record Key(String world, double x, double y, double z, float yaw, float pitch, int ticks) {
    }

    private static final class Viewer {
        final UUID id;
        final GameMode mode;
        final Location back;

        Viewer(Player p) {
            this.id = p.getUniqueId();
            this.mode = p.getGameMode();
            this.back = p.getLocation();
        }
    }

    private static final class Playback {
        final List<Key> keys;
        final List<Viewer> viewers = new ArrayList<>();
        final int start;
        final int total;
        ItemDisplay camera;

        Playback(List<Key> keys, int start) {
            this.keys = keys;
            this.start = start;
            int t = 0;
            for (int i = 0; i < keys.size() - 1; i++) t += Math.max(1, keys.get(i).ticks());
            this.total = t;
        }
    }

    private final UnstableSMP plugin;
    private final File file;
    private final Map<String, List<Key>> paths = new LinkedHashMap<>();
    private final Map<UUID, List<Key>> editing = new HashMap<>();
    private final List<Playback> playing = new ArrayList<>();
    private final Location scratch = new Location(null, 0, 0, 0);

    public CameraService(UnstableSMP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cameras.yml");
    }

    public void reload() {
        paths.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("paths");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            List<Key> keys = new ArrayList<>();
            for (Map<?, ?> m : root.getMapList(name)) {
                keys.add(new Key(String.valueOf(m.get("world")), num(m, "x"), num(m, "y"), num(m, "z"),
                        (float) num(m, "yaw"), (float) num(m, "pitch"), (int) num(m, "ticks")));
            }
            paths.put(name.toLowerCase(Locale.ROOT), keys);
        }
    }

    private static double num(Map<?, ?> m, String k) {
        Object o = m.get(k);
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, List<Key>> e : paths.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Key k : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("world", k.world());
                m.put("x", k.x());
                m.put("y", k.y());
                m.put("z", k.z());
                m.put("yaw", k.yaw());
                m.put("pitch", k.pitch());
                m.put("ticks", k.ticks());
                list.add(m);
            }
            y.set("paths." + e.getKey(), list);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save cameras.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ editing

    public int add(Player p, int ticks) {
        Location l = p.getEyeLocation();
        List<Key> list = editing.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        list.add(new Key(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), Math.max(1, ticks)));
        return list.size();
    }

    public int undo(Player p) {
        List<Key> list = editing.get(p.getUniqueId());
        if (list != null && !list.isEmpty()) list.remove(list.size() - 1);
        return list == null ? 0 : list.size();
    }

    public void clear(Player p) {
        editing.remove(p.getUniqueId());
    }

    public boolean save(Player p, String name) {
        List<Key> list = editing.get(p.getUniqueId());
        if (list == null || list.size() < 2) return false;
        paths.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(list));
        save();
        return true;
    }

    public boolean delete(String name) {
        boolean r = paths.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (r) save();
        return r;
    }

    public List<String> names() {
        return new ArrayList<>(paths.keySet());
    }

    /** Ticks a path lasts, or -1 if unknown. */
    public int length(String name) {
        List<Key> k = paths.get(name.toLowerCase(Locale.ROOT));
        if (k == null) return -1;
        int t = 0;
        for (int i = 0; i < k.size() - 1; i++) t += Math.max(1, k.get(i).ticks());
        return t;
    }

    // ------------------------------------------------------------------ playback

    public boolean play(String name, List<Player> viewers) {
        List<Key> keys = paths.get(name.toLowerCase(Locale.ROOT));
        if (keys == null || keys.size() < 2 || viewers.isEmpty()) return false;
        Key first = keys.get(0);
        World w = Bukkit.getWorld(first.world());
        if (w == null) return false;
        Playback pb = new Playback(keys, Bukkit.getCurrentTick());
        Location at = new Location(w, first.x(), first.y(), first.z(), first.yaw(), first.pitch());
        pb.camera = w.spawn(at, ItemDisplay.class, d -> {
            plugin.tracker().track(d, null, EntityTracker.Kind.DISPLAY);
            d.setItemStack(new ItemStack(Material.AIR));
            d.setTeleportDuration(2);
        });
        for (Player p : viewers) {
            stop(p);
            pb.viewers.add(new Viewer(p));
            p.teleport(at);
            p.setGameMode(GameMode.SPECTATOR);
            p.setSpectatorTarget(pb.camera);
        }
        playing.add(pb);
        return true;
    }

    public void stop(Player p) {
        for (Playback pb : playing) {
            for (Viewer v : new ArrayList<>(pb.viewers)) {
                if (v.id.equals(p.getUniqueId())) {
                    restore(v);
                    pb.viewers.remove(v);
                }
            }
        }
    }

    public boolean watching(Player p) {
        for (Playback pb : playing) for (Viewer v : pb.viewers) if (v.id.equals(p.getUniqueId())) return true;
        return false;
    }

    private void restore(Viewer v) {
        Player p = Bukkit.getPlayer(v.id);
        if (p == null) return;
        p.setSpectatorTarget(null);
        p.teleport(v.back);
        p.setGameMode(v.mode);
    }

    public void stopAll() {
        for (Playback pb : playing) {
            for (Viewer v : pb.viewers) restore(v);
            plugin.tracker().remove(pb.camera);
        }
        playing.clear();
    }

    @Override
    public void tick(int now) {
        if (playing.isEmpty()) return;
        for (int i = playing.size() - 1; i >= 0; i--) {
            Playback pb = playing.get(i);
            int t = now - pb.start;
            if (t > pb.total || pb.viewers.isEmpty() || !pb.camera.isValid()) {
                for (Viewer v : pb.viewers) restore(v);
                plugin.tracker().remove(pb.camera);
                playing.remove(i);
                continue;
            }
            sample(pb, t);
            pb.camera.teleport(scratch);
            for (Viewer v : pb.viewers) {
                Player p = Bukkit.getPlayer(v.id);
                if (p == null) continue;
                if (p.getSpectatorTarget() != pb.camera) {
                    if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
                    p.setSpectatorTarget(pb.camera);
                }
            }
        }
    }

    /** Catmull-Rom position and smoothed rotation at tick t into {@code scratch}. */
    private void sample(Playback pb, int t) {
        List<Key> k = pb.keys;
        int seg = 0, acc = 0;
        while (seg < k.size() - 2 && acc + Math.max(1, k.get(seg).ticks()) <= t) {
            acc += Math.max(1, k.get(seg).ticks());
            seg++;
        }
        double u = Math.min(1.0, (t - acc) / (double) Math.max(1, k.get(seg).ticks()));
        double s = u * u * (3 - 2 * u); // ease in/out per segment
        Key p0 = k.get(Math.max(0, seg - 1)), p1 = k.get(seg), p2 = k.get(Math.min(k.size() - 1, seg + 1)), p3 = k.get(Math.min(k.size() - 1, seg + 2));
        scratch.setWorld(Bukkit.getWorld(p1.world()));
        scratch.setX(cr(p0.x(), p1.x(), p2.x(), p3.x(), s));
        scratch.setY(cr(p0.y(), p1.y(), p2.y(), p3.y(), s));
        scratch.setZ(cr(p0.z(), p1.z(), p2.z(), p3.z(), s));
        float dyaw = wrap(p2.yaw() - p1.yaw());
        scratch.setYaw(p1.yaw() + dyaw * (float) s);
        scratch.setPitch(p1.pitch() + (p2.pitch() - p1.pitch()) * (float) s);
    }

    static double cr(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    @Override
    public String name() {
        return "camera";
    }
}
