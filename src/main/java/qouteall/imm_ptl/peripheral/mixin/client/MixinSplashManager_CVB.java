package qouteall.imm_ptl.peripheral.mixin.client;

import net.minecraft.client.resources.SplashManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(SplashManager.class)
public abstract class MixinSplashManager_CVB {
    @Shadow
    private List<Component> splashes;
    
    @Shadow
    private static Component literalSplash(String text) {
        throw new AssertionError();
    }
    
    // in 26.3 the splash list is an immutable list of components, replaced on each reload
    @Inject(
        method = "apply(Ljava/util/List;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("RETURN")
    )
    private void onApply(
        List<Component> preparations, ResourceManager resourceManager, ProfilerFiller profiler, CallbackInfo ci
    ) {
        List<Component> result = new ArrayList<>(splashes.size() + 2);
        for (Component splash : splashes) {
            String text = splash.getString();
            if (text.equals("Euclidian!")) {
                result.add(literalSplash("Non-Euclidian!"));
            }
            else if (text.equals("Slow acting portals!")) {
                result.add(literalSplash("Fast acting portals!"));
                result.add(literalSplash("Immersive Portals!"));
            }
            else {
                result.add(splash);
            }
        }
        splashes = List.copyOf(result);
    }
}
