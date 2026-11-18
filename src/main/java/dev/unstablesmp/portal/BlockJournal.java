package dev.unstablesmp.portal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Every block a portal changes, with what was there before. Blocks are restored when portals close, when the plugin
 * disables, and on the next start if the server crashed or was killed mid-breach: each change is appended to
 * restore.log the moment it happens ({@code +} = changed, {@code -} = put back), so nothing is ever unrecorded.
 * Protection listeners use {@link #tracked} so nobody can break, blow up or push portal blocks.
 */
public final class BlockJournal {
    public record Pos(UUID world, int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }

        String key() {
            return world + ";" + x + ";" + y + ";" + z;
        }
    }

    private final File file;
    private final Logger log;
    private final Map<Pos, String> original = new LinkedHashMap<>();
    private OutputStream out;
    private boolean failed;

    public BlockJournal(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public boolean tracked(Block b) {
        return !original.isEmpty() && original.containsKey(Pos.of(b));
    }

    public int size() {
        return original.size();
    }

    /** Changes a block, remembering the first original state. No physics, so nothing falls or flows mid-animation. */
    public void set(Block b, BlockData data) {
        Pos p = Pos.of(b);
        if (!original.containsKey(p)) {
            String was = b.getBlockData().getAsString();
            original.put(p, was);
            append("+;" + p.key() + ";" + was);
        }
        b.setBlockData(data, false);
    }

    /** Puts one block back. Returns false if it wasn't tracked. */
    public boolean restore(Block b) {
        Pos p = Pos.of(b);
        String data = original.remove(p);
        if (data == null) return false;
        apply(b, data);
        append("-;" + p.key());
        if (original.isEmpty()) reset();
        return true;
    }

    public int restoreAll() {
        int n = 0;
        for (Map.Entry<Pos, String> e : new ArrayList<>(original.entrySet())) {
            World w = Bukkit.getWorld(e.getKey().world());
            if (w == null) continue;
            apply(w.getBlockAt(e.getKey().x(), e.getKey().y(), e.getKey().z()), e.getValue());
            original.remove(e.getKey());
            n++;
        }
        rewrite();
        return n;
    }

    private void apply(Block b, String data) {
        try {
            b.setBlockData(Bukkit.createBlockData(data), false);
        } catch (IllegalArgumentException ex) {
            log.warning("Could not restore " + data + " at " + b.getX() + " " + b.getY() + " " + b.getZ() + ": " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ restore.log

    private void append(String line) {
        if (failed) return;
        try {
            if (out == null) {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                out = new FileOutputStream(file, true);
            }
            // unbuffered: once write() returns, a killed server still leaves the line on disk
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            failed = true;
            log.warning("Could not write " + file + " (crash recovery off until restart): " + ex.getMessage());
        }
    }

    /** Nothing left to restore: drop the log. */
    private void reset() {
        close();
        if (file.exists() && !file.delete()) log.warning("Could not delete " + file);
    }

    /** Rewrites the log with just what's still pending (blocks in unloaded worlds), or deletes it. */
    private void rewrite() {
        reset();
        if (original.isEmpty()) return;
        for (Map.Entry<Pos, String> e : original.entrySet()) append("+;" + e.getKey().key() + ";" + e.getValue());
    }

    public void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // nothing useful to do; the lines are already written
        }
        out = null;
    }

    /** Restores blocks left behind by a crash. Blocks in worlds that aren't loaded stay in the log for next time. */
    public int restoreLeftovers() {
        if (!file.isFile()) return 0;
        Map<Pos, String> pending = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warning("Could not read " + file + ": " + ex.getMessage());
            return 0;
        }
        for (String line : lines) {
            String[] p = line.split(";", 6);
            if (p.length < 5) continue;
            try {
                Pos pos = new Pos(UUID.fromString(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                if (p[0].equals("+") && p.length == 6) pending.putIfAbsent(pos, p[5]);
                else if (p[0].equals("-")) pending.remove(pos);
            } catch (IllegalArgumentException ex) {
                // a half-written last line from a hard kill: skip it
            }
        }
        int restored = 0;
        for (Map.Entry<Pos, String> e : pending.entrySet()) {
            World w = Bukkit.getWorld(e.getKey().world());
            if (w == null) {
                original.put(e.getKey(), e.getValue());
                continue;
            }
            apply(w.getBlockAt(e.getKey().x(), e.getKey().y(), e.getKey().z()), e.getValue());
            restored++;
        }
        rewrite();
        return restored;
    }
}
