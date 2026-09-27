package qouteall.imm_ptl.peripheral;

import com.mojang.serialization.MapCodec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.peripheral.alternate_dimension.AlternateDimensions;
import qouteall.imm_ptl.peripheral.alternate_dimension.ChaosBiomeSource;
import qouteall.imm_ptl.peripheral.alternate_dimension.ErrorTerrainGenerator;
import qouteall.imm_ptl.peripheral.alternate_dimension.FormulaGenerator;
import qouteall.imm_ptl.peripheral.alternate_dimension.NormalSkylandGenerator;
import qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement;
import qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration;
import qouteall.imm_ptl.peripheral.wand.ClientPortalWandPortalDrag;
import qouteall.imm_ptl.peripheral.wand.PortalWandInteraction;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem;

import java.util.function.BiConsumer;

public class PeripheralModMain {
    
    // in 26.3 the item/block id must be set in the properties before constructing
    public static final Identifier PORTAL_HELPER_ID =
        McHelper.newResourceLocation("immersive_portals", "portal_helper");
    
    public static final Block portalHelperBlock =
        new Block(
            BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, PORTAL_HELPER_ID))
                .noOcclusion().isRedstoneConductor((a, b, c) -> false)
        );
    
    public static final BlockItem portalHelperBlockItem =
        new PortalHelperItem(
            PeripheralModMain.portalHelperBlock,
            new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, PORTAL_HELPER_ID))
                .useBlockDescriptionPrefix()
        );
    
    public static final CreativeModeTab TAB =
        FabricCreativeModeTab.builder()
            .icon(() -> new ItemStack(PortalWandItem.instance))
            .title(Component.translatable("imm_ptl.item_group"))
            .displayItems((enabledFeatures, entries) -> {
                PortalWandItem.addIntoCreativeTag(entries);
                
                CommandStickItem.addIntoCreativeTag(entries);
                
                entries.accept(PeripheralModMain.portalHelperBlockItem);
            })
            .build();
    
    @Environment(EnvType.CLIENT)
    public static void initClient() {
        IPOuterClientMisc.initClient();
        
        PortalWandItem.initClient();
        
        ClientPortalWandPortalDrag.init();
    }
    
    public static void init() {
        FormulaGenerator.init();
        
        IntrinsicPortalGeneration.init();
        
        DimStackManagement.init();
        
        AlternateDimensions.init();
        
        DimensionAPI.suppressExperimentalWarningForNamespace("immersive_portals");
        
        PortalWandItem.init();
        
        CommandStickItem.init();
        
        PortalWandInteraction.init();
        
        CommandStickItem.registerCommandStickTypes();
        
    }
    
    public static void registerItems(BiConsumer<Identifier, Item> regFunc) {
        regFunc.accept(
            PORTAL_HELPER_ID,
            portalHelperBlockItem
        );
        
        regFunc.accept(
            CommandStickItem.ID,
            CommandStickItem.instance
        );
        
        regFunc.accept(
            PortalWandItem.ID,
            PortalWandItem.instance
        );
    }
    
    public static void registerBlocks(BiConsumer<Identifier, Block> regFunc) {
        regFunc.accept(
            PORTAL_HELPER_ID,
            portalHelperBlock
        );
    }
    
    public static void registerChunkGenerators(
        BiConsumer<Identifier, MapCodec<? extends ChunkGenerator>> regFunc
    ) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals:error_terrain_generator"),
            ErrorTerrainGenerator.MAP_CODEC
        );
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals:normal_skyland_generator"),
            NormalSkylandGenerator.MAP_CODEC
        );
    }
    
    public static void registerBiomeSources(
        BiConsumer<Identifier, MapCodec<? extends BiomeSource>> regFunc
    ) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals:chaos_biome_source"),
            ChaosBiomeSource.MAP_CODEC
        );
    }
    
    public static void registerCreativeTabs(
        BiConsumer<Identifier, CreativeModeTab> regFunc
    ) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "general"),
            TAB
        );
    }
}
