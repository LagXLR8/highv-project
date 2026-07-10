package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.item.ThermalKatanaItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Track "tốc độ đỉnh gần nhất" (recent peak speed) của mỗi player, thay vì chỉ
 * đọc velocity tức thời tại thời điểm hit.
 *
 * QUAN TRỌNG - lý do dùng position delta thay vì {@code getDeltaMovement()}:
 * Trên server, Player KHÔNG chạy physics/travel() để tự tích lũy velocity như
 * mob — server nhận vị trí tuyệt đối từ gói tin di chuyển của client rồi
 * {@code absMoveTo(...)} thẳng tới đó. field {@code deltaMovement} của
 * ServerPlayer vì vậy hầu như không phản ánh tốc độ đi bộ/sprint thật (nó chỉ
 * được set trong vài trường hợp đặc biệt như knockback, elytra, riptide).
 * Đọc thẳng {@code getDeltaMovement()} sẽ luôn ra số gần 0 bất kể tốc độ di
 * chuyển thật cao thế nào — đây là nguyên nhân thật của việc bonus damage
 * không bao giờ kích hoạt, không liên quan gì đến BetterCombat.
 *
 * Fix: tự tính tốc độ bằng khoảng cách di chuyển giữa 2 tick liên tiếp
 * (position hiện tại - position tick trước), thay vì đọc field velocity.
 *
 * Ngoài ra vẫn giữ cơ chế ring-buffer WINDOW_TICKS để lấy tốc độ ĐỈNH gần nhất
 * (chứ không phải tức thời tại đúng thời điểm hit), phòng trường hợp
 * BetterCombat có delay giữa lúc bắt đầu vung và lúc damage thực sự đăng ký.
 */
@EventBusSubscriber(modid = HighV.MOD_ID)
public final class ThermalKatanaSpeedTracker {

    /** Kích thước cửa sổ theo dõi (tick). 10 tick = 0.5s. */
    private static final int WINDOW_TICKS = 10;

    /** Bật log debug (throttled ~1 lần/giây/player). Tắt khi đã confirm hoạt động ổn. */
    private static final boolean DEBUG = false;

    private static final Map<UUID, double[]> buffers      = new HashMap<>();
    private static final Map<UUID, Integer>  cursors      = new HashMap<>();
    private static final Map<UUID, Vec3>     lastPosition = new HashMap<>();

    private ThermalKatanaSpeedTracker() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;

        UUID id = player.getUUID();

        if (!(player.getMainHandItem().getItem() instanceof ThermalKatanaItem)) {
            // Không cầm katana nữa → dọn hết state, tránh dùng lại peak/vị trí cũ
            // và tránh leak memory khi họ bỏ hẳn item.
            buffers.remove(id);
            cursors.remove(id);
            lastPosition.remove(id);
            return;
        }

        Vec3 currentPos  = player.position();
        Vec3 previousPos = lastPosition.put(id, currentPos);

        double speedBs;
        if (previousPos == null) {
            // Tick đầu tiên được track (vừa cầm katana lên) → chưa có mốc so sánh.
            speedBs = 0.0;
        } else {
            double dx = currentPos.x - previousPos.x;
            double dz = currentPos.z - previousPos.z;
            speedBs = Math.sqrt(dx * dx + dz * dz) * 20.0;
        }

        double[] buf = buffers.computeIfAbsent(id, k -> new double[WINDOW_TICKS]);
        int      cur = cursors.merge(id, 1, (oldVal, inc) -> (oldVal + 1) % WINDOW_TICKS);
        buf[cur] = speedBs;

        if (DEBUG && player.tickCount % 20 == 0) {
            HighV.LOGGER.info(
                    "[ThermalKatanaSpeedTracker] tick speed={} b/s buffer={}",
                    speedBs, java.util.Arrays.toString(buf));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        buffers.remove(id);
        cursors.remove(id);
        lastPosition.remove(id);
    }

    /**
     * Tốc độ ngang đỉnh (block/giây) trong {@value #WINDOW_TICKS} tick gần nhất,
     * tính bằng position delta chứ không phải velocity field.
     */
    public static double getRecentPeakSpeed(Player player) {
        double[] buf = buffers.get(player.getUUID());
        if (buf == null) {
            // Chưa được track (vd vừa cầm katana lên đúng tick này) → không có
            // dữ liệu lịch sử, trả về 0 thay vì đoán bừa.
            return 0.0;
        }
        double max = 0.0;
        for (double v : buf) {
            if (v > max) max = v;
        }
        return max;
    }
}