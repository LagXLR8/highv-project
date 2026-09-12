package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.enchantment.ModEnchantments;
import net.huwng.highv.network.packet.DashRequestPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Xử lý enchant "Dash" trên giày.
 *
 * Trigger (xử lý phía client, xem ClientInputHandler.tickDashInput): bấm
 * Shift (cạnh lên). Hướng dash: tổ hợp A/S/D nếu có giữ, ngược lại theo
 * hướng nhìn 3D (kể cả lên/xuống).
 *
 * Dash CỘNG THÊM vào velocity hiện có (không thay thế hoàn toàn) — giữ được
 * momentum đang có sẵn (vd đang bhop) thay vì cắt ngang. Có cooldown giữa 2
 * lần dash.
 *
 * NẾU dash có thành phần hướng LÊN TRÊN (dir.y > 0): trước khi cộng lực
 * dash, động năng NGANG hiện có (cả velocity thật lẫn momentum bhop đang
 * lưu) bị TRIỆT TIÊU BỚT theo tỉ lệ với độ "thẳng đứng" của cú dash.
 *
 * QUAN TRỌNG: sau dash, KHÔNG sync tốc độ dash vào momentum bhop (không
 * "bake" nó thành baseline vĩnh viễn) — thay vào đó gọi
 * BhopServerHandler.suppressAbsorbFor() để bhop tạm ngừng hoàn toàn trong
 * 1 khoảng ngắn, cho tốc độ dash tự nhiên trôi/rơi theo vật lý thường. Hết
 * khoảng đó, bhop quay lại đúng momentum CŨ (trước dash) — phần tăng thêm
 * từ dash không được giữ lại, tránh 1 cú dash đẩy bhop lên tới tận 60 b/s.
 */
@EventBusSubscriber(modid = HighV.MOD_ID)
public final class DashServerHandler {

    /** Độ mạnh của 1 lần dash, block/tick. 1.5 ≈ +30 b/s theo hướng dash. */
    private static final double DASH_STRENGTH = 2.5;

    /** Số tick tối thiểu giữa 2 lần dash liên tiếp (10 tick = 0.5 giây). */
    private static final int DASH_COOLDOWN_TICKS = 7;

    /**
     * Tỉ lệ tối đa động năng ngang bị triệt tiêu khi dash thẳng đứng hoàn
     * toàn lên trên (dir.y = 1.0). 0.7 nghĩa là mất tối đa 70% tốc độ ngang.
     */
    private static final double UPWARD_DRAIN_MAX_FRACTION = 0.5;

    /**
     * Số tick sau 1 cú dash mà bhop tạm ngừng hoàn toàn (không enforce,
     * không absorb) — để tốc độ dash tự nhiên trôi/rơi mà không bị bhop
     * "ép" hay "hấp thụ" vĩnh viễn vào momentum. 15 tick ≈ 0.75 giây.
     */
    private static final int DASH_SUPPRESS_TICKS = 15;

    /** Bật log debug. Tắt khi đã ổn. */
    private static final boolean DEBUG = false;

    private static final Map<UUID, Integer> lastDashTick = new HashMap<>();

    private DashServerHandler() {}

    /** Gọi từ DashRequestPacket.handle() khi client vừa bấm Shift. */
    public static void onDashRequest(ServerPlayer player, DashRequestPacket packet) {
        if (ModEnchantments.getDashLevel(player) <= 0) return;
        // Không cho phép Dash khi đang trong trạng thái Drift / Trượt
        if (DriftServerHandler.isSliding(player)) return;

        UUID id = player.getUUID();
        int now = player.tickCount;
        int last = lastDashTick.getOrDefault(id, Integer.MIN_VALUE / 2);
        if (now >= last && now - last < DASH_COOLDOWN_TICKS) {
            if (DEBUG) HighV.LOGGER.info("[Dash] bỏ qua, còn cooldown ({} tick)", DASH_COOLDOWN_TICKS - (now - last));
            return;
        }
        lastDashTick.put(id, now);

        Vec3 dir = new Vec3(packet.lookX(), packet.lookY(), packet.lookZ());
        double len = dir.length();
        if (len < 1.0e-4) return;
        dir = dir.scale(1.0 / len);

        Vec3 vel = player.getDeltaMovement();

        // Dash có hướng lên trên -> triệt tiêu bớt động năng ngang (velocity thật
        // + momentum bhop đang lưu) theo tỉ lệ với độ thẳng đứng của cú dash.
        double upward = Math.max(0.0, dir.y);
        if (upward > 0.0) {
            double drainFraction = upward * UPWARD_DRAIN_MAX_FRACTION;
            double keepFraction  = 1.0 - drainFraction;

            vel = new Vec3(vel.x * keepFraction, vel.y, vel.z * keepFraction);
            BhopServerHandler.drainMomentum(player, keepFraction);

            if (DEBUG) {
                HighV.LOGGER.info("[Dash] dash hướng lên (dir.y={}) -> triệt tiêu {}% động năng ngang",
                        dir.y, drainFraction * 100.0);
            }
        }

        Vec3 newVel = vel.add(dir.scale(DASH_STRENGTH));
        player.setDeltaMovement(newVel);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));

        // KHÔNG sync tốc độ dash vào momentum bhop (tránh "bake" vĩnh viễn) —
        // thay vào đó cho bhop tạm ngừng hẳn 1 khoảng, để tốc độ dash tự
        // nhiên trôi theo vật lý thường rồi bhop quay lại đúng momentum cũ.
        BhopServerHandler.suppressAbsorbFor(player, DASH_SUPPRESS_TICKS);

        if (DEBUG) {
            HighV.LOGGER.info("[Dash] dash dir=({}, {}, {}) strength={} b/s",
                    dir.x, dir.y, dir.z, DASH_STRENGTH * 20.0);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        lastDashTick.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        lastDashTick.remove(event.getEntity().getUUID());
    }
}