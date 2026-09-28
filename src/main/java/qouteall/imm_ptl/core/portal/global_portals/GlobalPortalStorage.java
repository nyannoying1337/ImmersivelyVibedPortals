package qouteall.imm_ptl.core.portal.global_portals;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.ducks.IEClientWorld;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.MiscHelper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Stores global portals.
 * Also stores bedrock replacement block state for dimension stack.
 */
@SuppressWarnings("resource")
public class GlobalPortalStorage extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    // ids for client-side global portals, counting down from far below any real entity id
    private static final AtomicInteger CLIENT_GLOBAL_PORTAL_ID_COUNTER =
        new AtomicInteger(-1_000_000);
    
    public List<Portal> data;
    public final WeakReference<ServerLevel> world;
    private int version = 1;
    private boolean shouldReSync = false;
    
    @Nullable
    public BlockState bedrockReplacement;
    
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register((server) -> {
            server.getAllLevels().forEach(world1 -> {
                GlobalPortalStorage gps = GlobalPortalStorage.get(world1);
                gps.tick();
            });
        });
        
        IPGlobal.SERVER_CLEANUP_EVENT.register((s) -> {
            for (ServerLevel world : s.getAllLevels()) {
                get(world).onServerClose();
            }
        });
        
        DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT.register((server, dims) -> {
            for (ServerLevel world : server.getAllLevels()) {
                GlobalPortalStorage gps = get(world);
                gps.clearAbnormalPortals(server);
                gps.syncToAllPlayers();
            }
        });
        
        if (!O_O.isDedicatedServer()) {
            initClient();
        }
    }
    
    public static GlobalPortalStorage get(
        ServerLevel world
    ) {
        return world.getDataStorage().computeIfAbsent(createSavedDataType(world));
    }

    // TODO(26.3, deferred by user): saved data files are now stored at data/<namespace>/<path>.dat,
    //  so the old saves' data/global_portal.dat is not read. It may need migration.
    private static SavedDataType<GlobalPortalStorage> createSavedDataType(ServerLevel world) {
        return new SavedDataType<>(
            Identifier.fromNamespaceAndPath("immersive_portals", "global_portal"),
            () -> {
                LOGGER.info("Global portal storage initialized {}", world.dimension().identifier());
                return new GlobalPortalStorage(world);
            },
            CompoundTag.CODEC.xmap(
                nbt -> {
                    GlobalPortalStorage globalPortalStorage = new GlobalPortalStorage(world);
                    globalPortalStorage.fromNbt(nbt);
                    return globalPortalStorage;
                },
                storage -> storage.save(new CompoundTag(), world.registryAccess())
            ),
            null // Fabric API allows null DataFixTypes
        );
    }
    
    @Environment(EnvType.CLIENT)
    private static void initClient() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(GlobalPortalStorage::onClientCleanup);
    }
    
    @Environment(EnvType.CLIENT)
    private static void onClientCleanup() {
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel clientWorld : ClientWorldLoader.getClientWorlds()) {
                for (Portal globalPortal : getGlobalPortals(clientWorld)) {
                    globalPortal.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                }
            }
        }
    }
    
    public GlobalPortalStorage(ServerLevel world_) {
        world = new WeakReference<>(world_);
        data = new ArrayList<>();
    }
    
    public static void onPlayerLoggedIn(ServerPlayer player) {
        MiscHelper.getServer().getAllLevels().forEach(
            world -> {
                GlobalPortalStorage storage = get(world);
                if (!storage.data.isEmpty()) {
                    Packet<ClientCommonPacketListener> packet = createSyncPacket(world, storage);
                    player.connection.send(packet);
                }
            }
        );
        
    }
    
    public static Packet<ClientCommonPacketListener> createSyncPacket(
        ServerLevel world, GlobalPortalStorage storage
    ) {
        return ServerPlayNetworking.createClientboundPacket(
            new ImmPtlNetworking.GlobalPortalSyncPacket(
                PortalAPI.serverDimKeyToInt(world.getServer(), world.dimension()),
                storage.save(new CompoundTag(), world.registryAccess())
            )
        );
    }
    
    public void onDataChanged() {
        setDirty(true);
        
        shouldReSync = true;
    }
    
    public void removePortal(Portal portal) {
        data.remove(portal);
        portal.remove(Entity.RemovalReason.KILLED);
        onDataChanged();
    }
    
    public void addPortal(Portal portal) {
        Validate.isTrue(!data.contains(portal));
        
        Validate.isTrue(portal.isPortalValid());
        
        portal.isGlobalPortal = true;
        portal.myUnsetRemoved();
        data.add(portal);
        onDataChanged();
    }
    
    public void removePortals(Predicate<Portal> predicate) {
        data.removeIf(portal -> {
            final boolean shouldRemove = predicate.test(portal);
            if (shouldRemove) {
                portal.remove(Entity.RemovalReason.KILLED);
            }
            return shouldRemove;
        });
        onDataChanged();
    }
    
    private void syncToAllPlayers() {
        ServerLevel currWorld = world.get();
        Validate.notNull(currWorld);
        Packet packet = createSyncPacket(currWorld, this);
        McHelper.getRawPlayerList().forEach(
            player -> player.connection.send(packet)
        );
    }
    
    public void fromNbt(CompoundTag tag) {
        
        ServerLevel currWorld = world.get();
        Validate.notNull(currWorld, "world is null");
        
        List<Portal> newData = getPortalsFromTag(tag, currWorld);
        data = newData;
        
        if (tag.contains("version")) {
            version = tag.getIntOr("version", 0);
        }
        
        if (tag.contains("bedrockReplacement")) {
            bedrockReplacement = NbtUtils.readBlockState(
                currWorld.holderLookup(Registries.BLOCK),
                tag.getCompoundOrEmpty("bedrockReplacement")
            );
        }
        else {
            bedrockReplacement = null;
        }
        
        clearAbnormalPortals(currWorld.getServer());
    }
    
    private static List<Portal> getPortalsFromTag(
        CompoundTag tag,
        Level currWorld
    ) {
        /**{@link CompoundTag#getType()}*/
        ListTag listTag = tag.getListOrEmpty("data");
        
        List<Portal> newData = new ArrayList<>();
        
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag compoundTag = listTag.getCompoundOrEmpty(i);
            Portal e = readPortalFromTag(currWorld, compoundTag);
            if (e != null) {
                newData.add(e);
            }
            else {
                Helper.err("error reading portal" + compoundTag);
            }
        }
        return newData;
    }
    
    private static Portal readPortalFromTag(Level currWorld, CompoundTag compoundTag) {
        Identifier entityId = McHelper.newResourceLocation(compoundTag.getStringOr("entity_type", ""));
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(entityId);

        Entity e = entityType.create(currWorld, EntitySpawnReason.LOAD);
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            e.load(TagValueInput.create(reporter, currWorld.registryAccess(), compoundTag));
        }
        
        ((Portal) e).isGlobalPortal = true;

        // In 26.3 a client-side entity gets id 0 until the server assigns one, and getId()/hashCode()/equals()
        // throw for id 0. Global portals are never in the level's entity list, so give them unique ids
        // that can't collide with real entities.
        if (currWorld.isClientSide()) {
            e.setId(CLIENT_GLOBAL_PORTAL_ID_COUNTER.decrementAndGet());
        }

        // normal portals' bounding boxes are limited
        // update to non-limited bounding box
        ((Portal) e).updateCache();
        
        return (Portal) e;
    }
    
    public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (data == null) {
            return tag;
        }
        
        ListTag listTag = new ListTag();
        ServerLevel currWorld = world.get();
        Validate.notNull(currWorld, "world is null");
        
        for (Portal portal : data) {
            Validate.isTrue(portal.level() == currWorld);
            CompoundTag portalTag;
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
                TagValueOutput output = TagValueOutput.createWithContext(reporter, currWorld.registryAccess());
                portal.saveWithoutId(output);
                portalTag = output.buildResult();
            }
            portalTag.putString(
                "entity_type",
                EntityType.getKey(portal.getType()).toString()
            );
            listTag.add(portalTag);
        }
        
        tag.put("data", listTag);
        
        tag.putInt("version", version);
        
        if (bedrockReplacement != null) {
            tag.put("bedrockReplacement", NbtUtils.writeBlockState(bedrockReplacement));
        }
        
        return tag;
    }
    
    public void tick() {
        if (shouldReSync) {
            syncToAllPlayers();
            shouldReSync = false;
        }
        
        if (version <= 1) {
            upgradeData(world.get());
            version = 2;
            setDirty(true);
        }
    }
    
    public void clearAbnormalPortals(MinecraftServer server) {
        data.removeIf(e -> {
            ResourceKey<Level> dimensionTo = ((Portal) e).getDestDim();
            if (server.getLevel(dimensionTo) == null) {
                LOGGER.error("Missing Dimension for global portal {}", dimensionTo.identifier());
                return true;
            }
            return false;
        });
    }
    
    private static void upgradeData(ServerLevel world) {
        //removed
    }
    
    @Environment(EnvType.CLIENT)
    public static void receiveGlobalPortalSync(ResourceKey<Level> dimension, CompoundTag compoundTag) {
        ClientLevel world = ClientWorldLoader.getWorld(dimension);
        
        List<Portal> oldGlobalPortals = ((IEClientWorld) world).ip_getGlobalPortals();
        if (oldGlobalPortals != null) {
            for (Portal p : oldGlobalPortals) {
                p.remove(Entity.RemovalReason.KILLED);
            }
        }
        
        List<Portal> newPortals = getPortalsFromTag(compoundTag, world);
        for (Portal p : newPortals) {
            p.myUnsetRemoved();
            p.isGlobalPortal = true;
            
            Validate.isTrue(p.isPortalValid());
            
            ClientWorldLoader.getWorld(p.getDestDim());
        }
        
        ((IEClientWorld) world).ip_setGlobalPortals(newPortals);
        
        LOGGER.info("Global Portals Updated {}", dimension.identifier());
    }
    
    public static void convertNormalPortalIntoGlobalPortal(Portal portal) {
        Validate.isTrue(!portal.getIsGlobal());
        Validate.isTrue(!portal.level().isClientSide());
        
        // global portal can only be square
        portal.setPortalShapeToDefault();
        
        portal.remove(Entity.RemovalReason.KILLED);
        
        Portal newPortal = McHelper.copyEntity(portal);
        
        get(((ServerLevel) portal.level())).addPortal(newPortal);
    }
    
    public static void convertGlobalPortalIntoNormalPortal(Portal portal) {
        Validate.isTrue(portal.getIsGlobal());
        Validate.isTrue(!portal.level().isClientSide());
        
        get(((ServerLevel) portal.level())).removePortal(portal);
        
        Portal newPortal = McHelper.copyEntity(portal);
        
        McHelper.spawnServerEntity(newPortal);
    }
    
    private void onServerClose() {
        for (Portal portal : data) {
            portal.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        }
    }
    
    @NotNull
    public static List<Portal> getGlobalPortals(Level world) {
        List<Portal> result;
        if (world.isClientSide()) {
            result = CHelper.getClientGlobalPortal(world);
        }
        else if (world instanceof ServerLevel) {
            result = get(((ServerLevel) world)).data;
        }
        else {
            result = null;
        }
        return result != null ? result : Collections.emptyList();
    }
}
