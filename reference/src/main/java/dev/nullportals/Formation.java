package dev.nullportals;

/** Formation shapes. Offsets are (right, back) in blocks from the formation's front-centre. */
enum Formation {
    GRID("Grid", "IRON_BARS"),
    LINE("Line", "OAK_FENCE"),
    DOUBLE_LINE("Double Line", "NETHER_BRICK_FENCE"),
    CHECKER("Checkerboard", "WHITE_CARPET"),
    COLUMN("Column", "END_ROD"),
    WEDGE("Wedge", "ARROW"),
    CIRCLE("Circle", "SNOWBALL");

    final String label;
    final String icon;

    Formation(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    /** @return {right, back} */
    double[] offset(int i, int n, double s) {
        if (n <= 0) n = 1;
        switch (this) {
            case LINE:
                return new double[] {(i - (n - 1) / 2.0) * s, 0};
            case DOUBLE_LINE: {
                int cols = (int) Math.ceil(n / 2.0);
                int row = i / cols, col = i % cols;
                int inRow = row == 0 ? cols : n - cols;
                return new double[] {(col - (inRow - 1) / 2.0) * s, row * s};
            }
            case COLUMN:
                return new double[] {((i % 2) - 0.5) * s, (i / 2) * s};
            case WEDGE: {
                if (i == 0) return new double[] {0, 0};
                int k = (i + 1) / 2;
                int side = (i % 2 == 1) ? -1 : 1;
                return new double[] {side * k * s * 0.8, k * s * 0.8};
            }
            case CIRCLE: {
                double r = Math.max(2.5, n * s / (2 * Math.PI));
                double a = 2 * Math.PI * i / n;
                return new double[] {Math.cos(a) * r, r + Math.sin(a) * r};
            }
            case CHECKER: {
                int cols = (int) Math.ceil(Math.sqrt(n));
                int row = i / cols, col = i % cols;
                double shift = (row % 2 == 1) ? s / 2 : 0;
                return new double[] {(col - (cols - 1) / 2.0) * s + shift, row * s * 0.8};
            }
            case GRID:
            default: {
                int cols = (int) Math.ceil(Math.sqrt(n));
                int row = i / cols, col = i % cols;
                int inRow = Math.min(cols, n - row * cols);
                return new double[] {(col - (inRow - 1) / 2.0) * s, row * s};
            }
        }
    }

    Formation next() {
        Formation[] v = values();
        return v[(ordinal() + 1) % v.length];
    }
}
