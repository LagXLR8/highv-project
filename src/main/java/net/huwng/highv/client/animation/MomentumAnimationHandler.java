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
import net.huwng.highv.enchantment.ModEnchantments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Chuyển đổi animation thân người theo trạng thái bhop hiện tại (đọc từ
 * getDeltaMovement() — hợp lệ phía client vì BhopServerHandler gửi
 * ClientboundSetEntityMotionPacket mỗi khi ép velocity).
 *
 * Không có flag "đang bhop" nào được đồng bộ riêng từ server (BhopServerHandler
 * chỉ giữ momentumSpeed ở phía server), nên phía client dùng ngưỡng tốc độ
 * ngang (>= 10 b/s, khớp THRESHOLD cũ dùng cho pose "light") LÀM PROXY cho
 * "đang trong trạng thái bhop", CỘNG THÊM điều kiện phải đang mặc giày có
 * enchant Bhopping (ModEnchantments.getBhopLevel > 0) — để tránh nhầm các
 * nguồn tốc độ cao khác (dash, rơi tự do, elytra...) thành bhop khi không hề
 * mang enchant này.
 *
 * Khi đang coi là "trong bhop" (speed >= ngưỡng):
 *  - Đang trên không (!onGround)      -> bhopping_air (loop)
 *  - Chân vừa/đang chạm đất (onGround) -> bhopping_land (loop)
 *  - Nhảy lên không trở lại            -> quay lại bhopping_air
 * Dưới ngưỡng tốc độ -> không có animation lean nào (trả về null / vanilla).
 *
 * onGround() của player khi đang bhop thật ra "nhấp nháy" true/false rất
 * nhanh — chạm đất 1 tick rồi hop lên ngay tick sau — nên nếu đổi pose ngay
 * theo onGround thô, bhopping_land gần như không kịp hiện/fade xong đã bị
 * bhopping_air ghi đè. Vì vậy có 1 khoảng "giữ tối thiểu" (MIN_LAND_HOLD_TICKS)
 * sau khi vào BHOP_LAND: trong khoảng đó dù onGround đã về false (đang bay
 * lại), vẫn KHÔNG cho chuyển pose, đảm bảo animation land luôn kịp chạy trọn
 * (kể cả thời gian crossfade FADE_TICKS) trước khi đổi sang air.
 *
 * Yêu cầu 2 file animation đã có sẵn trong:
 *  assets/highv/player_animation/bhopping_air.json
 *  assets/highv/player_animation/bhopping_land.json
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class MomentumAnimationHandler {

    private static final ResourceLocation LAYER_ID = HighV.id("momentum_lean_layer");

    private static final ResourceLocation ANIM_BHOP_AIR  = HighV.id("bhopping_air");
    private static final ResourceLocation ANIM_BHOP_LAND = HighV.id("bhopping_land");

    /** Ngưỡng tốc độ ngang (block/tick) để coi là "đang trong bhop". ~10 b/s. */
    private static final double BHOP_SPEED_THRESHOLD = 0.5;

    private static final int FADE_TICKS = 6;

    /**
     * Số tick tối thiểu phải giữ BHOP_LAND trước khi được phép đổi sang pose
     * khác (kể cả khi onGround đã về false vì hop lên lại). Phải >= FADE_TICKS
     * để animation land luôn kịp fade-in trọn vẹn, tránh bị "cắt cụt".
     */
    private static final int MIN_LAND_HOLD_TICKS = 10;

    /** Trạng thái pose hiện tại mỗi player, tránh gọi replaceAnimationWithFade lặp mỗi tick. */
    private enum Pose { IDLE, BHOP_AIR, BHOP_LAND }
    private static final java.util.Map<java.util.UUID, Pose> currentPose = new java.util.HashMap<>();

    /** Tick (tuyệt đối, theo player.tickCount) mà tới lúc đó vẫn còn bị khóa ở BHOP_LAND. */
    private static final java.util.Map<java.util.UUID, Integer> landLockUntilTick = new java.util.HashMap<>();

    private MomentumAnimationHandler() {}

    /** Gọi 1 lần từ ClientSetup.register() hoặc FMLClientSetupEvent. */
    public static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
                LAYER_ID,
                1000, // ưu tiên thấp hơn layer combat của BetterCombat, không đè lên đòn chém
                player -> new ModifierLayer<IAnimation>()
        );
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (AbstractClientPlayer player : mc.level.players()) {
            updatePlayer(player);
        }
    }

    private static void updatePlayer(AbstractClientPlayer player) {
        @SuppressWarnings("unchecked")
        ModifierLayer<IAnimation> layer =
                (ModifierLayer<IAnimation>) PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER_ID);
        if (layer == null) return; // chưa kịp khởi tạo factory cho player này

        Vec3 v = player.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);

        Pose rawTarget;
        if (speed < BHOP_SPEED_THRESHOLD) {
            rawTarget = Pose.IDLE;
        } else if (ModEnchantments.getBhopLevel(player) <= 0) {
            // Đủ tốc độ nhưng KHÔNG có enchant Bhopping trên giày -> tốc độ
            // cao này đến từ nguồn khác (dash, elytra, rơi tự do, v.v.), không
            // phải bhop thật -> không phát animation bhop.
            rawTarget = Pose.IDLE;
        } else if (WallstrideAnimationHandler.isActive(player.getUUID())
                || DashAnimationHandler.isActive(player.getUUID())
                || DriftAnimationHandler.isActive(player.getUUID())) {
            // Đang bám tường (wallrun_left/right), đang trong pose dash
            // (dash_front/back/left/right), hoặc đang trượt (slide) -> nhường,
            // không chồng thêm layer bhopping_air/bhopping_land lên trên
            // (nhiều layer override toàn thân sẽ cộng dồn rotation nếu cùng
            // active). Dash đẩy tốc độ ngang lên rất cao (DASH_STRENGTH), và
            // Drift yêu cầu chính xác cùng điều kiện onGround+tốc độ cao mà
            // BHOP_LAND cũng dùng, nên nếu không check riêng sẽ luôn bị đè.
            // Xem WallstrideAnimationHandler / DashAnimationHandler / DriftAnimationHandler.
            rawTarget = Pose.IDLE;
        } else {
            rawTarget = player.onGround() ? Pose.BHOP_LAND : Pose.BHOP_AIR;
        }

        java.util.UUID id = player.getUUID();
        Pose current = currentPose.getOrDefault(id, Pose.IDLE);

        boolean overriddenByOtherSystem =
                WallstrideAnimationHandler.isActive(player.getUUID())
                        || DashAnimationHandler.isActive(player.getUUID())
                        || DriftAnimationHandler.isActive(player.getUUID());

        // Đang ở BHOP_LAND và muốn đổi sang pose khác -> chỉ cho phép nếu đã
        // qua khỏi khoảng giữ tối thiểu, để animation land không bị cắt cụt
        // bởi onGround "nhấp nháy" khi hop liên tục. NGOẠI LỆ: nếu Wallstride
        // hoặc Dash đang active thì bỏ qua khóa này luôn — 2 hệ thống đó có
        // ưu tiên cao hơn, không được để khóa land giữ bhop lại đè lên chúng.
        if (current == Pose.BHOP_LAND && rawTarget != Pose.BHOP_LAND && !overriddenByOtherSystem) {
            int lockUntil = landLockUntilTick.getOrDefault(id, Integer.MIN_VALUE);
            if (player.tickCount < lockUntil) return;
        }

        Pose target = rawTarget;
        if (target == current) return; // không đổi, khỏi fade lại
        currentPose.put(id, target);

        if (target == Pose.BHOP_LAND) {
            landLockUntilTick.put(id, player.tickCount + MIN_LAND_HOLD_TICKS);
        }

        switch (target) {
            case IDLE -> layer.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.LINEAR), null);
            case BHOP_AIR -> playIfPresent(layer, ANIM_BHOP_AIR);
            case BHOP_LAND -> playIfPresent(layer, ANIM_BHOP_LAND);
        }
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
                HighV.LOGGER.warn("[MomentumAnimation] Không tìm thấy animation '{}' (thiếu file trong assets/{}/player_animation/?)",
                        animId, animId.getNamespace());
            } else {
                HighV.LOGGER.warn("[MomentumAnimation] Animation '{}' không phải KeyframeAnimation (thực tế: {})",
                        animId, raw.getClass().getName());
            }
            return;
        }
        layer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.LINEAR),
                new KeyframeAnimationPlayer(anim));
    }
}