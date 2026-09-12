package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.item.ThermalKatanaItem;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
 *
 * THÊM 2 CƠ CHẾ MỚI:
 *  1) Cooldown {@value #KATANA_COOLDOWN_TICKS} tick (2s) giữa 2 lần chém
 *     TRÚNG liên tiếp của CÙNG 1 player. Trong lúc cooldown, đòn chém vẫn có
 *     animation/hitbox bình thường (không can thiệp được vào BetterCombat ở
 *     mức đó), nhưng damage bị HỦY HOÀN TOÀN (event.setCanceled) — hiệu quả
 *     tương đương "chưa hồi chiêu thì chưa chém được".
 *  2) Mỗi lần chém trúng THÀNH CÔNG (qua được cooldown), giảm bớt động năng
 *     ngang hiện tại của attacker theo MOMENTUM_KEEP_FRACTION — vừa giảm
 *     velocity thật (setDeltaMovement + sync packet) vừa gọi
 *     BhopServerHandler.drainMomentum() để đồng bộ luôn momentum bhop đang
 *     lưu (nếu có) — BẮT BUỘC phải gọi cả 2, vì nếu chỉ đổi velocity thật mà
 *     không báo Bhop, tick tiếp theo Bhop sẽ tự enforce lại về đúng momentum
 *     CŨ (chưa giảm), xoá sạch hiệu ứng giảm tốc của đòn chém.
 */
@EventBusSubscriber(modid = HighV.MOD_ID)
public final class ThermalKatanaCombatHandler {

    /** Tốc độ (block/giây) bắt đầu có bonus. Dưới mức này = damage gốc, không cộng thêm. */
    private static final double SPEED_MIN = 5.0;
    /** Tốc độ (block/giây) đạt bonus tối đa. */
    private static final double SPEED_MAX = 40.0;
    /** Damage cộng thêm tối đa khi tốc độ >= SPEED_MAX. */
    private static final float MAX_BONUS_DAMAGE = 8.0f;

    /** Cooldown giữa 2 lần chém trúng liên tiếp, tính bằng tick (40 tick = 2 giây). */
    private static final int KATANA_COOLDOWN_TICKS = 40;

    /**
     * Số tick chênh lệch tối đa để 2 lần onIncomingDamage được coi là "cùng 1
     * nhát chém lan" (nhiều mục tiêu, không phải nhát mới) — không bị cooldown
     * chặn lẫn nhau. BetterCombat xử lý tất cả mục tiêu của 1 nhát chém tuần
     * tự trong cùng 1 tick (đôi khi lệch 1 tick do thứ tự xử lý), nên để 1 là
     * đủ an toàn mà vẫn phân biệt được với 1 nhát chém MỚI thật sự.
     */
    private static final int MULTI_HIT_GRACE_TICKS = 1;

    /** Tỉ lệ động năng ngang GIỮ LẠI sau mỗi lần chém trúng (0.5 = mất 50%). */
    private static final double MOMENTUM_KEEP_FRACTION = 0.5;

    /** Lưu tick (theo attacker.tickCount) của lần chém trúng gần nhất mỗi player. */
    private static final Map<UUID, Integer> lastHitTick = new HashMap<>();

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

        UUID id  = attacker.getUUID();
        int  now = attacker.tickCount;
        int  last = lastHitTick.getOrDefault(id, Integer.MIN_VALUE / 2);

        // Chỉ chặn nếu đây là 1 nhát chém MỚI cách nhát trước quá gần (< cooldown)
        // VÀ không cùng tick với nhát trước — nếu cùng tick (hoặc lệch rất ít),
        // đây là NHIỀU MỤC TIÊU của CÙNG 1 nhát chém lan, không phải nhát mới,
        // nên phải cho qua hết chứ không được chặn.
        int ticksSinceLast = now - last;
        boolean isNewSwing = ticksSinceLast > MULTI_HIT_GRACE_TICKS;

        if (isNewSwing && ticksSinceLast < KATANA_COOLDOWN_TICKS) {
            if (DEBUG) {
                HighV.LOGGER.info("[ThermalKatana] hủy damage, còn cooldown ({} tick)",
                        KATANA_COOLDOWN_TICKS - ticksSinceLast);
            }
            event.setCanceled(true);
            return;
        }
        lastHitTick.put(id, now);

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

        // Chỉ giảm động năng ĐÚNG 1 LẦN mỗi nhát chém — nếu không check isNewSwing,
        // chém lan trúng nhiều mục tiêu sẽ bị trừ tốc độ lặp lại cho từng mục tiêu
        // (vd trúng 3 con = mất ~87.5% thay vì đúng 50% dự kiến).
        if (isNewSwing) {
            drainAttackerMomentum(attacker);
        }
    }

    /**
     * Giảm bớt động năng ngang của attacker sau 1 lần chém trúng thành công.
     * Đổi cả velocity thật lẫn momentum bhop đang lưu (nếu có) — xem javadoc
     * đầu file vì sao bắt buộc phải đổi cả 2.
     */
    private static void drainAttackerMomentum(Player attacker) {
        if (!(attacker instanceof ServerPlayer serverPlayer)) return;

        Vec3 vel = serverPlayer.getDeltaMovement();
        Vec3 newVel = new Vec3(vel.x * MOMENTUM_KEEP_FRACTION, vel.y, vel.z * MOMENTUM_KEEP_FRACTION);
        serverPlayer.setDeltaMovement(newVel);
        serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));

        BhopServerHandler.drainMomentum(serverPlayer, MOMENTUM_KEEP_FRACTION);

        if (DEBUG) {
            HighV.LOGGER.info("[ThermalKatana] drain momentum sau khi chém, keepFraction={}", MOMENTUM_KEEP_FRACTION);
        }
    }

    /** Tính bonus damage dựa theo tốc độ ngang ĐỈNH gần nhất của attacker (b/s). */
    private static double computeSpeedBonus(Player attacker) {
        double speedBs = ThermalKatanaSpeedTracker.getRecentPeakSpeed(attacker);

        double t = Mth.clamp((speedBs - SPEED_MIN) / (SPEED_MAX - SPEED_MIN), 0.0, 1.0);
        return t * MAX_BONUS_DAMAGE;
    }
}