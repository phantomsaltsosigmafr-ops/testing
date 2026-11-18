package dev.unstablesmp.bot.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Mojang-signed copies of the pack skins in plugins/UnstableSMP/skins/. The client only draws a tab-list head (or a
 * skin without the resource pack) from a textures property signed by Mojang, so each PNG is matched by a hash of its
 * pixels to a signed copy: the plugin's default NULL skin ships with one, and any other PNG (a recoloured NULL skin,
 * custom skins) is uploaded to MineSkin once and cached in skins/heads.yml.
 */
public final class SkinHeads {
    /** A Mojang-signed textures property. */
    public record Texture(String value, String signature) {
    }

    private static final String BUNDLED = "skin-heads.yml";
    private static final URI MINESKIN = URI.create("https://api.mineskin.org/v2/generate");

    private final Plugin plugin;
    private final File skinDir;
    private final File cacheFile;
    private final Logger log;
    /** Signed textures by pixel hash: bundled ones plus the cache. Main thread only. */
    private final Map<String, Texture> byHash = new HashMap<>();
    /** Pack skin id to its signed texture, for the PNGs present at the last reload. Main thread only. */
    private final Map<String, Texture> byId = new HashMap<>();
    private final Map<String, String> uploading = new HashMap<>();
    private ExecutorService uploader;
    private HttpClient http;
    private Consumer<String> onReady = id -> { };
    private boolean bodies = true;
    private boolean upload = true;
    private String apiKey = "";
    /** Uploader thread only: earliest time the next MineSkin request may be sent. */
    private long nextRequestAt;

    public SkinHeads(Plugin plugin, File dataFolder, Logger log) {
        this.plugin = plugin;
        this.skinDir = new File(dataFolder, "skins");
        this.cacheFile = new File(skinDir, "heads.yml");
        this.log = log;
    }

    /** Called on the main thread with a pack skin id whenever a new signed copy becomes available. */
    public void onReady(Consumer<String> listener) {
        this.onReady = listener;
    }

    /** Re-reads settings and matches every PNG in skins/ (call after the resource pack has written its skins). */
    public void reload(ConfigurationSection c) {
        bodies = c == null || c.getBoolean("use-for-bodies", true);
        upload = c == null || c.getBoolean("mineskin-upload", true);
        apiKey = c == null ? "" : c.getString("mineskin-api-key", "");
        byHash.clear();
        byId.clear();
        try (InputStream in = plugin.getResource(BUNDLED)) {
            if (in != null) load(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            log.warning("Could not read bundled skin heads: " + ex.getMessage());
        }
        if (cacheFile.isFile()) load(YamlConfiguration.loadConfiguration(cacheFile));
        File[] pngs = skinDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (pngs == null) return;
        for (File f : pngs) {
            String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
            BufferedImage img;
            try {
                img = ImageIO.read(f);
            } catch (IOException ex) {
                continue;
            }
            if (img == null || img.getWidth() != 64 || (img.getHeight() != 64 && img.getHeight() != 32)) continue;
            String hash = pixelHash(img);
            Texture t = byHash.get(hash);
            if (t != null) byId.put(id, t);
            else if (upload) queueUpload(id, f, hash);
        }
    }

    private void load(YamlConfiguration y) {
        for (String hash : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(hash);
            if (s == null) continue;
            String value = s.getString("value", ""), sig = s.getString("signature", "");
            if (!value.isBlank() && !sig.isBlank()) byHash.put(hash.toLowerCase(Locale.ROOT), new Texture(value, sig));
        }
    }

    /** The id in a {@code pack:<id>[:slim]} skin spec, or null for any other kind of spec. */
    public static String packId(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (!s.regionMatches(true, 0, "pack:", 0, 5)) return null;
        String id = s.substring(5).toLowerCase(Locale.ROOT);
        return id.endsWith(":slim") ? id.substring(0, id.length() - 5) : id;
    }

    /** The signed copy of {@code pack:<id>}, or null if there is none (yet). */
    public Texture texture(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** Whether bot bodies should wear the signed copy (visible without the resource pack). */
    public boolean forBodies() {
        return bodies;
    }

    public void shutdown() {
        if (uploader != null) uploader.shutdownNow();
        uploader = null;
    }

    // ------------------------------------------------------------------ hashing

    /** SHA-256 of the skin's pixels (fully transparent pixels count as one colour), independent of PNG encoding. */
    public static String pixelHash(BufferedImage img) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        byte[] px = new byte[4];
        putInt(px, img.getWidth());
        md.update(px);
        putInt(px, img.getHeight());
        md.update(px);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                putInt(px, (argb >>> 24) == 0 ? 0 : argb);
                md.update(px);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void putInt(byte[] b, int v) {
        b[0] = (byte) (v >>> 24);
        b[1] = (byte) (v >>> 16);
        b[2] = (byte) (v >>> 8);
        b[3] = (byte) v;
    }

    // ------------------------------------------------------------------ MineSkin

    private void queueUpload(String id, File png, String hash) {
        if (uploading.containsKey(hash)) return;
        uploading.put(hash, id);
        if (uploader == null) {
            uploader = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "UnstableSMP-skin-upload");
                t.setDaemon(true);
                return t;
            });
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        }
        final String key = apiKey;
        uploader.execute(() -> {
            Texture t = null;
            String error = null;
            try {
                t = generate(png, key);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException ex) {
                error = ex.getMessage();
            }
            final Texture done = t;
            final String err = error;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> finished(id, hash, done, err));
        });
    }

    private void finished(String id, String hash, Texture t, String error) {
        uploading.remove(hash);
        if (t == null) {
            log.warning("Could not get a signed copy of skins/" + id + ".png from MineSkin (" + error
                    + "); its tab heads use a default skin. Run /uss reload to try again.");
            return;
        }
        byHash.put(hash, t);
        byId.put(id, t);
        YamlConfiguration y = cacheFile.isFile() ? YamlConfiguration.loadConfiguration(cacheFile) : new YamlConfiguration();
        y.set(hash + ".skin", id);
        y.set(hash + ".value", t.value());
        y.set(hash + ".signature", t.signature());
        try {
            y.save(cacheFile);
        } catch (IOException ex) {
            log.warning("Could not save " + cacheFile + ": " + ex.getMessage());
        }
        log.info("Signed skin ready for pack:" + id + " (tab heads and pack-free bodies).");
        onReady.accept(id);
    }

    /** Uploads one PNG (blocking, uploader thread). Waits out MineSkin's rate limit and retries twice. */
    private Texture generate(File png, String key) throws IOException, InterruptedException {
        byte[] file = Files.readAllBytes(png.toPath());
        String boundary = "----uss" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "variant", "classic");
        field(body, boundary, "visibility", "unlisted");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] payload = body.toByteArray();
        String last = "no response";
        for (int attempt = 0; attempt < 3; attempt++) {
            long idle = nextRequestAt - System.currentTimeMillis();
            if (idle > 0) Thread.sleep(idle);
            HttpRequest.Builder req = HttpRequest.newBuilder(MINESKIN).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "UnstableSMP/" + plugin.getPluginMeta().getVersion())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            if (key != null && !key.isBlank()) req.header("Authorization", "Bearer " + key.trim());
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject json;
            try {
                json = JsonParser.parseString(res.body()).getAsJsonObject();
            } catch (RuntimeException ex) {
                last = "HTTP " + res.statusCode();
                nextRequestAt = System.currentTimeMillis() + 10_000;
                continue;
            }
            long wait = waitMillis(json);
            nextRequestAt = System.currentTimeMillis() + wait; // keeps queued uploads inside the rate limit
            Texture t = parse(json);
            if (t != null) return t;
            last = "HTTP " + res.statusCode() + " " + firstError(json);
            if (res.statusCode() != 429 && res.statusCode() < 500) break;
            nextRequestAt = System.currentTimeMillis() + Math.max(wait, 10_000);
        }
        throw new IOException(last);
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** {@code skin.texture.data.{value,signature}} from a MineSkin v2 response. */
    static Texture parse(JsonObject json) {
        if (!json.has("success") || !json.get("success").getAsBoolean()) return null;
        JsonObject data = obj(obj(obj(json, "skin"), "texture"), "data");
        if (data == null || !data.has("value") || !data.has("signature")) return null;
        return new Texture(data.get("value").getAsString(), data.get("signature").getAsString());
    }

    private static long waitMillis(JsonObject json) {
        JsonObject rl = obj(json, "rateLimit");
        long next = 0, delay = 6000;
        JsonObject n = obj(rl, "next");
        if (n != null && n.has("relative")) next = n.get("relative").getAsLong();
        JsonObject d = obj(rl, "delay");
        if (d != null && d.has("millis")) delay = d.get("millis").getAsLong();
        return Math.min(120_000, Math.max(next, delay) + 500);
    }

    private static String firstError(JsonObject json) {
        JsonElement errs = json.get("errors");
        if (errs != null && errs.isJsonArray() && !errs.getAsJsonArray().isEmpty()) {
            JsonElement e = errs.getAsJsonArray().get(0);
            if (e.isJsonObject() && e.getAsJsonObject().has("message")) return e.getAsJsonObject().get("message").getAsString();
        }
        return "";
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
