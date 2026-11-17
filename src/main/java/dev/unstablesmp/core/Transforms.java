package dev.unstablesmp.core;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Display transformation math. A BlockDisplay's model spans 0..1 on every axis and is transformed as
 * {@code v -> translation + leftRotation * (scale * v)}. These helpers place an axis-aligned box of a given
 * size, centred on a point, then rotated about the entity origin.
 */
public final class Transforms {
    private static final Quaternionf IDENTITY = new Quaternionf();

    private Transforms() {
    }

    /**
     * Block display box: centre (cx,cy,cz) and size (sx,sy,sz) in the rotated frame, rotated by {@code rot} about
     * the entity location. Scratch vector is reused; the returned Transformation copies its inputs.
     */
    public static Transformation box(Quaternionf rot, float cx, float cy, float cz, float sx, float sy, float sz, Vector3f scratch) {
        scratch.set(cx - sx * 0.5f, cy - sy * 0.5f, cz - sz * 0.5f);
        rot.transform(scratch);
        return new Transformation(scratch, rot, new Vector3f(sx, sy, sz), IDENTITY);
    }

    /** Box whose centre is given in world-relative (un-rotated) coordinates while the box itself is rotated. */
    public static Transformation boxAt(Quaternionf rot, float wx, float wy, float wz, float sx, float sy, float sz, Vector3f scratch) {
        scratch.set(-sx * 0.5f, -sy * 0.5f, -sz * 0.5f);
        rot.transform(scratch);
        scratch.add(wx, wy, wz);
        return new Transformation(scratch, rot, new Vector3f(sx, sy, sz), IDENTITY);
    }

    /** Item displays render centred on their origin, so only an offset, rotation and scale are needed. */
    public static Transformation item(float ox, float oy, float oz, Quaternionf rot, float scale) {
        return new Transformation(new Vector3f(ox, oy, oz), rot, new Vector3f(scale, scale, scale), IDENTITY);
    }

    /** Zero-size transformation used for pop-in and fade-out animations. */
    public static Transformation collapsed(float cx, float cy, float cz) {
        return new Transformation(new Vector3f(cx, cy, cz), IDENTITY, new Vector3f(0.001f, 0.001f, 0.001f), IDENTITY);
    }

    /** Yaw angle (radians) whose rotation maps local +Z onto the horizontal direction (dx, dz). */
    public static float yawTo(double dx, double dz) {
        return (float) Math.atan2(dx, dz);
    }
}
