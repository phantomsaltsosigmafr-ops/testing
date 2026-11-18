package dev.unstablesmp.portal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The block layout of one portal in portal space: {@code across} runs along the portal's width (0 = centre column),
 * {@code up} runs from the bottom frame row (0) to the top frame row (height + 1). Pure maths, so it's unit tested.
 */
public final class PortalShape {

    /** One block of the portal. */
    public record Cell(int across, int up, Kind kind) {
    }

    public enum Kind { FRAME, CORNER, SURFACE }

    public final int width;
    public final int height;
    /** Lowest and highest across offsets of the surface (the opening). */
    public final int first;
    public final int last;
    /** Frame blocks in build order: bottom row, then both sides rising together, then the top row from the middle out. */
    public final List<Cell> frame;
    /** Surface blocks grouped by row, bottom row first. */
    public final List<List<Cell>> surfaceRows;

    public PortalShape(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("portal must be at least 1x1");
        this.width = width;
        this.height = height;
        this.first = -(width / 2);
        this.last = first + width - 1;
        int left = first - 1, right = last + 1, top = height + 1;

        List<Cell> f = new ArrayList<>();
        for (int a = left; a <= right; a++) f.add(new Cell(a, 0, a == left || a == right ? Kind.CORNER : Kind.FRAME));
        for (int u = 1; u <= height; u++) {
            f.add(new Cell(left, u, Kind.FRAME));
            f.add(new Cell(right, u, Kind.FRAME));
        }
        f.add(new Cell(left, top, Kind.CORNER));
        f.add(new Cell(right, top, Kind.CORNER));
        List<Cell> topRow = new ArrayList<>();
        for (int a = first; a <= last; a++) topRow.add(new Cell(a, top, Kind.FRAME));
        topRow.sort((x, y) -> Integer.compare(Math.abs(x.across()), Math.abs(y.across())));
        f.addAll(topRow);
        this.frame = Collections.unmodifiableList(f);

        List<List<Cell>> rows = new ArrayList<>();
        for (int u = 1; u <= height; u++) {
            List<Cell> row = new ArrayList<>();
            for (int a = first; a <= last; a++) row.add(new Cell(a, u, Kind.SURFACE));
            rows.add(Collections.unmodifiableList(row));
        }
        this.surfaceRows = Collections.unmodifiableList(rows);
    }

    public int surfaceCount() {
        return width * height;
    }

    /** Every cell (frame + surface). */
    public List<Cell> all() {
        List<Cell> out = new ArrayList<>(frame);
        for (List<Cell> r : surfaceRows) out.addAll(r);
        return out;
    }

    /** Across offset of the visual centre line (half a block left of column 0 for even widths). */
    public double centreAcross() {
        return (first + last) / 2.0;
    }

    /** The surface column nearest the centre (where a single-column beam rises). */
    public int centreColumn() {
        return (int) Math.round(centreAcross() + 0.01);
    }
}
