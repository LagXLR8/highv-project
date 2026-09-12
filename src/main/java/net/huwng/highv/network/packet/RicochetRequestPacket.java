package net.huwng.highv.network.packet;

import net.huwng.highv.event.RicochetServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: gửi ĐÚNG 1 LẦN khi người chơi vừa bấm Space (cạnh lên)
 * trong khi đang mặc giày có enchant Ricochet. Không cần chứa dữ liệu gì —
 * hướng đẩy được xác định hoàn toàn ở server dựa theo wall normal đang lưu
 * trong WallstrideServerHandler (null nếu hiện không đang chạy tường, lúc
 * đó Ricochet không có tác dụng gì).
 */
public record RicochetRequestPacket() implements CustomPacketPayload {

    public static final Type<RicochetRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("highv", "ricochet_request"));

    public static final StreamCodec<FriendlyByteBuf, RicochetRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new RicochetRequestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RicochetRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
            RicochetServerHandler.onRicochetRequest(serverPlayer);
        });
    }
}
