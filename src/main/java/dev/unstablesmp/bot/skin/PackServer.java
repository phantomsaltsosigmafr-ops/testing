package dev.unstablesmp.bot.skin;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Builds and (optionally) hosts the UnstableSMP resource pack. It contains generated skins (the black NULL skin) plus
 * every PNG dropped into plugins/UnstableSMP/skins/, exposed as {@code pack:<name>} skins for bots via the 1.21.9+
 * profile skin patch. Served by a tiny built-in HTTP server so no external hosting is needed.
 */
public final class PackServer {
    public static final String NAMESPACE = "unstablesmp";
    private final File dataFolder;
    private final Logger log;
    private final Set<String> skins = new LinkedHashSet<>();
    private final UUID packId = UUID.nameUUIDFromBytes("unstablesmp-pack".getBytes(StandardCharsets.UTF_8));
    private HttpServer http;
    private byte[] zip = new byte[0];
    private byte[] sha1 = new byte[20];
    private String url;
    private boolean enabled;
    private boolean sendOnJoin;
    private boolean required;
    private Component prompt = Component.text("UnstableSMP skins (black NULL skin etc.)");

    public PackServer(File dataFolder, Logger log) {
        this.dataFolder = dataFolder;
        this.log = log;
    }

    public void reload(ConfigurationSection c, Component prompt) {
        stop();
        if (prompt != null) this.prompt = prompt;
        enabled = c != null && c.getBoolean("enabled", true);
        sendOnJoin = c != null && c.getBoolean("send-on-join", true);
        required = c != null && c.getBoolean("required", false);
        skins.clear();
        try {
            build(c);
        } catch (IOException | NoSuchAlgorithmException ex) {
            log.warning("Could not build the resource pack: " + ex.getMessage());
            enabled = false;
            return;
        }
        if (!enabled) return;
        String external = c.getString("external-url", "");
        if (!external.isBlank()) {
            url = external;
            log.info("Resource pack: using external URL " + url + " (upload " + new File(dataFolder, "pack/UnstableSMP-pack.zip") + ")");
            return;
        }
        int port = c.getInt("port", 8163);
        String host = c.getString("host", "");
        if (host.isBlank()) host = Bukkit.getIp();
        if (host == null || host.isBlank()) {
            log.warning("Resource pack: set resource-pack.host (your server's public IP or domain) so players can download"
                    + " pack skins. Bots fall back to their fallback skins until then.");
            enabled = false;
            return;
        }
        try {
            http = HttpServer.create(new InetSocketAddress(c.getString("bind", "0.0.0.0"), port), 0);
            http.createContext("/UnstableSMP.zip", ex -> {
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, zip.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(zip);
                }
            });
            http.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "UnstableSMP-pack");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            url = "http://" + host + ":" + port + "/UnstableSMP.zip";
            log.info("Resource pack served at " + url + " (" + skins.size() + " skins)");
        } catch (IOException ex) {
            log.warning("Resource pack server could not bind port " + port + ": " + ex.getMessage());
            enabled = false;
        }
    }

    public void stop() {
        if (http != null) http.stop(0);
        http = null;
    }

    public boolean active() {
        return enabled && url != null;
    }

    public boolean hasSkin(String id) {
        return skins.contains(id);
    }

    public Set<String> skins() {
        return skins;
    }

    public boolean sendOnJoin() {
        return sendOnJoin;
    }

    public void send(Player p) {
        if (!active()) return;
        p.addResourcePack(packId, url, sha1, net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(prompt), required);
    }

    // ------------------------------------------------------------------ building

    private void build(ConfigurationSection c) throws IOException, NoSuchAlgorithmException {
        File packDir = new File(dataFolder, "pack");
        File skinDir = new File(dataFolder, "skins");
        if (!packDir.exists() && !packDir.mkdirs()) throw new IOException("cannot create " + packDir);
        if (!skinDir.exists() && !skinDir.mkdirs()) throw new IOException("cannot create " + skinDir);

        ConfigurationSection nullSkin = c == null ? null : c.getConfigurationSection("null-skin");
        File nullPng = new File(skinDir, "null.png");
        if (!nullPng.exists()) {
            ImageIO.write(nullSkin(nullSkin == null ? "none" : nullSkin.getString("eyes", "none"),
                    nullSkin == null ? 0x0B0B0B : Integer.parseInt(nullSkin.getString("color", "0B0B0B").replace("#", ""), 16)), "png", nullPng);
        }
        int format = c == null ? 75 : c.getInt("pack-format", 75);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            String mcmeta = "{\"pack\":{\"description\":\"UnstableSMP simulator skins\",\"pack_format\":" + format
                    + ",\"supported_formats\":{\"min_inclusive\":34,\"max_inclusive\":999},\"min_format\":34,\"max_format\":999}}";
            put(zos, "pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
            File[] pngs = skinDir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
            if (pngs != null) {
                for (File f : pngs) {
                    String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                    put(zos, "assets/" + NAMESPACE + "/textures/entity/skins/" + id + ".png", Files.readAllBytes(f.toPath()));
                    skins.add(id);
                }
            }
        }
        zip = bytes.toByteArray();
        sha1 = MessageDigest.getInstance("SHA-1").digest(zip);
        Files.write(new File(packDir, "UnstableSMP-pack.zip").toPath(), zip);
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    /** A 64x64 all-black player skin (subtle shading), optionally with eyes. */
    static BufferedImage nullSkin(String eyes, int rgb) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int base = 0xFF000000 | rgb;
        int shade = 0xFF000000 | darker(rgb, 0.7);
        int light = 0xFF000000 | lighter(rgb, 10);
        int[][] regions = { // x, y, w, h of the base (non-overlay) layer
            {0, 0, 32, 16}, {0, 16, 16, 16}, {16, 16, 24, 16}, {40, 16, 16, 16}, {16, 48, 16, 16}, {32, 48, 16, 16}};
        for (int[] r : regions) {
            for (int x = r[0]; x < r[0] + r[2]; x++) {
                for (int y = r[1]; y < r[1] + r[3]; y++) {
                    img.setRGB(x, y, ((x + y) % 7 == 0) ? light : (y % 4 == 3 ? shade : base));
                }
            }
        }
        int eye = switch (eyes.toLowerCase(java.util.Locale.ROOT)) {
            case "white" -> 0xFFF2F2F2;
            case "red" -> 0xFFD01818;
            case "purple" -> 0xFFB14CFF;
            default -> 0;
        };
        if (eye != 0) { // face is at x 8..15, y 8..15
            img.setRGB(9, 12, eye);
            img.setRGB(10, 12, eye);
            img.setRGB(13, 12, eye);
            img.setRGB(14, 12, eye);
        }
        return img;
    }

    private static int darker(int rgb, double f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f), g = (int) (((rgb >> 8) & 0xFF) * f), b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static int lighter(int rgb, int add) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + add), g = Math.min(255, ((rgb >> 8) & 0xFF) + add), b = Math.min(255, (rgb & 0xFF) + add);
        return (r << 16) | (g << 8) | b;
    }
}
