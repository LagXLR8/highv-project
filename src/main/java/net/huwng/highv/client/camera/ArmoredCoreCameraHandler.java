package net.huwng.highv.client.camera;

import net.huwng.highv.HighV;
import net.huwng.highv.client.HighVClientConfig;
import net.huwng.highv.client.SpeedEffectSystem;
import net.huwng.highv.client.animation.DashAnimationHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;

/**
 * Hệ thống Camera Chiến Đấu Góc Nhìn Thứ 3 Chuẩn Armored Core 6 (AC6 True 3D Combat Camera):
 *
 * 1. KHÔNG GIAN CHIẾN ĐẤU RỘNG MỞ & ĐỘ CAO VƯỢT TRỘI:
 *    - Khoảng cách camera cơ sở 5.2m (thay vì 4.0m chật hẹp của vanilla).
 *    - Tầm nhìn bao quát toàn bộ cơ thể, hiệu ứng phản lực, vệt kiếm và đấu trường.
 *    - Độ cao nâng +0.75m trên đỉnh đầu, góc nhìn chúc nhẹ ~8° xuống phía trước.
 *    - Nhân vật nằm ở 1/3 dưới giữa màn hình: Tâm ngắm và mục tiêu phía trước luôn thông thoáng 100%!
 *
 * 2. CO GIÃN KHOẢNG CÁCH THEO TỐC ĐỘ (DYNAMIC BOOST ZOOM):
 *    - Đi bộ / Đứng yên: 5.2m.
 *    - Chạy nước rút (Sprint): Lùi mượt về 5.8m.
 *    - Dash / Quick Boost: Giật lùi đàn hồi ra 6.4m - 6.6m với lò xo giảm chấn cực kỳ đã mắt!
 *
 * 3. GÓC LỆCH VAI TINH TẾ & TỰ ĐỔI VAI:
 *    - Lệch nhẹ 0.32m sang phải tạo góc quay thể thao, tự động chuyển sang vai trái nếu vách đá bên phải cản.
 *    - Căn giữa (0.0m) khi trong hầm chật hẹp.
 *
 * 4. TRIỆT TIÊU 100% LỖI LỆCH TÂM NGẮM:
 *    - Tọa độ Camera thật được di chuyển trong không gian 3D, loại bỏ hack dịch ma trận 2D cũ.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class ArmoredCoreCameraHandler {

    private ArmoredCoreCameraHandler() {}

    // ── Giá trị nội suy hiện tại (Mỗi frame render) ──
    public static double currentDistance = 5.2;
    public static double targetDistance  = 5.2;

    public static double currentHeight   = 0.75;
    public static double targetHeight    = 0.75;

    public static double currentSide     = -0.32;
    public static double targetSide      = -0.32;

    public static double currentDownwardPitch = 8.0;
    public static double targetDownwardPitch  = 8.0;

    /**
     * Can thiệp vào sự kiện NeoForge chính thống để set khoảng cách camera 3D thực tế.
     * Minecraft tự động chạy thuật toán chống xuyên tường (getMaxZoom) trên giá trị này!
     */
    @SubscribeEvent
    public static void onCalculateCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        if (!HighVClientConfig.ENABLE_AC6_CAMERA.get()) return;
        if (!ShoulderCameraState.INSTANCE.isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.getCameraType().isFirstPerson()) return;

        event.setDistance((float) currentDistance);
    }

    /**
     * Cập nhật toàn bộ các thông số động lực học mỗi frame trước khi Camera setup.
     */
    public static void update(double dt) {
        if (!HighVClientConfig.ENABLE_AC6_CAMERA.get()) return;
        if (!ShoulderCameraState.INSTANCE.isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        double baseDist  = HighVClientConfig.AC6_CAMERA_BASE_DISTANCE.get();
        double boostDist = HighVClientConfig.AC6_CAMERA_BOOST_DISTANCE.get();
        double baseHeight = HighVClientConfig.AC6_CAMERA_HEIGHT.get();
        double baseSide  = HighVClientConfig.AC6_CAMERA_SHOULDER_OFFSET.get();

        boolean isDashing = DashAnimationHandler.isActive(player.getUUID());
        boolean isSprinting = player.isSprinting();
        boolean isAirborne = !player.onGround();
        boolean isCrouching = player.isCrouching();
        double speed = SpeedEffectSystem.smoothedSpeed;

        // 1. Khoảng cách camera động (Dynamic Boost Zoom)
        if (isDashing) {
            targetDistance = boostDist;
        } else if (isSprinting || speed > 6.0) {
            double speedRatio = Math.min(1.0, Math.max(0.0, (speed - 5.0) / 15.0));
            targetDistance = baseDist + (boostDist - baseDist) * (0.5 + 0.5 * speedRatio);
        } else {
            targetDistance = baseDist;
        }

        // Nội suy khoảng cách: Bung nhanh khi tăng tốc (0.03s), thu chậm êm ái khi dừng (0.08s)
        double distSmoothing = targetDistance > currentDistance ? 0.03 : 0.08;
        currentDistance = CameraFeelMath.damp(currentDistance, targetDistance, distSmoothing, dt);

        // 2. Độ cao camera (Vertical Clearance)
        if (isAirborne) {
            targetHeight = baseHeight + 0.20; // Nâng cao hơn khi đang trên không để bao quát đất & trời
        } else if (isCrouching) {
            targetHeight = baseHeight - 0.22;
        } else {
            targetHeight = baseHeight;
        }
        currentHeight = CameraFeelMath.damp(currentHeight, targetHeight, 0.05, dt);

        // 3. Độ lệch vai & Tự động đổi vai khi sát tường
        Vec3 look = player.getLookAngle();
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 eyePos = player.getEyePosition();

        double wallDetectDist = 1.10;
        Vec3 rightCheck = eyePos.add(right.scale(wallDetectDist));
        BlockHitResult hitRight = mc.level.clip(new ClipContext(
                eyePos, rightCheck, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean wallOnRight = hitRight.getType() == HitResult.Type.BLOCK;

        Vec3 leftCheck = eyePos.add(right.scale(-wallDetectDist));
        BlockHitResult hitLeft = mc.level.clip(new ClipContext(
                eyePos, leftCheck, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean wallOnLeft = hitLeft.getType() == HitResult.Type.BLOCK;

        if (wallOnRight && !wallOnLeft) {
            targetSide = baseSide; // Chuyển sang vai trái
        } else if (wallOnLeft && !wallOnRight) {
            targetSide = -baseSide; // Vai phải
        } else if (wallOnRight && wallOnLeft) {
            targetSide = 0.0; // Hẻm hẹp -> Căn giữa
        } else {
            targetSide = -baseSide; // Mặc định lệch nhẹ vai phải
        }
        currentSide = CameraFeelMath.damp(currentSide, targetSide, 0.06, dt);

        // 4. Góc chúc nhẹ xuống phía trước (Downward Pitch Compensation)
        // Đảm bảo tâm ngắm luôn nhìn thẳng vào điểm nhắm trước mặt ở cự ly ~25m
        targetDownwardPitch = Math.toDegrees(Math.atan2(currentHeight, Math.max(1.0, currentDistance)));
        currentDownwardPitch = CameraFeelMath.damp(currentDownwardPitch, targetDownwardPitch, 0.05, dt);
    }
}
