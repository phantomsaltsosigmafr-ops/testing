package dev.unstablesmp.life;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A starter base, in build order: clear the plot, level it, foundation, log-and-plank walls with windows and a door,
 * a gable roof, beds (one per crew member), a double chest, furnace, crafting table, torches, a sign, then a fenced
 * farm with water and crops. Local coordinates: the house is x 0..6, z 0..6 with its front (door) wall at z = 0
 * facing local north; the farm is x 8..14. {@link #rotate} turns it to face any way. Pure data, so it's unit tested.
 */
public final class Blueprint {

    public enum Kind { CLEAR, FILL, GROUND, BLOCK, CONNECT, DOOR, BED, CHEST, SIGN, CROP, ANIMAL }

    /** One step: a block state at a local position. */
    public record Piece(int x, int y, int z, String data, Kind kind) {
    }

    public static final int MIN_X = -1, MAX_X = 15, MIN_Z = -1, MAX_Z = 7, TOP = 9;

    private Blueprint() {
    }

    /** The interior centre of the house (where residents hang around), local. */
    public static int[] homeSpot() {
        return new int[] {3, 1, 3};
    }

    public static int[] farmSpot() {
        return new int[] {11, 1, 3};
    }

    /** Local head-of-bed position of bed {@code i} (0..3). */
    public static int[] bedHead(int i) {
        return switch (i) {
            case 1 -> new int[] {2, 1, 5};
            case 2 -> new int[] {3, 1, 5};
            case 3 -> new int[] {1, 1, 3};
            default -> new int[] {1, 1, 5};
        };
    }

    public static int[] chestSpot() {
        return new int[] {5, 1, 4};
    }

    /** Whether a local column belongs to the farm's crop beds. */
    public static boolean isCropBed(int x, int z) {
        return x >= 9 && x <= 13 && x != 11 && z >= 1 && z <= 5;
    }

    public static List<Piece> plan(String wood, String crop, String bedColour, int beds) {
        String w = "minecraft:" + wood;
        List<Piece> p = new ArrayList<>();
        // 1. clear the plot top-down (trees, grass, flowers)
        for (int y = TOP; y >= 1; y--) {
            for (int x = MIN_X; x <= MAX_X; x++) for (int z = MIN_Z; z <= MAX_Z; z++) p.add(new Piece(x, y, z, "minecraft:air", Kind.CLEAR));
        }
        // 2. fill holes under it and lay grass
        for (int y = -3; y <= -1; y++) {
            for (int x = MIN_X; x <= MAX_X; x++) for (int z = MIN_Z; z <= MAX_Z; z++) p.add(new Piece(x, y, z, "minecraft:dirt", Kind.FILL));
        }
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                boolean house = x >= 0 && x <= 6 && z >= 0 && z <= 6;
                boolean farm = x >= 9 && x <= 13 && z >= 1 && z <= 5;
                if (!house && !farm) p.add(new Piece(x, 0, z, "minecraft:grass_block", Kind.GROUND));
            }
        }
        // 3. foundation
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean edge = x == 0 || x == 6 || z == 0 || z == 6;
                p.add(new Piece(x, 0, z, edge ? "minecraft:cobblestone" : w + "_planks", Kind.BLOCK));
            }
        }
        // 4. walls, bottom up: log corners, plank walls, glass windows, a gap for the door
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    if (!(x == 0 || x == 6 || z == 0 || z == 6)) continue;
                    if (x == 3 && z == 0 && y <= 2) continue;
                    boolean corner = (x == 0 || x == 6) && (z == 0 || z == 6);
                    boolean window = y == 2 && ((z == 6 && (x == 2 || x == 4)) || ((x == 0 || x == 6) && (z == 2 || z == 4)) || (z == 0 && x == 5));
                    String data = corner ? w + "_log[axis=y]" : window ? "minecraft:glass_pane" : w + "_planks";
                    p.add(new Piece(x, y, z, data, window ? Kind.CONNECT : Kind.BLOCK));
                }
            }
        }
        // 5. front and back walls up to the roof, then the gable ends
        for (int x = 0; x <= 6; x++) {
            boolean corner = x == 0 || x == 6;
            p.add(new Piece(x, 4, 0, corner ? w + "_log[axis=y]" : w + "_planks", Kind.BLOCK));
            p.add(new Piece(x, 4, 6, corner ? w + "_log[axis=y]" : w + "_planks", Kind.BLOCK));
        }
        int[][] gable = {{4, 1, 5}, {5, 1, 5}, {6, 2, 4}}; // the ridge row closes the top
        for (int[] g : gable) {
            for (int z = g[1]; z <= g[2]; z++) {
                p.add(new Piece(0, g[0], z, w + "_planks", Kind.BLOCK));
                p.add(new Piece(6, g[0], z, w + "_planks", Kind.BLOCK));
            }
        }
        // 6. gable roof: stairs rows from the eaves up, then the ridge
        int[][] rows = {{4, -1, 0}, {4, 7, 1}, {5, 0, 0}, {5, 6, 1}, {6, 1, 0}, {6, 5, 1}, {7, 2, 0}, {7, 4, 1}};
        for (int[] r : rows) {
            String facing = r[2] == 0 ? "south" : "north";
            for (int x = -1; x <= 7; x++) p.add(new Piece(x, r[0], r[1], w + "_stairs[facing=" + facing + ",half=bottom,shape=straight]", Kind.BLOCK));
        }
        for (int x = -1; x <= 7; x++) p.add(new Piece(x, 7, 3, w + "_planks", Kind.BLOCK));
        for (int x = -1; x <= 7; x++) p.add(new Piece(x, 8, 3, w + "_slab[type=bottom]", Kind.BLOCK));
        // 7. door
        p.add(new Piece(3, 1, 0, w + "_door[facing=south,half=lower,hinge=left,open=false]", Kind.DOOR));
        p.add(new Piece(3, 2, 0, w + "_door[facing=south,half=upper,hinge=left,open=false]", Kind.DOOR));
        // 8. inside: beds (one each), double chest, furnace, crafting table, torches
        for (int i = 0; i < Math.max(1, Math.min(4, beds)); i++) {
            int[] head = bedHead(i);
            int footZ = head[2] - 1;
            p.add(new Piece(head[0], 1, footZ, "minecraft:" + bedColour + "_bed[facing=south,part=foot]", Kind.BED));
            p.add(new Piece(head[0], 1, head[2], "minecraft:" + bedColour + "_bed[facing=south,part=head]", Kind.BED));
        }
        if (beds < 3) p.add(new Piece(4, 1, 5, "minecraft:furnace[facing=north]", Kind.BLOCK));
        p.add(new Piece(5, 1, 4, "minecraft:chest[facing=west,type=right]", Kind.CHEST));
        p.add(new Piece(5, 1, 5, "minecraft:chest[facing=west,type=left]", Kind.CHEST));
        p.add(new Piece(5, 1, 1, "minecraft:crafting_table", Kind.BLOCK));
        p.add(new Piece(1, 2, 1, "minecraft:wall_torch[facing=east]", Kind.BLOCK));
        p.add(new Piece(5, 2, 3, "minecraft:wall_torch[facing=west]", Kind.BLOCK));
        // 9. outside: a sign by the door and a torch
        p.add(new Piece(2, 2, -1, w + "_wall_sign[facing=north]", Kind.SIGN));
        p.add(new Piece(4, 2, -1, "minecraft:wall_torch[facing=north]", Kind.BLOCK));
        // 10. the farm: fence and gate, farmland, a water channel, crops, torches on the corners
        for (int x = 8; x <= 14; x++) {
            for (int z = 0; z <= 6; z++) {
                if (!(x == 8 || x == 14 || z == 0 || z == 6)) continue;
                if (x == 11 && z == 0) {
                    p.add(new Piece(x, 1, z, w + "_fence_gate[facing=north,open=false,in_wall=false]", Kind.BLOCK));
                } else {
                    p.add(new Piece(x, 1, z, w + "_fence", Kind.CONNECT));
                }
            }
        }
        for (int x = 9; x <= 13; x++) {
            for (int z = 1; z <= 5; z++) p.add(new Piece(x, 0, z, x == 11 ? "minecraft:water" : "minecraft:farmland[moisture=7]", Kind.BLOCK));
        }
        for (int x = 9; x <= 13; x++) {
            for (int z = 1; z <= 5; z++) if (isCropBed(x, z)) p.add(new Piece(x, 1, z, "minecraft:" + crop, Kind.CROP));
        }
        for (int[] t : new int[][] {{8, 0}, {14, 0}, {8, 6}, {14, 6}}) p.add(new Piece(t[0], 2, t[1], "minecraft:torch", Kind.BLOCK));
        return p;
    }

    // ------------------------------------------------------------------ bases that grow

    /** How many extensions a base grows after the house: pen, enchanting hut, workshop, nether portal. */
    public static final int STAGES = 4;

    /** Clear box of extension {@code stage} (local): minX, maxX, minZ, maxZ. */
    public static int[] extensionBox(int stage) {
        return switch (stage) {
            case 1 -> new int[] {-1, 7, 8, 15};
            case 2 -> new int[] {8, 15, 8, 16};
            case 3 -> new int[] {-10, -2, -1, 7};
            default -> new int[] {-10, -2, 8, 15};
        };
    }

    /** Where to stand to use the nether portal (stage 4), local. */
    public static int[] portalFront() {
        return new int[] {-6, 1, 10};
    }

    /** Extension {@code stage} of a base: 1 animal pen, 2 enchanting hut, 3 workshop, 4 nether portal. */
    public static List<Piece> extension(int stage, String wood) {
        String w = "minecraft:" + wood;
        List<Piece> p = new ArrayList<>();
        int[] box = extensionBox(stage);
        for (int y = TOP; y >= 1; y--) {
            for (int x = box[0]; x <= box[1]; x++) for (int z = box[2]; z <= box[3]; z++) p.add(new Piece(x, y, z, "minecraft:air", Kind.CLEAR));
        }
        for (int y = -5; y <= -1; y++) {
            for (int x = box[0]; x <= box[1]; x++) for (int z = box[2]; z <= box[3]; z++) p.add(new Piece(x, y, z, "minecraft:dirt", Kind.FILL));
        }
        for (int x = box[0]; x <= box[1]; x++) for (int z = box[2]; z <= box[3]; z++) p.add(new Piece(x, 0, z, "minecraft:grass_block", Kind.GROUND));
        switch (stage) {
            case 1 -> { // animal pen: fence ring, gate facing the house, a trough, a few animals
                for (int x = 0; x <= 6; x++) {
                    for (int z = 9; z <= 14; z++) {
                        if (!(x == 0 || x == 6 || z == 9 || z == 14)) continue;
                        if (x == 3 && z == 9) p.add(new Piece(x, 1, z, w + "_fence_gate[facing=north,open=false,in_wall=false]", Kind.BLOCK));
                        else p.add(new Piece(x, 1, z, w + "_fence", Kind.CONNECT));
                    }
                }
                p.add(new Piece(5, 0, 13, "minecraft:water", Kind.BLOCK));
                p.add(new Piece(1, 1, 13, "minecraft:hay_block", Kind.BLOCK));
                p.add(new Piece(0, 2, 9, "minecraft:torch", Kind.BLOCK));
                p.add(new Piece(6, 2, 9, "minecraft:torch", Kind.BLOCK));
                p.add(new Piece(2, 1, 11, "cow", Kind.ANIMAL));
                p.add(new Piece(4, 1, 12, "cow", Kind.ANIMAL));
                p.add(new Piece(3, 1, 11, "sheep", Kind.ANIMAL));
                p.add(new Piece(2, 1, 13, "chicken", Kind.ANIMAL));
            }
            case 2 -> { // enchanting hut: cobble floor, plank walls, door toward the house, table ringed by bookshelves
                for (int x = 8; x <= 14; x++) for (int z = 9; z <= 15; z++) p.add(new Piece(x, 0, z, "minecraft:cobblestone", Kind.BLOCK));
                for (int y = 1; y <= 3; y++) {
                    for (int x = 8; x <= 14; x++) {
                        for (int z = 9; z <= 15; z++) {
                            if (!(x == 8 || x == 14 || z == 9 || z == 15)) continue;
                            if (x == 11 && z == 9 && y <= 2) continue;
                            boolean corner = (x == 8 || x == 14) && (z == 9 || z == 15);
                            boolean window = y == 2 && (x == 8 || x == 14) && z == 12;
                            p.add(new Piece(x, y, z, corner ? w + "_log[axis=y]" : window ? "minecraft:glass_pane" : w + "_planks", window ? Kind.CONNECT : Kind.BLOCK));
                        }
                    }
                }
                for (int x = 8; x <= 15; x++) for (int z = 8; z <= 16; z++) p.add(new Piece(x, 4, z, w + "_slab[type=bottom]", Kind.BLOCK));
                p.add(new Piece(11, 1, 9, w + "_door[facing=south,half=lower,hinge=left,open=false]", Kind.DOOR));
                p.add(new Piece(11, 2, 9, w + "_door[facing=south,half=upper,hinge=left,open=false]", Kind.DOOR));
                p.add(new Piece(11, 1, 12, "minecraft:enchanting_table", Kind.BLOCK));
                for (int y = 1; y <= 2; y++) {
                    for (int z = 11; z <= 13; z++) {
                        p.add(new Piece(9, y, z, "minecraft:bookshelf", Kind.BLOCK));
                        p.add(new Piece(13, y, z, "minecraft:bookshelf", Kind.BLOCK));
                    }
                    for (int x = 10; x <= 12; x++) p.add(new Piece(x, y, 14, "minecraft:bookshelf", Kind.BLOCK));
                }
                p.add(new Piece(10, 3, 10, "minecraft:wall_torch[facing=south]", Kind.BLOCK));
                p.add(new Piece(12, 3, 10, "minecraft:wall_torch[facing=south]", Kind.BLOCK));
            }
            case 3 -> { // workshop: open shed with log posts and a slab roof
                for (int x = -8; x <= -2; x++) for (int z = 0; z <= 6; z++) p.add(new Piece(x, 0, z, "minecraft:cobblestone", Kind.BLOCK));
                for (int[] c : new int[][] {{-8, 0}, {-2, 0}, {-8, 6}, {-2, 6}}) {
                    for (int y = 1; y <= 3; y++) p.add(new Piece(c[0], y, c[1], w + "_log[axis=y]", Kind.BLOCK));
                }
                for (int x = -9; x <= -2; x++) for (int z = -1; z <= 7; z++) p.add(new Piece(x, 4, z, w + "_slab[type=bottom]", Kind.BLOCK));
                p.add(new Piece(-7, 1, 5, "minecraft:anvil[facing=east]", Kind.BLOCK));
                p.add(new Piece(-6, 1, 5, "minecraft:smithing_table", Kind.BLOCK));
                p.add(new Piece(-5, 1, 5, "minecraft:blast_furnace[facing=north]", Kind.BLOCK));
                p.add(new Piece(-4, 1, 5, "minecraft:grindstone[face=floor,facing=north]", Kind.BLOCK));
                p.add(new Piece(-3, 1, 5, "minecraft:barrel[facing=up]", Kind.BLOCK));
                p.add(new Piece(-7, 1, 1, "minecraft:stonecutter[facing=north]", Kind.BLOCK));
                p.add(new Piece(-5, 2, 3, "minecraft:lantern[hanging=false]", Kind.BLOCK));
            }
            default -> { // nether portal: obsidian frame, lit
                for (int x = -7; x <= -4; x++) {
                    for (int y = 1; y <= 5; y++) {
                        boolean frame = x == -7 || x == -4 || y == 1 || y == 5;
                        if (frame) p.add(new Piece(x, y, 12, "minecraft:obsidian", Kind.BLOCK));
                    }
                }
                for (int x = -6; x <= -5; x++) for (int y = 2; y <= 4; y++) p.add(new Piece(x, y, 12, "minecraft:nether_portal[axis=x]", Kind.BLOCK));
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ market stall and bandit camp

    /** A market stall facing local north: a barrel counter, fence posts, a wool awning. The seller stands at (1,1,1). */
    public static List<Piece> stall(String wood, String colour) {
        String w = "minecraft:" + wood;
        List<Piece> p = new ArrayList<>();
        for (int y = 4; y >= 1; y--) for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) p.add(new Piece(x, y, z, "minecraft:air", Kind.CLEAR));
        for (int y = -2; y <= -1; y++) for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) p.add(new Piece(x, y, z, "minecraft:dirt", Kind.FILL));
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) p.add(new Piece(x, 0, z, "minecraft:grass_block", Kind.GROUND));
        for (int x = 0; x <= 2; x++) p.add(new Piece(x, 1, 0, "minecraft:barrel[facing=north]", Kind.BLOCK));
        p.add(new Piece(0, 1, 2, w + "_fence", Kind.CONNECT));
        p.add(new Piece(2, 1, 2, w + "_fence", Kind.CONNECT));
        for (int[] c : new int[][] {{0, 0}, {2, 0}, {0, 2}, {2, 2}}) p.add(new Piece(c[0], 2, c[1], w + "_fence", Kind.CONNECT));
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) p.add(new Piece(x, 3, z, "minecraft:" + colour + "_wool", Kind.BLOCK));
        p.add(new Piece(1, 2, 0, "minecraft:lantern[hanging=false]", Kind.BLOCK));
        return p;
    }

    /** A bandit hideout facing local north: a rough shack, a campfire, a loot chest, barrels and a sign. */
    public static List<Piece> camp() {
        List<Piece> p = new ArrayList<>();
        for (int y = 6; y >= 1; y--) for (int x = -1; x <= 7; x++) for (int z = -3; z <= 5; z++) p.add(new Piece(x, y, z, "minecraft:air", Kind.CLEAR));
        for (int x = -1; x <= 7; x++) for (int z = -3; z <= 5; z++) p.add(new Piece(x, 0, z, "minecraft:coarse_dirt", Kind.BLOCK));
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 4; z++) {
                    boolean back = z == 4, side = x == 0 || x == 6;
                    if (!(back || side)) continue;
                    boolean corner = side && (z == 0 || z == 4);
                    p.add(new Piece(x, y, z, corner ? "minecraft:spruce_log[axis=y]" : (x + y + z) % 3 == 0 ? "minecraft:cobblestone" : "minecraft:spruce_planks", Kind.BLOCK));
                }
            }
        }
        for (int x = -1; x <= 7; x++) for (int z = -1; z <= 5; z++) p.add(new Piece(x, 4, z, "minecraft:spruce_slab[type=bottom]", Kind.BLOCK));
        p.add(new Piece(3, 1, 3, "minecraft:chest[facing=north,type=single]", Kind.CHEST));
        p.add(new Piece(1, 1, 3, "minecraft:barrel[facing=up]", Kind.BLOCK));
        p.add(new Piece(5, 1, 3, "minecraft:barrel[facing=up]", Kind.BLOCK));
        p.add(new Piece(1, 1, 1, "minecraft:crafting_table", Kind.BLOCK));
        p.add(new Piece(5, 1, 1, "minecraft:white_bed[facing=south,part=foot]", Kind.BED));
        p.add(new Piece(5, 1, 2, "minecraft:white_bed[facing=south,part=head]", Kind.BED));
        p.add(new Piece(3, 1, -2, "minecraft:campfire[lit=true,facing=north]", Kind.BLOCK));
        p.add(new Piece(0, 2, -1, "minecraft:spruce_wall_sign[facing=north]", Kind.SIGN));
        p.add(new Piece(6, 2, -1, "minecraft:lantern[hanging=false]", Kind.BLOCK));
        return p;
    }

    // ------------------------------------------------------------------ rotation

    private static final String[] DIRS = {"north", "east", "south", "west"};
    private static final Pattern FACING = Pattern.compile("facing=(north|east|south|west)");
    private static final Pattern AXIS = Pattern.compile("axis=([xz])");

    /** Local (x, z) to a world offset after {@code r} clockwise quarter turns. */
    public static int[] rotate(int x, int z, int r) {
        return switch (Math.floorMod(r, 4)) {
            case 1 -> new int[] {-z, x};
            case 2 -> new int[] {-x, -z};
            case 3 -> new int[] {z, -x};
            default -> new int[] {x, z};
        };
    }

    /** Turns the facing/axis properties of a block state by {@code r} clockwise quarter turns. */
    public static String rotateData(String data, int r) {
        int q = Math.floorMod(r, 4);
        if (q == 0) return data;
        Matcher m = FACING.matcher(data);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int i = java.util.Arrays.asList(DIRS).indexOf(m.group(1));
            m.appendReplacement(sb, "facing=" + DIRS[(i + q) % 4]);
        }
        m.appendTail(sb);
        String out = sb.toString();
        if (q % 2 == 1) {
            Matcher a = AXIS.matcher(out);
            StringBuilder sb2 = new StringBuilder();
            while (a.find()) a.appendReplacement(sb2, "axis=" + (a.group(1).equals("x") ? "z" : "x"));
            a.appendTail(sb2);
            out = sb2.toString();
        }
        return out;
    }
}
