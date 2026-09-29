package qouteall.q_misc_util;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.McHelper;
import qouteall.q_misc_util.dimension.DimIntIdMap;
import qouteall.q_misc_util.dimension.DimensionIntId;

public class MiscNetworking {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /**
     * @param seaLevelTag dimension id to sea level. Vanilla only tells the client the sea level of the dimension
     *                    it's in; ImmPtl's client worlds of other dimensions use this.
     */
    public static record DimIdSyncPacket(
        CompoundTag dimIntIdTag,
        CompoundTag dimTypeTag,
        CompoundTag seaLevelTag
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DimIdSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(
                McHelper.newResourceLocation("imm_ptl:dim_int_id_sync")
            );
        
        public static final StreamCodec<FriendlyByteBuf, DimIdSyncPacket> CODEC =
            StreamCodec.of(
                (b, p) -> p.write(b), DimIdSyncPacket::read
            );
        
        public static DimIdSyncPacket createFromServer(MinecraftServer server) {
            DimIntIdMap rec = DimensionIntId.getServerMap(server);
            CompoundTag dimIntIdTag = rec.toTag(dim -> true);
            
            RegistryAccess registryManager = server.registryAccess();
            Registry<DimensionType> dimensionTypes = registryManager.lookupOrThrow(Registries.DIMENSION_TYPE);
            
            CompoundTag dimIdToDimTypeIdTag = new CompoundTag();
            CompoundTag seaLevelTag = new CompoundTag();
            for (ServerLevel world : server.getAllLevels()) {
                seaLevelTag.putInt(world.dimension().identifier().toString(), world.getSeaLevel());

                ResourceKey<Level> dimId = world.dimension();
                
                DimensionType dimType = world.dimensionType();
                Identifier dimTypeId = dimensionTypes.getKey(dimType);
                
                if (dimTypeId == null) {
                    LOGGER.error("Cannot find dimension type for {}", dimId.identifier());
                    LOGGER.error(
                        "Registered dimension types {}", dimensionTypes.keySet()
                    );
                    dimTypeId = BuiltinDimensionTypes.OVERWORLD.identifier();
                }
                
                dimIdToDimTypeIdTag.putString(
                    dimId.identifier().toString(),
                    dimTypeId.toString()
                );
            }
            
            return new DimIdSyncPacket(dimIntIdTag, dimIdToDimTypeIdTag, seaLevelTag);
        }
        
        public static Packet<ClientCommonPacketListener> createPacket(MinecraftServer server) {
            return ServerPlayNetworking.createClientboundPacket(
                DimIdSyncPacket.createFromServer(server)
            );
        }
        
        public void write(FriendlyByteBuf buf) {
            buf.writeNbt(dimIntIdTag);
            buf.writeNbt(dimTypeTag);
            buf.writeNbt(seaLevelTag);
        }
        
        public static DimIdSyncPacket read(FriendlyByteBuf buf) {
            CompoundTag idMapTag = buf.readNbt();
            CompoundTag typeTag = buf.readNbt();
            CompoundTag seaLevelTag = buf.readNbt();
            
            return new DimIdSyncPacket(idMapTag, typeTag, seaLevelTag);
        }
        
        public void handle() {
            DimIntIdMap rec = DimIntIdMap.fromTag(dimIntIdTag);
            LOGGER.info("Client received dim id sync packet\n{}", rec);
            DimensionIntId.clientRecord = rec;
            
            ImmutableMap.Builder<ResourceKey<Level>, ResourceKey<DimensionType>> builder =
                new ImmutableMap.Builder<>();
            
            for (String key : dimTypeTag.keySet()) {
                ResourceKey<Level> dimId = ResourceKey.create(
                    Registries.DIMENSION,
                    McHelper.newResourceLocation(key)
                );
                String dimTypeId = dimTypeTag.getStringOr(key, "");
                ResourceKey<DimensionType> dimType = ResourceKey.create(
                    Registries.DIMENSION_TYPE,
                    McHelper.newResourceLocation(dimTypeId)
                );
                builder.put(dimId, dimType);
            }
            
            var dimTypeMap = builder.build();
            ClientWorldLoader.dimIdToDimTypeId = dimTypeMap;

            ImmutableMap.Builder<ResourceKey<Level>, Integer> seaLevels = new ImmutableMap.Builder<>();
            for (String key : seaLevelTag.keySet()) {
                seaLevels.put(
                    ResourceKey.create(Registries.DIMENSION, McHelper.newResourceLocation(key)),
                    seaLevelTag.getIntOr(key, 63)
                );
            }
            ClientWorldLoader.dimIdToSeaLevel = seaLevels.build();
            LOGGER.info(
                "Client accepted dimension type mapping {}",
                dimTypeMap
            );
        }
        
        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    
    @Environment(EnvType.CLIENT)
    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(
            DimIdSyncPacket.TYPE,
            (p, c) -> {
                p.handle();
            }
        );
    }
    
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(
            DimIdSyncPacket.TYPE, DimIdSyncPacket.CODEC
        );
    }
}
