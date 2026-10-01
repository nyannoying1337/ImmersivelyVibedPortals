package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen;
import qouteall.imm_ptl.core.platform_specific.IPConfig;

import java.util.List;
import java.util.function.Function;

// In 26.3 the initial screens are added by Gui instead of Minecraft
@Mixin(Gui.class)
public class MixinGui_InitialScreen {
    @Inject(
        method = "addInitialScreens",
        at = @At("RETURN")
    )
    private void onAddInitialScreens(
        List<Function<Runnable, Screen>> output, CallbackInfoReturnable<Boolean> cir
    ) {
        IPConfig config = IPConfig.getConfig();
        if (IPortalInitialScreen.shouldShow(config)) {
            output.add(IPortalInitialScreen::new);
        }
    }
}
