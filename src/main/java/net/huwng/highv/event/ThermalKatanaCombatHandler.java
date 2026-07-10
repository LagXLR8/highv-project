package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.item.ThermalKatanaItem;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Thermal Katana damage scaling.
 *
 * Damage gốc của katana chỉ 5 (xem ThermalKatanaItem). Handler này cộng thêm
 * damage dựa theo tốc độ ngang (b/s) của người chơi: đứng yên / đi bộ chậm →
 * gần như damage gốc, chạy/rơi/momentum từ grappling hook càng nhanh → damage
 * càng lớn, tối đa +MAX_BONUS_DAMAGE khi đạt SPEED_MAX.
 *
 * QUAN TRỌNG: tốc độ dùng để tính bonus KHÔNG đọc trực tiếp từ
 * {@code attacker.getDeltaMovement()} tại thời điểm event fire, mà lấy từ
 * {@link ThermalKatanaSpeedTracker#getRecentPeakSpeed(Player)} — tức tốc độ
 * đỉnh trong ~0.5s gần nhất. Lý do: BetterCombat có delay "upswing" giữa lúc
 * bắt đầu vung và lúc damage thực sự đăng ký trên server, trong lúc đó sprint
 * đã bị hủy nên vận tốc tức thời đã decay gần hết — đọc trực tiếp sẽ luôn ra
 * bonus gần 0 dù người chơi đang chạy/rơi rất nhanh lúc vung kiếm.
 *
 * Hook ở LivingIncomingDamageEvent (điểm sớm nhất trong pipeline damage, trước
 * khi giáp/kháng giảm damage) để bonus damage cũng bị giáp mục tiêu giảm bớt
 * như damage vũ khí thông thường — không phải true damage.
 */
@EventBusSubscriber(modid = HighV.MOD_ID)
public final class ThermalKatanaCombatHandler {

    /** Tốc độ (block/giây) bắt đầu có bonus. Dưới mức này = damage gốc, không cộng thêm. */
    private static final double SPEED_MIN = 5.0;
    /** Tốc độ (block/giây) đạt bonus tối đa. */
    private static final double SPEED_MAX = 40.0;
    /** Damage cộng thêm tối đa khi tốc độ >= SPEED_MAX. */
    private static final float MAX_BONUS_DAMAGE = 8.0f;

    private ThermalKatanaCombatHandler() {}

    /**
     * Bật log debug để soi chính xác handler đang fail ở bước nào:
     * event không fire, mainhand check fail, hay tốc độ tính ra luôn 0.
     * Tắt lại (false) khi đã xác định xong nguyên nhân.
     */
    private static final boolean DEBUG = false;

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        Entity       direct = source.getDirectEntity();

        if (DEBUG) {
            HighV.LOGGER.info(
                    "[ThermalKatana] onIncomingDamage fired. direct={} amount={}",
                    direct == null ? "null" : direct.getClass().getSimpleName(),
                    event.getAmount());
        }

        if (!(direct instanceof Player attacker)) {
            if (DEBUG) HighV.LOGGER.info("[ThermalKatana] skip: direct entity không phải Player");
            return;
        }

        boolean isKatana = attacker.getMainHandItem().getItem() instanceof ThermalKatanaItem;
        if (DEBUG) {
            HighV.LOGGER.info(
                    "[ThermalKatana] attacker={} mainhandItem={} isKatana={}",
                    attacker.getGameProfile().getName(),
                    attacker.getMainHandItem().getItem(),
                    isKatana);
        }
        if (!isKatana) return;

        double peakSpeed = ThermalKatanaSpeedTracker.getRecentPeakSpeed(attacker);
        double bonus      = computeSpeedBonus(attacker);

        if (DEBUG) {
            HighV.LOGGER.info(
                    "[ThermalKatana] peakSpeed={} b/s bonus={} baseAmount={} finalAmount={}",
                    peakSpeed, bonus, event.getAmount(), event.getAmount() + (float) bonus);
        }

        if (bonus > 0.0) {
            event.setAmount(event.getAmount() + (float) bonus);
        }
    }

    /** Tính bonus damage dựa theo tốc độ ngang ĐỈNH gần nhất của attacker (b/s). */
    private static double computeSpeedBonus(Player attacker) {
        double speedBs = ThermalKatanaSpeedTracker.getRecentPeakSpeed(attacker);

        double t = Mth.clamp((speedBs - SPEED_MIN) / (SPEED_MAX - SPEED_MIN), 0.0, 1.0);
        return t * MAX_BONUS_DAMAGE;
    }
}