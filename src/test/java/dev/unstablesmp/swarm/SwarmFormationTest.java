package dev.unstablesmp.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SwarmFormationTest {
    private static final int[] SIZES = {1, 2, 7, 24, 60};
    private final FormationParams p = new FormationParams();

    @Test
    void everyFormationProducesFiniteDistinctSlots() {
        for (SwarmFormation f : SwarmFormation.values()) {
            for (int n : SIZES) {
                double[] xs = new double[n], ys = new double[n], zs = new double[n];
                f.layout(n, 1234, 10, 64, -5, 0, 1, 50, p, xs, ys, zs);
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < n; i++) {
                    assertTrue(Double.isFinite(xs[i]) && Double.isFinite(ys[i]) && Double.isFinite(zs[i]), f + " n=" + n + " slot " + i);
                    seen.add(String.format("%.3f/%.3f/%.3f", xs[i], ys[i], zs[i]));
                }
                assertEquals(n, seen.size(), f + " has overlapping slots for n=" + n);
            }
        }
    }

    @Test
    void haloFloatsAboveTheAnchor() {
        int n = 24;
        double[] xs = new double[n], ys = new double[n], zs = new double[n];
        SwarmFormation.HALO.layout(n, 0, 0, 100, 0, 0, 1, 0, p, xs, ys, zs);
        for (int i = 0; i < n; i++) {
            assertTrue(Math.abs(ys[i] - (100 + p.haloHeight)) <= 0.25, "halo height");
        }
    }

    @Test
    void domeCageStaysInsideItsRadiusAndAboveGround() {
        int n = 40;
        double[] xs = new double[n], ys = new double[n], zs = new double[n];
        SwarmFormation.DOME_CAGE.layout(n, 99, 0, 70, 0, 1, 0, 0, p, xs, ys, zs);
        for (int i = 0; i < n; i++) {
            double r = Math.sqrt(xs[i] * xs[i] + (ys[i] - 70) * (ys[i] - 70) + zs[i] * zs[i]);
            assertEquals(p.domeRadius, r, 1e-6, "slot on the dome surface");
            assertTrue(ys[i] >= 70, "dome slots are above the cage floor");
        }
    }

    @Test
    void vWedgeApexPointsAlongTheFacing() {
        int n = 9;
        double[] xs = new double[n], ys = new double[n], zs = new double[n];
        // facing +X
        SwarmFormation.V_WEDGE.layout(n, 0, 0, 0, 0, 1, 0, 0, p, xs, ys, zs);
        for (int i = 1; i < n; i++) assertTrue(xs[0] > xs[i], "apex is the most forward slot");
    }

    @Test
    void pincerClosesInOverTime() {
        int n = 12;
        double[] xs = new double[n], ys = new double[n], zs = new double[n];
        SwarmFormation.PINCER.layout(n, 0, 0, 0, 0, 0, 1, 0, p, xs, ys, zs);
        double early = Math.hypot(xs[0], zs[0]);
        SwarmFormation.PINCER.layout(n, 0, 0, 0, 0, 0, 1, 40, p, xs, ys, zs);
        double late = Math.hypot(xs[0], zs[0]);
        assertTrue(late < early, "pincer arms close on the target");
    }

    @Test
    void parseAcceptsAliases() {
        assertEquals(SwarmFormation.ARROWHEAD_DIVE, SwarmFormation.parse("arrowhead"));
        assertEquals(SwarmFormation.DOME_CAGE, SwarmFormation.parse("dome-cage"));
        assertEquals(SwarmFormation.ORBIT_SHIELD, SwarmFormation.parse("shield"));
        assertEquals(SwarmFormation.SPIRAL_VORTEX, SwarmFormation.parse("Spiral Vortex"));
        assertNotNull(SwarmFormation.parse("sky_crown"));
        assertNull(SwarmFormation.parse("banana"));
        for (SwarmFormation f : SwarmFormation.values()) assertEquals(f, SwarmFormation.parse(f.id()));
    }

    @Test
    void cyclingWrapsBothWays() {
        assertEquals(SwarmFormation.ORBIT_SHIELD, SwarmFormation.HALO.next(-1));
        assertEquals(SwarmFormation.HALO, SwarmFormation.ORBIT_SHIELD.next(1));
    }
}
