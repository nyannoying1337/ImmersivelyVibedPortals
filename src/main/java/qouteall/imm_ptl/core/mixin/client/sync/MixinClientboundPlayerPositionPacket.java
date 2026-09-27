package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;

/**
 * In 26.3 {@link ClientboundPlayerPositionPacket} is a record serialized by a composite
 * {@link StreamCodec}, there is no FriendlyByteBuf constructor to inject into any more.
 * So the codec is wrapped: after decoding the vanilla fields, the player dimension is read.
 * This wrapper only changes decoding (which only happens on client).
 * The server side must write the dimension after the vanilla fields when encoding
 * (see {@link qouteall.imm_ptl.core.mixin.common.position_sync.MixinPlayerPositionLookS2CPacket}).
 */
// TODO(26.3): MixinPlayerPositionLookS2CPacket (position_sync, not owned here) still injects into the removed
//  ClientboundPlayerPositionPacket.write(FriendlyByteBuf). It must instead wrap STREAM_CODEC's encode
//  the same way (write the dimension after the vanilla fields), otherwise this read will desync.
@Mixin(ClientboundPlayerPositionPacket.class)
public class MixinClientboundPlayerPositionPacket {
    @Shadow
    @Final
    @Mutable
    public static StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket> STREAM_CODEC;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void onClassInit(CallbackInfo ci) {
        StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket> original = STREAM_CODEC;
        STREAM_CODEC = StreamCodec.of(
            original::encode,
            buf -> {
                ClientboundPlayerPositionPacket packet = original.decode(buf);
                onRead(buf, packet);
                return packet;
            }
        );
    }

    private static void onRead(FriendlyByteBuf buf, ClientboundPlayerPositionPacket packet) {
        if (ImmPtlNetworkConfig.doesServerHaveImmPtl()) {
            ResourceKey<Level> playerDimension = buf.readResourceKey(Registries.DIMENSION);
            ((IEPlayerPositionLookS2CPacket) (Object) packet).ip_setPlayerDimension(playerDimension);
        }
    }

}
