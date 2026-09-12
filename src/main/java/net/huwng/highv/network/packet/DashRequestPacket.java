package net.huwng.highv.network.packet;

import net.huwng.highv.event.DashServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: gửi ĐÚNG 1 LẦN khi người chơi vừa bấm Shift (cạnh lên)
 * trong khi KHÔNG giữ phím di chuyển nào (W/A/S/D), và đang mặc giày có
 * enchant Dash. Chứa sẵn vector hướng nhìn 3D (đã normalize) tại thời điểm
 * đó — dash luôn phóng theo đúng hướng đang nhìn, kể cả lên/xuống.
 */
public record DashRequestPacket(float lookX, float lookY, float lookZ) implements CustomPacketPayload {

    public static final Type<DashRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("highv", "dash_request"));

    public static final StreamCodec<FriendlyByteBuf, DashRequestPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeFloat(pkt.lookX);
                        buf.writeFloat(pkt.lookY);
                        buf.writeFloat(pkt.lookZ);
                    },
                    buf -> new DashRequestPacket(buf.readFloat(), buf.readFloat(), buf.readFloat())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DashRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
            DashServerHandler.onDashRequest(serverPlayer, packet);
        });
    }
}