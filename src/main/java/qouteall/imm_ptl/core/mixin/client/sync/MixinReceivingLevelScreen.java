package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;

// ReceivingLevelScreen was merged into LevelLoadingScreen in 26.x (this mixin is empty)
@Mixin(LevelLoadingScreen.class)
public class MixinReceivingLevelScreen {
}
