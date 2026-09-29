package qouteall.imm_ptl.peripheral.dim_stack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.Helper;

public class DimListWidget extends AbstractSelectionList<DimEntryWidget> {
    
    public static final int ROW_WIDTH = 300;
    
    public static interface DraggingCallback {
        void run(int selectedIndex, int mouseOnIndex);
    }
    
    public final Screen parent;
    private final Type type;
    @Nullable
    private final DraggingCallback draggingCallback;
    
    
    public static enum Type {
        mainDimensionList, addDimensionList
    }
    
    public DimListWidget(
        int width,
        int height,
        int top,
        int itemHeight,
        Screen parent,
        Type type,
        @Nullable DraggingCallback draggingCallback
    ) {
        super(Minecraft.getInstance(), width, height, top, itemHeight);
        this.parent = parent;
        this.type = type;
        this.draggingCallback = draggingCallback;
    }
    
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (type == Type.mainDimensionList && draggingCallback != null) {
            DimEntryWidget selected = getSelected();
        
            if (selected != null) {
                DimEntryWidget mouseOn = getEntryAtPosition(event.x(), event.y());
                if (mouseOn != null) {
                    if (mouseOn != selected) {
                        int selectedIndex = children().indexOf(selected);
                        int mouseOnIndex = children().indexOf(mouseOn);
                        if (selectedIndex != -1 && mouseOnIndex != -1) {
                            draggingCallback.run(selectedIndex, mouseOnIndex);
                        }
                        else {
                            Helper.err("Invalid dragging");
                        }
                    }
                }
            }
        }
        
        return super.mouseDragged(event, deltaX, deltaY);
    }
    
    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    
    }
    
    // make it wider
    @Override
    public int getRowWidth() {
        return ROW_WIDTH;
    }
    
    @Override
    protected void extractListBackground(GuiGraphicsExtractor guiGraphics) {
        // don't render background
    }

    /**
     * In 26.3 {@link #children()} is unmodifiable; edit a copy and replace the entries with it.
     */
    public void mutateEntries(java.util.function.Consumer<java.util.List<DimEntryWidget>> mutation) {
        DimEntryWidget selected = getSelected();
        java.util.List<DimEntryWidget> entries = new java.util.ArrayList<>(children());
        mutation.accept(entries);
        replaceEntries(entries);
        if (selected != null && entries.contains(selected)) {
            setSelected(selected);
        }
    }
}
