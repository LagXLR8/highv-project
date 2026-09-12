package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.enchantment.ModEnchantments;
import net.huwng.highv.network.packet.DriftStateSyncPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Xử lý enchant "Drift" trên giày — trượt (slide) khi đang chạy nhanh và giữ
 * phím Drift (mặc định Left Ctrl — keybind RIÊNG, xem ModKeyMappings.DRIFT,
 * KHÔNG dùng phím Sneak vanilla, để không đụng với cạnh kích hoạt Dash).
 * Trạng thái giữ phím này được đồng bộ qua DriftInputPacket mỗi client tick
 * (xem updateHolding()).
 *
 * Điều kiện MỖI TICK để bắt đầu/tiếp tục trượt:
 *  - Có enchant Drift trên giày.
 *  - Đang đứng đất (onGround()) — kỹ năng MẶT ĐẤT, khác bhop/dash/wallstride
 *    (đều là kỹ năng trên không).
 *  - Đang giữ phím Drift (holdingCtrl == true, từ DriftInputPacket).
 *  - Tốc độ ngang hiện tại >= MIN_DRIFT_SPEED (15 b/s) — CHỈ xét lúc BẮT ĐẦU
 *    trượt. Sau khi đã trượt, tốc độ được GIỮ NGUYÊN không đổi (xem dưới),
 *    nên không thể tự "rớt xuống dưới ngưỡng" được nữa.
 * Thiếu 1 trong các điều kiện trên (thả phím Drift, nhảy/rơi khỏi đất, hoặc
 * mất enchant) -> dừng trượt ngay lập tức, trả pose/hitbox/jump về bình
 * thường, không còn ép velocity gì nữa.
 *
 * GIỮ NGUYÊN ĐỘNG NĂNG (không giảm dần): committed speed được chốt CHÍNH XÁC
 * bằng tốc độ ngang tại thời điểm bắt đầu trượt, và giữ NGUYÊN GIÁ TRỊ ĐÓ
 * suốt quá trình trượt — không nhân thêm hệ số giảm dần nào cả. Chỉ HƯỚNG di
 * chuyển thay đổi (xem điều hướng bên dưới), độ lớn luôn cố định cho tới khi
 * trượt kết thúc.
 *
 * ĐIỀU HƯỚNG TRONG LÚC TRƯỢT: hướng trượt xoay dần MỖI TICK về phía hướng
 * đang NHÌN (yaw ngang của camera), giới hạn tối đa MAX_TURN_DEGREES_PER_TICK
 * độ/tick — giống hệt kỹ thuật "steerable" của Bhop. Hướng BẮT ĐẦU trượt lấy
 * đúng hướng di chuyển thật tại khoảnh khắc bấm Drift (không nhảy thẳng theo
 * camera ngay lập tức), sau đó xoay dần theo camera mỗi tick — cho phép lái
 * trượt bằng cách quay chuột, không cần giữ thêm phím WASD nào khác.
 *
 * KHÔNG CHO NHẢY KHI ĐANG TRƯỢT: gắn tạm AttributeModifier triệt tiêu hẳn
 * attribute minecraft:jump_strength (đưa về 0) trong lúc trượt — bấm Space
 * lúc này không tạo lực nhảy nào cả. Gỡ ngay khi hết trượt. Cách này không
 * cần mixin, tương tự kỹ thuật triệt tiêu gravity của WallstrideServerHandler.
 * Tác dụng phụ tốt kèm theo: vì không thể nhảy được nữa, player.onGround()
 * không còn cơ hội bật thành false do chủ động nhảy nữa — chỉ còn tắt khi
 * chạy hết mép đất/rơi tự nhiên.
 *
 * HẠ HITBOX: dùng net.huwng.highv.mixin.PlayerHitboxMixin, ghi đè thẳng
 * Player#getDimensions(Pose) để trả về EntityDimensions tự định nghĩa riêng
 * cho Drift (không mượn số đo của Pose có sẵn nào) MỖI KHI isSliding(player)
 * ở dưới trả true — bất kể Pose thật sự của player lúc đó là gì (Pose vẫn
 * giữ nguyên STANDING suốt lúc trượt, không đụng vào). Nhờ vậy không còn
 * tranh chấp với Player.updatePlayerPose() (vanilla tự đánh giá lại Pose mỗi
 * tick dựa trên isVisuallySwimming()/isCrouching()/...) như hồi còn ép
 * Pose.SWIMMING — xem lịch sử ở doc comment DriftStateSyncPacket. Mỗi lúc
 * bắt đầu/kết thúc trượt đều gọi player.refreshDimensions() (applySlidePose/
 * removeSlidePose dưới đây) để buộc server tính lại hitbox ngay lập tức.
 *
 * ĐỒNG BỘ NGƯỢC VÀO BHOP: giống hệt lý do WallstrideServerHandler gọi
 * BhopServerHandler.syncMomentumToCurrentVelocity mỗi tick — tránh Bhop
 * "nhớ" momentum cũ (từ trước khi trượt) và ném velocity ngược lại đè lên
 * đúng lúc Drift đang giữ tốc độ có kiểm soát. Gọi sync mỗi tick trong lúc
 * trượt để Bhop luôn "nghĩ" momentum của nó CHÍNH LÀ committed speed hiện tại
 * của Drift. CÒN GỌI THÊM refreshGroundGrace() mỗi tick — Bhop có 1 bộ đếm
 * "đứng đất bao lâu" HOÀN TOÀN TÁCH BIỆT khỏi giá trị momentum, tự reset
 * momentum về 0 nếu đứng đất quá GRACE_TICKS (0.5s) — mà Drift là kỹ năng
 * đứng đất có thể kéo dài lâu hơn nhiều. Không refresh bộ đếm này thì dù
 * sync momentum đều tay, Bhop vẫn tự ý mất chain giữa chừng, và sau khi
 * đứng dậy sẽ không còn gì để enforce/tiếp tục bhop nữa.
 *
 * MẤT ĐỘNG NĂNG KHI VA CHẠM: nếu bị chặn ngang bởi block/tường
 * (Entity#horizontalCollision) trong lúc đang trượt, dừng trượt ngay lập
 * tức (mất động năng) thay vì tiếp tục ép velocity đâm vào vật cản.
 *
 * THỜI LƯỢNG TỐI ĐA + COOLDOWN: 1 lần trượt liên tục tối đa
 * MAX_SLIDE_TICKS (5s), quá mốc đó tự động dừng. MỌI lý do kết thúc trượt
 * (thả phím, rời đất, mất enchant, va chạm, hết thời lượng) đều kích hoạt
 * cooldown SLIDE_COOLDOWN_TICKS (3s) — trong lúc cooldown, dù đủ điều kiện
 * (đủ tốc độ, đang giữ phím, đứng đất) cũng KHÔNG bắt đầu trượt lại được.
 *
 * RESET SAU KHI CHẾT/RESPAWN: slideStartTick/cooldownUntilTick đều lưu theo
 * player.tickCount TUYỆT ĐỐI — nhưng respawn tạo ra 1 ServerPlayer instance
 * MỚI với tickCount bắt đầu lại từ 0 (dù cùng UUID). Nếu không dọn map khi
 * chết, cooldownUntilTick vẫn còn giữ 1 con số lớn từ trước khi chết (vd
 * 50000+ tick), trong khi tickCount mới chỉ vừa đếm lại từ 0 -> điều kiện
 * "player.tickCount < cooldownUntilTick" đúng trong RẤT LÂU (tới khi tick
 * mới đếm kịp bằng số cũ) -> Drift coi như bị khoá cứng, phím C không còn
 * tác dụng cho tới khi thoát ra vào lại (logout xoá sạch map — đây chính
 * là lý do relog luôn sửa được tạm thời). Fix: xoá sạch state khi respawn
 * (xem onRespawn dưới), y hệt logout.
 */
@EventBusSubscriber(modid = HighV.MOD_ID)
public final class DriftServerHandler {

    /** Tốc độ ngang tối thiểu để BẮT ĐẦU trượt, block/tick. 0.75 ≈ 15 b/s. */
    private static final double MIN_DRIFT_SPEED = 0.6;

    /** Tốc độ xoay tối đa của hướng trượt theo camera, độ/tick. */
    private static final double MAX_TURN_DEGREES_PER_TICK = 25.0;

    /** Giá trị mặc định của attribute minecraft:jump_strength — trừ đi để đưa về 0. */
    private static final double DEFAULT_JUMP_STRENGTH = 0.42;

    /** ID của AttributeModifier triệt tiêu khả năng nhảy lúc trượt. */
    private static final ResourceLocation NO_JUMP_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(HighV.MOD_ID, "drift_no_jump");

    /** Thời lượng trượt liên tục tối đa, tick. Public — HUD client đọc để vẽ thanh thời gian. 100 tick = 5s. */
    public static final int MAX_SLIDE_TICKS = 70;

    /** Tỉ lệ tốc độ tối thiểu phải MẤT ĐI (so với committed) do va chạm ngang
     *  mới coi là va chạm THẬT SỰ đáng dừng trượt — xem ghi chú ở
     *  onPlayerTick. Cùng giá trị/ý nghĩa với BhopServerHandler. */
    private static final double MIN_COLLISION_SPEED_LOSS_FRACTION = 0.5;

    /** Bật log debug. Tắt khi đã ổn. */
    private static final boolean DEBUG = false;

    /** Có đang giữ phím Drift không (đồng bộ từ DriftInputPacket mỗi client tick). */
    private static final Map<UUID, Boolean> holdingCtrl = new HashMap<>();

    /** Committed speed hiện tại (block/tick), null/không có key = không đang trượt. */
    private static final Map<UUID, Double> slideSpeed = new HashMap<>();

    /** Tốc độ ban đầu lúc mới bắt đầu cú trượt — dùng để tính giảm tốc theo thời gian còn lại. */
    private static final Map<UUID, Double> initialSlideSpeed = new HashMap<>();

    /** Hướng trượt (đơn vị, chỉ x/z) của tick gần nhất — xoay dần về hướng nhìn mỗi tick. */
    private static final Map<UUID, Vec3> slideDirection = new HashMap<>();

    /** Tick tuyệt đối (player.tickCount) lúc bắt đầu lần trượt hiện tại — để tính đã trượt bao lâu (xem MAX_SLIDE_TICKS). */
    private static final Map<UUID, Integer> slideStartTick = new HashMap<>();

    private DriftServerHandler() {}

    /** Gọi từ DriftInputPacket.handle() mỗi khi nhận packet input mới từ client. */
    public static void updateHolding(ServerPlayer player, boolean holding) {
        holdingCtrl.put(player.getUUID(), holding);
    }

    /** True nếu player hiện đang trượt (Drift active). */
    public static boolean isSliding(ServerPlayer player) {
        return slideSpeed.containsKey(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide) return;

        UUID id = player.getUUID();
        boolean wasSliding = slideSpeed.containsKey(id);

        boolean holding = holdingCtrl.getOrDefault(id, false);

        if (ModEnchantments.getDriftLevel(player) <= 0
                || !player.onGround()
                || !holding) {
            if (wasSliding) stopSliding(player);
            return;
        }

        Vec3 v = player.getDeltaMovement();
        double currentSpeed = Math.sqrt(v.x * v.x + v.z * v.z);

        Double committed = slideSpeed.get(id);

        // MẤT ĐỘNG NĂNG KHI VA CHẠM (đã giảm độ nhạy — xem lý do tương tự ở
        // BhopServerHandler): horizontalCollision tự bật cả với va chạm rất
        // nhẹ (mép block, bậc thang, gờ đất không bằng phẳng...) — hầu như
        // TICK NÀO đứng trên địa hình gồ ghề cũng có thể trúng cờ này dù
        // không hề đâm vào gì cả. Trước đây dừng trượt ngay khi cờ này bật
        // dù chỉ 1 tick, gây hiện tượng "vừa bắt đầu trượt xong dừng ngay"
        // (hitbox thoáng thấp xuống rồi bật lại bình thường) ngay khi vừa
        // đáp đất trên địa hình không phẳng tuyệt đối — chỉ coi là va chạm
        // THẬT SỰ khi tốc độ ngang thực tế đã tụt xuống đáng kể so với
        // committed (theo đúng MIN_COLLISION_SPEED_LOSS_FRACTION dùng chung
        // với Bhop), thay vì mọi lần cờ bật.
        if (committed != null && player.horizontalCollision) {
            double actualSpeed = currentSpeed;
            if (actualSpeed < committed * (1.0 - MIN_COLLISION_SPEED_LOSS_FRACTION)) {
                if (DEBUG) HighV.LOGGER.info("[Drift] va chạm ngang đáng kể -> mất động năng, dừng trượt");
                stopSliding(player);
                return;
            }
        }

        if (committed == null) {
            // Không còn cooldown trượt — chỉ cần đủ tốc độ là bắt đầu trượt ngay.
            if (currentSpeed < MIN_DRIFT_SPEED) return;
            committed = currentSpeed;
            initialSlideSpeed.put(id, currentSpeed);
            slideStartTick.put(id, player.tickCount);

            // Hướng bắt đầu = đúng hướng đang di chuyển thật (không nhảy
            // thẳng theo camera ngay lập tức) — fallback về hướng nhìn nếu
            // vì lý do gì đó currentSpeed đã đủ ngưỡng nhưng vector gần như 0.
            Vec3 startDir = currentSpeed > 1.0e-3
                    ? new Vec3(v.x / currentSpeed, 0, v.z / currentSpeed)
                    : horizontalLookDirection(player);
            slideDirection.put(id, startDir);
        }

        // THỜI LƯỢNG TỐI ĐA: trượt liên tục quá MAX_SLIDE_TICKS -> tự động dừng
        int elapsed = player.tickCount - slideStartTick.getOrDefault(id, player.tickCount);
        if (elapsed >= MAX_SLIDE_TICKS) {
            if (DEBUG) HighV.LOGGER.info("[Drift] hết thời lượng trượt tối đa -> tự dừng");
            stopSliding(player);
            return;
        }

        // GIẢM TỐC THEO THỜI GIAN CÒN LẠI CỦA CÚ TRƯỢT ĐÓ:
        // remainingFraction giảm dần từ 1.0 (lúc mới trượt) xuống 0.0 (lúc hết thời gian)
        double remainingFraction = (double) (MAX_SLIDE_TICKS - elapsed) / (double) MAX_SLIDE_TICKS;
        double initSpeed = initialSlideSpeed.getOrDefault(id, committed);

        // Tốc độ giảm dần từ 100% về minSpeedRatio (~28%, tương đương tốc độ đi bộ/chạy thường)
        double minSpeedRatio = 0.28;
        double speedFactor = minSpeedRatio + (1.0 - minSpeedRatio) * remainingFraction;
        double currentSlideSpeed = initSpeed * speedFactor;

        // Điều hướng: xoay dần hướng trượt hiện tại về phía hướng đang nhìn,
        // giới hạn MAX_TURN_DEGREES_PER_TICK độ/tick — cho phép lái bằng
        // chuột trong lúc trượt mà không cần giữ thêm phím nào khác.
        Vec3 lookDir = horizontalLookDirection(player);
        Vec3 dir = rotateTowards(
                slideDirection.getOrDefault(id, lookDir),
                lookDir,
                Math.toRadians(MAX_TURN_DEGREES_PER_TICK));
        slideDirection.put(id, dir);

        // QUAN TRONG: set state TRUOC khi goi applySlidePose()
        slideSpeed.put(id, currentSlideSpeed);

        if (!wasSliding) {
            applySlidePose(player);
            broadcastState(player, true);
        }

        // Vận tốc giảm dần theo thời gian còn lại của cú trượt
        player.setDeltaMovement(dir.x * currentSlideSpeed, v.y, dir.z * currentSlideSpeed);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.fallDistance = 0.0f;

        // Xem ghi chú lớp — tránh Bhop "nhớ" momentum cũ cao hơn và ném
        // velocity ngược lại đè lên lúc Drift đang giữ tốc độ có kiểm soát.
        BhopServerHandler.syncMomentumToCurrentVelocity(player);
        // QUAN TRỌNG: Drift là kỹ năng ĐỨNG ĐẤT LIÊN TỤC, thường lâu hơn hẳn
        // GRACE_TICKS (10 tick = 0.5s) của Bhop. Nếu không refresh bộ đếm
        // "đứng đất bao lâu" mỗi tick, Bhop sẽ tự coi là mất chain và reset
        // momentum về 0 GIỮA CHỪNG lúc đang trượt (dù sync ở trên vẫn chạy
        // đều) — khiến sau khi đứng dậy không còn gì để tiếp tục bhop. Xem
        BhopServerHandler.refreshGroundGrace(player);
        if (DEBUG) {
            HighV.LOGGER.info("[Drift] trượt, committed={} b/s", committed * 20.0);
        }
    }

    /**
     * Kích hoạt Slide-Jump khi người chơi bấm Jump trong lúc đang trượt.
     * Nhảy cao rõ rệt (~3 block), giữ NGUYÊN tốc độ ngang hiện tại (không cộng thêm momentum).
     */
    public static void onSlideJump(ServerPlayer player) {
        UUID id = player.getUUID();
        Double committed = slideSpeed.get(id);
        if (committed == null) return; // Không đang trượt

        Vec3 dir = slideDirection.getOrDefault(id, horizontalLookDirection(player));
        stopSliding(player);

        // Giữ NGUYÊN tốc độ ngang (không cộng thêm momentum), nhảy cao 6 block
        double hSpeed = committed;
        double jumpY = 1.08; // Nhảy cao chính xác ~6 block theo gia tốc trọng lực MC

        Vec3 jumpVel = new Vec3(dir.x * hSpeed, jumpY, dir.z * hSpeed);
        player.setDeltaMovement(jumpVel);
        player.fallDistance = 0f;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));

        // Nạp vào momentum Bhop để tiếp tục chuỗi Bhop trên không
        BhopServerHandler.syncMomentumToCurrentVelocity(player);
    }

    private static void stopSliding(ServerPlayer player) {
        UUID id = player.getUUID();
        slideSpeed.remove(id);
        initialSlideSpeed.remove(id);
        slideDirection.remove(id);
        slideStartTick.remove(id);
        removeSlidePose(player);
        removeNoJump(player);
        broadcastState(player, false);
    }

    /**
     * Gửi DriftStateSyncPacket cho mọi client đang track player này (kể cả
     * chính player) — xem doc comment DriftStateSyncPacket để biết lý do cần
     * packet riêng thay vì suy ra qua Pose.
     */
    private static void broadcastState(ServerPlayer player, boolean sliding) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                player, new DriftStateSyncPacket(player.getUUID(), sliding));
    }

    /**
     * refreshDimensions() để PlayerHitboxMixin tính lại hitbox NGAY (đọc
     * isSliding(player), đã true từ trước đó — xem ghi chú ở onPlayerTick),
     * và gắn modifier triệt tiêu nhảy — xem doc comment đầu lớp.
     */
    private static void applySlidePose(ServerPlayer player) {
        player.refreshDimensions();
        applyNoJump(player);
    }

    /**
     * refreshDimensions() để PlayerHitboxMixin trả hitbox về bình thường
     * NGAY — gọi SAU KHI slideSpeed.remove(id) đã chạy (xem stopSliding()),
     * nên isSliding(player) lúc này đã false.
     */
    private static void removeSlidePose(ServerPlayer player) {
        player.refreshDimensions();
    }

    /** Gắn AttributeModifier triệt tiêu khả năng nhảy (nếu chưa có). */
    private static void applyNoJump(ServerPlayer player) {
        AttributeInstance attr = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (attr == null) return;
        if (attr.getModifier(NO_JUMP_MODIFIER_ID) != null) return;
        attr.addTransientModifier(new AttributeModifier(
                NO_JUMP_MODIFIER_ID, -DEFAULT_JUMP_STRENGTH, AttributeModifier.Operation.ADD_VALUE));
    }

    /** Gỡ AttributeModifier triệt tiêu khả năng nhảy (không lỗi nếu chưa có). */
    private static void removeNoJump(ServerPlayer player) {
        AttributeInstance attr = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (attr == null) return;
        attr.removeModifier(NO_JUMP_MODIFIER_ID);
    }

    /** Hướng nhìn ngang (unit-length, chỉ x/z) theo yaw hiện tại. */
    private static Vec3 horizontalLookDirection(ServerPlayer player) {
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

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        holdingCtrl.remove(id);
        slideSpeed.remove(id);
        initialSlideSpeed.remove(id);
        slideDirection.remove(id);
        slideStartTick.remove(id);
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            removeNoJump(serverPlayer);
        }
    }

    /**
     * Respawn tạo ServerPlayer instance MỚI với tickCount đếm lại từ 0 (dù
     * cùng UUID) — dọn sạch state như logout để tránh bug kẹt trạng thái.
     */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        UUID id = event.getEntity().getUUID();
        holdingCtrl.remove(id);
        slideSpeed.remove(id);
        initialSlideSpeed.remove(id);
        slideDirection.remove(id);
        slideStartTick.remove(id);
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            removeNoJump(serverPlayer);
        }
    }
}