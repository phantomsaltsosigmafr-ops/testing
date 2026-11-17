package dev.unstablesmp.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Random;
import org.junit.jupiter.api.Test;

class SlotAssignerTest {

    @Test
    void assignmentIsAPermutation() {
        Random r = new Random(7);
        for (int n = 1; n <= 64; n += 7) {
            double[] bx = new double[n], by = new double[n], bz = new double[n];
            double[] sx = new double[n], sy = new double[n], sz = new double[n];
            for (int i = 0; i < n; i++) {
                bx[i] = r.nextDouble() * 20;
                by[i] = r.nextDouble() * 20;
                bz[i] = r.nextDouble() * 20;
                sx[i] = r.nextDouble() * 20;
                sy[i] = r.nextDouble() * 20;
                sz[i] = r.nextDouble() * 20;
            }
            int[] out = new int[n];
            boolean[] taken = new boolean[n];
            SlotAssigner.assign(n, bx, by, bz, sx, sy, sz, out, taken);
            boolean[] used = new boolean[n];
            for (int i = 0; i < n; i++) {
                assertFalse(used[out[i]], "slot assigned twice");
                used[out[i]] = true;
            }
        }
    }

    @Test
    void botsAlreadyOnSlotsKeepThem() {
        int n = 10;
        double[] x = new double[n], y = new double[n], z = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i * 3;
            y[i] = 64;
            z[i] = -i * 2;
        }
        int[] out = new int[n];
        SlotAssigner.assign(n, x, y, z, x, y, z, out, new boolean[n]);
        for (int i = 0; i < n; i++) assertEquals(i, out[i]);
    }
}
