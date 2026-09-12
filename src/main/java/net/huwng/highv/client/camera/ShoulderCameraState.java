package net.huwng.highv.client.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Trạng thái bật/tắt và điều khiển vị trí của "shoulder camera" (camera qua
 * vai)
 * trong góc nhìn thứ 3.
 *
 * Cải tiến:
 * 1. Vị trí cao hơn: VERTICAL_OFFSET được tinh chỉnh để camera cao hơn tầm vai,
 * cho tầm nhìn thoáng và không bị thấp.
 * 2. Tự động đổi vai (Auto Shoulder Swap):
 * - Raycast sang bên phải người chơi (~1.1m). Nếu phát hiện có tường/bề mặt
 * block chắn, camera tự động chuyển mượt mà sang vai trái (+0.55).
 * - Khi bên phải thông thoáng (hoặc bên trái có tường), camera tự động
 * lướt trở lại vai phải (-0.55).
 * - Sử dụng hàm giảm chấn damp (CameraFeelMath) để quá trình đổi vai
 * diễn ra mượt mà, chuẩn trải nghiệm game bắn súng/hành động TPS.
 */
public final class ShoulderCameraState {
    public static final ShoulderCameraState INSTANCE = new ShoulderCameraState();

    private ShoulderCameraState() {
    }

    public static double getBaseHeight() {
        return net.huwng.highv.client.HighVClientConfig.SHOULDER_CAMERA_HEIGHT.get();
    }

    public static double getBaseSideOffset() {
        return net.huwng.highv.client.HighVClientConfig.SHOULDER_CAMERA_OFFSET.get();
    }

    public static double getBaseDistance() {
        return net.huwng.highv.client.HighVClientConfig.SHOULDER_CAMERA_DISTANCE.get();
    }

    /** Khoảng cách raycast phát hiện tường bên cạnh người chơi (mét). */
    private static final double WALL_DETECT_DIST = 1.25;

    /** Tốc độ làm mượt khi đổi vai / chỉnh cao độ (giây). */
    private static final double SWAP_SMOOTHING = 0.08;

    private boolean enabled = false;
    private double currentSideOffset = -0.70;
    private double targetSideOffset = -0.70;
    private double currentVerticalOffset = 0.85;
    private double targetVerticalOffset = 0.85;

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        enabled = !enabled;
        if (enabled) {
            targetSideOffset = -getBaseSideOffset();
            targetVerticalOffset = getBaseHeight();
        }
    }

    public double getCurrentSideOffset() {
        return currentSideOffset;
    }

    public double getCurrentVerticalOffset() {
        return currentVerticalOffset;
    }

    /**
     * Cập nhật vị trí và tự động đổi vai mỗi frame/tick.
     */
    public void update(double dt) {
        if (!enabled)
            return;

        if (net.huwng.highv.client.HighVClientConfig.ENABLE_AC6_CAMERA.get()) {
            ArmoredCoreCameraHandler.update(dt);
            currentSideOffset = ArmoredCoreCameraHandler.currentSide;
            currentVerticalOffset = ArmoredCoreCameraHandler.currentHeight;
            return;
        }

        double baseSide = getBaseSideOffset();
        double baseHeight = getBaseHeight();

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && mc.level != null) {
            Vec3 look = player.getLookAngle();
            Vec3 lookH = new Vec3(look.x, 0.0, look.z);
            // Vector chuẩn vuông góc sang bên phải của hướng nhìn người chơi
            Vec3 right = lookH.lengthSqr() > 1.0e-5
                    ? lookH.normalize().cross(new Vec3(0, 1, 0)).normalize()
                    : new Vec3(1, 0, 0);
            Vec3 eyePos = player.getEyePosition();

            // 1. Kiểm tra tường bên phải
            Vec3 rightCheck = eyePos.add(right.scale(WALL_DETECT_DIST));
            BlockHitResult hitRight = mc.level.clip(new ClipContext(
                    eyePos, rightCheck, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            boolean wallOnRight = hitRight.getType() == HitResult.Type.BLOCK;

            // 2. Kiểm tra tường bên trái
            Vec3 leftCheck = eyePos.add(right.scale(-WALL_DETECT_DIST));
            BlockHitResult hitLeft = mc.level.clip(new ClipContext(
                    eyePos, leftCheck, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            boolean wallOnLeft = hitLeft.getType() == HitResult.Type.BLOCK;

            // Đổi vai thông minh:
            // - Tường bên phải -> đổi sang vai trái (+baseSide)
            // - Tường bên trái -> đổi về vai phải (-baseSide)
            // - Tường ở cả 2 bên (hầm hẹp 1-block) -> căn giữa (0.0)
            // - Thoáng cả 2 bên -> mặc định vai phải (-baseSide)
            if (wallOnRight && !wallOnLeft) {
                targetSideOffset = baseSide;
            } else if (wallOnLeft && !wallOnRight) {
                targetSideOffset = -baseSide;
            } else if (wallOnRight && wallOnLeft) {
                targetSideOffset = 0.0;
            } else {
                targetSideOffset = -baseSide;
            }

            // 3. Kiểm tra trần phía trên đầu (Ceiling clearance)
            Vec3 upCheck = eyePos.add(0.0, baseHeight + 0.25, 0.0);
            BlockHitResult hitUp = mc.level.clip(new ClipContext(
                    eyePos, upCheck, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hitUp.getType() == HitResult.Type.BLOCK) {
                double clearance = Math.sqrt(hitUp.getLocation().distanceToSqr(eyePos));
                targetVerticalOffset = Math.max(0.0, clearance - 0.20);
            } else {
                targetVerticalOffset = baseHeight;
            }
        }

        currentSideOffset = CameraFeelMath.damp(currentSideOffset, targetSideOffset, SWAP_SMOOTHING, dt);
        currentVerticalOffset = CameraFeelMath.damp(currentVerticalOffset, targetVerticalOffset, SWAP_SMOOTHING, dt);
    }
}
