package dev.unstablesmp.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PortalShapeTest {

    @Test
    void countsAndNoOverlap() {
        for (int w = 1; w <= 6; w++) {
            for (int h = 2; h <= 7; h++) {
                PortalShape s = new PortalShape(w, h);
                assertEquals(2 * (w + 2) + 2 * h, s.frame.size(), "frame " + w + "x" + h);
                assertEquals(w * h, s.surfaceCount());
                Set<String> seen = new HashSet<>();
                for (PortalShape.Cell c : s.all()) assertTrue(seen.add(c.across() + "," + c.up()), "duplicate cell " + c);
                assertEquals(s.frame.size() + w * h, seen.size());
            }
        }
    }

    @Test
    void openingIsCentredAndFramed() {
        PortalShape odd = new PortalShape(3, 5);
        assertEquals(-1, odd.first);
        assertEquals(1, odd.last);
        assertEquals(0.0, odd.centreAcross());
        assertEquals(0, odd.centreColumn());
        PortalShape even = new PortalShape(4, 5);
        assertEquals(-2, even.first);
        assertEquals(1, even.last);
        assertEquals(-0.5, even.centreAcross());
        for (PortalShape.Cell c : odd.frame) {
            boolean edge = c.across() < odd.first || c.across() > odd.last || c.up() == 0 || c.up() == odd.height + 1;
            assertTrue(edge, "frame cell inside the opening: " + c);
        }
    }

    @Test
    void frameBuildsBottomFirstAndSurfaceRowsRise() {
        PortalShape s = new PortalShape(3, 5);
        for (int i = 0; i < s.width + 2; i++) assertEquals(0, s.frame.get(i).up());
        assertEquals(s.height + 1, s.frame.get(s.frame.size() - 1).up());
        for (int r = 0; r < s.surfaceRows.size(); r++) {
            for (PortalShape.Cell c : s.surfaceRows.get(r)) assertEquals(r + 1, c.up());
        }
    }

    @Test
    void siteAxes() {
        Site east = new Site(10, 64, 20, 1, 0);
        assertEquals(10, east.worldX(2, 0)); // across runs along Z when facing east
        assertEquals(22, east.worldZ(2, 0));
        assertEquals(11, east.worldX(0, 1));
        Site north = new Site(0, 64, 0, 0, -1);
        assertEquals(3, north.worldX(3, 0));
        assertEquals(-2, north.worldZ(0, 2));
        assertEquals("north", north.facingName());
        int[] f = Site.facingFromYaw(0f);
        assertEquals(0, f[0]);
        assertEquals(1, f[1]); // yaw 0 looks south
        int[] w = Site.facingFromYaw(90f);
        assertEquals(-1, w[0]); // yaw 90 looks west
        int[] t = Site.facingToward(0, 0, -5, 2);
        assertEquals(-1, t[0]);
        assertEquals(0, t[1]);
    }
}
