package qouteall.imm_ptl.core.mixin.common.position_sync;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;

/**
 * In 26.x {@link ClientboundPlayerPositionPacket} is a record serialized by a composite
 * {@link StreamCodec}, there is no write(FriendlyByteBuf) method to inject into any more.
 * So the codec is wrapped: after encoding the vanilla fields, the player dimension is written.
 * This wrapper only changes encoding (which only happens on server).
 * The client side reads the dimension after the vanilla fields when decoding
 * (see qouteall.imm_ptl.core.mixin.client.sync.MixinClientboundPlayerPositionPacket).
 */
@Mixin(ClientboundPlayerPositionPacket.class)
public class MixinPlayerPositionLookS2CPacket implements IEPlayerPositionLookS2CPacket {
    @Shadow
    @Final
    @Mutable
    public static StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket> STREAM_CODEC;

    @Unique
    private ResourceKey<Level> playerDimension;

    @Override
    public ResourceKey<Level> ip_getPlayerDimension() {
        return playerDimension;
    }

    @Override
    public void ip_setPlayerDimension(ResourceKey<Level> dimension) {
        playerDimension = dimension;
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void ip_onClassInitWrapEncoding(CallbackInfo ci) {
        StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket> original = STREAM_CODEC;
        STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                original.encode(buf, packet);
                ip_onWrite(buf, packet);
            },
            original::decode
        );
    }

    @Unique
    private static void ip_onWrite(FriendlyByteBuf buf, ClientboundPlayerPositionPacket packet) {
        ResourceKey<Level> dimension =
            ((IEPlayerPositionLookS2CPacket) (Object) packet).ip_getPlayerDimension();
        buf.writeResourceKey(dimension);
    }
}
