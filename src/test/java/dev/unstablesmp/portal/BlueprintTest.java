package dev.unstablesmp.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.unstablesmp.life.Blueprint;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlueprintTest {

    @Test
    void everyBuiltPositionIsPlacedOnce() {
        for (int beds = 1; beds <= 4; beds++) {
            List<Blueprint.Piece> plan = Blueprint.plan("oak", "wheat", "red", beds);
            Set<String> seen = new HashSet<>();
            int bedPieces = 0, chests = 0, signs = 0;
            for (Blueprint.Piece p : plan) {
                if (p.kind() == Blueprint.Kind.CLEAR || p.kind() == Blueprint.Kind.FILL || p.kind() == Blueprint.Kind.GROUND) continue;
                assertTrue(seen.add(p.x() + "," + p.y() + "," + p.z()), "placed twice: " + p);
                assertTrue(p.x() >= Blueprint.MIN_X && p.x() <= Blueprint.MAX_X && p.z() >= Blueprint.MIN_Z && p.z() <= Blueprint.MAX_Z, "outside the plot: " + p);
                if (p.kind() == Blueprint.Kind.BED) bedPieces++;
                if (p.kind() == Blueprint.Kind.CHEST) chests++;
                if (p.kind() == Blueprint.Kind.SIGN) signs++;
            }
            assertEquals(beds * 2, bedPieces, "a two-block bed each");
            assertEquals(2, chests, "double chest");
            assertEquals(1, signs);
        }
    }

    @Test
    void buildOrderGoesClearLevelFoundationWallsRoof() {
        List<Blueprint.Piece> plan = Blueprint.plan("spruce", "carrots", "blue", 2);
        int lastClear = -1, firstBlock = Integer.MAX_VALUE;
        for (int i = 0; i < plan.size(); i++) {
            Blueprint.Kind k = plan.get(i).kind();
            if (k == Blueprint.Kind.CLEAR) lastClear = i;
            if (k == Blueprint.Kind.BLOCK) firstBlock = Math.min(firstBlock, i);
        }
        assertTrue(lastClear < firstBlock, "the plot is cleared before anything is built");
        assertTrue(plan.stream().anyMatch(p -> p.data().contains("spruce_stairs")), "roof");
        assertTrue(plan.stream().anyMatch(p -> p.data().equals("minecraft:carrots")), "crops");
    }

    @Test
    void rotationTurnsFacingAndAxis() {
        assertEquals("minecraft:oak_stairs[facing=west,half=bottom]", Blueprint.rotateData("minecraft:oak_stairs[facing=south,half=bottom]", 1));
        assertEquals("minecraft:oak_log[axis=z]", Blueprint.rotateData("minecraft:oak_log[axis=x]", 3));
        assertEquals("minecraft:oak_log[axis=x]", Blueprint.rotateData("minecraft:oak_log[axis=x]", 2));
        int[] r = Blueprint.rotate(2, 5, 1);
        assertEquals(-5, r[0]);
        assertEquals(2, r[1]);
        // local north (0,-1) turned once clockwise points east (+x)
        int[] n = Blueprint.rotate(0, -1, 1);
        assertEquals(1, n[0]);
        assertEquals(0, n[1]);
    }

    private static void noDuplicates(List<Blueprint.Piece> plan, String what) {
        Set<String> seen = new HashSet<>();
        for (Blueprint.Piece p : plan) {
            if (p.kind() == Blueprint.Kind.CLEAR || p.kind() == Blueprint.Kind.FILL || p.kind() == Blueprint.Kind.GROUND) continue;
            assertTrue(seen.add(p.x() + "," + p.y() + "," + p.z()), what + " placed twice: " + p);
        }
        assertTrue(seen.size() > 5, what + " has blocks");
    }

    @Test
    void extensionsStayInTheirBoxesAndOffTheHouse() {
        for (int stage = 1; stage <= Blueprint.STAGES; stage++) {
            int[] box = Blueprint.extensionBox(stage);
            List<Blueprint.Piece> plan = Blueprint.extension(stage, "birch");
            noDuplicates(plan, "stage " + stage);
            for (Blueprint.Piece p : plan) {
                assertTrue(p.x() >= box[0] && p.x() <= box[1] && p.z() >= box[2] && p.z() <= box[3], "stage " + stage + " outside its box: " + p);
                boolean inHouse = p.x() >= 0 && p.x() <= 6 && p.z() >= 0 && p.z() <= 6;
                assertTrue(!inHouse, "stage " + stage + " on the house: " + p);
            }
        }
        assertTrue(Blueprint.extension(4, "oak").stream().anyMatch(p -> p.data().contains("nether_portal")), "stage 4 is a portal");
    }

    @Test
    void stallAndCampBuild() {
        noDuplicates(Blueprint.stall("oak", "red"), "stall");
        List<Blueprint.Piece> camp = Blueprint.camp();
        noDuplicates(camp, "camp");
        assertEquals(1, camp.stream().filter(p -> p.kind() == Blueprint.Kind.CHEST).count());
        assertEquals(1, camp.stream().filter(p -> p.kind() == Blueprint.Kind.SIGN).count());
    }
}
