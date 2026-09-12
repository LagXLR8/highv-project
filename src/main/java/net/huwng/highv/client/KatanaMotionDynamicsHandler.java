package net.huwng.highv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.huwng.highv.client.camera.CameraFeelMath;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Procedural Katana & Arm Motion Dynamics:
 * Mô phỏng động học và tương tác vật lý sống động cho cánh tay và thanh Katana
 * theo mọi chuyển động của người chơi (chuẩn AAA phong cách Cyberpunk / Titanfall):
 *
 * 1. Breathing / Idle Sway: Nhịp thở tự nhiên, mềm mại khi đứng yên.
 * 2. Walking / Sprinting Bobbing: Lắc lư nhún nhảy theo bước chân (nhịp chân kép)
 *    với tần số và biên độ tương thích tốc độ di chuyển thật.
 * 3. Sprint Posture Transition: Khi chạy nước rút (Sprint), kiếm hạ thấp góc nghiêng
 *    và gập gọn vào thân mình tạo tư thế xung phong khí thế.
 * 4. Crouch Transition: Khi ngồi (Sneak), kiếm thu gần ngực tạo thế rình rập,
 *    giảm biên độ rung lắc.
 * 5. Mouse Look Sway (Rotational Inertia): Khi lia chuột trái/phải/lên/xuống,
 *    thanh kiếm trễ nhẹ theo quán tính và nghiêng (bank roll) theo chiều quay.
 * 6. Movement Inertia & Strafe Banking: Khi di chuyển ngang (A/D strafe), kiếm dạt
 *    và nghiêng roll theo lực ly tâm.
 * 7. Jump, Fall & Landing Impact: Nhảy làm kiếm kéo trễ xuống, rơi tự do kiếm nổi lên,
 *    và tiếp đất tạo cú nảy lò xo (spring bounce) chân thực, đầm tay.
 * 8. Live-Tuner Safety: Tự động đưa về trạng thái tĩnh khi người chơi mở F8 để
 *    tinh chỉnh tọa độ chính xác 100%.
 */
public final class KatanaMotionDynamicsHandler {

    private KatanaMotionDynamicsHandler() {}

    // ── Tọa độ và góc xoay động học hiện tại ──
    public static float posX = 0.0f;
    public static float posY = 0.0f;
    public static float posZ = 0.0f;
    public static float rotX = 0.0f;
    public static float rotY = 0.0f;
    public static float rotZ = 0.0f;

    // ── Biến theo dõi thời gian và frame ──
    private static long lastNanoTime = 0;
    private static float prevYaw = 0.0f;
    private static float prevPitch = 0.0f;
    private static boolean initialized = false;

    // ── Chu kỳ nhịp thở và bước chân ──
    private static float breathPhase = 0.0f;
    private static float walkPhase = 0.0f;

    // ── Trọng số nội suy mượt ──
    private static float currentWalkWeight = 0.0f;
    private static float currentSprintWeight = 0.0f;
    private static float currentCrouchWeight = 0.0f;

    // ── Quán tính lia chuột (Mouse Look Sway) ──
    private static float swayYaw = 0.0f;
    private static float swayPitch = 0.0f;
    private static float targetSwayYaw = 0.0f;
    private static float targetSwayPitch = 0.0f;

    // ── Quán tính rẽ hướng (Strafe Roll) & Gia tốc tới/lui ──
    private static float strafeRoll = 0.0f;
    private static float accelLagZ = 0.0f;

    // ── Nhảy, rơi tự do và tiếp đất (Jump, Fall & Land) ──
    private static float airLagY = 0.0f;
    private static float airLagPitch = 0.0f;
    private static boolean wasOnGround = true;
    private static double prevAirVelY = 0.0;

    private static float landOffset = 0.0f;
    private static float landVelocity = 0.0f;

    // ── Tích hợp các chuyển động độc quyền của High V (Drift, Wallstride, Dash) ──
    private static float driftWeight = 0.0f;
    private static float wallWeight = 0.0f;
    private static float targetWallRoll = 0.0f;
    private static float dashWeight = 0.0f;

    // ── Hiệu ứng Zoom ra/vào và xung lực khi Dash ──
    private static float dashZoomOffset = 0.0f; // Vị trí Z (âm = xa/zoom out, dương = gần/zoom in)
    private static float dashZoomVelocity = 0.0f;
    private static float dashScaleOffset = 0.0f; // Hệ số phóng to/thu nhỏ
    private static float dashScaleVelocity = 0.0f;
    private static float dashImpulsePitch = 0.0f;
    private static float dashImpulseRoll = 0.0f;
    private static float dashImpulseX = 0.0f;
    public static float dynamicScale = 0.0f;

    // ── Hệ số triệt tiêu chuyển động khi bật F8 tuner ──
    private static float tunerFade = 1.0f;

    /**
     * Kích hoạt xung lực động lực học và hiệu ứng Zoom In/Out cho tay & thanh Katana khi Dash.
     */
    public static void triggerDashImpulse(net.huwng.highv.client.animation.DashAnimationHandler.Pose pose) {
        switch (pose) {
            case FRONT, NONE -> {
                // Dash tới trước: Gia tốc giật cánh tay lùi lại (Zoom vào / In), sau đó phóng vút ra trước (Zoom ra / Out)
                dashZoomVelocity = 1.6f;
                dashScaleVelocity = 1.3f;
                dashImpulsePitch = 4.5f;
            }
            case BACK -> {
                // Dash lùi sau: Thân người giật lùi, hất thanh kiếm lao xa ra trước (Zoom ra / Out), sau đó kéo giật về ngực thủ (Zoom vào / In)
                dashZoomVelocity = -1.6f;
                dashScaleVelocity = -1.3f;
                dashImpulsePitch = -5.0f;
            }
            case LEFT -> {
                dashImpulseRoll = -7.0f;
                dashImpulseX = -0.05f;
                dashZoomVelocity = 0.5f;
                dashScaleVelocity = 0.4f;
            }
            case RIGHT -> {
                dashImpulseRoll = 7.0f;
                dashImpulseX = 0.05f;
                dashZoomVelocity = 0.5f;
                dashScaleVelocity = 0.4f;
            }
        }
    }

    /**
     * Cập nhật toàn bộ các thành phần động học mỗi frame render.
     */
    public static void update(AbstractClientPlayer player, float partialTicks) {
        long now = System.nanoTime();
        if (lastNanoTime == 0) {
            lastNanoTime = now;
            return;
        }
        float dt = (now - lastNanoTime) / 1_000_000_000.0f;
        lastNanoTime = now;
        dt = Mth.clamp(dt, 0.001f, 0.066f); // Giới hạn giữa 15 FPS và 1000 FPS để chống giật

        float curYaw = player.getViewYRot(partialTicks);
        float curPitch = player.getViewXRot(partialTicks);

        if (!initialized) {
            prevYaw = curYaw;
            prevPitch = curPitch;
            initialized = true;
        }

        // 1. Quán tính lia chuột (Mouse Look Sway)
        float deltaYaw = (float) CameraFeelMath.wrapAngleDelta(curYaw - prevYaw);
        float deltaPitch = curPitch - prevPitch;
        prevYaw = curYaw;
        prevPitch = curPitch;

        float yawRate = deltaYaw / dt;
        float pitchRate = deltaPitch / dt;

        float newTargetSwayYaw = (float) CameraFeelMath.clamp(-yawRate * 0.022f, -8.0, 8.0);
        float newTargetSwayPitch = (float) CameraFeelMath.clamp(-pitchRate * 0.018f, -6.0, 6.0);

        targetSwayYaw = (float) CameraFeelMath.damp(targetSwayYaw, newTargetSwayYaw, 0.1, dt);
        targetSwayPitch = (float) CameraFeelMath.damp(targetSwayPitch, newTargetSwayPitch, 0.1, dt);

        swayYaw = (float) CameraFeelMath.damp(swayYaw, targetSwayYaw, 0.015, dt);
        swayPitch = (float) CameraFeelMath.damp(swayPitch, targetSwayPitch, 0.015, dt);

        // 2. Chuyển động bước đi & chạy nước rút (Walk & Sprint Bobbing)
        Vec3 deltaMove = player.getDeltaMovement();
        double horizSpeed = Math.sqrt(deltaMove.x * deltaMove.x + deltaMove.z * deltaMove.z);
        boolean onGround = player.onGround();
        boolean isMoving = onGround && horizSpeed > 0.02;
        boolean isSprinting = player.isSprinting() && isMoving;
        boolean isCrouching = player.isCrouching();

        float targetSprint = isSprinting ? 1.0f : 0.0f;
        currentSprintWeight = (float) CameraFeelMath.damp(currentSprintWeight, targetSprint, 0.02, dt);

        float targetCrouch = isCrouching ? 1.0f : 0.0f;
        currentCrouchWeight = (float) CameraFeelMath.damp(currentCrouchWeight, targetCrouch, 0.02, dt);

        float targetWalk = isMoving ? (float) CameraFeelMath.clamp(horizSpeed / (isSprinting ? 0.28 : 0.215), 0.0, 1.0) : 0.0f;
        currentWalkWeight = (float) CameraFeelMath.damp(currentWalkWeight, targetWalk, 0.02, dt);

        // Tăng tốc độ nhịp chuyển động của tay cho khớp với nhịp bước chân: Đi bộ 9.2f, Chạy nước rút 13.8f
        float cadence = (isSprinting ? 13.8f : 9.2f);
        if (isMoving) {
            walkPhase += dt * cadence * (float) Math.max(0.7, horizSpeed / (isSprinting ? 0.28 : 0.215));
        }

        // Nhún nhịp chân dứt khoát, đầm tay và ăn khớp bước chân:
        float bobY = (float) (-Math.abs(Math.sin(walkPhase)) * (0.038f + 0.030f * currentSprintWeight)) * currentWalkWeight;
        // Đung đưa sang trái/phải theo bước chân
        float bobX = (float) (Math.sin(walkPhase * 0.5f) * (0.024f + 0.018f * currentSprintWeight)) * currentWalkWeight;
        // Nghiêng cổ tay và kiếm
        float bobRotZ = (float) (Math.sin(walkPhase * 0.5f) * (2.4f + 2.0f * currentSprintWeight)) * currentWalkWeight;
        float bobRotX = (float) (Math.cos(walkPhase) * (1.8f + 1.4f * currentSprintWeight)) * currentWalkWeight;

        // 3. Nhịp thở tự nhiên khi đứng yên (Idle Breathing)
        breathPhase += dt * 1.5f;
        float breathWeight = (1.0f - currentWalkWeight);
        float breathY = (float) Math.sin(breathPhase) * 0.008f * breathWeight;
        float breathX = (float) Math.cos(breathPhase * 0.5f) * 0.005f * breathWeight;
        float breathRotZ = (float) Math.sin(breathPhase * 0.8f) * 0.35f * breathWeight;
        float breathRotX = (float) Math.cos(breathPhase) * 0.25f * breathWeight;

        // 4. Quán tính rẽ hướng di chuyển (A/D Strafe Banking)
        float yawRad = (float) Math.toRadians(curYaw);
        float sinY = Mth.sin(yawRad);
        float cosY = Mth.cos(yawRad);

        float localStrafe = (float) (-deltaMove.x * sinY + deltaMove.z * cosY); // >0 là sang phải, <0 là sang trái
        float localForward = (float) (deltaMove.x * cosY + deltaMove.z * sinY); // >0 là tiến tới trước

        float targetStrafeRoll = (float) CameraFeelMath.clamp(-localStrafe * 14.0, -4.5, 4.5);
        strafeRoll = (float) CameraFeelMath.damp(strafeRoll, targetStrafeRoll, 0.04, dt);

        float targetAccelLagZ = (float) CameraFeelMath.clamp(localForward * 0.12, -0.04, 0.04);
        accelLagZ = (float) CameraFeelMath.damp(accelLagZ, targetAccelLagZ, 0.05, dt);

        // 5. Nhảy, rơi tự do và nảy lò xo tiếp đất (Jump, Fall & Land Impact)
        double velY = deltaMove.y;
        float targetAirY = 0.0f;
        float targetAirPitch = 0.0f;
        if (!onGround) {
            targetAirY = (float) CameraFeelMath.clamp(-velY * 0.04, -0.06, 0.07);
            targetAirPitch = (float) CameraFeelMath.clamp(velY * 5.0, -8.0, 8.0);
        }
        airLagY = (float) CameraFeelMath.damp(airLagY, targetAirY, 0.08, dt);
        airLagPitch = (float) CameraFeelMath.damp(airLagPitch, targetAirPitch, 0.08, dt);

        // Phát hiện tiếp đất
        if (!wasOnGround && onGround && prevAirVelY < -0.15) {
            float impact = (float) CameraFeelMath.clamp(-prevAirVelY * 2.2, 0.2, 1.0);
            landVelocity -= impact * 0.55f;
        }
        wasOnGround = onGround;
        prevAirVelY = onGround ? 0.0 : velY;

        // Bộ giảm chấn lò xo (Spring-Damper) cho cú tiếp đất
        float springK = 140.0f;
        float springDamping = 14.0f;
        float springAcc = -springK * landOffset - springDamping * landVelocity;
        landVelocity += springAcc * dt;
        landOffset += landVelocity * dt;
        landOffset = (float) CameraFeelMath.clamp(landOffset, -0.08, 0.03);
        float landRotX = -landOffset * 40.0f;

        // 6. Tư thế chạy nước rút & ngồi rình rập (Sprint & Crouch Stance)
        float sprintX = -0.02f * currentSprintWeight;
        float sprintY = -0.015f * currentSprintWeight;
        float sprintZ = 0.03f * currentSprintWeight;
        float sprintRotX = 3.5f * currentSprintWeight;
        float sprintRotY = -2.0f * currentSprintWeight;
        float sprintRotZ = -2.5f * currentSprintWeight;

        float crouchY = -0.02f * currentCrouchWeight;
        float crouchZ = 0.025f * currentCrouchWeight;
        float crouchScale = (1.0f - 0.5f * currentCrouchWeight);

        // 7. Tích hợp chuyển động đặc trưng của mod High V (Dash, Drift, Wallstride)
        java.util.UUID uuid = player.getUUID();
        if (net.huwng.highv.client.animation.DriftAnimationHandler.isActive(uuid)) {
            driftWeight = (float) CameraFeelMath.damp(driftWeight, 1.0f, 0.02, dt);
        } else {
            driftWeight = (float) CameraFeelMath.damp(driftWeight, 0.0f, 0.05, dt);
        }

        if (net.huwng.highv.client.animation.WallstrideAnimationHandler.isActive(uuid)) {
            int wallSide = net.huwng.highv.client.animation.WallstrideAnimationHandler.getWallSide(uuid);
            targetWallRoll = (wallSide != 0 ? -wallSide : -1) * 5.5f;
            wallWeight = (float) CameraFeelMath.damp(wallWeight, 1.0f, 0.02, dt);
        } else {
            targetWallRoll = 0.0f;
            wallWeight = (float) CameraFeelMath.damp(wallWeight, 0.0f, 0.05, dt);
        }

        if (net.huwng.highv.client.animation.DashAnimationHandler.isActive(uuid)) {
            dashWeight = (float) CameraFeelMath.damp(dashWeight, 1.0f, 0.01, dt);
        } else {
            dashWeight = (float) CameraFeelMath.damp(dashWeight, 0.0f, 0.06, dt);
        }

        float driftX = 0.02f * driftWeight;
        float driftY = -0.035f * driftWeight;
        float driftZ = 0.04f * driftWeight;
        float driftRotZ = 5.0f * driftWeight;
        float driftRotX = 3.5f * driftWeight;

        float wallRotZ = targetWallRoll * wallWeight;

        float dashZ = -0.05f * dashWeight;
        float dashRotX = 3.5f * dashWeight;

        // 7B. Mô phỏng vật lý lò xo giảm chấn (Spring-Damper) cho hiệu ứng Zoom In/Out khi Dash
        float dashSpringK = 135.0f;
        float dashDamping = 14.5f;

        float dashAccZ = -dashSpringK * dashZoomOffset - dashDamping * dashZoomVelocity;
        dashZoomVelocity += dashAccZ * dt;
        dashZoomOffset += dashZoomVelocity * dt;

        float dashAccScale = -dashSpringK * dashScaleOffset - dashDamping * dashScaleVelocity;
        dashScaleVelocity += dashAccScale * dt;
        dashScaleOffset += dashScaleVelocity * dt;

        dashZoomOffset = (float) CameraFeelMath.clamp(dashZoomOffset, -0.15, 0.15);
        dashScaleOffset = (float) CameraFeelMath.clamp(dashScaleOffset, -0.22, 0.22);

        dashImpulsePitch = (float) CameraFeelMath.damp(dashImpulsePitch, 0.0f, 0.06, dt);
        dashImpulseRoll = (float) CameraFeelMath.damp(dashImpulseRoll, 0.0f, 0.06, dt);
        dashImpulseX = (float) CameraFeelMath.damp(dashImpulseX, 0.0f, 0.06, dt);

        // 8. Triệt tiêu mượt mà khi mở HUD F8 để người chơi tinh chỉnh tọa độ
        if (KatanaArmLiveTuner.tunerActive) {
            tunerFade = (float) CameraFeelMath.damp(tunerFade, 0.0f, 0.001, dt);
        } else {
            tunerFade = (float) CameraFeelMath.damp(tunerFade, 1.0f, 0.05, dt);
        }

        if (tunerFade < 0.001f) {
            posX = posY = posZ = 0.0f;
            rotX = rotY = rotZ = 0.0f;
            dynamicScale = 0.0f;
            return;
        }

        // 9. Tổng hợp toàn bộ độ lệch dịch chuyển, góc xoay và scale
        posX = (breathX + bobX * crouchScale + swayYaw * 0.0035f - strafeRoll * 0.004f + sprintX + driftX + dashImpulseX) * tunerFade;
        posY = (breathY + bobY * crouchScale + swayPitch * 0.003f + airLagY + landOffset + sprintY + crouchY + driftY) * tunerFade;
        posZ = (accelLagZ + sprintZ + crouchZ + driftZ + dashZ + dashZoomOffset) * tunerFade;

        rotX = (breathRotX + bobRotX * crouchScale + swayPitch * 0.4f + airLagPitch + landRotX + sprintRotX + driftRotX + dashRotX + dashImpulsePitch) * tunerFade;
        rotY = (swayYaw * 0.35f + sprintRotY) * tunerFade;
        rotZ = (breathRotZ + bobRotZ * crouchScale + swayYaw * 0.45f + strafeRoll + sprintRotZ + driftRotZ + wallRotZ + dashImpulseRoll) * tunerFade;

        dynamicScale = dashScaleOffset * tunerFade;
    }

    /**
     * Áp dụng độ dịch chuyển, góc xoay và zoom scale động học lên ma trận vẽ cánh tay & thanh Katana.
     */
    public static void applyMotion(PoseStack pose) {
        if (!HighVClientConfig.ENABLE_PROCEDURAL_SWORD_MOTION.get())
            return;

        pose.translate(posX, posY, posZ);
        if (rotZ != 0.0f)
            pose.mulPose(Axis.ZP.rotationDegrees(rotZ));
        if (rotX != 0.0f)
            pose.mulPose(Axis.XP.rotationDegrees(rotX));
        if (rotY != 0.0f)
            pose.mulPose(Axis.YP.rotationDegrees(rotY));

        float currentScale = 1.0f + dynamicScale;
        if (Math.abs(dynamicScale) > 0.001f) {
            pose.scale(currentScale, currentScale, currentScale);
        }
    }

    public static void reset() {
        initialized = false;
        lastNanoTime = 0;
        landOffset = 0.0f;
        landVelocity = 0.0f;
        swayYaw = 0.0f;
        swayPitch = 0.0f;
        currentWalkWeight = 0.0f;
        currentSprintWeight = 0.0f;
        dashZoomOffset = 0.0f;
        dashZoomVelocity = 0.0f;
        dashScaleOffset = 0.0f;
        dashScaleVelocity = 0.0f;
        dynamicScale = 0.0f;
        dashImpulsePitch = 0.0f;
        dashImpulseRoll = 0.0f;
        dashImpulseX = 0.0f;
    }
}
