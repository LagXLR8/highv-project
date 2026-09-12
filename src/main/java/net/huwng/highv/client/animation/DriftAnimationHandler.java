package net.huwng.highv.client.animation;

import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.huwng.highv.HighV;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Animation Drift — slide, liên tục trong lúc đang trượt (giống tinh thần
 * MomentumAnimationHandler: TRẠNG THÁI, không phải sự kiện tức thời như Dash).
 *
 * PHÁT HIỆN QUA PACKET RIÊNG (DriftStateSyncPacket), KHÔNG suy đoán qua
 * Pose/velocity/onGround nữa. Lý do: ban đầu dùng {@code player.getPose() ==
 * Pose.SWIMMING} (vì Pose vốn được đồng bộ sẵn cho hitbox) nhưng KHÔNG đáng
 * tin cậy — vanilla có logic riêng (Player.updatePlayerPose(), chạy mỗi tick
 * TRƯỚC PlayerTickEvent.Post của DriftServerHandler) tự đánh giá lại Pose dựa
 * trên isVisuallySwimming()/isCrouching()/isFallFlying(); vì Drift không bật
 * cờ "isSwimming" thật, vanilla liên tục cố trả Pose về STANDING, khiến
 * animation dựa vào Pose bị chập chờn/không lên. DriftServerHandler giờ chủ
 * động gửi DriftStateSyncPacket đúng lúc bắt đầu/kết thúc trượt — client chỉ
 * cần đọc {@link #setSlidingState} đã ghi, không tự suy luận gì thêm.
 *
 * HITBOX (từ khi có PlayerHitboxMixin): KHÔNG còn ép Pose.SWIMMING nữa —
 * hitbox + eye height thấp trong lúc trượt giờ do
 * {@code net.huwng.highv.mixin.PlayerHitboxMixin} quyết định thẳng, dựa
 * đúng trên tín hiệu {@link #isSyncedSliding} (client) /
 * {@code DriftServerHandler.isSliding} (server) — không mượn dimensions có
 * sẵn của bất kỳ Pose vanilla nào, và không còn tranh chấp với
 * Player.updatePlayerPose() như mô tả ở trên nữa vì ta không đụng tới Pose.
 * refreshDimensionsIfChanged() ở dưới chỉ có nhiệm vụ buộc client tính lại
 * dimensions NGAY khi tín hiệu đổi (mixin tự nó không tự kích hoạt lại một
 * mình — cần gọi refreshDimensions() mỗi khi trạng thái lật).
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class DriftAnimationHandler {

    private static final ResourceLocation LAYER_ID = HighV.id("drift_layer");
    private static final ResourceLocation ANIM_SLIDE = HighV.id("slide");

    private static final int FADE_TICKS = 5;

    /** Trạng thái trượt đã đồng bộ từ server qua DriftStateSyncPacket (UUID -> đang trượt hay không). */
    private static final Map<UUID, Boolean> syncedSliding = new HashMap<>();

    private enum DriftPose { NONE, SLIDE }
    private static final Map<UUID, DriftPose> currentPose = new HashMap<>();

    private DriftAnimationHandler() {}

    /** Gọi 1 lần từ ClientSetup.register() hoặc FMLClientSetupEvent. */
    public static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
                LAYER_ID,
                1000, // cùng mức ưu tiên với các layer momentum/wallstride/dash khác
                player -> new ModifierLayer<IAnimation>()
        );
    }

    /** Gọi từ DriftStateSyncPacket.handle() mỗi khi nhận packet đồng bộ trạng thái trượt. */
    public static void setSlidingState(UUID playerId, boolean sliding) {
        if (sliding) {
            syncedSliding.put(playerId, true);
        } else {
            syncedSliding.remove(playerId);
        }
    }

    /**
     * True nếu player hiện đang được coi là trong pose trượt (đang phát
     * slide). Dùng cho MomentumAnimationHandler để tự nhường, tránh chồng
     * layer bhopping_air/bhopping_land lên trên animation trượt.
     */
    public static boolean isActive(UUID playerId) {
        return currentPose.getOrDefault(playerId, DriftPose.NONE) != DriftPose.NONE;
    }

    /**
     * True nếu server đã báo player này đang trượt (đọc thẳng từ
     * syncedSliding, KHÔNG qua currentPose/isActive — isActive() nhường
     * DriftPose.NONE khi đang dash giữa chừng, chỉ đúng cho mục đích chọn
     * animation, không phản ánh đúng việc server có đang giữ hitbox thấp hay
     * không). Dùng cho PlayerHitboxMixin để quyết định hitbox phía client
     * (bản thân LocalPlayer + các RemoteClientPlayer khác).
     */
    public static boolean isSyncedSliding(UUID playerId) {
        return syncedSliding.getOrDefault(playerId, false);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (AbstractClientPlayer player : mc.level.players()) {
            refreshDimensionsIfChanged(player);
            updatePlayer(player);
        }
    }

    /** Trạng thái trượt (synced) tick trước, theo player — để chỉ refreshDimensions() đúng lúc đổi, không mỗi tick. */
    private static final Map<UUID, Boolean> lastRefreshSliding = new HashMap<>();

    /**
     * Refresh hitbox/eye-height ngay khi trạng thái trượt (server-synced)
     * của MỘT player thay đổi. Áp dụng cho mọi AbstractClientPlayer trong
     * mc.level.players() — danh sách này đã bao gồm cả LocalPlayer của
     * chính mình, nên không cần xử lý riêng cho local nữa. Không còn đụng
     * tới Pose: hitbox thật sự (xem PlayerHitboxMixin#getDimensions) đọc
     * thẳng isSyncedSliding(); refreshDimensions() ở đây chỉ để buộc client
     * tính lại NGAY LẬP TỨC thay vì đợi lần refresh tự nhiên tiếp theo của
     * vanilla (vốn thường gắn với đổi Pose — mà giờ ta không đổi Pose nữa).
     */
    private static void refreshDimensionsIfChanged(AbstractClientPlayer player) {
        UUID id = player.getUUID();
        boolean sliding = syncedSliding.getOrDefault(id, false);
        if (lastRefreshSliding.getOrDefault(id, false) != sliding) {
            lastRefreshSliding.put(id, sliding);
            player.refreshDimensions();
        }
    }

    private static void updatePlayer(AbstractClientPlayer player) {
        @SuppressWarnings("unchecked")
        ModifierLayer<IAnimation> layer =
                (ModifierLayer<IAnimation>) PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER_ID);
        if (layer == null) return; // chưa kịp khởi tạo factory cho player này

        DriftPose target = detectPose(player);

        UUID id = player.getUUID();
        if (currentPose.getOrDefault(id, DriftPose.NONE) == target) return; // không đổi, khỏi fade lại
        currentPose.put(id, target);

        switch (target) {
            case NONE -> layer.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.LINEAR), null);
            case SLIDE -> playIfPresent(layer, ANIM_SLIDE);
        }
    }

    private static DriftPose detectPose(AbstractClientPlayer player) {
        if (!syncedSliding.getOrDefault(player.getUUID(), false)) return DriftPose.NONE;

        // Ctrl (Drift) và Shift/Sneak (Dash) là 2 phím khác nhau, nhưng vẫn
        // có thể GIỮ Ctrl để trượt rồi BẤM THÊM Shift để dash giữa chừng
        // (dash không yêu cầu onGround, có thể xảy ra ngay lúc đang trượt) —
        // nhường animation dash hiển thị trước trong lúc đó, xem DashAnimationHandler.
        if (DashAnimationHandler.isActive(player.getUUID())) return DriftPose.NONE;

        return DriftPose.SLIDE;
    }

    /**
     * Chỉ phát animation nếu file resource tồn tại. Nếu chưa tạo file
     * (assets/highv/player_animation/...), getAnimation() trả về null —
     * không được đưa thẳng vào KeyframeAnimationPlayer (sẽ NPE lúc chơi),
     * nên bỏ qua và log cảnh báo 1 lần thay vì crash game.
     */
    private static void playIfPresent(ModifierLayer<IAnimation> layer, ResourceLocation animId) {
        Object raw = PlayerAnimationRegistry.getAnimation(animId);
        if (!(raw instanceof KeyframeAnimation anim)) {
            if (raw == null) {
                HighV.LOGGER.warn("[DriftAnimation] Không tìm thấy animation '{}' (thiếu file trong assets/{}/player_animation/?)",
                        animId, animId.getNamespace());
            } else {
                HighV.LOGGER.warn("[DriftAnimation] Animation '{}' không phải KeyframeAnimation (thực tế: {})",
                        animId, raw.getClass().getName());
            }
            return;
        }
        layer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.LINEAR),
                new KeyframeAnimationPlayer(anim));
    }
}