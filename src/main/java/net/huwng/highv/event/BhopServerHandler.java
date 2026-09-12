package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.enchantment.ModEnchantments;
import net.huwng.highv.network.packet.BhopInputPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Vật lý Bhopping.
 *
 * Chỉ 1 khái niệm duy nhất mỗi player: "momentumSpeed" (block/tick).
 *  - 0 nghĩa là không có gì đang active — vanilla tự lo hết, không đụng gì cả.
 *  - > 0 nghĩa là đang trong 1 chain bhop — MỖI TICK (cả lúc đang đất lẫn
 *    đang bay), ép thẳng velocity ngang về đúng magnitude này theo hướng
 *    hiện tại. Vì luôn ghi đè SAU CÙNG mỗi tick, vanilla không còn cơ hội
 *    "ăn" mất tốc độ nữa.
 *
 * Hướng di chuyển — có 2 trạng thái, phân biệt bằng "steerable":
 *
 *  1) steerable = true (mặc định, và TỰ ĐỘNG phục hồi sau STEER_LOCK_TICKS
 *     dù KHÔNG cần chạm đất/hop lại — xem lý do ở dưới):
 *      - Có giữ A hoặc D → hướng muốn đi là tổ hợp A/D (+ W nếu có giữ),
 *        xoay dần theo turn-rate.
 *      - KHÔNG giữ A/D nữa → hướng muốn đi tự động là "phía trước" theo yaw,
 *        momentum xoay dần trả lại đúng hướng đang nhìn.
 *
 *  2) steerable = false (momentum VỪA bị 1 nguồn ngoài — vd hook đẩy nhanh
 *     hơn, hoặc dash sau khi suppress hết hạn — ghi đè trực tiếp qua
 *     syncMomentumToCurrentVelocity): TẠM tắt luật "không giữ A/D thì tự
 *     quay về phía trước" trong đúng STEER_LOCK_TICKS tick. Lúc đó nếu
 *     không giữ A/D, hướng được GIỮ NGUYÊN y hệt, không tự xoay. A/D vẫn
 *     luôn hoạt động để chủ động steer nếu muốn.
 *
 *     QUAN TRỌNG — khóa có THỜI HẠN, không phải vĩnh viễn: nếu bắt khóa này
 *     chỉ được mở lại khi player chạm đất/hop lại, thì khi đang bay/rơi
 *     liên tục trên không sau 1 cú dash hoặc bị nguồn ngoài đẩy (không có cơ
 *     hội chạm đất để nhảy lại), player sẽ bị KẸT VĨNH VIỄN không đổi hướng
 *     theo camera được nữa cho tới khi hạ cánh — đây chính là bug đã gặp
 *     phải. Nên giờ khóa luôn tự hết hạn sau STEER_LOCK_TICKS bất kể có hop
 *     lại hay không. Nếu có 1 cú hop hợp lệ xảy ra trong lúc đang khóa,
 *     khóa được mở ngay lập tức (không cần chờ hết hạn).
 *
 * Nếu hướng muốn đi (khi có giữ A/D) ĐỐI LẬP với hướng momentum hiện tại
 * (dot &lt; 0, lệch hơn 90°) → momentum giảm dần mỗi tick.
 *
 * Mất chain (đứng đất quá GRACE_TICKS mà không nhảy lại) → momentumSpeed về
 * 0 ngay lập tức.
 *
 * QUAN TRỌNG (fix mất điều khiển khi vượt speed cap): công thức boost mỗi
 * lần hop KHÔNG BAO GIỜ được làm GIẢM momentum hiện tại — chỉ được tăng
 * thêm hoặc giữ nguyên. Trước đây công thức luôn kẹp cứng về MAX_SPEED, nên
 * nếu momentum đã vượt cap (do dash/absorb đẩy lên), hop tiếp theo sẽ kéo nó
 * tụt về đúng MAX_SPEED — nhưng velocity thật vẫn ở mức cao hơn, khiến
 * currentSpeed > momentum ngay trong cùng tick đó, tự kích hoạt nhánh absorb
 * dù không phải nguồn ngoài thật — lặp vòng này mỗi tick khi đang vượt cap,
 * gây mất điều khiển cho tới khi tốc độ tự nhiên rớt xuống dưới cap. Xem
 * computeBoostedMomentum().
 *
 * MẤT ĐỘNG NĂNG KHI VA CHẠM (đã giảm độ nhạy — xem MIN_COLLISION_SPEED_LOSS_FRACTION
 * ở onPlayerTick): chỉ hạ momentum khi va chạm THỰC SỰ làm giảm đáng kể tốc
 * độ ngang thật, không phải mọi lần horizontalCollision bật (cờ này bật cả
 * với va chạm rất nhẹ). Hạ về ĐÚNG tốc độ còn lại, không luôn zero ra.
 * KHÔNG áp dụng khi player đang ở trạng thái có thể wall-run (trên không +
 * có enchant Wallstride) — nhường hẳn không gian đó cho
 * WallstrideServerHandler, tránh phá mất tốc ngay lúc vừa bám tường.
 */
@EventBusSubscriber(modid = HighV.MOD_ID)
public final class BhopServerHandler {

    /** Mỗi lần hop hợp lệ, momentumSpeed nhân thêm bấy nhiêu. */
    private static final double BOOST_STEP = 1.075;

    /** Trần tốc độ ngang, block/tick. 1.5 ≈ 30 b/s. */
    private static final double MAX_SPEED = 1.5;

    /** Số tick tối đa được phép đứng đất giữa 2 lần nhảy trước khi mất chain. */
    private static final int GRACE_TICKS = 10;

    /** Tốc độ xoay tối đa của hướng di chuyển mỗi tick, độ. */
    private static final double MAX_TURN_DEGREES_PER_TICK = 100.0;

    /** Hệ số giảm momentum mỗi tick khi hướng muốn đi đối lập hướng hiện tại. */
    private static final double OPPOSING_DECAY_PER_TICK = 0.93;

    /** Ngưỡng tốc độ ngang tối thiểu để coi là "có hướng đáng tin cậy" khi sync. */
    private static final double MIN_SPEED_TO_SYNC_DIRECTION = 1.0e-3;

    /** Tỉ lệ tốc độ tối thiểu phải MẤT ĐI (so với momentum đang enforce) do va
     *  chạm ngang mới coi là "va chạm thật sự" đáng để hạ momentum — xem
     *  ghi chú ở onPlayerTick, mục MẤT ĐỘNG NĂNG KHI VA CHẠM. 0.5 = phải mất
     *  ít nhất 50% tốc độ. Tăng số này nếu vẫn còn thấy quá nhạy. */
    private static final double MIN_COLLISION_SPEED_LOSS_FRACTION = 0.5;

    /** Số tick khóa "steerable theo camera" sau khi 1 nguồn ngoài ghi đè hướng
     *  (absorb). Sau khoảng này, TỰ ĐỘNG mở lại luật "thả A/D thì quay về
     *  phía trước" — KHÔNG cần phải chạm đất/hop lại. Quan trọng cho trường
     *  hợp đang bay/rơi liên tục trên không (không có cơ hội hop lại), tránh
     *  bị khóa vĩnh viễn không đổi hướng theo camera được nữa. */
    private static final int STEER_LOCK_TICKS = 12;

    /** Bật log debug. Tắt khi đã ổn. */
    private static final boolean DEBUG = false;

    private static final Map<UUID, BhopInputPacket> inputs        = new HashMap<>();
    private static final Map<UUID, Boolean>         wasOnGround   = new HashMap<>();
    private static final Map<UUID, Integer>         groundTicks   = new HashMap<>();
    private static final Map<UUID, Double>          momentumSpeed = new HashMap<>();
    private static final Map<UUID, Vec3>            lastDirection = new HashMap<>();

    /** Tick (theo player.tickCount) mà tới lúc đó bhop tạm ngừng enforce/absorb — dùng cho dash. */
    private static final Map<UUID, Integer> suppressAbsorbUntilTick = new HashMap<>();

    /**
     * Tick (tuyệt đối) mà tới lúc đó luật "thả A/D thì quay về phía trước"
     * (steerable theo camera) bị tạm khóa. Set mỗi khi momentum bị 1 nguồn
     * ngoài ghi đè (absorb). Hết hạn TỰ ĐỘNG mở lại, không cần hop.
     */
    private static final Map<UUID, Integer> steerLockedUntilTick = new HashMap<>();

    private BhopServerHandler() {}

    /** Gọi từ BhopInputPacket.handle() mỗi khi nhận packet input mới từ client. */
    public static void updateInput(ServerPlayer player, BhopInputPacket packet) {
        inputs.put(player.getUUID(), packet);
    }

    /**
     * Bảo bhop TẠM NGỪNG hoàn toàn (không enforce, không absorb) trong
     * {@code ticks} tick tiếp theo. Dùng cho Dash: thay vì hấp thụ tốc độ
     * dash vào momentum vĩnh viễn (khiến 1 cú dash "bake" thẳng vào baseline
     * bhop, cộng dồn tiếp qua BOOST_STEP thành số rất lớn), giờ bhop đứng
     * ngoài hoàn toàn trong lúc dash đang "bay" — tốc độ dash tự nhiên trôi
     * theo vật lý thường (gravity/friction), KHÔNG bị ép gì cả. Hết khoảng
     * suppress, bhop quay lại enforce đúng momentum CŨ (trước dash, không
     * đổi) — nghĩa là phần tăng thêm từ dash không được giữ lại.
     */
    public static void suppressAbsorbFor(ServerPlayer player, int ticks) {
        suppressAbsorbUntilTick.put(player.getUUID(), player.tickCount + ticks);
    }

    /**
     * Reset bộ đếm "đứng đất bao lâu" (groundTicks) về 0. Dùng cho các hệ
     * thống KHÁC giữ player đứng đất liên tục lâu hơn GRACE_TICKS một cách
     * hợp lệ (vd Drift — kỹ năng trượt trên mặt đất, có thể kéo dài nhiều
     * giây) — nếu không gọi hàm này, Bhop sẽ tự coi "đứng đất quá lâu" là
     * mất chain và reset momentum về 0 GIỮA CHỪNG lúc đang trượt, dù
     * syncMomentumToCurrentVelocity vẫn đang được gọi đều — vì 2 việc đó
     * hoàn toàn tách biệt (1 cái theo dõi GIÁ TRỊ momentum, 1 cái theo dõi
     * THỜI GIAN đứng đất). Gọi hàm này mỗi tick trong lúc hệ thống ngoài
     * đang chủ động giữ player đứng yên hợp lệ, để khi hệ thống đó kết thúc,
     * Bhop tiếp tục enforce với groundTicks vẫn còn trong hạn — không bị mất
     * chain oan uổng.
     * Không có tác dụng gì nếu player hiện không có chain bhop đang active.
     */
    public static void refreshGroundGrace(ServerPlayer player) {
        groundTicks.put(player.getUUID(), 0);
    }

    /**
     * Đồng bộ lại momentum/hướng của Bhop theo đúng velocity thực tế hiện tại
     * của player. Gọi từ DashServerHandler (hoặc bất kỳ hệ thống ngoài nào
     * khác, vd grappling hook) ngay sau khi áp dụng 1 lực đẩy, để lần enforce
     * tiếp theo của Bhop không "kéo" velocity lại về giá trị cũ trước đó.
     *
     * Việc gọi hàm này cũng khóa steerable-theo-camera trong STEER_LOCK_TICKS
     * tick: báo cho Bhop biết hướng vừa rồi đến từ nguồn ngoài, KHÔNG tự
     * động xoay hướng về phía trước nữa trong khoảng đó — tránh hướng bị
     * "cong" lệch ngay sau khi bị ghi đè. Khóa tự hết hạn sau STEER_LOCK_TICKS
     * dù không hop lại, để không bị kẹt hướng vĩnh viễn khi đang bay liên tục.
     *
     * Không có tác dụng gì nếu player hiện không có chain bhop đang active.
     */
    public static void syncMomentumToCurrentVelocity(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!momentumSpeed.containsKey(id)) return;

        Vec3   v     = player.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        if (speed < 1.0e-4) return;

        momentumSpeed.put(id, speed);
        lastDirection.put(id, new Vec3(v.x / speed, 0, v.z / speed));
        steerLockedUntilTick.put(id, player.tickCount + STEER_LOCK_TICKS);

        if (DEBUG) HighV.LOGGER.info("[Bhop] sync sau nguồn ngoài -> momentum={} b/s", speed * 20.0);
    }

    /**
     * Đặt thẳng momentum của Bhop theo tỉ lệ giữ lại (keepFraction, 0..1),
     * KHÔNG dựa vào velocity hiện tại. Dùng khi 1 nguồn ngoài (vd Dash đẩy
     * lên trời) cần chủ động TRIỆT TIÊU bớt động năng ngang đang lưu trong
     * bhop, thay vì chỉ đồng bộ theo velocity thực tế.
     * Không có tác dụng gì nếu player hiện không có chain bhop đang active.
     */
    public static void drainMomentum(ServerPlayer player, double keepFraction) {
        UUID id = player.getUUID();
        Double current = momentumSpeed.get(id);
        if (current == null || current <= 0.0) return;

        double clampedKeep = Math.max(0.0, Math.min(1.0, keepFraction));
        double newMomentum = current * clampedKeep;
        momentumSpeed.put(id, newMomentum);

        if (DEBUG) {
            HighV.LOGGER.info("[Bhop] drain momentum {} -> {} b/s (keepFraction={})",
                    current * 20.0, newMomentum * 20.0, clampedKeep);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide) return;

        UUID id = player.getUUID();

        if (ModEnchantments.getBhopLevel(player) <= 0) {
            clearAll(id);
            return;
        }

        boolean currentOnGround  = player.onGround();
        boolean previousOnGround = wasOnGround.getOrDefault(id, true);
        wasOnGround.put(id, currentOnGround);

        BhopInputPacket in = inputs.get(id);
        double momentum = momentumSpeed.getOrDefault(id, 0.0);

        // KHÔNG áp dụng mất-động-năng-do-va-chạm khi player đang ở trạng thái
        // CÓ THỂ wall-run (trên không + có enchant Wallstride)
        boolean possiblyWallRunning = !currentOnGround && ModEnchantments.getWallstrideLevel(player) > 0;

        // MẤT ĐỘNG NĂNG KHI VA CHẠM (đã giảm độ nhạy)
        if (!possiblyWallRunning && momentum > 1.0e-4 && player.horizontalCollision) {
            double actualSpeed = horizontalSpeed(player);
            if (actualSpeed < momentum * (1.0 - MIN_COLLISION_SPEED_LOSS_FRACTION)) {
                if (DEBUG) {
                    HighV.LOGGER.info("[Bhop] va chạm ngang đáng kể -> momentum {} -> {} b/s",
                            momentum * 20.0, actualSpeed * 20.0);
                }
                momentumSpeed.put(id, actualSpeed);
                return;
            }
        }

        if (currentOnGround) {
            int ticks = groundTicks.merge(id, 1, Integer::sum);
            if (ticks > GRACE_TICKS && momentum > 0.0) {
                if (DEBUG) HighV.LOGGER.info("[Bhop] chain break (đứng đất quá {} tick) — reset về 0", GRACE_TICKS);
                momentum = 0.0;
                momentumSpeed.put(id, 0.0);
                lastDirection.remove(id);
                steerLockedUntilTick.remove(id);
            }
        } else {
            groundTicks.put(id, 0);
            boolean justJumped = previousOnGround;
            if (justJumped) {
                steerLockedUntilTick.remove(id);

                if (in != null && in.forward()) {
                    if (momentum <= 0.0) {
                        // Lần hop đầu tiên bắt đầu chuỗi (vd: đi bộ hoặc chạy bộ rồi nhảy):
                        // Đảm bảo tốc độ khởi đầu không bị kẹt về 0 do server không có deltaMovement khi đi bộ,
                        // tối thiểu đạt tốc độ nhảy vanilla chuẩn (walk jump ≈ 0.215, sprint jump ≈ 0.28)
                        double minJumpBase = player.isSprinting() ? 0.28 : 0.215;
                        double startSpeed = Math.max(horizontalSpeed(player), minJumpBase);
                        momentum = computeBoostedMomentum(startSpeed);
                        momentumSpeed.put(id, momentum);

                        // Hướng ban đầu: ưu tiên hướng vận tốc nếu có, fallback về hướng wish / look
                        Vec3 v = player.getDeltaMovement();
                        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
                        Vec3 initialDir;
                        if (speed >= MIN_SPEED_TO_SYNC_DIRECTION) {
                            initialDir = new Vec3(v.x / speed, 0, v.z / speed);
                        } else {
                            initialDir = computeWishDirection(in, true);
                            if (initialDir == null) initialDir = computeFallbackDirection(player);
                        }
                        lastDirection.put(id, initialDir);
                    } else {
                        // Đang trong chuỗi chain bhop: boost tiếp tục momentum
                        momentum = computeBoostedMomentum(momentum);
                        momentumSpeed.put(id, momentum);
                        syncDirectionFromVelocity(player, id);
                    }
                    if (DEBUG) HighV.LOGGER.info("[Bhop] hop boost momentum={} b/s", momentum * 20.0);
                }
            }
        }

        if (momentum > 1.0e-4) {
            boolean suppressed = player.tickCount < suppressAbsorbUntilTick.getOrDefault(id, 0);
            if (suppressed) {
                if (DEBUG) HighV.LOGGER.info("[Bhop] suppressed (dash đang bay), momentum giữ nguyên {} b/s", momentum * 20.0);
            } else {
                double currentSpeed = horizontalSpeed(player);
                if (currentSpeed > momentum + 1.0e-3) {
                    syncMomentumToCurrentVelocity(player);
                    if (DEBUG) HighV.LOGGER.info("[Bhop] absorb external speed boost -> momentum={} b/s", currentSpeed * 20.0);
                } else {
                    double newMomentum = enforceMomentum(player, id, in, momentum);
                    if (newMomentum != momentum) momentumSpeed.put(id, newMomentum);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clearAll(event.getEntity().getUUID());
    }

    /**
     * Respawn tạo ServerPlayer instance MỚI, tickCount đếm lại từ 0 dù cùng
     * UUID — steerLockedUntilTick/suppressAbsorbUntilTick là mốc TUYỆT ĐỐI
     * theo tickCount cũ, sai lệch với tickCount mới y hệt bug đã sửa ở
     * DriftServerHandler/WallstrideServerHandler (xem doc comment ở đó).
     * Dọn sạch toàn bộ state khi respawn — hợp lý cả về gameplay (chết thì
     * không còn lý do gì giữ momentum/khoá cũ nữa).
     */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        clearAll(event.getEntity().getUUID());
    }

    /**
     * Tính momentum mới sau 1 lần hop hợp lệ. QUAN TRỌNG: không bao giờ được
     * làm GIẢM startSpeed — chỉ tăng thêm (kẹp ở MAX_SPEED) hoặc giữ nguyên
     * nếu startSpeed đã vượt MAX_SPEED sẵn (từ dash/absorb). Nếu công thức
     * này làm giảm momentum xuống dưới startSpeed, velocity thật (chưa kịp
     * đổi) sẽ cao hơn momentum mới ngay trong tick đó, tự kích hoạt nhầm
     * nhánh absorb ở onPlayerTick — đây chính là nguyên nhân gây mất điều
     * khiển khi đang vượt speed cap.
     */
    private static double computeBoostedMomentum(double startSpeed) {
        double base    = Math.max(startSpeed, 0.20);
        double boosted = Math.min(base * BOOST_STEP, MAX_SPEED);
        return Math.max(startSpeed, boosted);
    }

    /**
     * Ghi lastDirection theo đúng hướng vận tốc ngang THỰC TẾ hiện tại của
     * player. Dùng ngay tại các thời điểm momentum bị thay đổi đột ngột
     * (hop boost) để tránh 1 tick "ép sai hướng" trước khi turn-rate kịp
     * xoay lại. Nếu tốc độ ngang hiện tại quá nhỏ (gần như đứng yên),
     * dùng hướng wish/look của người chơi thay vì bỏ qua để tránh hướng bị rỗng.
     */
    private static void syncDirectionFromVelocity(ServerPlayer player, UUID id) {
        Vec3   v     = player.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        if (speed < MIN_SPEED_TO_SYNC_DIRECTION) {
            BhopInputPacket in = inputs.get(id);
            Vec3 wishDir = computeWishDirection(in, true);
            if (wishDir == null) wishDir = computeFallbackDirection(player);
            lastDirection.put(id, wishDir);
            return;
        }

        lastDirection.put(id, new Vec3(v.x / speed, 0, v.z / speed));
    }

    /**
     * Ép velocity ngang theo "momentum" theo hướng muốn đi (xem computeWishDirection).
     * Nếu hướng đích đối lập hướng hiện tại, momentum giảm dần. Trả về momentum mới.
     */
    private static double enforceMomentum(ServerPlayer player, UUID id, BhopInputPacket in, double momentum) {
        Vec3 momentumDir = lastDirection.getOrDefault(id, computeFallbackDirection(player));
        boolean steerable = player.tickCount >= steerLockedUntilTick.getOrDefault(id, Integer.MIN_VALUE);
        Vec3 wishDir = computeWishDirection(in, steerable);

        if (wishDir != null) {
            double dot = wishDir.x * momentumDir.x + wishDir.z * momentumDir.z;
            if (dot < 0) {
                momentum *= OPPOSING_DECAY_PER_TICK;
            }

            double maxRadians = Math.toRadians(MAX_TURN_DEGREES_PER_TICK);
            momentumDir = rotateTowards(momentumDir, wishDir, maxRadians);
        }
        // wishDir == null: hoặc chưa từng nhận packet, hoặc steerable=false (đang
        // khóa) và không giữ A/D -> giữ nguyên momentumDir, không xoay, không decay.

        lastDirection.put(id, momentumDir);

        Vec3 vel = player.getDeltaMovement();
        double newX = momentumDir.x * momentum;
        double newZ = momentumDir.z * momentum;

        player.setDeltaMovement(newX, vel.y, newZ);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));

        return momentum;
    }

    /**
     * Hướng "muốn đi tới":
     *  - Đang giữ A hoặc D → tổ hợp của A/D (và W nếu có giữ), tương đối
     *    theo yaw của packet. LUÔN áp dụng bất kể steerable hay không — A/D
     *    luôn được phép chủ động steer.
     *  - KHÔNG giữ A/D:
     *      - steerable = true  → trả về hướng "phía trước" theo yaw (luật
     *        cũ: quay lại đúng hướng đang nhìn khi thả A/D).
     *      - steerable = false (đang trong STEER_LOCK_TICKS sau khi bị nguồn
     *        ngoài ghi đè) → trả về null (giữ nguyên hướng hiện tại, không
     *        tự xoay) — để không bị Bhop "kéo cong" lại hướng vừa bị ghi đè.
     * Trả về null cũng khi chưa từng nhận packet input nào (rất hiếm).
     */
    private static Vec3 computeWishDirection(BhopInputPacket in, boolean steerable) {
        if (in == null) return null;

        double yawRad = Math.toRadians(in.yaw());
        double forwardX = -Math.sin(yawRad), forwardZ = Math.cos(yawRad);
        double rightX   =  Math.cos(yawRad), rightZ   = Math.sin(yawRad);

        boolean turning = in.left() || in.right();

        if (!turning) {
            return steerable ? new Vec3(forwardX, 0, forwardZ) : null;
        }

        double wishX = 0, wishZ = 0;
        if (in.forward()) { wishX += forwardX; wishZ += forwardZ; }
        if (in.left())     { wishX -= rightX;   wishZ -= rightZ;   }
        if (in.right())    { wishX += rightX;   wishZ += rightZ;   }

        double len = Math.sqrt(wishX * wishX + wishZ * wishZ);
        if (len > 1.0e-6) {
            return new Vec3(wishX / len, 0, wishZ / len);
        }

        // Trường hợp hiếm: A và D giữ cùng lúc triệt tiêu nhau.
        return steerable ? new Vec3(forwardX, 0, forwardZ) : null;
    }

    /** Hướng dự phòng khi chưa từng có lastDirection (rất hiếm) — dùng yaw thật của entity. */
    private static Vec3 computeFallbackDirection(ServerPlayer player) {
        double yawRad = Math.toRadians(player.getYRot());
        return new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
    }

    /** Xoay "current" về phía "target" tối đa maxRadians (2D, quanh trục Y). */
    private static Vec3 rotateTowards(Vec3 current, Vec3 target, double maxRadians) {
        double curAngle = Math.atan2(current.x, current.z);
        double tgtAngle = Math.atan2(target.x, target.z);

        double diff = tgtAngle - curAngle;
        while (diff > Math.PI)  diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;

        double clamped  = Math.max(-maxRadians, Math.min(maxRadians, diff));
        double newAngle = curAngle + clamped;

        return new Vec3(Math.sin(newAngle), 0, Math.cos(newAngle));
    }

    private static double horizontalSpeed(ServerPlayer player) {
        Vec3 v = player.getDeltaMovement();
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    private static void clearAll(UUID id) {
        inputs.remove(id);
        wasOnGround.remove(id);
        groundTicks.remove(id);
        momentumSpeed.remove(id);
        lastDirection.remove(id);
        steerLockedUntilTick.remove(id);
        suppressAbsorbUntilTick.remove(id);
    }
}