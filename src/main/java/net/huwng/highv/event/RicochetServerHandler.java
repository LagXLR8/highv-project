package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.enchantment.ModEnchantments;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Xử lý enchant "Ricochet" trên giày.
 *
 * Ricochet CHỈ hoạt động khi người chơi đang bám tường nhờ enchant
 * Wallstride (WallstrideServerHandler.getOutwardWallNormal trả về khác
 * null) VÀ vừa bấm Space (xem RicochetRequestPacket, gửi 1 lần mỗi cạnh lên
 * của Space từ client). Không bám tường -> bấm Space không có tác dụng gì.
 *
 * Hướng đẩy: chính là wall normal HƯỚNG RA NGOÀI mà Wallstride đang lưu cho
 * người chơi đó (vuông góc mặt tường, trỏ từ tường ra phía người chơi) —
 * tức "hướng ngược lại với bức tường đang chạy".
 */
public final class RicochetServerHandler {

    /** Tốc độ đẩy ra khỏi tường, block/tick. 1.3 ≈ 26 b/s — mạnh hơn hẳn tốc độ chạy tường (10 b/s). */
    private static final double RICOCHET_STRENGTH = 1.8;

    /** Số tick tạm ngừng Wallstride sau khi đẩy ra, tránh bị bám dính lại tường ngay lập tức. */
    private static final int WALLSTRIDE_SUPPRESS_TICKS = 7;

    /** Bật log debug. Tắt khi đã ổn. */
    private static final boolean DEBUG = false;

    private RicochetServerHandler() {}

    /** Gọi từ RicochetRequestPacket.handle() khi client vừa bấm Space. */
    public static void onRicochetRequest(ServerPlayer player) {
        if (ModEnchantments.getRicochetLevel(player) <= 0) return;

        Vec3 outwardNormal = WallstrideServerHandler.getOutwardWallNormal(player);
        if (outwardNormal == null) return; // không đang chạy tường -> không có tác dụng

        Vec3 vel = player.getDeltaMovement();
        Vec3 newVel = new Vec3(
                outwardNormal.x * RICOCHET_STRENGTH,
                vel.y,
                outwardNormal.z * RICOCHET_STRENGTH
        );
        player.setDeltaMovement(newVel);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));

        // "Bake" thẳng vào momentum Bhop (khác với Dash) — vì đây là 1 cú đổi
        // hướng vĩnh viễn, không phải burst tạm thời cần tự trôi rồi phục hồi
        // momentum cũ. Dùng suppressAbsorbFor (kiểu Dash) ở đây sẽ khiến Bhop
        // tự động quay lại đúng momentum CŨ (trước khi bị đẩy) sau khi hết
        // suppress — vô hiệu hoá tác dụng của Ricochet nếu người chơi đang có
        // chain bhop active. Sync thẳng để momentum mới = velocity sau cú đẩy.
        BhopServerHandler.syncMomentumToCurrentVelocity(player);

        // Tạm ngừng Wallstride 1 khoảng để không bị bám dính lại tường ngay
        // lập tức (vị trí chưa kịp đổi trong cùng tick vừa bị đẩy ra).
        WallstrideServerHandler.suppressFor(player, WALLSTRIDE_SUPPRESS_TICKS);

        if (DEBUG) {
            HighV.LOGGER.info("[Ricochet] đẩy ra khỏi tường -> ({}, {}) b/s",
                    newVel.x * 20.0, newVel.z * 20.0);
        }
    }
}
