package net.huwng.highv.client.camera;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.huwng.highv.HighV;
import net.huwng.highv.client.HighVClientConfig;
import net.huwng.highv.client.SpeedEffectSystem;
import net.huwng.highv.client.animation.DashAnimationHandler;
import net.huwng.highv.client.animation.DriftAnimationHandler;
import net.huwng.highv.client.animation.WallstrideAnimationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.UUID;

/**
 * Hệ thống POV Động Toàn Diện (Dynamic Point of View & Dynamic FOV):
 * Mang lại trải nghiệm góc nhìn thứ nhất sống động, điện ảnh chuẩn AAA:
 *
 * 1. DYNAMIC FOV:
 *    - Tự động mở rộng tầm nhìn mượt mà khi chạy nước rút (Sprint: +4.5°).
 *    - Bùng nổ góc rộng khi tăng tốc cao (Speed Rush: lên tới +14.0°).
 *    - Xung lực phóng tầm nhìn khi lướt phản lực (Dash Boost: +5.5°).
 *    - Mở rộng góc nhìn khi trượt tốc độ (Drift: +3.0°).
 *    - Micro-zoom đanh thép khi vung chém Katana (-1.8°) tạo điểm nhấn va đập.
 *
 * 2. DYNAMIC CAMERA POV (Góc nhìn camera nhún nhảy & chuyển động theo người chơi):
 *    - Head Bobbing tự nhiên: Nhún nhẹ theo bước chân khi đi bộ và chạy (thay thế view bobbing thô của vanilla).
 *    - Strafe Roll: Camera nghiêng nhẹ theo lực ly tâm khi di chuyển ngang A/D (±2.5°).
 *    - Jump & Fall Tilt: Ngẩng/cúi nhẹ đón hướng chuyển động khi nhảy và rơi tự do.
 *    - Landing Impact Cushion: Giảm chấn lò xo nhún nảy khi tiếp đất đầm tay.
 *    - Slash Directional Recoil: Chấn động giật nhẹ camera theo hướng vung nhát chém.
 *    - Wallstride & Drift Lean: Nghiêng camera bám tường (Wallstride) và trượt cua (Drift).
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class DynamicPovHandler {

    private DynamicPovHandler() {}

    // ── Output Camera POV Transform (Áp dụng lên Camera Matrix) ──
    public static float cameraBobX = 0.0f;
    public static float cameraBobY = 0.0f;
    public static float cameraPitch = 0.0f;
    public static float cameraYaw = 0.0f;
    public static float cameraRoll = 0.0f;
    public static float cameraLandOffset = 0.0f;

    // ── Biến trạng thái FOV ──
    private static double displayedExtraFov = 0.0;
    private static double targetExtraFov = 0.0;
    private static float slashFovImpulse = 0.0f;
    private static float dashFovImpulse = 0.0f;

    // ── Biến trạng thái Camera POV ──
    private static long lastFrameNanos = -1;
    private static float walkPhase = 0.0f;
    private static float smoothWalkWeight = 0.0f;
    private static float smoothSprintWeight = 0.0f;
    private static float smoothStrafeRoll = 0.0f;
    private static float smoothAirPitch = 0.0f;

    private static float landVelocity = 0.0f;
    private static float landOffset = 0.0f;
    private static boolean wasOnGround = true;
    private static double prevAirVelY = 0.0;

    // Slash impulse
    private static int prevSwingTime = 0;
    private static int slashDir = 1;
    private static float slashImpulseX = 0.0f;
    private static float slashImpulsePitch = 0.0f;
    private static float slashImpulseRoll = 0.0f;

    // ─────────────────────────────────────────────────────────────────────────
    // 1. DYNAMIC FOV (ComputeFov Event)
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) return;
        if (!HighVClientConfig.ENABLE_DYNAMIC_FOV.get()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        long now = System.nanoTime();
        double dt = lastFrameNanos < 0 ? 0.0 : (now - lastFrameNanos) / 1_000_000_000.0;
        dt = Math.min(dt, 0.066);

        // 1A. Tính toán FOV theo tốc độ (bắt đầu từ bước đi 4.2 b/s đến tốc độ cao 35 b/s)
        double speed = SpeedEffectSystem.smoothedSpeed;
        double speedFov = 0.0;
        if (player.isSprinting() && speed > 2.0) {
            speedFov += 4.5; // Sprint baseline FOV mượt mà
        }
        if (speed > 5.5) {
            double highRatio = Mth.clamp((speed - 5.5) / 25.0, 0.0, 1.0);
            speedFov += highRatio * 10.0; // Tăng dần lên tối đa +14.5 độ khi tốc độ cực cao
        }

        // 1B. FOV khi Dash (Lướt phản lực)
        UUID uuid = player.getUUID();
        if (DashAnimationHandler.isActive(uuid)) {
            dashFovImpulse = (float) CameraFeelMath.damp(dashFovImpulse, 5.5f, 0.02, dt);
        } else {
            dashFovImpulse = (float) CameraFeelMath.damp(dashFovImpulse, 0.0f, 0.08, dt);
        }

        // 1C. FOV khi Drift (Trượt)
        double driftFov = DriftAnimationHandler.isActive(uuid) ? 3.0 : 0.0;

        // 1D. Suy giảm Slash micro-zoom
        slashFovImpulse = (float) CameraFeelMath.damp(slashFovImpulse, 0.0f, 0.05, dt);

        targetExtraFov = speedFov + dashFovImpulse + driftFov + slashFovImpulse;

        // Nội suy mượt mà (bắt nhanh, nhả chậm)
        double smoothing = targetExtraFov > displayedExtraFov ? 0.04 : 0.15;
        displayedExtraFov = CameraFeelMath.damp(displayedExtraFov, targetExtraFov, smoothing, dt);

        if (Math.abs(displayedExtraFov) > 0.001) {
            event.setFOV(event.getFOV() + (float) displayedExtraFov);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. TICK TRACKING (Phát hiện nhát chém & tiếp đất)
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.isPaused()) return;

        // Phát hiện chém kiếm để kích hoạt xung lực camera (Slash recoil)
        int currentSwingTime = player.swingTime;
        if (currentSwingTime == 1 && prevSwingTime == 0) {
            slashDir = -slashDir;
            slashImpulseRoll = slashDir * 1.4f;
            slashImpulsePitch = -0.7f;
            slashImpulseX = slashDir * 0.5f;
            slashFovImpulse = -1.6f; // Micro-zoom đanh thép
        }
        prevSwingTime = currentSwingTime;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. CẬP NHẬT CAMERA POV DYNAMICS (Mỗi frame render)
    // ─────────────────────────────────────────────────────────────────────────
    public static void updateCamera(LocalPlayer player, float partialTicks) {
        long now = System.nanoTime();
        if (lastFrameNanos < 0) {
            lastFrameNanos = now;
            return;
        }
        float dt = (float) Math.min((now - lastFrameNanos) / 1_000_000_000.0, 0.066);
        lastFrameNanos = now;

        Vec3 move = player.getDeltaMovement();
        double horizSpeed = Math.sqrt(move.x * move.x + move.z * move.z);
        boolean onGround = player.onGround();
        boolean isMoving = onGround && horizSpeed > 0.02;
        boolean isSprinting = player.isSprinting() && isMoving;

        // 3A. Trọng số bước chân
        float targetWalk = isMoving ? (float) Mth.clamp(horizSpeed / (isSprinting ? 0.28 : 0.215), 0.0, 1.0) : 0.0f;
        smoothWalkWeight = (float) CameraFeelMath.damp(smoothWalkWeight, targetWalk, 0.06, dt);

        float targetSprint = isSprinting ? 1.0f : 0.0f;
        smoothSprintWeight = (float) CameraFeelMath.damp(smoothSprintWeight, targetSprint, 0.04, dt);

        float cadence = isSprinting ? 13.8f : 9.2f;
        if (isMoving) {
            walkPhase += dt * cadence * (float) Math.max(0.7, horizSpeed / (isSprinting ? 0.28 : 0.215));
        }

        // Head Bobbing tự nhiên:
        // Nhún nhịp chân dọc: -abs(sin(walkPhase))
        cameraBobY = (float) (-Math.abs(Math.sin(walkPhase)) * (0.016f + 0.015f * smoothSprintWeight)) * smoothWalkWeight;
        // Triệt tiêu hoàn toàn rung lắc và nghiêng ngang của camera để đường chân trời luôn thẳng, không bị nghiêng trái/phải khi di chuyển:
        cameraBobX = 0.0f;
        float bobRoll = 0.0f;
        smoothStrafeRoll = 0.0f;

        // 3C. Nhảy và rơi tự do (Pitch Tilt)
        double velY = move.y;
        float targetAirPitch = 0.0f;
        if (!onGround) {
            targetAirPitch = (float) Mth.clamp(velY * 2.8, -4.5, 4.5);
        }
        smoothAirPitch = (float) CameraFeelMath.damp(smoothAirPitch, targetAirPitch, 0.08, dt);

        // 3D. Tiếp đất giảm chấn (Landing Impact Bounce)
        if (!wasOnGround && onGround && prevAirVelY < -0.15) {
            float impact = (float) Mth.clamp(-prevAirVelY * 1.8, 0.2, 1.0);
            landVelocity -= impact * 0.35f;
        }
        wasOnGround = onGround;
        prevAirVelY = onGround ? 0.0 : velY;

        float springK = 150.0f;
        float springDamping = 15.0f;
        float springAcc = -springK * landOffset - springDamping * landVelocity;
        landVelocity += springAcc * dt;
        landOffset += landVelocity * dt;
        landOffset = (float) Mth.clamp(landOffset, -0.05, 0.02);
        cameraLandOffset = landOffset;

        // 3E. Xung lực nhát chém Katana (Slash Recoil Decay)
        slashImpulseRoll = (float) CameraFeelMath.damp(slashImpulseRoll, 0.0f, 0.08, dt);
        slashImpulsePitch = (float) CameraFeelMath.damp(slashImpulsePitch, 0.0f, 0.08, dt);
        slashImpulseX = (float) CameraFeelMath.damp(slashImpulseX, 0.0f, 0.08, dt);

        // 3F. Tổng hợp góc xoay Camera POV
        cameraPitch = smoothAirPitch + slashImpulsePitch - landOffset * 22.0f;
        cameraYaw = slashImpulseX;
        cameraRoll = bobRoll + smoothStrafeRoll + slashImpulseRoll;
    }

    /**
     * Áp dụng độ lệch vị trí và góc xoay Camera POV lên ma trận view thế giới trong bobHurt.
     */
    public static void applyCameraPov(PoseStack matrices) {
        if (!HighVClientConfig.ENABLE_DYNAMIC_POV_CAMERA.get()) return;

        matrices.translate(cameraBobX, cameraBobY + cameraLandOffset, 0.0);
        if (cameraPitch != 0.0f) matrices.mulPose(Axis.XP.rotationDegrees(cameraPitch));
        if (cameraYaw != 0.0f)   matrices.mulPose(Axis.YP.rotationDegrees(cameraYaw));
        if (cameraRoll != 0.0f)  matrices.mulPose(Axis.ZP.rotationDegrees(cameraRoll));
    }
}
