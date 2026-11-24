package dev.unstablesmp.life;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * What makes residents look like they live there: they build a starter base block by block (holding the block,
 * swinging, the right sounds), sleep in their beds with their spawn set there, chop trees, harvest and replant their
 * farm, hunt animals, and put their loot away in their chest - sorted.
 */
final class Homestead {

    enum Step { PLACED, IDLE, TOO_FAR, DONE }

    private final Logger log;
    private LifeConfig cfg;
    private final Map<String, List<Blueprint.Piece>> plans = new HashMap<>();
    private final Map<String, BlockData> dataCache = new HashMap<>();

    Homestead(Logger log, LifeConfig cfg) {
        this.log = log;
        this.cfg = cfg;
    }

    void config(LifeConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ geometry

    static int[] world(LifeDirector.Resident f, int lx, int ly, int lz) {
        int[] o = Blueprint.rotate(lx, lz, f.rot);
        return new int[] {f.bx + o[0], f.by + ly, f.bz + o[1]};
    }

    static Block block(World w, LifeDirector.Resident f, int[] local) {
        int[] c = world(f, local[0], local[1], local[2]);
        return w.getBlockAt(c[0], c[1], c[2]);
    }

    /** Rotation that turns the front door toward (tx, tz). */
    static int facingRotation(int x, int z, int tx, int tz) {
        int dx = tx - x, dz = tz - z;
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? 1 : 3;
        return dz > 0 ? 2 : 0;
    }

    /** Why the last plot was turned down (for the log when no plot is found). */
    String lastProblem = "";

    /** Whether a plot can take a base: nobody's build on it, fairly flat (under the trees), no water. */
    boolean siteOk(World w, int ax, int ay, int az, int rot) {
        String why = problem(w, ax, ay, az, rot);
        if (why != null) lastProblem = why;
        return why == null;
    }

    private String problem(World w, int ax, int ay, int az, int rot) {
        for (int lx = Blueprint.MIN_X; lx <= Blueprint.MAX_X; lx++) {
            for (int lz = Blueprint.MIN_Z; lz <= Blueprint.MAX_Z; lz++) {
                int[] o = Blueprint.rotate(lx, lz, rot);
                int x = ax + o[0], z = az + o[1];
                if (!w.isChunkGenerated(x >> 4, z >> 4)) return "land not generated yet";
                int g = LifeUtil.ground(w, x, z);
                if (g == Integer.MIN_VALUE) return "water";
                if (g - ay > 5 || ay - g > 3) return "too steep"; // up to 5 dug out, 3 filled in
                for (int y = ay - 1; y <= ay + Blueprint.TOP; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (y <= ay + 1 && b.isLiquid()) return "water";
                    if (manMade(b)) return "someone's build (" + b.getType().name().toLowerCase() + ")";
                }
            }
        }
        return null;
    }

    /** Things players build with (or that mean a structure is there): a base never goes on top of them. */
    static boolean manMade(Block b) {
        Material m = b.getType();
        if (m.isAir()) return false;
        String n = m.name();
        if (Tag.PLANKS.isTagged(m) || Tag.STAIRS.isTagged(m) || Tag.SLABS.isTagged(m) || Tag.DOORS.isTagged(m) || Tag.BEDS.isTagged(m)
                || Tag.FENCES.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.WOOL.isTagged(m)
                || Tag.WOOL_CARPETS.isTagged(m) || Tag.ALL_SIGNS.isTagged(m) || Tag.RAILS.isTagged(m) || Tag.BUTTONS.isTagged(m)
                || Tag.PRESSURE_PLATES.isTagged(m) || Tag.TRAPDOORS.isTagged(m)) return true;
        if (n.contains("GLASS") || n.contains("CONCRETE") || n.contains("BRICK") || n.contains("TORCH") || n.contains("LANTERN")
                || n.equals("COBBLESTONE") || n.equals("LADDER") || n.equals("SCAFFOLDING") || n.equals("TNT") || n.equals("FARMLAND")
                || n.equals("DIRT_PATH") || n.startsWith("STRIPPED_") || n.equals("OBSIDIAN") || n.equals("CRAFTING_TABLE")) return true;
        return b.getState(false) instanceof org.bukkit.block.TileState;
    }

    /** The floor height that fits a plot best: the middle of its ground heights (MIN_VALUE if it isn't land). */
    static int floorFor(World w, int ax, int az, int rot) {
        List<Integer> hs = new ArrayList<>();
        for (int lx = Blueprint.MIN_X; lx <= Blueprint.MAX_X; lx += 2) {
            for (int lz = Blueprint.MIN_Z; lz <= Blueprint.MAX_Z; lz += 2) {
                int[] o = Blueprint.rotate(lx, lz, rot);
                if (!w.isChunkGenerated((ax + o[0]) >> 4, (az + o[1]) >> 4)) return Integer.MIN_VALUE;
                int g = LifeUtil.ground(w, ax + o[0], az + o[1]);
                if (g == Integer.MIN_VALUE) return Integer.MIN_VALUE;
                hs.add(g);
            }
        }
        java.util.Collections.sort(hs);
        return hs.get(hs.size() / 2);
    }

    List<Blueprint.Piece> plan(LifeDirector.Resident f) {
        return plans.computeIfAbsent(LifeUtil.key(f.name), k -> Blueprint.plan(f.wood, f.crop, f.bedColour, f.beds));
    }

    void forget(LifeDirector.Resident f) {
        plans.remove(LifeUtil.key(f.name));
    }

    private BlockData data(String raw, int rot) {
        String key = rot + "|" + raw;
        return dataCache.computeIfAbsent(key, k -> Bukkit.createBlockData(Blueprint.rotateData(raw, rot)));
    }

    // ------------------------------------------------------------------ building

    /** One building action by {@code builder} on founder {@code f}'s base ({@code force}: even if it's out of reach). */
    Step buildStep(LifeDirector.Resident f, Player builder, boolean force) {
        World w = Bukkit.getWorld(f.world);
        if (w == null) return Step.IDLE;
        List<Blueprint.Piece> plan = plan(f);
        double spent = 0;
        while (f.progress < plan.size()) {
            Blueprint.Piece pc = plan.get(f.progress);
            int[] c = world(f, pc.x(), pc.y(), pc.z());
            Block b = w.getBlockAt(c[0], c[1], c[2]);
            Material cur = b.getType();
            double cost;
            switch (pc.kind()) {
                case CLEAR -> {
                    if (cur.isAir()) {
                        f.progress++;
                        continue;
                    }
                    if (!force && far(builder, c)) return Step.TOO_FAR;
                    boolean light = Tag.LEAVES.isTagged(cur) || Tag.REPLACEABLE.isTagged(cur) || Tag.FLOWERS.isTagged(cur);
                    hold(builder, breakTool(cur));
                    w.playEffect(b.getLocation(), Effect.STEP_SOUND, b.getBlockData());
                    b.setType(Material.AIR, false);
                    cost = light ? 0.34 : 1;
                }
                case FILL -> {
                    if (cur.isSolid()) {
                        f.progress++;
                        continue;
                    }
                    if (!force && far(builder, c)) return Step.TOO_FAR;
                    place(builder, b, Material.DIRT.createBlockData());
                    cost = 0.5;
                }
                case GROUND -> {
                    boolean dirtLike = cur == Material.DIRT || cur == Material.COARSE_DIRT || cur == Material.ROOTED_DIRT || cur == Material.PODZOL;
                    if (cur == Material.GRASS_BLOCK || (cur.isSolid() && !dirtLike)) {
                        f.progress++;
                        continue;
                    }
                    if (!force && far(builder, c)) return Step.TOO_FAR;
                    place(builder, b, Material.GRASS_BLOCK.createBlockData());
                    cost = 0.5;
                }
                default -> {
                    BlockData want = data(pc.data(), f.rot);
                    if (b.getBlockData().matches(want) || (pc.kind() == Blueprint.Kind.CROP && cur == want.getMaterial())) {
                        f.progress++;
                        continue;
                    }
                    if (!force && far(builder, c)) return Step.TOO_FAR;
                    if (pc.kind() == Blueprint.Kind.CROP && want instanceof Ageable a) {
                        Ageable copy = (Ageable) a.clone();
                        copy.setAge(ThreadLocalRandom.current().nextInt(copy.getMaximumAge() + 1));
                        want = copy;
                    }
                    place(builder, b, want);
                    afterPlace(f, b, pc);
                    cost = 1;
                }
            }
            f.progress++;
            spent += cost;
            if (spent >= 1) return Step.PLACED;
        }
        if (!f.built) {
            f.built = true;
            log.info(f.name + " finished building their base at " + f.bx + " " + f.by + " " + f.bz + ".");
        }
        return Step.DONE;
    }

    /** Not at the job: more than 14 blocks away across (the whole plot is in reach from the middle of it) or 12 up/down. */
    private static boolean far(Player p, int[] c) {
        Location l = p.getLocation();
        double dx = l.getX() - (c[0] + 0.5), dy = l.getY() - c[1], dz = l.getZ() - (c[2] + 0.5);
        return dx * dx + dz * dz > 14 * 14 || Math.abs(dy) > 12;
    }

    /** A free spot to stand near the next piece (for the goto when the builder is out of reach). */
    int[] nextSpot(LifeDirector.Resident f) {
        List<Blueprint.Piece> plan = plan(f);
        Blueprint.Piece pc = plan.get(Math.min(f.progress, plan.size() - 1));
        int[] c = world(f, pc.x(), 0, pc.z());
        World w = Bukkit.getWorld(f.world);
        if (w != null) {
            for (int[] o : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                for (int y = f.by + 1; y <= f.by + 4; y++) {
                    if (w.getBlockAt(c[0] + o[0], y, c[2] + o[1]).isPassable() && w.getBlockAt(c[0] + o[0], y + 1, c[2] + o[1]).isPassable()
                            && w.getBlockAt(c[0] + o[0], y - 1, c[2] + o[1]).getType().isSolid()) return new int[] {c[0] + o[0], y, c[2] + o[1]};
                }
            }
        }
        return new int[] {c[0], f.by + 1, c[2]};
    }

    private void place(Player p, Block b, BlockData data) {
        hold(p, handItem(data.getMaterial()));
        boolean physics = data instanceof MultipleFacing || data.getMaterial() == Material.WATER;
        b.setBlockData(data, physics);
        try {
            b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), data.getSoundGroup().getPlaceSound(), 1f, 0.8f);
        } catch (RuntimeException ignored) {
            // some states have no sound group
        }
    }

    private void afterPlace(LifeDirector.Resident f, Block b, Blueprint.Piece pc) {
        switch (pc.kind()) {
            case CONNECT -> connect(b);
            case SIGN -> {
                if (b.getState() instanceof Sign sign) {
                    List<String> lines = cfg.signs;
                    String raw = lines.isEmpty() ? "{name}'s base" : lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
                    String[] parts = raw.replace("{name}", f.name).split("\\|");
                    for (int i = 0; i < 4 && i < parts.length; i++) sign.getSide(Side.FRONT).line(i, Component.text(parts[i]));
                    sign.update(true, false);
                }
            }
            case CHEST -> {
                if (b.getState() instanceof Chest chest && chest.getBlockInventory().isEmpty()) {
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    for (String s : cfg.starterItems) {
                        String[] p = s.split(":");
                        Material m = Material.matchMaterial(p[0].trim());
                        if (m == null || !m.isItem()) continue;
                        int n = p.length > 1 ? dev.unstablesmp.Settings.IntRange.parse(p[1], new dev.unstablesmp.Settings.IntRange(1, 1)).random() : 1;
                        if (n > 0 && r.nextBoolean()) chest.getBlockInventory().addItem(new ItemStack(m, Math.min(n, m.getMaxStackSize())));
                    }
                }
            }
            default -> { }
        }
    }

    /** Fences and panes don't work out their own connections when placed by code; this does it both ways. */
    static void connect(Block b) {
        if (!(b.getBlockData() instanceof MultipleFacing mf)) return;
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Block n = b.getRelative(face);
            boolean join = joins(b.getType(), n.getType());
            if (mf.getAllowedFaces().contains(face)) mf.setFace(face, join);
            if (n.getBlockData() instanceof MultipleFacing nf && joins(n.getType(), b.getType())
                    && nf.getAllowedFaces().contains(face.getOppositeFace())) {
                nf.setFace(face.getOppositeFace(), true);
                n.setBlockData(nf, false);
            }
        }
        b.setBlockData(mf, false);
    }

    private static boolean joins(Material self, Material other) {
        if (other.isOccluding()) return true;
        if (Tag.FENCES.isTagged(self)) return Tag.WOODEN_FENCES.isTagged(other) || Tag.FENCE_GATES.isTagged(other);
        return other.name().endsWith("GLASS_PANE") || other == Material.IRON_BARS || other.name().endsWith("GLASS");
    }

    // ------------------------------------------------------------------ hands

    /** Puts an item in the bot's hand for the action (the real weapon is put back when the chore ends). */
    void hold(Player p, Material m) {
        if (m == null || !m.isItem()) return;
        p.getInventory().setItemInMainHand(new ItemStack(m));
        p.swingMainHand();
    }

    static Material breakTool(Material m) {
        if (Tag.LOGS.isTagged(m) || Tag.PLANKS.isTagged(m) || Tag.LEAVES.isTagged(m)) return Material.IRON_AXE;
        if (Tag.DIRT.isTagged(m) || Tag.SAND.isTagged(m) || m == Material.GRAVEL || m == Material.CLAY) return Material.IRON_SHOVEL;
        if (Tag.REPLACEABLE.isTagged(m) || Tag.FLOWERS.isTagged(m)) return null;
        return Material.IRON_PICKAXE;
    }

    static Material handItem(Material m) {
        String n = m.name();
        if (m == Material.WALL_TORCH) return Material.TORCH;
        if (n.endsWith("_WALL_SIGN")) return Material.matchMaterial(n.replace("_WALL_SIGN", "_SIGN"));
        if (m == Material.WATER) return Material.WATER_BUCKET;
        if (m == Material.FARMLAND) return Material.IRON_HOE;
        if (m == Material.WHEAT) return Material.WHEAT_SEEDS;
        if (m == Material.CARROTS) return Material.CARROT;
        if (m == Material.POTATOES) return Material.POTATO;
        if (m == Material.BEETROOTS) return Material.BEETROOT_SEEDS;
        return m.isItem() ? m : null;
    }

    // ------------------------------------------------------------------ beds

    Location bed(LifeDirector.Resident f, int index) {
        World w = Bukkit.getWorld(f.world);
        if (w == null || !f.built) return null;
        int[] c = world(f, Blueprint.bedHead(Math.min(index, Math.max(0, f.beds - 1)))[0], 1,
                Blueprint.bedHead(Math.min(index, Math.max(0, f.beds - 1)))[2]);
        Block b = w.getBlockAt(c[0], c[1], c[2]);
        return Tag.BEDS.isTagged(b.getType()) ? b.getLocation() : null;
    }

    /** Spawn point in their bed, like a player who slept there. */
    void setSpawn(Player p, LifeDirector.Resident f, int index) {
        Location bed = bed(f, index);
        if (bed != null) p.setRespawnLocation(bed, true);
    }

    // ------------------------------------------------------------------ farm

    /** Harvests and replants one ripe crop; IDLE when none are ripe, TOO_FAR when the bot isn't at the farm. */
    Step harvestStep(LifeDirector.Resident f, Player p, boolean force) {
        World w = Bukkit.getWorld(f.world);
        if (w == null || !f.built) return Step.IDLE;
        List<Block> ripe = new ArrayList<>();
        for (int x = 9; x <= 13; x++) {
            for (int z = 1; z <= 5; z++) {
                if (!Blueprint.isCropBed(x, z)) continue;
                Block b = block(w, f, new int[] {x, 1, z});
                if (b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge()) ripe.add(b);
            }
        }
        if (ripe.isEmpty()) return Step.IDLE;
        Block b = ripe.get(ThreadLocalRandom.current().nextInt(ripe.size()));
        if (!force && far(p, new int[] {b.getX(), b.getY(), b.getZ()})) return Step.TOO_FAR;
        Ageable a = (Ageable) b.getBlockData();
        hold(p, Material.IRON_HOE);
        w.playEffect(b.getLocation(), Effect.STEP_SOUND, b.getBlockData());
        give(p, produce(b.getType()));
        a.setAge(0);
        b.setBlockData(a, false);
        return Step.PLACED;
    }

    private static List<ItemStack> produce(Material crop) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> out = new ArrayList<>();
        switch (crop) {
            case WHEAT -> {
                out.add(new ItemStack(Material.WHEAT));
                out.add(new ItemStack(Material.WHEAT_SEEDS, 1 + r.nextInt(2)));
            }
            case CARROTS -> out.add(new ItemStack(Material.CARROT, 2 + r.nextInt(3)));
            case POTATOES -> out.add(new ItemStack(Material.POTATO, 2 + r.nextInt(3)));
            case BEETROOTS -> {
                out.add(new ItemStack(Material.BEETROOT));
                out.add(new ItemStack(Material.BEETROOT_SEEDS, 1 + r.nextInt(2)));
            }
            default -> { }
        }
        return out;
    }

    // ------------------------------------------------------------------ trees

    /** A tree near home, as its trunk logs bottom to top; empty if none found. */
    List<int[]> findTree(World w, int cx, int cz, LifeDirector.Resident base) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 160; attempt++) {
            double ang = r.nextDouble(Math.PI * 2);
            int d = 10 + r.nextInt(32);
            int x = cx + (int) (Math.cos(ang) * d), z = cz + (int) (Math.sin(ang) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (base != null && base.base && insideBase(base, x, z)) continue;
            int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!Tag.LOGS.isTagged(w.getBlockAt(x, top, z).getType())) continue;
            int y = top;
            while (Tag.LOGS.isTagged(w.getBlockAt(x, y - 1, z).getType()) && top - y < 30) y--;
            if (!Tag.DIRT.isTagged(w.getBlockAt(x, y - 1, z).getType())) continue;
            List<int[]> logs = new ArrayList<>();
            for (int yy = y; yy <= top; yy++) logs.add(new int[] {x, yy, z});
            return logs;
        }
        return List.of();
    }

    private static boolean insideBase(LifeDirector.Resident f, int x, int z) {
        int[] a = world(f, Blueprint.MIN_X - 2, 0, Blueprint.MIN_Z - 2), b = world(f, Blueprint.MAX_X + 2, 0, Blueprint.MAX_Z + 2);
        return x >= Math.min(a[0], b[0]) && x <= Math.max(a[0], b[0]) && z >= Math.min(a[2], b[2]) && z <= Math.max(a[2], b[2]);
    }

    /** Chops the next log (bottom up); replants a sapling at {@code trunkBase} when the trunk is gone. */
    Step chopStep(Player p, List<int[]> logs, int[] trunkBase) {
        if (logs.isEmpty()) return Step.DONE;
        int[] c = logs.get(0);
        World w = p.getWorld();
        Block b = w.getBlockAt(c[0], c[1], c[2]);
        if (!Tag.LOGS.isTagged(b.getType())) {
            logs.remove(0);
            return logs.isEmpty() ? Step.DONE : Step.IDLE;
        }
        Location l = p.getLocation();
        double dx = l.getX() - (c[0] + 0.5), dz = l.getZ() - (c[2] + 0.5);
        if (dx * dx + dz * dz > 5.5 * 5.5) return Step.TOO_FAR;
        Material log = b.getType();
        hold(p, Material.IRON_AXE);
        w.playEffect(b.getLocation(), Effect.STEP_SOUND, b.getBlockData());
        b.setType(Material.AIR, false);
        give(p, List.of(new ItemStack(log)));
        logs.remove(0);
        if (logs.isEmpty()) {
            if (trunkBase != null) replant(w, w.getBlockAt(trunkBase[0], trunkBase[1], trunkBase[2]), log);
            return Step.DONE;
        }
        return Step.PLACED;
    }

    private static void replant(World w, Block spot, Material log) {
        if (ThreadLocalRandom.current().nextDouble() > 0.6) return;
        if (!spot.getType().isAir() || !Tag.DIRT.isTagged(spot.getRelative(BlockFace.DOWN).getType())) return;
        Material sapling = Material.matchMaterial(log.name().replace("STRIPPED_", "").replace("_LOG", "_SAPLING").replace("_WOOD", "_SAPLING"));
        if (sapling == null || !Tag.SAPLINGS.isTagged(sapling) || sapling == Material.DARK_OAK_SAPLING) sapling = Material.OAK_SAPLING;
        spot.setType(sapling, false);
    }

    // ------------------------------------------------------------------ hunting

    /** The nearest grown farm animal near home that nobody owns or named. */
    Entity findPrey(Player p, int range) {
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : p.getNearbyEntities(range, 12, range)) {
            if (!(e instanceof Animals a) || !(a instanceof Cow || a instanceof Pig || a instanceof Sheep || a instanceof Chicken || a instanceof Rabbit)) continue;
            if (!a.isAdult() || a.customName() != null || a.isLeashed() || (a instanceof Tameable t && t.isTamed())) continue;
            double d = e.getLocation().distanceSquared(p.getLocation());
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ chest

    Block chest(LifeDirector.Resident f) {
        World w = Bukkit.getWorld(f.world);
        if (w == null || !f.built) return null;
        Block b = block(w, f, Blueprint.chestSpot());
        return b.getType() == Material.CHEST ? b : null;
    }

    static boolean hasLoot(Player p) {
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && stashable(it.getType())) return true;
        return false;
    }

    /**
     * Opens the chest, puts the loot away (ores, logs, stone, crops, mob drops - never their gear), sorts the whole
     * chest, and closes it a moment later. Returns false if the bot isn't next to its chest.
     */
    boolean store(Player p, Block chestBlock, LifeDirector.Scheduler later) {
        Location l = p.getLocation();
        if (l.getWorld() != chestBlock.getWorld() || l.distanceSquared(chestBlock.getLocation().add(0.5, 0, 0.5)) > 5 * 5) return false;
        if (!(chestBlock.getState() instanceof Chest c)) return false;
        c.open();
        p.swingMainHand();
        later.run(25 + ThreadLocalRandom.current().nextInt(30), () -> {
            if (!(chestBlock.getState() instanceof Chest open)) return;
            Inventory into = open.getInventory();
            PlayerInventory inv = p.getInventory();
            ItemStack[] storage = inv.getStorageContents();
            for (int i = 0; i < storage.length; i++) {
                ItemStack it = storage[i];
                if (it == null || !stashable(it.getType())) continue;
                Map<Integer, ItemStack> left = into.addItem(it);
                storage[i] = left.isEmpty() ? null : left.values().iterator().next();
            }
            inv.setStorageContents(storage);
            sort(into);
            p.swingMainHand();
            later.run(20 + ThreadLocalRandom.current().nextInt(30), () -> {
                if (chestBlock.getState() instanceof Chest shut) shut.close();
            });
        });
        return true;
    }

    /** Stacks merged, grouped by kind (ores, wood, stone, food...), then by name. */
    static void sort(Inventory inv) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) items.add(it);
        items.sort(Comparator.comparingInt((ItemStack it) -> group(it.getType())).thenComparing(it -> it.getType().name()));
        inv.clear();
        for (ItemStack it : items) inv.addItem(it);
    }

    private static int group(Material m) {
        String n = m.name();
        if (n.contains("DIAMOND") || n.contains("EMERALD") || n.endsWith("_INGOT") || n.startsWith("RAW_") || n.endsWith("_ORE")) return 0;
        if (n.equals("COAL") || n.equals("REDSTONE") || n.equals("LAPIS_LAZULI") || n.equals("QUARTZ")) return 1;
        if (Tag.LOGS.isTagged(m) || Tag.PLANKS.isTagged(m) || Tag.SAPLINGS.isTagged(m) || m == Material.STICK) return 2;
        if (m.isEdible() || n.contains("SEEDS") || m == Material.WHEAT) return 4;
        if (n.contains("STONE") || n.contains("DEEPSLATE") || m == Material.DIRT || m == Material.GRAVEL || m == Material.SAND) return 3;
        return 5;
    }

    /** Loot worth stashing; never weapons, armour, totems, pearls or other PvP gear. */
    static boolean stashable(Material m) {
        if (m.getMaxStackSize() == 1) return false;
        String n = m.name();
        if (n.endsWith("_ORE") || n.startsWith("RAW_") || n.endsWith("_INGOT") || n.endsWith("_NUGGET")) return true;
        if (n.equals("DIAMOND") || n.equals("EMERALD") || n.equals("COAL") || n.equals("REDSTONE") || n.equals("LAPIS_LAZULI")
                || n.equals("QUARTZ") || n.equals("AMETHYST_SHARD") || n.equals("FLINT")) return true;
        if (Tag.LOGS.isTagged(m) || Tag.PLANKS.isTagged(m) || Tag.SAPLINGS.isTagged(m) || m == Material.STICK || m == Material.APPLE) return true;
        if (n.equals("COBBLESTONE") || n.equals("COBBLED_DEEPSLATE") || n.equals("STONE") || n.equals("DIRT") || n.equals("GRAVEL")
                || n.equals("SAND") || n.equals("ANDESITE") || n.equals("DIORITE") || n.equals("GRANITE") || n.equals("TUFF")) return true;
        if (m == Material.WHEAT || m == Material.WHEAT_SEEDS || m == Material.CARROT || m == Material.POTATO || m == Material.BEETROOT
                || m == Material.BEETROOT_SEEDS || m == Material.POISONOUS_POTATO) return true;
        return m == Material.LEATHER || m == Material.BEEF || m == Material.PORKCHOP || m == Material.MUTTON || m == Material.CHICKEN
                || m == Material.RABBIT || m == Material.RABBIT_HIDE || m == Material.FEATHER || Tag.WOOL.isTagged(m)
                || m == Material.STRING || m == Material.BONE || m == Material.ROTTEN_FLESH || m == Material.GUNPOWDER
                || m == Material.SPIDER_EYE || m == Material.EGG;
    }

    static void give(Player p, List<ItemStack> items) {
        for (ItemStack it : items) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
        }
    }
}
