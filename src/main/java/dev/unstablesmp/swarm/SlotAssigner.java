package dev.unstablesmp.swarm;

/**
 * Greedy nearest-free-slot assignment. Runs when a formation changes or the bot count changes, so each bot
 * flies the shortest reasonable distance to its new place. O(n^2) on primitives, no allocation when the
 * caller reuses the {@code taken} array.
 */
public final class SlotAssigner {
    private SlotAssigner() {
    }

    /**
     * @param n          bots and slots
     * @param bx         bot positions
     * @param sx         slot positions
     * @param slotOfBot  output: slot index per bot
     * @param taken      scratch, length >= n
     */
    public static void assign(int n, double[] bx, double[] by, double[] bz,
                              double[] sx, double[] sy, double[] sz, int[] slotOfBot, boolean[] taken) {
        for (int i = 0; i < n; i++) taken[i] = false;
        for (int b = 0; b < n; b++) {
            int best = -1;
            double bestSq = Double.MAX_VALUE;
            for (int s = 0; s < n; s++) {
                if (taken[s]) continue;
                double dx = sx[s] - bx[b], dy = sy[s] - by[b], dz = sz[s] - bz[b];
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestSq) {
                    bestSq = d;
                    best = s;
                }
            }
            taken[best] = true;
            slotOfBot[b] = best;
        }
    }
}
