package qouteall.imm_ptl.core.render;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * ImmPtl's lines on the F3 screen. In 26.3 the F3 screen is made of entries,
 * which can be toggled in the debug options screen.
 */
public class ImmPtlDebugScreenEntry implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("immersive_portals", "portal_rendering");
    
    public static void init() {
        DebugScreenEntries.register(ID, new ImmPtlDebugScreenEntry());
    }
    
    @Override
    public void display(
        DebugScreenDisplayer displayer, @Nullable Level serverOrClientLevel,
        @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk
    ) {
        for (String line : RenderStates.collectDebugText()) {
            displayer.addLine(line);
        }
    }
}
