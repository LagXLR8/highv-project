package net.huwng.highv.network.packet;

import net.huwng.highv.event.DriftServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: gửi khi người chơi bấm Space (Jump) trong lúc đang Drift (trượt).
 * Server sẽ kích hoạt Slide-Jump: phóng người chơi lên và về phía trước với vận tốc
 * được khuếch đại, chuyển mượt mà vào chuỗi Bhopping trên không.
 */
public record SlideJumpPacket() implements CustomPacketPayload {

    public static final Type<SlideJumpPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("highv", "slide_jump"));

    public static final StreamCodec<FriendlyByteBuf, SlideJumpPacket> STREAM_CODEC =
            StreamCodec.unit(new SlideJumpPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SlideJumpPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
            DriftServerHandler.onSlideJump(serverPlayer);
        });
    }
}
