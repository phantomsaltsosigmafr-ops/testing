package dev.unstablesmp.portal;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Where a portal stands: {@code (x, y, z)} is the bottom frame block of the centre column (sunk into the ground),
 * {@code (fx, fz)} the direction bots pour out (one of the four horizontal axes).
 */
public record Site(int x, int y, int z, int fx, int fz) {

    public Site {
        if (Math.abs(fx) + Math.abs(fz) != 1) throw new IllegalArgumentException("facing must be one horizontal axis");
    }

    /** Across axis (along the portal's width). */
    public int ax() {
        return Math.abs(fz);
    }

    public int az() {
        return Math.abs(fx);
    }

    public int worldX(int across, int out) {
        return x + ax() * across + fx * out;
    }

    public int worldZ(int across, int out) {
        return z + az() * across + fz * out;
    }

    public Block block(World w, int across, int up) {
        return w.getBlockAt(worldX(across, 0), y + up, worldZ(across, 0));
    }

    public Block block(World w, int across, int up, int out) {
        return w.getBlockAt(worldX(across, out), y + up, worldZ(across, out));
    }

    /** Facing toward a point: the dominant horizontal axis from (x, z) to (tx, tz). */
    public static int[] facingToward(double x, double z, double tx, double tz) {
        double dx = tx - x, dz = tz - z;
        if (Math.abs(dx) >= Math.abs(dz)) return new int[] {dx >= 0 ? 1 : -1, 0};
        return new int[] {0, dz >= 0 ? 1 : -1};
    }

    /** Facing from a Bukkit yaw (0 = south/+Z, 90 = west/-X). */
    public static int[] facingFromYaw(float yaw) {
        double rad = Math.toRadians(yaw);
        return facingToward(0, 0, -Math.sin(rad), Math.cos(rad));
    }

    public String facingName() {
        if (fx == 1) return "east";
        if (fx == -1) return "west";
        return fz == 1 ? "south" : "north";
    }
}
