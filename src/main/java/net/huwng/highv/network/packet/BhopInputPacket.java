package net.huwng.highv.network.packet;

import net.huwng.highv.event.BhopServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: gửi mỗi client tick khi người chơi đang mặc giày có
 * enchant Bhopping. Chứa trạng thái phím W/A/D/Space và góc yaw hiện tại,
 * dùng để BhopServerHandler tính Momentum, Air-strafe và Turn-break.
 */
public record BhopInputPacket(
        boolean forward, boolean left, boolean right, boolean jump,
        float yaw
) implements CustomPacketPayload {

    public static final Type<BhopInputPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("highv", "bhop_input"));

    public static final StreamCodec<FriendlyByteBuf, BhopInputPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBoolean(pkt.forward);
                        buf.writeBoolean(pkt.left);
                        buf.writeBoolean(pkt.right);
                        buf.writeBoolean(pkt.jump);
                        buf.writeFloat(pkt.yaw);
                    },
                    buf -> new BhopInputPacket(
                            buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                            buf.readFloat()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BhopInputPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
            BhopServerHandler.updateInput(serverPlayer, packet);
        });
    }
}