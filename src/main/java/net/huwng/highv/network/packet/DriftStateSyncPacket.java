package net.huwng.highv.network.packet;

import net.huwng.highv.client.animation.DriftAnimationHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → Client: broadcast MỖI KHI trạng thái trượt của 1 player THAY ĐỔI
 * (bắt đầu/kết thúc — KHÔNG gửi mỗi tick, chỉ gửi lúc đổi). Gửi cho mọi
 * client đang track player đó (kể cả chính player).
 *
 * LÝ DO CẦN PACKET RIÊNG: ban đầu DriftAnimationHandler suy ra trạng thái
 * trượt bằng cách đọc {@code player.getPose() == Pose.SWIMMING} (vì Pose vốn
 * đã được đồng bộ sẵn cho hitbox) — nhưng cách này không ổn định: vanilla có
 * logic riêng (Player.updatePlayerPose(), chạy mỗi tick TRƯỚC
 * PlayerTickEvent.Post của DriftServerHandler) tự đánh giá lại Pose dựa trên
 * isVisuallySwimming()/isCrouching()/isFallFlying() — vì Drift không thật sự
 * bật cờ "isSwimming" (chỉ ép enum Pose thôi), vanilla liên tục cố trả Pose
 * về STANDING, tạo ra tranh chấp mỗi tick không đáng tin cậy để animation
 * dựa vào. Packet này tách hẳn tín hiệu "đang trượt cho animation" ra khỏi
 * Pose — DriftServerHandler chủ động gửi đúng lúc bắt đầu/kết thúc, không
 * phụ thuộc gì vào việc Pose có bị vanilla giành ghi đè hay không.
 */
public record DriftStateSyncPacket(UUID playerId, boolean sliding) implements CustomPacketPayload {

    public static final Type<DriftStateSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("highv", "drift_state_sync"));

    public static final StreamCodec<FriendlyByteBuf, DriftStateSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.playerId);
                        buf.writeBoolean(pkt.sliding);
                    },
                    buf -> new DriftStateSyncPacket(buf.readUUID(), buf.readBoolean())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DriftStateSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                DriftAnimationHandler.setSlidingState(packet.playerId(), packet.sliding()));
    }
}