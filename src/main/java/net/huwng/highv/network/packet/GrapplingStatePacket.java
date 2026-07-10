package net.huwng.highv.network.packet;

import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.item.GrapplingHookItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: gửi mỗi tick khi hook đang tồn tại (flying hoặc attached).
 *
 * Fields:
 *  - pulling  : client có đang giữ chuột phải (false → retract)
 *  - lookX/Z  : horizontal look direction (normalized, Y bỏ qua)
 *  - inputX/Z : WASD input vector trong world-space (normalized hoặc zero nếu không bấm)
 *               Dùng để apply inertia khi không có lực kéo hook
 */
public record GrapplingStatePacket(
        boolean pulling,
        double lookX, double lookY, double lookZ,
        double inputX, double inputZ
) implements CustomPacketPayload {

    public static final Type<GrapplingStatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("highv", "grappling_state"));

    public static final StreamCodec<FriendlyByteBuf, GrapplingStatePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBoolean(pkt.pulling);
                        buf.writeDouble(pkt.lookX);
                        buf.writeDouble(pkt.lookY);
                        buf.writeDouble(pkt.lookZ);
                        buf.writeDouble(pkt.inputX);
                        buf.writeDouble(pkt.inputZ);
                    },
                    buf -> new GrapplingStatePacket(
                            buf.readBoolean(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GrapplingStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;

            GrapplingHookEntity hook =
                    GrapplingHookItem.findActiveHook(serverPlayer.serverLevel(), serverPlayer);
            if (hook == null) return;

            if (!packet.pulling()) {
                hook.retract();
                return;
            }

            Vec3 lookDir  = new Vec3(packet.lookX(),  packet.lookY(),  packet.lookZ());
            Vec3 inputDir = new Vec3(packet.inputX(), 0, packet.inputZ());
            hook.updateClientInput(lookDir, inputDir);
        });
    }
}
