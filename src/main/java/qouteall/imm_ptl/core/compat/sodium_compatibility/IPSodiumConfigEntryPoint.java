package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import qouteall.imm_ptl.core.platform_specific.ConfigPreset;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.platform_specific.IPConfigGUI;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Adds the most used settings to Sodium's video settings screen (Sodium's config API, entrypoint
 * "sodium:config_api_user" in fabric.mod.json; only loaded by Sodium). The values are IPConfig's: saving here saves
 * the config file, which applies a chosen preset (IPConfig.onConfigChanged). All other settings are behind the
 * "All Settings" button (the Cloth Config screen).
 */
@Environment(EnvType.CLIENT)
public class IPSodiumConfigEntryPoint implements ConfigEntryPoint {
    private static final String NS = "immersive_portals";
    private static final int THEME_COLOR = 0x9B6BFF;

    private final StorageEventHandler storage = () -> IPConfig.getConfig().saveConfigFile();

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        IPConfig defaults = new IPConfig();

        builder.registerOwnModOptions()
            .setColorTheme(builder.createColorTheme().setBaseThemeRGB(THEME_COLOR))
            .setNonTintedIcon(Identifier.fromNamespaceAndPath(NS, "icon.png"))
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("imm_ptl.sodium_options.page"))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createEnumOption(id("preset"), ConfigPreset.class)
                        .setStorageHandler(storage)
                        .setName(optionName("preset"))
                        .setTooltip(tooltip("preset"))
                        .setElementNameProvider(preset -> Component.translatable("imm_ptl.sodium_options.preset." + preset.name()))
                        .setDefaultValue(defaults.preset)
                        .setBinding(v -> IPConfig.getConfig().preset = v, () -> IPConfig.getConfig().preset)
                        .setImpact(OptionImpact.VARIES)
                    )
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("imm_ptl.sodium_options.group.performance"))
                    .addOption(intOption(builder, "maxPortalLayer", 0, 10, defaults.maxPortalLayer,
                        v -> IPConfig.getConfig().maxPortalLayer = v, () -> IPConfig.getConfig().maxPortalLayer)
                        .setImpact(OptionImpact.HIGH))
                    .addOption(booleanOption(builder, "reducedPortalRendering", defaults.reducedPortalRendering,
                        v -> IPConfig.getConfig().reducedPortalRendering = v, () -> IPConfig.getConfig().reducedPortalRendering)
                        .setImpact(OptionImpact.HIGH))
                    .addOption(booleanOption(builder, "reduceFarPortalUpdates", defaults.reduceFarPortalUpdates,
                        v -> IPConfig.getConfig().reduceFarPortalUpdates = v, () -> IPConfig.getConfig().reduceFarPortalUpdates)
                        .setImpact(OptionImpact.MEDIUM))
                    .addOption(booleanOption(builder, "enableClientPerformanceAdjustment", defaults.enableClientPerformanceAdjustment,
                        v -> IPConfig.getConfig().enableClientPerformanceAdjustment = v, () -> IPConfig.getConfig().enableClientPerformanceAdjustment)
                        .setImpact(OptionImpact.MEDIUM))
                    .addOption(intOption(builder, "indirectLoadingRadiusCap", 1, 32, defaults.indirectLoadingRadiusCap,
                        v -> IPConfig.getConfig().indirectLoadingRadiusCap = v, () -> IPConfig.getConfig().indirectLoadingRadiusCap)
                        .setImpact(OptionImpact.MEDIUM))
                    .addOption(booleanOption(builder, "renderYourselfInPortal", defaults.renderYourselfInPortal,
                        v -> IPConfig.getConfig().renderYourselfInPortal = v, () -> IPConfig.getConfig().renderYourselfInPortal)
                        .setImpact(OptionImpact.LOW))
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("imm_ptl.sodium_options.group.visuals"))
                    .addOption(booleanOption(builder, "correctCrossPortalEntityRendering", defaults.correctCrossPortalEntityRendering,
                        v -> IPConfig.getConfig().correctCrossPortalEntityRendering = v, () -> IPConfig.getConfig().correctCrossPortalEntityRendering)
                        .setImpact(OptionImpact.LOW))
                    .addOption(booleanOption(builder, "netherPortalOverlay", defaults.netherPortalOverlay,
                        v -> IPConfig.getConfig().netherPortalOverlay = v, () -> IPConfig.getConfig().netherPortalOverlay)
                        .setImpact(OptionImpact.LOW))
                    .addOption(booleanOption(builder, "enableNetherPortalEffect", defaults.enableNetherPortalEffect,
                        v -> IPConfig.getConfig().enableNetherPortalEffect = v, () -> IPConfig.getConfig().enableNetherPortalEffect)
                        .setImpact(OptionImpact.LOW))
                    .addOption(booleanOption(builder, "enableCrossPortalSound", defaults.enableCrossPortalSound,
                        v -> IPConfig.getConfig().enableCrossPortalSound = v, () -> IPConfig.getConfig().enableCrossPortalSound)
                        .setImpact(OptionImpact.LOW))
                )
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createExternalButtonOption(id("all_settings"))
                        .setName(Component.translatable("imm_ptl.sodium_options.all_settings"))
                        .setTooltip(Component.translatable("imm_ptl.sodium_options.all_settings.tooltip"))
                        .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(IPConfigGUI.createClothConfigScreen(parent)))
                    )
                )
            );
    }

    private BooleanOptionBuilder booleanOption(
        ConfigBuilder builder, String field, boolean defaultValue, Consumer<Boolean> save, Supplier<Boolean> load
    ) {
        return builder.createBooleanOption(id(field))
            .setStorageHandler(storage)
            .setName(optionName(field))
            .setTooltip(tooltip(field))
            .setDefaultValue(defaultValue)
            .setBinding(save, load);
    }

    private IntegerOptionBuilder intOption(
        ConfigBuilder builder, String field, int min, int max, int defaultValue, Consumer<Integer> save, Supplier<Integer> load
    ) {
        return builder.createIntegerOption(id(field))
            .setStorageHandler(storage)
            .setName(optionName(field))
            .setTooltip(tooltip(field))
            .setRange(min, max, 1)
            .setValueFormatter(v -> Component.literal(String.valueOf(v)))
            .setDefaultValue(defaultValue)
            .setBinding(save, load);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NS, path.toLowerCase(java.util.Locale.ROOT));
    }

    // shorter than in the Cloth Config screen (Sodium's option rows are narrow)
    private static Component optionName(String field) {
        return Component.translatable("imm_ptl.sodium_options." + field);
    }

    private static Component tooltip(String field) {
        return Component.translatable("imm_ptl.sodium_options." + field + ".tooltip");
    }
}
