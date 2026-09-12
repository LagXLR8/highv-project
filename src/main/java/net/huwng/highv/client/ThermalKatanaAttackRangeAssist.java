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
 * Công thức: cứ mỗi 10 b/s được +1.0 range_bonus, cap ở MAX_RANGE_BONUS.
 * Vd: 10 b/s → +1.0, 20 b/s → +2.0, ... 50+ b/s → +5.0 (cap).
 *
 * Đây là API mở rộng chính thức BetterCombat cung cấp sẵn cho đúng use-case
 * này (net.bettercombat.api.client.AttackRangeExtensions), thay vì phải tự
 * viết lại hệ thống hit-detection.
 *
 * LƯU Ý: giá trị này CỘNG THÊM vào range_bonus tĩnh đã khai báo trong
 * data/highv/weapon_attributes/thermal_katana.json (hiện là 4.0) — KHÔNG
 * thay thế. Giữ nguyên range_bonus tĩnh đó vì nó ảnh hưởng tới bước lọc sơ bộ
 * (getInitialTargets) của BetterCombat — bonus động ở đây chỉ tác động tới
 * bước sau (kích thước hitbox chính xác), bị giới hạn bởi bước lọc sơ bộ nếu
 * không có range_bonus tĩnh đủ lớn làm nền (xem lịch sử trao đổi trước đó).
 */
public final class ThermalKatanaAttackRangeAssist {

    /** Mỗi bao nhiêu b/s thì +1.0 range_bonus. */
    private static final double SPEED_PER_RANGE_POINT = 10.0;

    /** Trần range_bonus động tối đa cộng thêm được. */
    private static final double MAX_RANGE_BONUS = 5.0;

    /** Bật log debug. Tắt khi đã ổn. */
    private static final boolean DEBUG = false;

    private ThermalKatanaAttackRangeAssist() {}

    public static void register() {
        AttackRangeExtensions.register(ThermalKatanaAttackRangeAssist::computeModifier);
        if (DEBUG) {
            net.huwng.highv.HighV.LOGGER.info("[ThermalKatanaAttackRangeAssist] đã register vào AttackRangeExtensions");
        }
    }

    private static AttackRangeExtensions.Modifier computeModifier(AttackRangeExtensions.Context context) {
        Player player = context.player();

        if (!(player.getMainHandItem().getItem() instanceof ThermalKatanaItem)) {
            // Không phải katana → không thay đổi gì (ADD 0).
            return new AttackRangeExtensions.Modifier(0.0, AttackRangeExtensions.Operation.ADD);
        }

        Vec3   v       = player.getDeltaMovement();
        double speedBs = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;

        double extraRange = Math.min(speedBs / SPEED_PER_RANGE_POINT, MAX_RANGE_BONUS);

        if (DEBUG) {
            net.huwng.highv.HighV.LOGGER.info(
                    "[ThermalKatanaAttackRangeAssist] computeModifier gọi: speedBs={} extraRange={} baseAttackRange={}",
                    speedBs, extraRange, context.attackRange());
        }

        return new AttackRangeExtensions.Modifier(extraRange, AttackRangeExtensions.Operation.ADD);
    }
}