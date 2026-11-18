package dev.unstablesmp.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FloatingPortalTest {

    @Test
    void classicPortalIsFourByFive() {
        List<FloatingPortal.Cell> cells = FloatingPortal.cells(2, 3);
        assertEquals(20, cells.size());
        assertEquals(14, cells.stream().filter(FloatingPortal.Cell::frame).count());
        assertEquals(6, cells.stream().filter(c -> !c.frame()).count());
    }

    @Test
    void anySizeFramesTheOpening() {
        for (int w = 2; w <= 8; w++) {
            for (int h = 3; h <= 8; h++) {
                List<FloatingPortal.Cell> cells = FloatingPortal.cells(w, h);
                assertEquals((w + 2) * (h + 2), cells.size());
                assertEquals(w * h, cells.stream().filter(c -> !c.frame()).count());
                Set<String> seen = new HashSet<>();
                for (FloatingPortal.Cell c : cells) {
                    assertTrue(seen.add(c.across() + "," + c.up()), "duplicate " + c);
                    boolean edge = c.across() == 0 || c.across() == w + 1 || c.up() == 0 || c.up() == h + 1;
                    assertEquals(edge, c.frame(), "frame flag wrong at " + c);
                }
            }
        }
    }
}
