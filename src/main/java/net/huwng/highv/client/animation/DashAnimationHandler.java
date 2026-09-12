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
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Animation Dash — dash_front / dash_back / dash_left / dash_right.
 *
 * KHÁC với Momentum/Wallstride: Dash là 1 SỰ KIỆN TỨC THỜI (bấm Shift), không
 * phải trạng thái liên tục đọc được từ velocity/onGround mỗi tick. Vì vậy
 * không dò lại state ở đây — {@link #trigger} được gọi thẳng từ
 * ClientInputHandler.tickDashInput() ngay tại đúng thời điểm cạnh lên Shift
 * kích hoạt dash (cùng lúc gửi DashRequestPacket), kèm hướng đã tính sẵn từ
 * đúng các phím A/S/D đang giữ tại thời điểm đó.
 *
 * GIỚI HẠN QUAN TRỌNG: trigger() chỉ được gọi cho LOCAL PLAYER (ClientInputHandler
 * chỉ đọc phím của chính người chơi đang điều khiển client này). Server KHÔNG
 * broadcast lại sự kiện "player X vừa dash" cho người khác, nên người chơi
 * KHÁC đứng gần sẽ KHÔNG thấy animation dash này khi họ dash — chỉ chính chủ
 * (view local) mới thấy. Nếu muốn người khác cũng thấy, cần thêm 1 packet
 * server -> client broadcast riêng, hiện chưa có.
 *
 * Sau khi trigger, animation giữ trong DASH_ANIM_HOLD_TICKS rồi tự fade về
 * null — khớp tinh thần DASH_SUPPRESS_TICKS bên DashServerHandler (khoảng
 * thời gian dash còn "ảnh hưởng" tới vật lý trước khi mọi thứ trở lại bình
 * thường).
 *
 * Yêu cầu 4 file animation đã có sẵn trong:
 *  assets/highv/player_animation/dash_front.json
 *  assets/highv/player_animation/dash_back.json
 *  assets/highv/player_animation/dash_left.json
 *  assets/highv/player_animation/dash_right.json
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class DashAnimationHandler {

    private static final ResourceLocation LAYER_ID = HighV.id("dash_layer");

    private static final ResourceLocation ANIM_FRONT = HighV.id("dash_front");
    private static final ResourceLocation ANIM_BACK  = HighV.id("dash_back");
    private static final ResourceLocation ANIM_LEFT  = HighV.id("dash_right");
    private static final ResourceLocation ANIM_RIGHT = HighV.id("dash_left");

    /** Số tick giữ pose dash trước khi tự fade về null. 15 tick ≈ 0.75s, khớp DASH_SUPPRESS_TICKS bên server. */
    private static final int DASH_ANIM_HOLD_TICKS = 10;

    private static final int FADE_TICKS = 4;

    public enum Pose { NONE, FRONT, BACK, LEFT, RIGHT }

    private static final Map<UUID, Pose> currentPose = new HashMap<>();
    private static final Map<UUID, Integer> holdUntilTick = new HashMap<>();

    private DashAnimationHandler() {}

    /** Gọi 1 lần từ ClientSetup.register() hoặc FMLClientSetupEvent. */
    public static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
                LAYER_ID,
                1000, // cùng mức ưu tiên với các layer momentum/wallstride khác
                player -> new ModifierLayer<IAnimation>()
        );
    }

    /**
     * True nếu player hiện đang được coi là trong pose dash (chưa hết
     * DASH_ANIM_HOLD_TICKS). Dùng cho MomentumAnimationHandler (và các
     * layer full-thân khác) để tự nhường, tránh chồng layer lên animation
     * dash đang phát.
     */
    public static boolean isActive(UUID playerId) {
        return currentPose.getOrDefault(playerId, Pose.NONE) != Pose.NONE;
    }

    public static Pose getPose(UUID playerId) {
        return currentPose.getOrDefault(playerId, Pose.NONE);
    }

    /**
     * Gọi ngay tại thời điểm dash được kích hoạt (cạnh lên Shift), CHỈ dành
     * cho local player — xem ghi chú giới hạn ở đầu lớp. Áp dụng animation
     * ngay lập tức (không đợi tick kế tiếp) để phản hồi tức thời theo input.
     */
    public static void trigger(AbstractClientPlayer player, Pose category) {
        if (category == Pose.NONE) return;

        UUID id = player.getUUID();
        currentPose.put(id, category);
        holdUntilTick.put(id, player.tickCount + DASH_ANIM_HOLD_TICKS);
        applyPose(player, category);
    }

    /** Mỗi tick chỉ kiểm tra hết hạn để fade về null — việc BẬT pose do trigger() lo. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || holdUntilTick.isEmpty()) return;

        for (AbstractClientPlayer player : mc.level.players()) {
            UUID id = player.getUUID();
            Integer until = holdUntilTick.get(id);
            if (until == null) continue;

            if (player.tickCount >= until) {
                holdUntilTick.remove(id);
                currentPose.put(id, Pose.NONE);
                applyPose(player, Pose.NONE);
            }
        }
    }

    private static void applyPose(AbstractClientPlayer player, Pose target) {
        @SuppressWarnings("unchecked")
        ModifierLayer<IAnimation> layer =
                (ModifierLayer<IAnimation>) PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER_ID);
        if (layer == null) return; // chưa kịp khởi tạo factory cho player này

        switch (target) {
            case NONE -> layer.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.LINEAR), null);
            case FRONT -> playIfPresent(layer, ANIM_FRONT);
            case BACK  -> playIfPresent(layer, ANIM_BACK);
            case LEFT  -> playIfPresent(layer, ANIM_LEFT);
            case RIGHT -> playIfPresent(layer, ANIM_RIGHT);
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
                HighV.LOGGER.warn("[DashAnimation] Không tìm thấy animation '{}' (thiếu file trong assets/{}/player_animation/?)",
                        animId, animId.getNamespace());
            } else {
                HighV.LOGGER.warn("[DashAnimation] Animation '{}' không phải KeyframeAnimation (thực tế: {})",
                        animId, raw.getClass().getName());
            }
            return;
        }
        layer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.LINEAR),
                new KeyframeAnimationPlayer(anim));
    }
}