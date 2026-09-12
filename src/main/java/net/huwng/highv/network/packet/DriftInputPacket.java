package net.huwng.highv.network.packet;

import net.huwng.highv.event.DriftServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: gửi mỗi client tick khi người chơi đang mặc giày có
 * enchant Drift. Chứa đúng 1 trạng thái — có đang giữ phím Drift (mặc định
 * Left Ctrl, xem ModKeyMappings.DRIFT) hay không. DriftServerHandler dùng
 * giá trị này thay cho player.isShiftKeyDown() vì Drift dùng keybind RIÊNG,
 * không phải phím Sneak vanilla.
 */
public record DriftInputPacket(boolean holding) implements CustomPacketPayload {

    public static final Type<DriftInputPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("highv", "drift_input"));

    public static final StreamCodec<FriendlyByteBuf, DriftInputPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeBoolean(pkt.holding),
                    buf -> new DriftInputPacket(buf.readBoolean())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DriftInputPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
            DriftServerHandler.updateHolding(serverPlayer, packet.holding());
        });
    }
}
