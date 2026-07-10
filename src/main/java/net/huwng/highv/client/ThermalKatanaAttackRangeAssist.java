package net.huwng.highv.client;

import net.bettercombat.api.client.AttackRangeExtensions;
import net.huwng.highv.item.ThermalKatanaItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Bù thêm attack_range cho Thermal Katana dựa theo tốc độ ngang thực tế của
 * người chơi (client-side, vì đây là local player nên deltaMovement đọc chính
 * xác — khác với server-side Player, xem ghi chú trong ThermalKatanaSpeedTracker).
 *
 * Lý do cần: BetterCombat detect hit dựa trên vị trí player tại thời điểm
 * "tipping point" (sau khoảng windup). Nếu chạy càng nhanh, player càng đi
 * xa mục tiêu trong lúc windup đó → hitbox tại vị trí hiện tại dễ hụt.
 * Đây là API mở rộng chính thức BetterCombat cung cấp sẵn cho đúng use-case
 * này (net.bettercombat.api.client.AttackRangeExtensions), thay vì phải tự
 * viết lại hệ thống hit-detection.
 */
public final class ThermalKatanaAttackRangeAssist {

    /**
     * Thời gian (giây) coi như "cửa sổ windup + trễ mạng" cần bù range.
     * File weapon_attributes hiện set "upswing": 0.05 cho katana → windup rơi
     * vào khoảng sàn tối thiểu ~1 tick (~0.05s) theo code BetterCombat, cộng
     * thêm biên độ cho ping/jitter. Nếu sau này đổi "upswing" trong json,
     * nên chỉnh hằng số này theo tỉ lệ tương ứng.
     */
    private static final double COMPENSATION_SECONDS = 0.15;

    /** Hệ số an toàn nhân thêm (sai số đo tốc độ, network jitter). */
    private static final double SAFETY_FACTOR = 1.3;

    private ThermalKatanaAttackRangeAssist() {}

    public static void register() {
        AttackRangeExtensions.register(ThermalKatanaAttackRangeAssist::computeModifier);
    }

    private static AttackRangeExtensions.Modifier computeModifier(AttackRangeExtensions.Context context) {
        Player player = context.player();

        if (!(player.getMainHandItem().getItem() instanceof ThermalKatanaItem)) {
            // Không phải katana → không thay đổi gì (ADD 0).
            return new AttackRangeExtensions.Modifier(0.0, AttackRangeExtensions.Operation.ADD);
        }

        Vec3   v       = player.getDeltaMovement();
        double speedBs = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;

        double extraRange = speedBs * COMPENSATION_SECONDS * SAFETY_FACTOR;
        return new AttackRangeExtensions.Modifier(extraRange, AttackRangeExtensions.Operation.ADD);
    }
}
