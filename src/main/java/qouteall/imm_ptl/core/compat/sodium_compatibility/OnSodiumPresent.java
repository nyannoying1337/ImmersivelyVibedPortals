package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteUtil;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Only loaded when Sodium is present.
 */
@Environment(EnvType.CLIENT)
public class OnSodiumPresent extends SodiumInterface.Invoker {
    @Override
    public boolean isSodiumPresent() {
        return true;
    }

    @Override
    public void markSpriteActive(TextureAtlasSprite sprite) {
        SpriteUtil.markSpriteActive(sprite);
    }

    // ImmPtl replaces the client chunk cache (ImmPtlClientChunkMap), so Sodium's own hooks in
    // ClientChunkCache don't see chunk loads. The light data flag still comes from Sodium's
    // ClientPacketListener hook.
    @Override
    public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
        ChunkTrackerHolder.get(world)
            .onChunkStatusAdded(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
    }

    @Override
    public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
        ChunkTrackerHolder.get(world)
            .onChunkStatusRemoved(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
    }
}
