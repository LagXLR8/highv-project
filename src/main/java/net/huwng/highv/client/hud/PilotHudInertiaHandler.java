package net.huwng.highv.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.huwng.highv.HighV;
import net.huwng.highv.client.animation.DashAnimationHandler;
import net.huwng.highv.client.animation.DriftAnimationHandler;
import net.huwng.highv.client.animation.WallstrideAnimationHandler;
import net.huwng.highv.client.camera.CameraFeelMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Hệ thống mô phỏng quán tính mũ bảo hiểm phi công (Titanfall Pilot Helmet Visor HUD).
 *
 * Toàn bộ giao diện trên màn hình (vanilla hotbar, máu, giáp, thanh đói, tâm ngắm,
 * cùng các widget custom của mod) sẽ chuyển động mềm mại theo quán tính và
 * gia tốc của người chơi:
 *  - Rotational Lag & Sway: Lia chuột nhanh làm HUD trễ nhẹ sang hướng ngược lại
 *    và đàn hồi trở về tâm (spring damper).
 *  - Translational Inertia: Di chuyển, strafe, bhop, dash làm HUD dạt nhẹ theo
 *    hướng ngược lại của gia tốc.
 *  - Landing & Jump Bounce: Nhảy và tiếp đất tạo xung lực nảy nhẹ dọc trục Y.
 *  - Roll Tilt: Chạy tường và trượt cua nghiêng nhẹ toàn bộ HUD theo góc nhìn.
 *  - Dash Thrust & G-Force: Lực đẩy phản lực làm giật nón bảo hiểm cực mạnh,
 *    nén/giãn scale HUD và rung chấn luồng đẩy.
 *  - Melee Slash Impulse: Nhát chém vung kiếm làm nón bảo hiểm giật và nhún theo lực chém.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class PilotHudInertiaHandler {

    // Hệ số giới hạn độ lệch (pixels trên GUI scale)
    private static final double MAX_SWAY_X = 14.0;
    private static final double MAX_SWAY_Y = 10.0;
    private static final double MAX_ROLL   = 2.8; // độ nghiêng tối đa

    // Hệ số độ nhạy theo input xoay chuột
    private static final double ROTATION_SENSITIVITY_X = 0.45;
    private static final double ROTATION_SENSITIVITY_Y = 0.35;

    // Tốc độ hồi vị trí về tâm (damping)
    private static final double DAMP_SPEED = 0.075;

    private static double prevYaw = 0.0;
    private static double prevPitch = 0.0;
    private static double prevVelY = 0.0;
    private static boolean initialized = false;

    // Vị trí lệch hiện tại (được nội suy mỗi frame)
    private static double currentSwayX = 0.0;
    private static double currentSwayY = 0.0;
    private static double currentRoll  = 0.0;

    private static double targetSwayX = 0.0;
    private static double targetSwayY = 0.0;
    private static double targetRoll  = 0.0;

    // Trạng thái chém (Melee slash interaction)
    private static int prevSwingTime = 0;
    private static int slashDir = 1; // Đổi chiều chém luân phiên qua lại (+1 / -1)
    private static double slashImpulseX = 0.0;
    private static double slashImpulseY = 0.0;
    private static double slashImpulseRoll = 0.0;
    private static long lastSlashTimeMs = 0L;

    // Trạng thái xung lực Dash (Thrust & G-Force impulse)
    private static double dashImpulseX = 0.0;
    private static double dashImpulseY = 0.0;
    private static double dashImpulseRoll = 0.0;
    private static double currentScale = 1.0;
    private static double targetScale = 1.0;

    private static boolean posePushed = false;

    private PilotHudInertiaHandler() {}

    public static void init() {
        if (net.neoforged.fml.ModList.get().isLoaded("bettercombat")) {
            registerBetterCombatEvents();
        }
    }

    private static void registerBetterCombatEvents() {
        try {
            net.bettercombat.api.client.BetterCombatClientEvents.ATTACK_START.register((player, attackHand) -> {
                int dir = 0;
                String anim = "";
                if (attackHand != null && attackHand.attack() != null && attackHand.attack().animation() != null) {
                    anim = attackHand.attack().animation().toString().toLowerCase();
                    if (anim.contains("left")) {
                        dir = -1;
                    } else if (anim.contains("right")) {
                        dir = 1;
                    }
                }
                triggerSlashImpulse(dir, false);
                net.huwng.highv.client.camera.DynamicPovHandler.triggerSlash(dir, anim, false);
            });

            net.bettercombat.api.client.BetterCombatClientEvents.ATTACK_HIT.register((player, attackHand, targets, cursorTarget) -> {
                boolean hit = (targets != null && !targets.isEmpty()) || cursorTarget != null;
                if (hit) {
                    onAttackHit();
                    net.huwng.highv.client.camera.DynamicPovHandler.onAttackHit();
                }
            });
        } catch (Throwable t) {
            HighV.LOGGER.warn("[PilotHudInertiaHandler] Failed to hook Better Combat events", t);
        }
    }

    public static void triggerSlashImpulse(int preferredDir, boolean hitTarget) {
        long now = System.currentTimeMillis();
        if (now - lastSlashTimeMs < 90) return;
        lastSlashTimeMs = now;

        if (preferredDir != 0) {
            slashDir = preferredDir;
        } else {
            slashDir = -slashDir; // Đổi chiều vung vũ khí mỗi nhát chém luân phiên
        }

        slashImpulseX = slashDir * 2.8;     // Dạt ngang nhẹ theo hướng vung
        slashImpulseY = 1.4;               // Nhún rất nhẹ HUD theo lực chém
        slashImpulseRoll = slashDir * 0.7; // Độ nghiêng nhẹ nón bảo hiểm

        if (hitTarget) {
            onAttackHit();
        }
    }

    public static void onAttackHit() {
        slashImpulseY += 0.8;
    }

    /**
     * Kích hoạt phản hồi quán tính cực mạnh khi Dash (Titanfall Pilot Visor G-Force).
     * @param dir Vector hướng dash 3D
     * @param pose Hướng tương đối của thân người (FRONT, BACK, LEFT, RIGHT)
     */
    public static void triggerDashImpulse(Vec3 dir, DashAnimationHandler.Pose pose) {
        switch (pose) {
            case LEFT -> {
                // Dash sang trái -> Quán tính giật mạnh nón sang PHẢI, nghiêng góc nhìn nón
                dashImpulseX = 22.0;
                dashImpulseRoll = 5.0;
                targetScale = 1.04;
            }
            case RIGHT -> {
                // Dash sang phải -> Quán tính giật mạnh nón sang TRÁI, nghiêng góc nhìn nón
                dashImpulseX = -22.0;
                dashImpulseRoll = -5.0;
                targetScale = 1.04;
            }
            case BACK -> {
                // Dash lùi -> Người chơi bị kéo lùi, nón hất lên và co nhẹ
                dashImpulseY = -14.0;
                targetScale = 0.95;
            }
            case FRONT, NONE -> {
                // Dash tới trước -> Gia tốc ép người chơi lùi vào nón, nón nhún xuống và zoom căng
                dashImpulseY = 12.0;
                targetScale = 1.06;
            }
            default -> {
                dashImpulseY = 12.0;
                targetScale = 1.06;
            }
        }

        // Bổ sung phản hồi theo góc 3D (nếu dash chéo lên trời hoặc cắm xuống đất)
        if (dir != null) {
            // dir.y > 0 (dash vọt lên) -> quán tính ép nón chúc xuống (+Y)
            // dir.y < 0 (dash chúi xuống) -> quán tính nâng nón hất lên (-Y)
            dashImpulseY += dir.y * 14.0;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            targetSwayX = 0;
            targetSwayY = 0;
            targetRoll = 0;
            initialized = false;
            return;
        }

        double curYaw = player.getYRot();
        double curPitch = player.getXRot();
        Vec3 vel = player.getDeltaMovement();

        if (!initialized) {
            prevYaw = curYaw;
            prevPitch = curPitch;
            prevVelY = vel.y;
            initialized = true;
            return;
        }

        // 1. Quán tính do quay góc nhìn (Rotational Lag)
        double deltaYaw = CameraFeelMath.wrapAngleDelta(curYaw - prevYaw);
        double deltaPitch = curPitch - prevPitch;
        prevYaw = curYaw;
        prevPitch = curPitch;

        // Trễ ngược hướng quay
        targetSwayX = CameraFeelMath.clamp(-deltaYaw * ROTATION_SENSITIVITY_X, -MAX_SWAY_X, MAX_SWAY_X);
        targetSwayY = CameraFeelMath.clamp(-deltaPitch * ROTATION_SENSITIVITY_Y, -MAX_SWAY_Y, MAX_SWAY_Y);

        // 2. Quán tính theo vận tốc di chuyển (Translational & Strafe Sway)
        double yawRad = Math.toRadians(curYaw);
        double forwardX = -Math.sin(yawRad), forwardZ = Math.cos(yawRad);
        double rightX = Math.cos(yawRad), rightZ = Math.sin(yawRad);

        double localStrafeVel = vel.x * rightX + vel.z * rightZ;
        targetSwayX -= CameraFeelMath.clamp(localStrafeVel * 4.0, -6.0, 6.0);

        // Nảy khi tiếp đất / nhảy
        double deltaVy = vel.y - prevVelY;
        prevVelY = vel.y;
        targetSwayY += CameraFeelMath.clamp(deltaVy * 5.5, -5.0, 5.0);

        // 3. Roll Tilt khi Wallstride và Drift
        double targetTilt = 0.0;
        int wallSide = WallstrideAnimationHandler.getWallSide(player.getUUID());
        if (wallSide != 0) {
            targetTilt = -wallSide * 1.8;
        } else if (DriftAnimationHandler.isSyncedSliding(player.getUUID())) {
            targetTilt = CameraFeelMath.clamp(-deltaYaw * 0.4, -MAX_ROLL, MAX_ROLL);
        }
        targetRoll = targetTilt;

        // 3.5 Rung chấn luồng đẩy phản lực trong lúc đang Dash (Thrust vibration)
        if (DashAnimationHandler.isActive(player.getUUID())) {
            double phase = player.tickCount * 2.0;
            targetSwayX += Math.sin(phase * 2.5) * 1.2;
            targetSwayY += Math.cos(phase * 2.1) * 0.9;
        }

        // 4. Phản hồi giật/nghiêng HUD khi người chơi chém thường (Vanilla fallback khi không dùng Better Combat)
        int curSwingTime = player.swingTime;
        if (curSwingTime > 0 && prevSwingTime == 0) {
            triggerSlashImpulse(0, mc.crosshairPickEntity != null);
        }
        prevSwingTime = curSwingTime;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        // Chỉ áp dụng khi đang trong game và không mở màn hình GUI tĩnh (chat, inventory, pause)
        if (mc.player == null || mc.screen != null || mc.options.hideGui || !net.huwng.highv.client.HighVClientConfig.ENABLE_PILOT_HUD_INERTIA.get()) {
            posePushed = false;
            return;
        }

        // Cập nhật làm mượt giá trị hiển thị mỗi frame (kết hợp chuyển động thông thường + xung lực chém + xung lực dash)
        double dt = 0.016;
        currentSwayX = CameraFeelMath.damp(currentSwayX, targetSwayX + slashImpulseX + dashImpulseX, DAMP_SPEED, dt);
        currentSwayY = CameraFeelMath.damp(currentSwayY, targetSwayY + slashImpulseY + dashImpulseY, DAMP_SPEED, dt);
        currentRoll  = CameraFeelMath.damp(currentRoll,  targetRoll  + slashImpulseRoll + dashImpulseRoll, DAMP_SPEED, dt);
        currentScale = CameraFeelMath.damp(currentScale, targetScale, 0.12, dt);

        // Dần đưa target và xung lực về 0 (đàn hồi spring)
        targetSwayX *= 0.88;
        targetSwayY *= 0.88;
        slashImpulseX = CameraFeelMath.damp(slashImpulseX, 0.0, 0.08, dt);
        slashImpulseY = CameraFeelMath.damp(slashImpulseY, 0.0, 0.08, dt);
        slashImpulseRoll = CameraFeelMath.damp(slashImpulseRoll, 0.0, 0.08, dt);

        dashImpulseX = CameraFeelMath.damp(dashImpulseX, 0.0, 0.065, dt);
        dashImpulseY = CameraFeelMath.damp(dashImpulseY, 0.0, 0.065, dt);
        dashImpulseRoll = CameraFeelMath.damp(dashImpulseRoll, 0.0, 0.065, dt);
        targetScale = CameraFeelMath.damp(targetScale, 1.0, 0.08, dt);

        PoseStack pose = event.getGuiGraphics().pose();
        pose.pushPose();
        posePushed = true;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        float centerX = screenW * 0.5f;
        float centerY = screenH * 0.5f;

        // Dịch tâm để quay, phóng thu và dạt theo quán tính mũ bảo hiểm
        pose.translate(centerX, centerY, 0);
        pose.translate(currentSwayX, currentSwayY, 0);
        if (Math.abs(currentRoll) > 0.01) {
            pose.mulPose(Axis.ZP.rotationDegrees((float) currentRoll));
        }
        if (Math.abs(currentScale - 1.0) > 0.001) {
            pose.scale((float) currentScale, (float) currentScale, 1.0f);
        }
        pose.translate(-centerX, -centerY, 0);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (posePushed) {
            event.getGuiGraphics().pose().popPose();
            posePushed = false;
        }
    }

    /**
     * Triệt tiêu (đảo ngược) phép biến đổi quán tính Pilot HUD cho các overlay toàn màn hình
     * (như vignette/motion blur) để giữ chúng nằm cố định tuyệt đối ở 4 cạnh màn hình.
     */
    public static void undoSway(PoseStack pose) {
        if (!posePushed) return;
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        float centerX = screenW * 0.5f;
        float centerY = screenH * 0.5f;

        pose.translate(centerX, centerY, 0);
        if (Math.abs(currentScale - 1.0) > 0.001) {
            float invScale = 1.0f / (float) currentScale;
            pose.scale(invScale, invScale, 1.0f);
        }
        if (Math.abs(currentRoll) > 0.01) {
            pose.mulPose(Axis.ZP.rotationDegrees((float) -currentRoll));
        }
        pose.translate(-currentSwayX, -currentSwayY, 0);
        pose.translate(-centerX, -centerY, 0);
    }
}
