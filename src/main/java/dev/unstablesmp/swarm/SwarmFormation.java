package dev.unstablesmp.swarm;

import java.util.Locale;
import org.bukkit.Material;

/**
 * Vexbot formations. {@link #layout} writes one target slot per bot into reused arrays. Every shape faces the
 * given direction (toward the target), slowly rotates and "breathes" over time.
 * <p>
 * Owner-anchored shapes are laid out around the owner's feet; target-anchored shapes around the target's feet
 * (or around the owner when there is no target).
 */
public enum SwarmFormation {
    HALO("Halo", Material.GOLD_NUGGET, false, "swarm"),
    SKY_CROWN("Sky Crown", Material.GOLDEN_HELMET, false, "swarm"),
    V_WEDGE("V-Wedge", Material.ARROW, false, "swarm"),
    PHALANX_WALL("Phalanx Wall", Material.SHIELD, false, "swarm"),
    SPIRAL_VORTEX("Spiral Vortex", Material.WIND_CHARGE, true, "vortex"),
    DOME_CAGE("Dome Cage", Material.IRON_BARS, true, "cage"),
    ARROWHEAD_DIVE("Arrowhead Dive", Material.SPECTRAL_ARROW, true, "arrowhead_dive"),
    PINCER("Pincer", Material.SHEARS, true, "pincer"),
    RAIN("Rain", Material.POINTED_DRIPSTONE, true, "rain"),
    ORBIT_SHIELD("Orbit Shield", Material.ENDER_EYE, false, "swarm");

    private static final double GOLDEN = Math.PI * (3 - Math.sqrt(5));
    private static final double TAU = Math.PI * 2;

    public final String label;
    public final Material icon;
    public final boolean targetAnchored;
    /** Damage key for contact hits in this formation (combos and kill messages use it). */
    public final String damageKey;

    SwarmFormation(String label, Material icon, boolean targetAnchored, String damageKey) {
        this.label = label;
        this.icon = icon;
        this.targetAnchored = targetAnchored;
        this.damageKey = damageKey;
    }

    /** Formations that periodically send a few bots to harass the target. */
    public boolean harasses() {
        return this == HALO || this == SKY_CROWN || this == V_WEDGE || this == PHALANX_WALL;
    }

    public SwarmFormation next(int dir) {
        SwarmFormation[] v = values();
        return v[Math.floorMod(ordinal() + dir, v.length)];
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SwarmFormation parse(String s) {
        if (s == null) return null;
        String n = s.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (n.equals("ARROWHEAD") || n.equals("DIVE")) n = "ARROWHEAD_DIVE";
        if (n.equals("VORTEX") || n.equals("SPIRAL")) n = "SPIRAL_VORTEX";
        if (n.equals("DOME") || n.equals("CAGE")) n = "DOME_CAGE";
        if (n.equals("WALL") || n.equals("PHALANX")) n = "PHALANX_WALL";
        if (n.equals("WEDGE") || n.equals("V")) n = "V_WEDGE";
        if (n.equals("SHIELD") || n.equals("ORBIT")) n = "ORBIT_SHIELD";
        if (n.equals("CROWN")) n = "SKY_CROWN";
        try {
            return valueOf(n);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Lays out {@code n} slots.
     *
     * @param t      time in ticks (drives rotation and breathing)
     * @param ax     anchor x (feet of owner or target)
     * @param fx     facing direction x (unit, horizontal)
     * @param fz     facing direction z (unit, horizontal)
     * @param age    ticks since this formation was selected (drives assembling / closing motions)
     */
    public void layout(int n, double t, double ax, double ay, double az, double fx, double fz, int age,
                       FormationParams p, double[] xs, double[] ys, double[] zs) {
        if (n <= 0) return;
        double rx = -fz, rz = fx; // right-hand vector
        double rot = t * p.rotationSpeed;
        double breathe = 1 + p.breathe * Math.sin(t * 0.07);
        switch (this) {
            case HALO -> {
                double r = Math.max(p.haloRadius, n * 0.16) * breathe;
                double y = ay + p.haloHeight;
                for (int i = 0; i < n; i++) {
                    double a = TAU * i / n + rot * 0.035;
                    xs[i] = ax + Math.cos(a) * r;
                    zs[i] = az + Math.sin(a) * r;
                    ys[i] = y + 0.18 * Math.sin(a * 3 + t * 0.12);
                }
            }
            case SKY_CROWN -> {
                double r = Math.max(p.crownRadius, n * 0.3) * breathe;
                double y = ay + p.crownHeight;
                for (int i = 0; i < n; i++) {
                    double a = TAU * i / n - rot * 0.012;
                    double spike = (i % 3 == 0) ? 1.6 : 0;
                    xs[i] = ax + Math.cos(a) * r;
                    zs[i] = az + Math.sin(a) * r;
                    ys[i] = y + spike + 0.3 * Math.sin(t * 0.05 + i);
                }
            }
            case V_WEDGE -> {
                double s = p.wedgeSpacing * breathe;
                double apX = ax + fx * p.wedgeDistance, apZ = az + fz * p.wedgeDistance, apY = ay + 1.6;
                for (int i = 0; i < n; i++) {
                    int k = (i + 1) / 2;
                    int side = (i % 2 == 1) ? -1 : 1;
                    xs[i] = apX + rx * side * k * s - fx * k * s * 0.85;
                    zs[i] = apZ + rz * side * k * s - fz * k * s * 0.85;
                    ys[i] = apY + 0.12 * k + 0.1 * Math.sin(t * 0.1 + k);
                }
            }
            case PHALANX_WALL -> {
                int cols = (int) Math.ceil(Math.sqrt(n * 2.5));
                double s = p.wallSpacing;
                double cx = ax + fx * p.wallDistance, cz = az + fz * p.wallDistance;
                for (int i = 0; i < n; i++) {
                    int row = i / cols, col = i % cols;
                    int inRow = Math.min(cols, n - row * cols);
                    double u = (col - (inRow - 1) / 2.0) * s;
                    double wave = 0.08 * Math.sin(t * 0.15 + col * 0.7);
                    xs[i] = cx + rx * u + fx * wave;
                    zs[i] = cz + rz * u + fz * wave;
                    ys[i] = ay + 0.4 + row * s;
                }
            }
            case SPIRAL_VORTEX -> {
                for (int i = 0; i < n; i++) {
                    double frac = (i + 0.5) / n;
                    double a = i * GOLDEN + rot * (0.16 - frac * 0.06);
                    double rad = p.vortexRadius * (0.45 + frac * 0.75) * breathe;
                    xs[i] = ax + Math.cos(a) * rad;
                    zs[i] = az + Math.sin(a) * rad;
                    ys[i] = ay + 0.3 + frac * p.vortexHeight;
                }
            }
            case DOME_CAGE -> {
                double r = p.domeRadius;
                for (int i = 0; i < n; i++) {
                    double yv = 0.04 + 0.94 * (i + 0.5) / n;
                    double rr = Math.sqrt(1 - yv * yv);
                    double a = i * GOLDEN + rot * 0.01;
                    xs[i] = ax + Math.cos(a) * rr * r;
                    zs[i] = az + Math.sin(a) * rr * r;
                    ys[i] = ay + yv * r;
                }
            }
            case ARROWHEAD_DIVE -> {
                double s = p.arrowSpacing;
                double tipX = ax - fx * p.arrowBack, tipZ = az - fz * p.arrowBack, tipY = ay + p.arrowHeight;
                for (int i = 0; i < n; i++) {
                    int k = (i + 1) / 2;
                    int side = (i % 2 == 1) ? -1 : 1;
                    double lateral = (i % 4 >= 2) ? 0.5 : 1.0; // inner and outer edges make a filled head
                    xs[i] = tipX + rx * side * k * s * 0.7 * lateral - fx * k * s;
                    zs[i] = tipZ + rz * side * k * s * 0.7 * lateral - fz * k * s;
                    ys[i] = tipY + 0.25 * k;
                }
            }
            case PINCER -> {
                int half = (n + 1) / 2;
                double close = Math.min(1.0, age / 40.0);
                double rad = p.pincerRadius * (1.5 - close * 0.9) * breathe;
                for (int i = 0; i < n; i++) {
                    int side = i < half ? -1 : 1;
                    int j = i < half ? i : i - half;
                    int m = i < half ? half : n - half;
                    double spread = m <= 1 ? 0 : (j / (double) (m - 1) - 0.5);
                    double phi = spread * 1.7;
                    double dx = rx * side * Math.cos(phi) + fx * Math.sin(phi);
                    double dz = rz * side * Math.cos(phi) + fz * Math.sin(phi);
                    xs[i] = ax + dx * rad;
                    zs[i] = az + dz * rad;
                    ys[i] = ay + 0.9 + 0.5 * (j % 3);
                }
            }
            case RAIN -> {
                int cols = (int) Math.ceil(Math.sqrt(n));
                int rows = (n + cols - 1) / cols;
                double s = p.rainSpacing;
                double a = rot * 0.01;
                double ca = Math.cos(a), sa = Math.sin(a);
                for (int i = 0; i < n; i++) {
                    int row = i / cols, col = i % cols;
                    double u = (col - (cols - 1) / 2.0) * s;
                    double w = (row - (rows - 1) / 2.0) * s;
                    xs[i] = ax + u * ca - w * sa;
                    zs[i] = az + u * sa + w * ca;
                    ys[i] = ay + p.rainHeight + 0.2 * Math.sin(t * 0.1 + i);
                }
            }
            case ORBIT_SHIELD -> {
                double r = p.shieldRadius * breathe;
                int perRing = (n + 1) / 2;
                for (int i = 0; i < n; i++) {
                    int ring = i % 2;
                    double a = TAU * (i / 2) / perRing + (ring == 0 ? rot * 0.14 : -rot * 0.14);
                    xs[i] = ax + Math.cos(a) * r;
                    zs[i] = az + Math.sin(a) * r;
                    ys[i] = ay + 1.1 + Math.sin(a) * r * 0.45 * (ring == 0 ? 1 : -1);
                }
            }
        }
    }
}
