package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Color+depth targets that portal views are rendered into, at most window-sized (see {@link PortalViewCrop}).
 * Targets are handed out during a frame and become free again at the next frame, except targets kept for a key:
 * a far portal's view whose image is reused in later frames ({@link FarPortalViewReuse}). A kept target is only
 * handed out for its key, until a frame doesn't use it.
 * <p>
 * A target grows at once when a larger one is asked for, and shrinks only when the sizes asked for it stayed
 * smaller for {@link #SHRINK_FRAMES} frames (not recreated every frame while a portal's size on screen changes).
 * Sizes are rounded up to {@link #SIZE_STEP} pixels.
 */
public class PortalViewTargetPool {
    private static final int SIZE_STEP = 64;
    private static final int SHRINK_FRAMES = 60;

    private static final class Slot {
        final TextureTarget target;
        // the largest size asked for since the last shrink check
        int maxWidth;
        int maxHeight;
        int framesSinceShrinkCheck;
        int usedFrame = Integer.MIN_VALUE;
        @Nullable Object keeper;

        Slot(TextureTarget target) {
            this.target = target;
        }
    }

    private final List<Slot> slots = new ArrayList<>();
    private int frame = 0;
    private int used = 0;

    public void beginFrame() {
        frame++;
        used = 0;
        for (Slot slot : slots) {
            // a kept target that the last frame didn't use is free again
            if (slot.keeper != null && slot.usedFrame < frame - 1) {
                slot.keeper = null;
            }
        }
    }

    /**
     * @return a target at least of that size (but not larger than the window), or null if the limit is reached
     */
    public @Nullable TextureTarget acquire(int limit, int width, int height) {
        return acquire(limit, width, height, null);
    }

    /**
     * Like {@link #acquire(int, int, int)}, and the target is kept for the key: {@link #reuse} returns it in the next
     * frame, unchanged. Returns the target kept for that key if there is one.
     */
    public @Nullable TextureTarget acquire(int limit, int width, int height, @Nullable Object keeper) {
        if (used >= limit) {
            return null;
        }

        Slot slot = null;
        if (keeper != null) {
            slot = findKept(keeper);
        }
        if (slot == null) {
            for (Slot s : slots) {
                if (s.keeper == null && s.usedFrame != frame) {
                    slot = s;
                    break;
                }
            }
        }

        int windowWidth = Minecraft.getInstance().getWindow().getWidth();
        int windowHeight = Minecraft.getInstance().getWindow().getHeight();
        width = Math.max(1, Math.min(width, windowWidth));
        height = Math.max(1, Math.min(height, windowHeight));

        if (slot != null) {
            TextureTarget target = slot.target;
            slot.maxWidth = Math.max(slot.maxWidth, width);
            slot.maxHeight = Math.max(slot.maxHeight, height);
            slot.framesSinceShrinkCheck++;

            int newWidth = target.width;
            int newHeight = target.height;
            if (newWidth < width || newHeight < height) {
                // grow at once
                newWidth = Math.max(newWidth, roundUp(width, windowWidth));
                newHeight = Math.max(newHeight, roundUp(height, windowHeight));
            }
            if (slot.framesSinceShrinkCheck >= SHRINK_FRAMES) {
                newWidth = Math.min(newWidth, roundUp(slot.maxWidth, windowWidth));
                newHeight = Math.min(newHeight, roundUp(slot.maxHeight, windowHeight));
                slot.framesSinceShrinkCheck = 0;
                slot.maxWidth = width;
                slot.maxHeight = height;
            }
            // the window got smaller
            newWidth = Math.min(newWidth, windowWidth);
            newHeight = Math.min(newHeight, windowHeight);
            if (newWidth != target.width || newHeight != target.height) {
                target.resize(newWidth, newHeight);
            }
        }
        else {
            TextureTarget target = new TextureTarget(
                "ImmPtl Portal View " + slots.size(), roundUp(width, windowWidth), roundUp(height, windowHeight),
                GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT
            );
            slot = new Slot(target);
            slot.maxWidth = width;
            slot.maxHeight = height;
            slots.add(slot);
            PortalSurfaceRendering.onTargetCreated(slots.size() - 1, target);
        }
        slot.usedFrame = frame;
        slot.keeper = keeper;
        used++;
        return slot.target;
    }

    /**
     * The target kept for the key, as it was rendered (not resized or cleared), for this frame too.
     * Null if there is none or the limit is reached.
     */
    public @Nullable TextureTarget reuse(Object keeper, int limit) {
        if (used >= limit) {
            return null;
        }
        Slot slot = findKept(keeper);
        if (slot == null) {
            return null;
        }
        slot.usedFrame = frame;
        used++;
        return slot.target;
    }

    private @Nullable Slot findKept(Object keeper) {
        for (Slot slot : slots) {
            if (slot.keeper == keeper) {
                return slot;
            }
        }
        return null;
    }

    private static int roundUp(int size, int max) {
        return Math.min(max, (size + SIZE_STEP - 1) / SIZE_STEP * SIZE_STEP);
    }

    public int getUsedCount() {
        return used;
    }

    public int indexOf(TextureTarget target) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).target == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The pixels of the targets used in this frame (for benchmarks and diagnostics).
     */
    public long getUsedPixels() {
        long pixels = 0;
        for (Slot slot : slots) {
            if (slot.usedFrame == frame) {
                pixels += (long) slot.target.width * slot.target.height;
            }
        }
        return pixels;
    }

    public void cleanUp() {
        PortalSurfaceRendering.onPoolCleared();
        for (Slot slot : slots) {
            slot.target.destroyBuffers();
        }
        slots.clear();
        used = 0;
    }
}
