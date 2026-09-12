package net.huwng.highv.client.camera;

import org.joml.SimplexNoise;
import org.joml.Vector3d;

/**
 * Trạng thái + logic tính toán camera feel, singleton phía client. Được gọi
 * mỗi lần {@code Camera} cập nhật (xem {@code CameraFeelMixin}), tính ra một
 * offset (pitch/yaw/roll) rồi cache lại để cả mixin của Camera lẫn mixin của
 * GameRenderer (áp roll lên ma trận vẽ) cùng dùng trong cùng một frame.
 *
 * Các cơ chế con:
 *  - blendProfile(): chuyển mượt giữa các "bộ cảm giác" (đi bộ/chạy/bơi/bay...)
 *  - verticalPitch() / forwardPitch(): camera hơi ngẩng/cúi theo velocity rơi & tiến/lùi
 *  - strafeRoll(): camera nghiêng theo velocity ngang khi strafe
 *  - turningRoll(): tích lũy + phân rã một lượng roll khi xoay camera nhanh, qua easing
 *  - idleSway(): camera trôi nhẹ theo simplex noise khi đứng yên đủ lâu, fade in/out
 *  - mouseSmoothing(): làm mượt input chuột (giữ nguyên chuyển động thật, chỉ trễ hiển thị)
 */
public final class CameraFeelState {
    public static final CameraFeelState INSTANCE = new CameraFeelState();

    private CameraFeelState() {}

    // ---- Tinh chỉnh chung (không theo profile) ----
    private static final double CONTEXT_BLEND_SMOOTHING = 0.1;

    private static final double TURN_ROLL_DECAY = 0.0825;
    private static final double TURN_ROLL_INTENSITY = 1.25;
    private static final double TURN_ROLL_ACCUMULATION = 0.0048;

    private static final double SWAY_INTENSITY = 0.6;
    private static final double SWAY_FREQUENCY = 0.16;
    private static final double SWAY_IDLE_DELAY = 0.15;
    private static final double SWAY_FADE_IN = 5.0;
    private static final double SWAY_FADE_OUT = 0.75;
    private static final double SWAY_FADE_CURVE = 3.0;

    private static final double VERTICAL_PITCH_BASE_SMOOTHING = 0.00004;
    private static final double VERTICAL_PITCH_DEADZONE = 0.4;
    private static final double FORWARD_PITCH_BASE_SMOOTHING = 0.008;

    /**
     * Vanilla tính khoảng lùi ra sau của camera góc nhìn thứ 3 TRƯỚC khi
     * pitch của camera-feel được cộng thêm vào (mình chỉ có 1 injection an
     * toàn tại RETURN của Camera#setup, không neo được vào giữa method như
     * bản gốc CameraOverhaul — xem README/lịch sử fix crash). Hệ quả: hướng
     * nhìn và vị trí camera hơi lệch pha, cảm giác như camera "sụp" xuống
     * khi cúi/ngẩng lúc di chuyển ở góc nhìn thứ 3. Giảm mạnh ảnh hưởng
     * pitch riêng cho góc nhìn thứ 3 để tránh cảm giác đó, vẫn giữ nguyên
     * 100% ở góc nhìn thứ nhất (không có vấn đề lệch pha vì đó là vị trí
     * mắt, không lùi ra sau).
     */
    private static final double THIRD_PERSON_PITCH_INFLUENCE = 0.2;
    private static final double STRAFE_ROLL_BASE_SMOOTHING = 0.008;
    private static final double MOUSE_SMOOTHING_BASE = 16.0;
    private static final double MOUSE_SMOOTHING_THRESHOLD = 0.001;

    // ---- State cho blend profile theo trạng thái di chuyển ----
    private final CameraFeelProfile activeProfile = CameraFeelProfile.blank();
    private boolean profileInitialized = false;

    // ---- State cho từng thành phần offset ----
    private double lastTickTime = -1;
    private double elapsedIdleTime = 0;
    private double lastActionTimestamp = 0;

    private final Vector3d prevVelocity = new Vector3d();
    private final Vector3d prevRotation = new Vector3d();
    private CameraFrameInput.Perspective prevPerspective;

    private double verticalPitchOffset;
    private double forwardPitchOffset;
    private double strafeRollOffset;
    private double turnRollAccumulated;
    private double wallstrideRollOffset;
    private double driftRollOffset;

    private double swayFactor;
    private double swayFactorTarget;

    private boolean mouseSmoothingInitialized = false;
    private double prevRawYaw, prevRawPitch;
    private double unwrappedYaw, unwrappedPitch;
    private double smoothedYaw, smoothedPitch;

    /** Kết quả của lần compute() gần nhất, dùng lại cho mixin thứ 2 (roll trên ma trận). */
    private final Vector3d cachedOffset = new Vector3d();

    public Vector3d getCachedOffset() {
        return cachedOffset;
    }

    public void notifyPlayerActed() {
        lastActionTimestamp = elapsedIdleTime;
    }

    /**
     * Tính lại toàn bộ offset cho frame hiện tại. Gọi đúng MỘT LẦN mỗi
     * frame (từ mixin của Camera), trước khi camera thật sự xoay.
     *
     * @param deltaTimeSeconds thời gian giữa 2 frame gần nhất (giây)
     */
    public void compute(CameraFrameInput input, double deltaTimeSeconds) {
        elapsedIdleTime += deltaTimeSeconds;

        blendProfile(input, deltaTimeSeconds);

        cachedOffset.set(0, 0, 0);

        boolean moved = !input.velocity.equals(prevVelocity) || !input.rotation.equals(prevRotation);
        if (moved) notifyPlayerActed();

        mouseSmoothing(input, deltaTimeSeconds);
        idleSway(deltaTimeSeconds);

        verticalPitch(input, deltaTimeSeconds);
        forwardPitch(input, deltaTimeSeconds);

        turningRoll(input);
        strafeRoll(input, deltaTimeSeconds);
        wallstrideRoll(input, deltaTimeSeconds);
        driftRoll(input, deltaTimeSeconds);

        prevVelocity.set(input.velocity);
        prevRotation.set(input.rotation);
        prevPerspective = input.perspective;
    }

    private void blendProfile(CameraFrameInput input, double dt) {
        CameraFeelProfile target;
        if (input.ridingVehicle) target = CameraFeelProfile.vehicle();
        else if (input.ridingMount) target = CameraFeelProfile.mounted();
        else if (input.swimming) target = CameraFeelProfile.swimming();
        else if (input.flying) target = CameraFeelProfile.flying();
        else if (input.sprinting) target = CameraFeelProfile.sprinting();
        else target = CameraFeelProfile.walking();

        if (!profileInitialized) {
            activeProfile.lerpFrom(target, target, 1.0);
            profileInitialized = true;
            return;
        }

        double step = CameraFeelMath.dampStep(CONTEXT_BLEND_SMOOTHING, dt);
        activeProfile.lerpFrom(activeProfile, target, step);
    }

    private void verticalPitch(CameraFrameInput input, double dt) {
        double smoothing = VERTICAL_PITCH_BASE_SMOOTHING * activeProfile.verticalSmoothingMultiplier;
        double target = input.velocity.y * activeProfile.verticalPitchFactor;
        if (Math.abs(target) < VERTICAL_PITCH_DEADZONE) target = 0;
        target *= perspectivePitchInfluence(input);

        verticalPitchOffset = CameraFeelMath.damp(verticalPitchOffset, target, smoothing, dt);
        cachedOffset.x += verticalPitchOffset;
    }

    private void forwardPitch(CameraFrameInput input, double dt) {
        double smoothing = FORWARD_PITCH_BASE_SMOOTHING * activeProfile.horizontalSmoothingMultiplier;
        double target = input.getViewRelativeVelocity().z * activeProfile.forwardPitchFactor;
        target *= perspectivePitchInfluence(input);

        forwardPitchOffset = CameraFeelMath.damp(forwardPitchOffset, target, smoothing, dt);
        cachedOffset.x += forwardPitchOffset;
    }

    private double perspectivePitchInfluence(CameraFrameInput input) {
        return input.perspective == CameraFrameInput.Perspective.FIRST_PERSON
            ? 1.0
            : THIRD_PERSON_PITCH_INFLUENCE;
    }

    private void strafeRoll(CameraFrameInput input, double dt) {
        double smoothing = STRAFE_ROLL_BASE_SMOOTHING * activeProfile.horizontalSmoothingMultiplier;
        double target = -input.getViewRelativeVelocity().x * activeProfile.strafeRollFactor;

        strafeRollOffset = CameraFeelMath.damp(strafeRollOffset, target, smoothing, dt);
        cachedOffset.z += strafeRollOffset;
    }

    private void turningRoll(CameraFrameInput input) {
        double yawDelta = prevRotation.y - input.rotation.y;
        if (input.perspective != prevPerspective) yawDelta = 0.0;

        // dt thật sự không cần thiết ở đây vì decay đã được gọi 1 lần/frame
        // trong compute(); nhưng để nhất quán vẫn dùng cùng công thức damp.
        turnRollAccumulated = CameraFeelMath.damp(turnRollAccumulated, 0, TURN_ROLL_DECAY, 1.0);
        turnRollAccumulated = CameraFeelMath.clamp(turnRollAccumulated + yawDelta * TURN_ROLL_ACCUMULATION, -1.0, 1.0);

        double eased = CameraFeelMath.clamp01(CameraFeelMath.easeInOutCubic(Math.abs(turnRollAccumulated)));
        double signedRoll = eased * TURN_ROLL_INTENSITY * Math.signum(turnRollAccumulated);
        cachedOffset.z += signedRoll;
    }

    private void wallstrideRoll(CameraFrameInput input, double dt) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        int wallSide = net.huwng.highv.client.animation.WallstrideAnimationHandler.getWallSide(mc.player.getUUID());
        double target = 0.0;
        if (wallSide > 0) {
            target = -13.0; // Tường bên phải -> camera nghiêng đón góc nhìn chuẩn (-13 độ)
        } else if (wallSide < 0) {
            target = 13.0;  // Tường bên trái -> camera nghiêng đón góc nhìn chuẩn (+13 độ)
        }
        wallstrideRollOffset = CameraFeelMath.damp(wallstrideRollOffset, target, 0.04, dt);
        cachedOffset.z += wallstrideRollOffset;
    }

    private void driftRoll(CameraFrameInput input, double dt) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        boolean sliding = net.huwng.highv.client.animation.DriftAnimationHandler.isSyncedSliding(mc.player.getUUID());
        double target = 0.0;
        if (sliding) {
            double yawDelta = CameraFeelMath.wrapAngleDelta(prevRotation.y - input.rotation.y);
            target = CameraFeelMath.clamp(yawDelta * 1.8, -7.0, 7.0);
        }
        driftRollOffset = CameraFeelMath.damp(driftRollOffset, target, 0.06, dt);
        cachedOffset.z += driftRollOffset;
    }

    private void idleSway(double dt) {
        boolean recentlyActed = (elapsedIdleTime - lastActionTimestamp) < SWAY_IDLE_DELAY;
        if (recentlyActed) {
            swayFactorTarget = 0;
        } else if (swayFactor == swayFactorTarget) {
            swayFactorTarget = 1;
        }

        double fadeLength = swayFactorTarget > 0 ? SWAY_FADE_IN : SWAY_FADE_OUT;
        double fadeStep = fadeLength > 0 ? dt / fadeLength : 1.0;
        swayFactor = CameraFeelMath.stepTowards(swayFactor, swayFactorTarget, fadeStep);

        double intensity = SWAY_INTENSITY * Math.pow(swayFactor, SWAY_FADE_CURVE);
        float noiseSample = (float) (elapsedIdleTime * SWAY_FREQUENCY);

        cachedOffset.x += SimplexNoise.noise(noiseSample, 420) * intensity;
        cachedOffset.y += SimplexNoise.noise(noiseSample, 1337) * intensity;
    }

    private void mouseSmoothing(CameraFrameInput input, double dt) {
        double smoothingValue = Math.max(0.0, activeProfile.mouseSmoothing);
        double rawYaw = input.rotation.y;
        double rawPitch = input.rotation.x;

        if (!mouseSmoothingInitialized || input.perspective != prevPerspective) {
            prevRawYaw = rawYaw;
            prevRawPitch = rawPitch;
            unwrappedYaw = rawYaw;
            unwrappedPitch = rawPitch;
            smoothedYaw = rawYaw;
            smoothedPitch = rawPitch;
            mouseSmoothingInitialized = true;
            return;
        }

        double yawStep = CameraFeelMath.wrapAngleDelta(rawYaw - prevRawYaw);
        double pitchStep = CameraFeelMath.wrapAngleDelta(rawPitch - prevRawPitch);
        prevRawYaw = rawYaw;
        prevRawPitch = rawPitch;

        unwrappedYaw += yawStep;
        unwrappedPitch += pitchStep;

        if (smoothingValue <= MOUSE_SMOOTHING_THRESHOLD) {
            smoothedYaw = unwrappedYaw;
            smoothedPitch = unwrappedPitch;
            return;
        }

        double rate = MOUSE_SMOOTHING_BASE / smoothingValue;
        double lerpStep = 1.0 - Math.exp(-rate * Math.max(0.0, dt));

        smoothedYaw += (unwrappedYaw - smoothedYaw) * lerpStep;
        smoothedPitch += (unwrappedPitch - smoothedPitch) * lerpStep;

        cachedOffset.y += CameraFeelMath.wrapAngleDelta(smoothedYaw - rawYaw);
        cachedOffset.x += CameraFeelMath.wrapAngleDelta(smoothedPitch - rawPitch);
    }
}
