package dev.unstablesmp.faction;

import java.util.Locale;
import org.bukkit.Material;

/** Ground formations for faction armies. Offsets are (right, back) in blocks from the formation's front centre. */
public enum ArmyFormation {
    GRID("Grid", Material.IRON_BARS),
    LINE("Line", Material.OAK_FENCE),
    DOUBLE_LINE("Double Line", Material.NETHER_BRICK_FENCE),
    CHECKERBOARD("Checkerboard", Material.WHITE_CARPET),
    COLUMN("Column", Material.END_ROD),
    WEDGE("Wedge", Material.ARROW),
    CIRCLE("Circle", Material.SNOWBALL);

    public final String label;
    public final Material icon;

    ArmyFormation(String label, Material icon) {
        this.label = label;
        this.icon = icon;
    }

    /** Writes {right, back} for slot i of n with spacing s into out[0], out[1]. */
    public void offset(int i, int n, double s, double[] out) {
        if (n <= 0) n = 1;
        switch (this) {
            case LINE -> {
                out[0] = (i - (n - 1) / 2.0) * s;
                out[1] = 0;
            }
            case DOUBLE_LINE -> {
                int cols = (int) Math.ceil(n / 2.0);
                int row = i / cols, col = i % cols;
                int inRow = row == 0 ? cols : n - cols;
                out[0] = (col - (inRow - 1) / 2.0) * s;
                out[1] = row * s;
            }
            case COLUMN -> {
                out[0] = ((i % 2) - 0.5) * s;
                out[1] = (i / 2) * s;
            }
            case WEDGE -> {
                if (i == 0) {
                    out[0] = 0;
                    out[1] = 0;
                } else {
                    int k = (i + 1) / 2;
                    int side = (i % 2 == 1) ? -1 : 1;
                    out[0] = side * k * s * 0.8;
                    out[1] = k * s * 0.8;
                }
            }
            case CIRCLE -> {
                double r = Math.max(2.5, n * s / (2 * Math.PI));
                double a = 2 * Math.PI * i / n;
                out[0] = Math.cos(a) * r;
                out[1] = r + Math.sin(a) * r;
            }
            case CHECKERBOARD -> {
                int cols = (int) Math.ceil(Math.sqrt(n));
                int row = i / cols, col = i % cols;
                double shift = (row % 2 == 1) ? s / 2 : 0;
                out[0] = (col - (cols - 1) / 2.0) * s + shift;
                out[1] = row * s * 0.8;
            }
            default -> { // GRID
                int cols = (int) Math.ceil(Math.sqrt(n));
                int row = i / cols, col = i % cols;
                int inRow = Math.min(cols, n - row * cols);
                out[0] = (col - (inRow - 1) / 2.0) * s;
                out[1] = row * s;
            }
        }
    }

    public ArmyFormation next() {
        ArmyFormation[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public static ArmyFormation parse(String s, ArmyFormation def) {
        if (s == null) return def;
        String n = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (n.equals("CHECKER")) n = "CHECKERBOARD";
        try {
            return valueOf(n);
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }
}
