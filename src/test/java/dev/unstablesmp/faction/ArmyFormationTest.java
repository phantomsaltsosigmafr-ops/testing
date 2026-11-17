package dev.unstablesmp.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArmyFormationTest {

    @Test
    void offsetsAreFiniteAndDistinct() {
        double[] o = new double[2];
        for (ArmyFormation f : ArmyFormation.values()) {
            for (int n : new int[] {1, 5, 10, 41}) {
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < n; i++) {
                    f.offset(i, n, 2.0, o);
                    assertTrue(Double.isFinite(o[0]) && Double.isFinite(o[1]), f + " " + i);
                    assertTrue(o[1] >= -1e-9, f + ": slots are never in front of the anchor");
                    seen.add(String.format("%.3f/%.3f", o[0], o[1]));
                }
                assertEquals(n, seen.size(), f + " overlapping slots, n=" + n);
            }
        }
    }

    @Test
    void lineIsOneRowAndGridIsSquareish() {
        double[] o = new double[2];
        for (int i = 0; i < 6; i++) {
            ArmyFormation.LINE.offset(i, 6, 2, o);
            assertEquals(0, o[1], 1e-9);
        }
        ArmyFormation.GRID.offset(8, 9, 2, o);
        assertEquals(4, o[1], 1e-9, "9 units in a grid make 3 rows");
    }

    @Test
    void parseHandlesSpellings() {
        assertEquals(ArmyFormation.CHECKERBOARD, ArmyFormation.parse("checker", ArmyFormation.GRID));
        assertEquals(ArmyFormation.DOUBLE_LINE, ArmyFormation.parse("double line", ArmyFormation.GRID));
        assertEquals(ArmyFormation.GRID, ArmyFormation.parse("nope", ArmyFormation.GRID));
    }

    @Test
    void traitsAcceptReferenceSpellings() {
        EnumSet<Trait> t = Trait.parse(List.of("darkness_on_hit", "FIRE_ON_HIT", "crit", "slowness", "blindness"), "test", null);
        assertEquals(EnumSet.of(Trait.DARKNESS, Trait.FIRE, Trait.CRIT, Trait.SLOW, Trait.BLIND), t);
    }
}
