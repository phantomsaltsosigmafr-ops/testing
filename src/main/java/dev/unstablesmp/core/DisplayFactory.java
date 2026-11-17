package dev.unstablesmp.core;

import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;

/** Spawns tracked, non-persistent display and interaction entities with sane defaults. */
public final class DisplayFactory {
    private final EntityTracker tracker;
    private float viewRange = 1.0f;

    public DisplayFactory(EntityTracker tracker) {
        this.tracker = tracker;
    }

    public void setViewRange(float viewRange) {
        this.viewRange = viewRange;
    }

    public EntityTracker tracker() {
        return tracker;
    }

    private void base(Display d, UUID owner) {
        tracker.track(d, owner, EntityTracker.Kind.DISPLAY);
        d.setViewRange(viewRange);
        d.setShadowRadius(0f);
        d.setShadowStrength(0f);
        d.setInterpolationDelay(0);
    }

    public BlockDisplay block(Location at, BlockData data, UUID owner, Consumer<BlockDisplay> extra) {
        return at.getWorld().spawn(at, BlockDisplay.class, d -> {
            base(d, owner);
            d.setBlock(data);
            if (extra != null) extra.accept(d);
        });
    }

    public BlockDisplay block(Location at, Material mat, UUID owner, Consumer<BlockDisplay> extra) {
        return block(at, mat.createBlockData(), owner, extra);
    }

    public ItemDisplay item(Location at, ItemStack stack, UUID owner, Consumer<ItemDisplay> extra) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            base(d, owner);
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            if (extra != null) extra.accept(d);
        });
    }

    public Interaction hitbox(Location at, float width, float height, UUID owner) {
        return at.getWorld().spawn(at, Interaction.class, i -> {
            tracker.track(i, owner, EntityTracker.Kind.DISPLAY);
            i.setInteractionWidth(width);
            i.setInteractionHeight(height);
            i.setResponsive(true);
        });
    }

    public static void glow(Display d, Color color) {
        d.setGlowing(true);
        d.setGlowColorOverride(color);
    }

    public static void fullBright(Display d) {
        d.setBrightness(new Display.Brightness(15, 15));
    }
}
