package net.huwng.highv.network.packet;

import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.entity.ModEntities;
import net.huwng.highv.item.GrapplingHookItem;
import net.huwng.highv.item.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: yêu cầu bắn hook đến target position hoặc target entity.
 *
 * Nếu targetEntityId >= 0 → bắn vào entity, bỏ qua targetX/Y/Z.
 * Nếu targetX != Double.MAX_VALUE → bắn vào block position.
 * Còn lại → bắn theo hướng nhìn server-side (không detect được target).
 */
public record GrapplingShootPacket(double targetX, double targetY, double targetZ, int targetEntityId)
        implements CustomPacketPayload {

    public static final Type<GrapplingShootPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("highv", "grappling_shoot"));

    public static final StreamCodec<FriendlyByteBuf, GrapplingShootPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeDouble(pkt.targetX);
                        buf.writeDouble(pkt.targetY);
                        buf.writeDouble(pkt.targetZ);
                        buf.writeInt(pkt.targetEntityId);
                    },
                    buf -> new GrapplingShootPacket(
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readInt())
            );

    /** Bắn vào block position */
    public static GrapplingShootPacket forBlock(double x, double y, double z) {
        return new GrapplingShootPacket(x, y, z, -1);
    }

    /** Bắn vào entity */
    public static GrapplingShootPacket forEntity(int entityId) {
        return new GrapplingShootPacket(Double.MAX_VALUE, 0, 0, entityId);
    }

    /** Bắn mù (không detect được target) */
    public static GrapplingShootPacket noTarget() {
        return new GrapplingShootPacket(Double.MAX_VALUE, 0, 0, -1);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(GrapplingShootPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
            if (!serverPlayer.getOffhandItem().is(ModItems.GRAPPLING_HOOK.get())) return;

            // Xóa hook cũ
            GrapplingHookEntity existing =
                    GrapplingHookItem.findActiveHook(serverPlayer.serverLevel(), serverPlayer);
            if (existing != null) existing.retract();

            Vec3 eyePos = serverPlayer.getEyePosition();
            GrapplingHookEntity hook = new GrapplingHookEntity(
                    ModEntities.GRAPPLING_HOOK.get(), serverPlayer.serverLevel());
            hook.setOwner(serverPlayer);
            hook.setPos(eyePos.x, eyePos.y, eyePos.z);

            if (packet.targetEntityId() >= 0) {
                // ── Bắn vào entity ──────────────────────────────────────────
                Entity target = serverPlayer.serverLevel().getEntity(packet.targetEntityId());
                if (target == null || target.isRemoved()) {
                    // Entity không còn tồn tại server-side → bắn mù
                    Vec3 look = serverPlayer.getLookAngle();
                    hook.setDeltaMovement(look.scale(GrapplingHookItem.SHOOT_SPEED));
                } else {
                    Vec3 targetCenter = target.position().add(0, target.getBbHeight() * 0.5, 0);
                    Vec3 dir = targetCenter.subtract(eyePos).normalize();
                    hook.setDeltaMovement(dir.scale(GrapplingHookItem.SHOOT_SPEED));
                    hook.setTargetEntityId(target.getId());
                    // Không set targetPos — hook sẽ bay đến khi canHitEntity() trả về true
                }

            } else if (packet.targetX() != Double.MAX_VALUE) {
                // ── Bắn vào block position ───────────────────────────────────
                Vec3 target = new Vec3(packet.targetX(), packet.targetY(), packet.targetZ());
                Vec3 dir    = target.subtract(eyePos).normalize();
                hook.setDeltaMovement(dir.scale(GrapplingHookItem.SHOOT_SPEED));
                hook.setTarget(target);

            } else {
                // ── Bắn mù theo hướng nhìn ──────────────────────────────────
                Vec3 look = serverPlayer.getLookAngle();
                hook.setDeltaMovement(look.scale(GrapplingHookItem.SHOOT_SPEED));
            }

            serverPlayer.serverLevel().addFreshEntity(hook);
            serverPlayer.getOffhandItem().hurtAndBreak(1, serverPlayer, EquipmentSlot.OFFHAND);
        });
    }
}
