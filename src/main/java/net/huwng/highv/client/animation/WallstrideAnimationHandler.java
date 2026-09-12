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
import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.item.GrapplingHookItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Animation bám tường (Wallstride) — wallrun_left / wallrun_right.
 *
 * Server (WallstrideServerHandler) không đồng bộ riêng 1 flag "đang bám
 * tường" cho client — chỉ giữ wallOutwardNormal nội bộ server để
 * RicochetServerHandler đọc. Nên phía client tự dò lại y hệt điều kiện bên
 * server (không đứng đất, có enchant Wallstride, đủ tốc độ ngang, có tường
 * đặc sát bên trái/phải trong tầm WALL_DETECT_RANGE) để tự quyết định pose,
 * KHÔNG dựa vào bất kỳ dữ liệu server nào khác ngoài velocity/vị trí vốn đã
 * đồng bộ sẵn qua thực thể.
 *
 * Quy ước trái/phải: bắn tia sang PHẢI trước (vuông góc hướng nhìn theo
 * yaw) — nếu trúng block đặc, tường ở BÊN PHẢI -> wallrun_right. Nếu không,
 * bắn sang TRÁI — trúng thì tường ở BÊN TRÁI -> wallrun_left. Không bên nào
 * trúng -> không có animation (null).
 *
 * QUAN TRỌNG — tránh chồng layer với Bhop: animation bám tường và
 * bhopping_air đều là override toàn thân (torso/tay/chân), nên nếu cả 2
 * layer cùng active một lúc (rất dễ xảy ra vì bám tường luôn ở trên không
 * + tốc độ cao, đúng điều kiện của bhopping_air), rotation của 2 layer sẽ
 * CỘNG DỒN lên nhau, ra tư thế sai. MomentumAnimationHandler đã được sửa để
 * gọi WallstrideAnimationHandler.isActive(uuid) và tự nhường (về IDLE) khi
 * đang bám tường — xem MomentumAnimationHandler.updatePlayer().
 *
 * Yêu cầu 2 file animation đã có sẵn trong:
 *  assets/highv/player_animation/wallrun_left.json
 *  assets/highv/player_animation/wallrun_right.json
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class WallstrideAnimationHandler {

    private static final ResourceLocation LAYER_ID = HighV.id("wallstride_layer");

    private static final ResourceLocation ANIM_WALLRUN_LEFT  = HighV.id("wallrun_left");
    private static final ResourceLocation ANIM_WALLRUN_RIGHT = HighV.id("wallrun_right");

    /** Tầm bắn tia dò tường sang 2 bên, block. Khớp WALL_DETECT_RANGE của WallstrideServerHandler. */
    private static final double WALL_DETECT_RANGE = 1.1;

    /** Tốc độ ngang tối thiểu để coi là đủ đà bám tường, block/tick. Khớp MIN_ENTRY_SPEED bên server. */
    private static final double MIN_WALLSTRIDE_SPEED = 0.20;

    private static final int FADE_TICKS = 5;

    private enum Pose { NONE, LEFT, RIGHT }
    private static final Map<UUID, Pose> currentPose = new HashMap<>();

    private WallstrideAnimationHandler() {}

    /** Gọi 1 lần từ ClientSetup.register() hoặc FMLClientSetupEvent. */
    public static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
                LAYER_ID,
                1000, // cùng mức ưu tiên với momentum layer — không đè lên đòn chém combat
                player -> new ModifierLayer<IAnimation>()
        );
    }

    /**
     * True nếu player hiện đang được coi là bám tường (đang phát wallrun_left
     * hoặc wallrun_right). Dùng cho MomentumAnimationHandler để tránh chồng
     * layer bhopping_air lên trên animation bám tường.
     */
    public static boolean isActive(UUID playerId) {
        return currentPose.getOrDefault(playerId, Pose.NONE) != Pose.NONE;
    }

    /**
     * Phía tường đang bám:
     *  +1 = tường bên phải
     *  -1 = tường bên trái
     *   0 = không bám tường
     */
    public static int getWallSide(UUID playerId) {
        Pose p = currentPose.getOrDefault(playerId, Pose.NONE);
        if (p == Pose.RIGHT) return 1;
        if (p == Pose.LEFT) return -1;
        return 0;
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

        Pose target = detectPose(player);

        UUID id = player.getUUID();
        if (currentPose.getOrDefault(id, Pose.NONE) == target) return; // không đổi, khỏi fade lại
        currentPose.put(id, target);

        switch (target) {
            case NONE -> layer.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.LINEAR), null);
            case LEFT -> playIfPresent(layer, ANIM_WALLRUN_LEFT);
            case RIGHT -> playIfPresent(layer, ANIM_WALLRUN_RIGHT);
        }
    }

    /**
     * Xác định pose bám tường hiện tại, lặp lại đúng điều kiện của
     * WallstrideServerHandler.onPlayerTick(): không đứng đất, có enchant, đủ
     * tốc độ ngang, có tường đặc sát 1 trong 2 bên.
     */
    private static Pose detectPose(AbstractClientPlayer player) {
        if (player.onGround()) return Pose.NONE;
        if (ModEnchantments.getWallstrideLevel(player) <= 0) return Pose.NONE;

        // Nếu hook đang bám và kéo người chơi, lập tức huỷ pose bám tường
        GrapplingHookEntity hook = GrapplingHookItem.findActiveHook(player.level(), player);
        if (hook != null && hook.isAttached()) return Pose.NONE;

        Vec3 v = player.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        if (speed < MIN_WALLSTRIDE_SPEED) return Pose.NONE;

        double yawRad = Math.toRadians(player.getYRot());
        Vec3 right = new Vec3(-Math.cos(yawRad), 0, -Math.sin(yawRad));
        Vec3 left = right.scale(-1);

        Vec3 originChest = player.position().add(0, player.getBbHeight() * 0.65, 0);
        Vec3 originWaist = player.position().add(0, player.getBbHeight() * 0.35, 0);

        boolean rightSolid = raycastSolid(player, originChest, right) || raycastSolid(player, originWaist, right);
        boolean leftSolid  = raycastSolid(player, originChest, left)  || raycastSolid(player, originWaist, left);

        if (rightSolid) return Pose.RIGHT;
        if (leftSolid)  return Pose.LEFT;
        return Pose.NONE;
    }

    private static boolean raycastSolid(AbstractClientPlayer player, Vec3 from, Vec3 dir) {
        Level level = player.level();
        Vec3 to = from.add(dir.scale(WALL_DETECT_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        BlockState state = level.getBlockState(hit.getBlockPos());
        return state.isSolid();
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
                HighV.LOGGER.warn("[WallstrideAnimation] Không tìm thấy animation '{}' (thiếu file trong assets/{}/player_animation/?)",
                        animId, animId.getNamespace());
            } else {
                HighV.LOGGER.warn("[WallstrideAnimation] Animation '{}' không phải KeyframeAnimation (thực tế: {})",
                        animId, raw.getClass().getName());
            }
            return;
        }
        layer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.LINEAR),
                new KeyframeAnimationPlayer(anim));
    }
}
