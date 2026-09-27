package qouteall.q_misc_util;

import net.minecraft.util.profiling.Profiler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.TreeMap;

/**
 * Make this because {@link Gui#setOverlayMessage(Component, boolean)} does not support multi-line
 */
@Environment(EnvType.CLIENT)
public class CustomTextOverlay {
    
    public static record Entry(
        Component component,
        long clearingTime
    ) {}
    
    private static final TreeMap<String, Entry> ENTRIES = new TreeMap<>();
    
    private static final boolean renderAtBottomCenter = true;
    
    @Nullable
    private static MultiLineLabel multiLineLabelCache;
    
    public static void putText(Component component, double durationSeconds, String key) {
        ENTRIES.put(
            key,
            new Entry(
                component,
                System.nanoTime() + Helper.secondToNano(durationSeconds)
            )
        );
        multiLineLabelCache = null;
    }
    
    public static void putText(Component component, double durationSeconds) {
        putText(component, durationSeconds, "5_defaultKey");
    }
    
    public static void putText(Component component, String key) {
        putText(component, 0.2, key);
    }
    
    public static void putText(Component component) {
        putText(component, 0.2, "5_defaultKey");
    }
    
    public static boolean remove(String key) {
        return ENTRIES.remove(key) != null;
    }
    
    /**
     * {@link net.minecraft.client.gui.Hud#extractRenderState(GuiGraphicsExtractor, DeltaTracker)}
     * {@link net.minecraft.client.gui.screens.AlertScreen}
     */
    public static void render(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker) {
        long currTime = System.nanoTime();
        
        boolean removes = ENTRIES.entrySet().removeIf(e -> e.getValue().clearingTime < currTime);
        if (removes) {
            multiLineLabelCache = null;
        }
        
        if (ENTRIES.isEmpty()) {
            return;
        }
        
        if (multiLineLabelCache == null) {
            // don't make the first component the base component
            // to avoid style override
            MutableComponent component = Component.empty();
            boolean isBeginning = true;
            for (Entry entry : ENTRIES.values()) {
                if (isBeginning) {
                    isBeginning = false;
                }
                else {
                    component.append("\n");
                }
                component.append(entry.component());
            }
            
            multiLineLabelCache = MultiLineLabel.create(
                Minecraft.getInstance().font,
                component,
                (Minecraft.getInstance().getWindow().getGuiScaledWidth() - 20)
            );
            assert multiLineLabelCache != null;
        }
        
        Minecraft minecraft = Minecraft.getInstance();
        
        guiGraphics.pose().pushMatrix();
        
        int guiScaledWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiScaledHeight = minecraft.getWindow().getGuiScaledHeight();
        
        Font font = minecraft.font;
        
        Profiler.get().push("imm_ptl_custom_overlay");
        if (renderAtBottomCenter) {
            // Note: the parchment names are incorrect
            multiLineLabelCache.visitLines(
                TextAlignment.CENTER,
                guiScaledWidth / 2, // x
                (int) (guiScaledHeight * 0.75), // y
                9, // line height
                guiGraphics.textRenderer()
            );
        }
        else {
            multiLineLabelCache.visitLines(
                TextAlignment.LEFT,
                10, // x
                10, // y
                9, // line height
                guiGraphics.textRenderer()
            );
        }
        
        guiGraphics.pose().popMatrix();
        
        Profiler.get().pop();
    }
}
