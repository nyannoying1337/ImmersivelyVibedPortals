package qouteall.imm_ptl.core.mixin.common.mc_util;

import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

@Mixin(SavedDataStorage.class)
public interface IESavedDataStorage {
    @Accessor("dataFolder")
    Path ip_getDataFolder();
}
