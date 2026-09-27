package qouteall.imm_ptl.core.platform_specific;

import me.shedaniel.autoconfig.AutoConfigClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;

@Environment(EnvType.CLIENT)
public class IPConfigGUI {
    public static Screen createClothConfigScreen(Screen parent) {
        return AutoConfigClient.getConfigScreen(IPConfig.class, parent).get();
    }
}
