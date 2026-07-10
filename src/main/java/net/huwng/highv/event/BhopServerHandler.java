package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.enchantment.ModEnchantments;
import net.huwng.highv.network.packet.BhopInputPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side xử lý enchant "Bhopping".
 *
 * Nhận {@link BhopInputPacket} mỗi client tick (chỉ được client gửi khi đang
 * mặc giày có enchant Bhopping — xem {@link ModEnchantments#getBhopLevel}) và
 * áp dụng velocity trực tiếp lên {@link ServerPlayer} bằng
 * {@code setDeltaMovement(...) + hurtMarked = true}, cùng convention với
 * {@code GrapplingHookEntity} (server-authoritative velocity, sync về client
 * qua velocity packet thay vì dựa vào travel() — ServerPlayer không tự chạy
 * physics tick như mob).
 *
 * Ba cơ chế chính (đúng như mô tả trong BhopInputPacket):
 *
 *  1. MOMENTUM — nhảy lúc đang chạy trên đất không bị mất tốc độ ngang (khác
 *     vanilla, vanilla giữ nguyên tốc độ khi nhảy nên thực ra phần này chủ
 *     yếu là đảm bảo không có friction nào ăn vào tốc độ đúng lúc nhảy).
 *
 *  2. AIR-STRAFE — thuật toán tăng tốc trên không kiểu Quake/CS: khi đang ở
 *     trên không, velocity được "kéo" về phía wish-direction (hướng tổng hợp
 *     từ phím W + A/D và yaw hiện tại) với gia tốc giới hạn bởi phần chênh
 *     lệch giữa wishSpeed và tốc độ hiện tại chiếu lên wish-direction. Đây là
 *     cơ chế cho phép tốc độ VƯỢT quá tốc độ chạy bộ bình thường nếu người
 *     chơi strafe (A/D + xoay chuột) đúng nhịp — đúng bản chất "bhop".
 *
 *  3. TURN-BREAK — nếu người chơi xoay camera quá gắt (> TURN_BREAK_ANGLE_DEG
 *     mỗi tick) trong khi đang đứng trên đất giữa 2 lần nhảy, phạt tốc độ
 *     (nhân với TURN_BREAK_PENALTY) để tránh spin-abuse thay vì phải strafe
 *     đúng kỹ thuật trên không.
 *
 * State (grounded tick trước, yaw tick trước) được lưu theo UUID và dọn dẹp
 * khi người chơi logout hoặc không còn enchant level nào.
 */
@EventBusSubscriber(modid = HighV.MOD_ID)
public final class BhopServerHandler {

    private BhopServerHandler() {}

    // ── Tuning constants ────────────────────────────────────────────────────
    // Tốc độ ngang tối đa cơ bản (block/tick). Vanilla sprint ~ 0.28 block/tick.
    private static final double BASE_MAX_SPEED   = 0.28;
    // Mỗi level Bhopping cộng thêm trần tốc độ "wish speed" — không phải trần
    // cứng, chỉ là mốc mà gia tốc air-strafe nhắm tới.
    private static final double SPEED_PER_LEVEL  = 0.05;

    // Gia tốc khi đang trên đất (đơn vị tuỳ ý, xem hàm accelerate()).
    private static final double GROUND_ACCEL     = 8.0;
    // Ma sát khi trên đất và không có input (WASD thả hết).
    private static final double GROUND_FRICTION  = 0.86;
    // Gia tốc air-strafe — thấp hơn nhiều so với ground để không "dính" hướng
    // nhìn ngay lập tức, tạo cảm giác phải strafe đúng nhịp mới lên tốc độ.
    private static final double AIR_ACCEL        = 1.4;
    // Trần wish-speed riêng cho air-strafe, tách khỏi maxSpeed theo level để
    // strafe không tăng tốc vô hạn chỉ nhờ level cao.
    private static final double AIR_WISH_SPEED   = 0.24;

    private static final double JUMP_VELOCITY    = 0.42; // = vanilla jump Y velocity

    private static final double TURN_BREAK_ANGLE_DEG = 45.0;
    private static final double TURN_BREAK_PENALTY   = 0.6;

    // An toàn: dù momentum tích luỹ nhiều thế nào, tổng tốc độ ngang không
    // bao giờ vượt quá (maxSpeed * hệ số này) — tránh exploit/lag do velocity
    // tăng vô hạn.
    private static final double HARD_CAP_MULTIPLIER = 3.0;

    // ── Per-player state ────────────────────────────────────────────────────
    private record PlayerState(boolean wasGrounded, float lastYaw) {}

    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();

    /**
     * Gọi mỗi khi server nhận {@link BhopInputPacket} từ client (xem
     * {@code BhopInputPacket.handle}). Không cần tự kiểm tra
     * {@code level().isClientSide} vì ServerPlayer chỉ tồn tại phía server.
     */
    public static void updateInput(ServerPlayer player, BhopInputPacket packet) {
        int level = ModEnchantments.getBhopLevel(player);
        UUID id = player.getUUID();

        if (level <= 0) {
            // Không còn mặc giày Bhopping (hoặc vừa tháo ra) → dọn state,
            // không áp dụng gì thêm, để player rơi lại vào vật lý vanilla.
            STATES.remove(id);
            return;
        }

        boolean grounded = player.onGround();
        PlayerState prev = STATES.get(id);

        Vec3   vel        = player.getDeltaMovement();
        double vx         = vel.x;
        double vz         = vel.z;
        double maxSpeed   = BASE_MAX_SPEED + SPEED_PER_LEVEL * level;

        Vec3 wishDir = buildWishDir(packet);

        if (grounded) {
            // ── Turn-break: xoay quá gắt trong khi đứng đất giữa 2 lần nhảy ──
            if (prev != null && prev.wasGrounded()) {
                float yawDelta = Mth.wrapDegrees(packet.yaw() - prev.lastYaw());
                if (Math.abs(yawDelta) > TURN_BREAK_ANGLE_DEG) {
                    vx *= TURN_BREAK_PENALTY;
                    vz *= TURN_BREAK_PENALTY;
                }
            }

            Vec3 accelerated = accelerate(vx, vz, wishDir, maxSpeed, GROUND_ACCEL);
            vx = accelerated.x;
            vz = accelerated.z;

            if (wishDir.lengthSqr() < 1.0E-6) {
                // Không bấm WASD → ma sát kéo dần về 0, giống vanilla.
                vx *= GROUND_FRICTION;
                vz *= GROUND_FRICTION;
            }

            double newVy = vel.y;
            if (packet.jump()) {
                // MOMENTUM: nhảy nhưng KHÔNG reset/giảm tốc độ ngang — chỉ
                // set lại Y velocity để bật nhảy, giữ nguyên vx/vz vừa tính.
                newVy = JUMP_VELOCITY;
            }

            applyClamped(player, vx, newVy, vz, maxSpeed);
        } else {
            // ── AIR-STRAFE ──
            Vec3 accelerated = accelerate(vx, vz, wishDir, AIR_WISH_SPEED, AIR_ACCEL);
            applyClamped(player, accelerated.x, vel.y, accelerated.z, maxSpeed);
        }

        STATES.put(id, new PlayerState(grounded, packet.yaw()));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    // =========================================================================
    //  PHYSICS HELPERS
    // =========================================================================

    /**
     * Thuật toán tăng tốc kiểu Quake/CS air-accelerate, dùng chung cho cả
     * ground lẫn air (khác nhau ở accel/wishSpeed truyền vào).
     *
     * addSpeed = wishSpeed - (tốc độ hiện tại chiếu lên wishDir)
     * Nếu addSpeed <= 0 (đã nhanh hơn hoặc bằng wishSpeed theo hướng đó) thì
     * không cộng thêm gì — đây chính là lý do air-strafe ĐÚNG NHỊP (liên tục
     * đổi wishDir bằng cách xoay chuột + đổi A/D) mới lên tốc độ được, còn
     * giữ nguyên 1 hướng thì tốc độ sẽ bão hoà ở wishSpeed.
     */
    private static Vec3 accelerate(double vx, double vz, Vec3 wishDir, double wishSpeed, double accel) {
        if (wishDir.lengthSqr() < 1.0E-6) {
            return new Vec3(vx, 0, vz);
        }

        double currentSpeed = vx * wishDir.x + vz * wishDir.z;
        double addSpeed     = wishSpeed - currentSpeed;
        if (addSpeed <= 0) {
            return new Vec3(vx, 0, vz);
        }

        double accelSpeed = Math.min(accel * wishSpeed, addSpeed);
        return new Vec3(vx + accelSpeed * wishDir.x, 0, vz + accelSpeed * wishDir.z);
    }

    /** Set velocity lên player, có clamp tổng tốc độ ngang theo HARD_CAP_MULTIPLIER. */
    private static void applyClamped(ServerPlayer player, double vx, double vy, double vz, double maxSpeed) {
        double hardCap = maxSpeed * HARD_CAP_MULTIPLIER;
        double speed   = Math.sqrt(vx * vx + vz * vz);
        if (speed > hardCap) {
            double scale = hardCap / speed;
            vx *= scale;
            vz *= scale;
        }

        player.setDeltaMovement(vx, vy, vz);
        player.hurtMarked = true;
    }

    /**
     * Wish-direction world-space từ input W/A/D + yaw hiện tại (packet
     * KHÔNG có phím lùi — đúng chuẩn bhop, chỉ forward + strafe).
     * Cùng công thức xoay với {@code ClientInputHandler.buildWasdWorldVec}
     * để client/server thống nhất hướng.
     */
    private static Vec3 buildWishDir(BhopInputPacket packet) {
        float fwd  = packet.forward() ? 1f : 0f;
        float side = (packet.right() ? 1f : 0f) - (packet.left() ? 1f : 0f);
        if (fwd == 0f && side == 0f) {
            return Vec3.ZERO;
        }

        double yaw = Math.toRadians(packet.yaw());
        double fx  = -Math.sin(yaw) * fwd - Math.cos(yaw) * side;
        double fz  =  Math.cos(yaw) * fwd - Math.sin(yaw) * side;
        double len = Math.sqrt(fx * fx + fz * fz);
        return len > 1.0E-6 ? new Vec3(fx / len, 0, fz / len) : Vec3.ZERO;
    }
}