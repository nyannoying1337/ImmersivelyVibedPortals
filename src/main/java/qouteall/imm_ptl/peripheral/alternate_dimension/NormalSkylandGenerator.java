package qouteall.imm_ptl.peripheral.alternate_dimension;

import com.google.common.base.Suppliers;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkGenerator_AlternateDim;
import qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IENoiseRouterData;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * It extends NoiseBasedChunkGenerator, because in
 * {@link ChunkMap}'s constructor
 * it uses instanceof to initialize random source.
 */
public class NormalSkylandGenerator extends NoiseBasedChunkGenerator {
    
    public static final MapCodec<NormalSkylandGenerator> MAP_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                RegistryOps.retrieveGetter(Registries.BIOME),
                RegistryOps.retrieveGetter(Registries.DENSITY_FUNCTION),
                RegistryOps.retrieveGetter(Registries.NOISE),
                RegistryOps.retrieveGetter(Registries.NOISE_SETTINGS),
                RegistryOps.retrieveGetter(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(g -> g.seed)
            )
            .apply(instance, NormalSkylandGenerator::create)
    );
    
    private RandomState delegatedRandomState;
    
    private final HolderGetter<Biome> biomeHolderGetter;
    private final HolderGetter<DensityFunction> densityFunctionHolderGetter;
    private final HolderGetter<NormalNoise> noiseParametersHolderGetter;
    private final long seed;
    
    public NormalSkylandGenerator(
        BiomeSource biomeSource,
        Holder<NoiseGeneratorSettings> noiseGeneratorSettings,
        
        NoiseBasedChunkGenerator delegate,
        
        HolderGetter<Biome> biomeHolderGetter,
        HolderGetter<DensityFunction> densityFunctionHolderGetter,
        HolderGetter<NormalNoise> noiseParametersHolderGetter,
        long seed
    ) {
        super(biomeSource, noiseGeneratorSettings);
        
        this.delegate = delegate;
        this.biomeHolderGetter = biomeHolderGetter;
        this.densityFunctionHolderGetter = densityFunctionHolderGetter;
        this.noiseParametersHolderGetter = noiseParametersHolderGetter;
        this.seed = seed;
        
        this.delegatedRandomState = RandomState.create(
            noiseParametersHolderGetter,
            seed,
            delegate.generatorSettings().value()
        );
    }
    
    public static NormalSkylandGenerator create(
        HolderGetter<Biome> biomeHolderGetter,
        HolderGetter<DensityFunction> densityFunctionHolderGetter,
        HolderGetter<NormalNoise> noiseParametersHolderGetter,
        HolderGetter<NoiseGeneratorSettings> noiseGeneratorSettingsHolderGetter,
        HolderGetter<MultiNoiseBiomeSourceParameterList> biomeParamListLookup,
        long seed
    ) {
        Holder.Reference<MultiNoiseBiomeSourceParameterList> overworldBiomeParamList =
            biomeParamListLookup.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        
        MultiNoiseBiomeSource overworldBiomeSource =
            MultiNoiseBiomeSource.createFromPreset(overworldBiomeParamList);
        
        NoiseGeneratorSettings overworldNGS = noiseGeneratorSettingsHolderGetter
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        
        NoiseGeneratorSettings intrinsicSkylandNGS = noiseGeneratorSettingsHolderGetter
            .getOrThrow(NoiseGeneratorSettings.FLOATING_ISLANDS).value();

//        NoiseGeneratorSettings endNGS = IENoiseGeneratorSettings.ip_end();
        
        NoiseGeneratorSettings usedSkylandNGS = new NoiseGeneratorSettings(
            intrinsicSkylandNGS.noiseSettings(),
            intrinsicSkylandNGS.defaultBlock(),
            intrinsicSkylandNGS.defaultFluid(),
            noNewCaves(
                IENoiseRouterData.ip_slideEndLike(IENoiseRouterData.ip_getFunction(
                    densityFunctionHolderGetter, IENoiseRouterData.get_BASE_3D_NOISE_END()
                ), 0, 128)
            ),
            // in 1.21.1 the surface was built by this generator (using the overworld surface rule),
            // in 26.3 the surface is built by the delegate in buildTerrain, so use the overworld rule here
            overworldNGS.materialRule(),
            intrinsicSkylandNGS.spawnTarget(),
            0, // overwrite seaLevel
            intrinsicSkylandNGS.disableMobGeneration(),
            intrinsicSkylandNGS.aquifers(),
            intrinsicSkylandNGS.useLegacyRandomSource(),
            NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        
        NoiseBasedChunkGenerator skylandGenerator = new NoiseBasedChunkGenerator(
            overworldBiomeSource, Holder.direct(usedSkylandNGS)
        );
        
        NormalSkylandGenerator result = new NormalSkylandGenerator(
            overworldBiomeSource,
            Holder.direct(overworldNGS),
            skylandGenerator,
            biomeHolderGetter,
            densityFunctionHolderGetter,
            noiseParametersHolderGetter,
            seed
        );
        
        ((IEChunkGenerator_AlternateDim) result).ip_setFeaturesPerStep(
            Suppliers.memoize(
                () -> FeatureSorter.buildFeaturesPerStep(
                    List.copyOf(overworldBiomeSource.possibleBiomes()),
                    holder -> {
                        Biome biome = holder.value();
                        BiomeGenerationSettings bgs = biome.getGenerationSettings();
                        List<HolderSet<PlacedFeature>> features = bgs.features();
                        // TODO modify feature
                        return features;
                    },
                    true
                )
            )
        );
        
        return result;
    }
    
    private final NoiseBasedChunkGenerator delegate;
    
    @Override
    protected @NotNull MapCodec<? extends ChunkGenerator> codec() {
        return MAP_CODEC;
    }
    
    /**
     * Replacement of the vanilla NoiseRouterData.noNewCaves() which was removed in 26.3.
     * Follows 26.3 {@link NoiseRouterData#floatingIslands}.
     */
    private static NoiseRouter noNewCaves(DensityFunction slide) {
        return NoiseRouterData.simpleRouter(
            DensityFunctions.add(NoiseRouterData.postProcess(slide, 8, 4), DensityFunctions.beardifier())
        );
    }
    
    // In 26.3, fillFromNoise, buildSurface and applyCarvers are merged into buildTerrain.
    // The noise chunk is no longer cached in ChunkAccess, so it doesn't need to be reset.
    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(
        ChunkAccess chunkAccess, Blender blender, RandomState pRandomState,
        StructureManager structureManager, BiomeManager biomeManager,
        @Nullable WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes
    ) {
        return delegate.buildTerrain(
            chunkAccess, blender, delegatedRandomState, structureManager, biomeManager,
            carverBiomeRegion, possibleBiomes
        );
    }
    
    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSetLookup, RandomState randomState, long seed) {
        // filter the mineshaft out
        // cannot use HolderLookup.filterElements because it does not provide id in predicate
        HolderLookup<StructureSet> structureSetLookupDelegate = new HolderLookup<StructureSet>() {
            @Override
            public Stream<Holder.Reference<StructureSet>> listElements() {
                return structureSetLookup.listElements().filter(
                    holder -> !holder.key().identifier().getPath().equals("mineshafts")
                );
            }
            
            @Override
            public Stream<HolderSet.Named<StructureSet>> listTags() {
                return structureSetLookup.listTags();
            }
            
            @Override
            public Optional<Holder.Reference<StructureSet>> get(ResourceKey<StructureSet> resourceKey) {
                return structureSetLookup.get(resourceKey);
            }
            
            @Override
            public Optional<HolderSet.Named<StructureSet>> get(TagKey<StructureSet> tagKey) {
                return structureSetLookup.get(tagKey);
            }
        };
        
        return super.createState(structureSetLookupDelegate, randomState, seed);
    }
}
