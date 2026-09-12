package net.huwng.highv.client.camera;

import net.huwng.highv.client.HighVClientConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Hệ thống Soft Lock & Hard Lock phong cách Armored Core 6 (AC6)
 * thiết kế tối ưu hóa cho Shoulder Camera:
 *
 * 1. SOFT LOCK (FCS Assist):
 *    - Quét mục tiêu hợp lệ trong nón tầm nhìn FCS (khoảng cách <= 40m, góc lệch <= 45°).
 *    - Người chơi giữ 100% quyền kiểm soát lia chuột, camera không bị cưỡng bức xoay.
 *    - Hiển thị khung ngắm Soft Lock (Cyan/Teal) bám theo mục tiêu trên màn hình.
 *
 * 2. HARD LOCK (Target Lock-On Camera):
 *    - Kích hoạt bằng phím bấm chuyên biệt (mặc định: Chuột giữa - Middle Mouse).
 *    - Camera tự động xoay mượt mà (Smooth Gimbal Tracking) bám chặt mục tiêu.
 *    - Hiển thị khung ngắm Hard Lock (Amber/Orange) kèm nhãn [LOCKED] và thanh máu mục tiêu.
 *    - Tự động ngắt nếu mục tiêu chết, vượt quá cự ly (> 48m), hoặc bị tường chắn > 1.5s.
 */
public final class TargetLockHandler {

    private TargetLockHandler() {}

    // ── Trạng thái mục tiêu ──
    private static LivingEntity softLockedTarget = null;
    private static LivingEntity hardLockedTarget = null;
    private static boolean isHardLocked = false;
    private static double targetDistance = 0.0;
    private static int losLostTicks = 0;

    // Biến xoay vòng ngắm hiệu ứng
    public static float lockRingAngle = 0.0f;

    // ── Getters công khai cho Renderer và Input ──
    public static boolean isHardLocked() {
        return isHardLocked && hardLockedTarget != null && hardLockedTarget.isAlive();
    }

    public static LivingEntity getHardLockedTarget() {
        return isHardLocked() ? hardLockedTarget : null;
    }

    public static LivingEntity getSoftLockedTarget() {
        return softLockedTarget != null && softLockedTarget.isAlive() ? softLockedTarget : null;
    }

    public static LivingEntity getActiveTarget() {
        if (isHardLocked()) return hardLockedTarget;
        return getSoftLockedTarget();
    }

    public static double getTargetDistance() {
        return targetDistance;
    }

    /**
     * Kiểm tra xem hệ thống Target Lock có đang được phép hoạt động hay không.
     */
    public static boolean isTargetingActive() {
        if (!HighVClientConfig.ENABLE_AC6_CAMERA.get()) return false;
        if (!HighVClientConfig.ENABLE_TARGET_LOCK.get()) return false;
        if (HighVClientConfig.REQUIRE_SHOULDER_CAMERA_FOR_LOCK.get()) {
            return ShoulderCameraState.INSTANCE.isEnabled();
        }
        return true;
    }

    /**
     * Bật/tắt Hard Lock khi người chơi bấm phím.
     */
    public static void toggleHardLock(LocalPlayer player) {
        if (player == null || !HighVClientConfig.ENABLE_AC6_CAMERA.get()) return;
        Minecraft mc = Minecraft.getInstance();

        if (isHardLocked) {
            disengageHardLock();
        } else {
            // Nếu có sẵn soft lock target, khóa ngay vào mục tiêu đó
            LivingEntity candidate = getSoftLockedTarget();
            if (candidate == null) {
                // Quét tức thời để tìm mục tiêu gần tâm nhất
                candidate = scanBestTarget(mc, player);
            }

            if (candidate != null) {
                hardLockedTarget = candidate;
                isHardLocked = true;
                losLostTicks = 0;

                // Âm thanh kích hoạt Hard Lock chuẩn sci-fi
                mc.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.EXPERIENCE_ORB_PICKUP, 1.8f, 0.6f));
                mc.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.ARROW_HIT_PLAYER, 1.6f, 0.4f));
            } else {
                // Âm thanh báo không tìm thấy mục tiêu
                mc.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.UI_BUTTON_CLICK.value(), 0.8f, 0.3f));
            }
        }
    }

    /**
     * Hủy chế độ Hard Lock và quay lại Soft Lock.
     */
    public static void disengageHardLock() {
        if (isHardLocked) {
            isHardLocked = false;
            hardLockedTarget = null;
            losLostTicks = 0;

            Minecraft mc = Minecraft.getInstance();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.UI_BUTTON_CLICK.value(), 1.3f, 0.4f));
        }
    }

    /**
     * Cập nhật quét mục tiêu và bám camera mỗi frame render.
     */
    public static void update(double dt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.isPaused()) return;

        // Cập nhật góc xoay vòng ngắm hiệu ứng
        lockRingAngle = (float) ((lockRingAngle + dt * 120.0f) % 360.0f);

        if (!isTargetingActive()) {
            if (isHardLocked) disengageHardLock();
            softLockedTarget = null;
            return;
        }

        double maxRange = HighVClientConfig.TARGET_LOCK_RANGE.get();

        // 1. Quản lý HARD LOCK
        if (isHardLocked && hardLockedTarget != null) {
            if (!hardLockedTarget.isAlive() || hardLockedTarget.isRemoved()) {
                disengageHardLock();
            } else {
                Vec3 eyePos = player.getEyePosition();
                Vec3 targetCenter = getTargetCenter(hardLockedTarget);
                double dist = eyePos.distanceTo(targetCenter);
                targetDistance = dist;

                if (dist > maxRange * 1.25) {
                    disengageHardLock();
                } else {
                    // Kiểm tra tầm nhìn thẳng (Line of Sight)
                    if (!hasLineOfSight(mc, player, eyePos, targetCenter)) {
                        losLostTicks++;
                        if (losLostTicks > 35) { // Bị che khuất quá ~1.7s -> hủy khóa
                            disengageHardLock();
                        }
                    } else {
                        losLostTicks = 0;
                    }

                    // Bám camera mượt mà vào mục tiêu (Smooth Camera Tracking)
                    if (isHardLocked) {
                        trackTargetWithCamera(mc, player, targetCenter, dt);
                    }
                }
            }
        }

        // 2. Quét SOFT LOCK (nếu không đang Hard Lock)
        if (!isHardLocked) {
            softLockedTarget = scanBestTarget(mc, player);
            if (softLockedTarget != null) {
                targetDistance = player.getEyePosition().distanceTo(getTargetCenter(softLockedTarget));
            } else {
                targetDistance = 0.0;
            }
        }
    }

    /**
     * Tính toán góc quay và điều khiển hướng nhìn camera của người chơi bám theo mục tiêu.
     */
    private static void trackTargetWithCamera(Minecraft mc, LocalPlayer player, Vec3 targetCenter, double dt) {
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();

        // Vector từ camera đến mục tiêu
        Vec3 toTarget = targetCenter.subtract(camPos);
        double horizDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
        if (horizDist < 0.001) return;

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
        float desiredPitch = (float) Math.toDegrees(-Math.atan2(toTarget.y, horizDist));
        if (ShoulderCameraState.INSTANCE.isEnabled()) {
            desiredPitch -= (float) ArmoredCoreCameraHandler.currentDownwardPitch;
        }

        float curYaw = player.getYRot();
        float curPitch = player.getXRot();

        float deltaYaw = (float) CameraFeelMath.wrapAngleDelta(desiredYaw - curYaw);
        float deltaPitch = (float) CameraFeelMath.wrapAngleDelta(desiredPitch - curPitch);

        // Nội suy mượt mà (Halflife 0.04s giúp camera lướt theo cực kỳ đầm và chuẩn)
        float smoothFactor = 0.042f;
        float stepYaw = (float) CameraFeelMath.damp(0.0, deltaYaw, smoothFactor, dt);
        float stepPitch = (float) CameraFeelMath.damp(0.0, deltaPitch, smoothFactor, dt);

        player.setYRot(curYaw + stepYaw);
        player.setXRot(Mth.clamp(curPitch + stepPitch, -88.5f, 88.5f));
        player.yRotO = player.getYRot();
        player.xRotO = player.getXRot();
    }

    /**
     * Quét và tìm mục tiêu tốt nhất trong nón tầm nhìn FCS.
     */
    public static LivingEntity scanBestTarget(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;

        double maxRange = HighVClientConfig.TARGET_LOCK_RANGE.get();
        Vec3 eyePos = player.getEyePosition();
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camLook = new Vec3(cam.getLookVector());

        AABB searchBox = new AABB(eyePos, eyePos).inflate(maxRange);
        List<LivingEntity> candidates = mc.level.getEntitiesOfClass(LivingEntity.class, searchBox,
                e -> isTargetable(e, player));

        LivingEntity bestEntity = null;
        double bestScore = -999.0;

        for (LivingEntity e : candidates) {
            Vec3 center = getTargetCenter(e);
            Vec3 toEnt = center.subtract(eyePos);
            double dist = toEnt.length();
            if (dist < 0.2 || dist > maxRange) continue;

            // Angular dot product: càng gần 1.0 càng ở chính giữa màn hình
            double dot = toEnt.normalize().dot(camLook);
            if (dot < 0.68) continue; // Ngoài nón FCS (~47°)

            // Kiểm tra Line of sight không bị block chắn
            if (!hasLineOfSight(mc, player, eyePos, center)) continue;

            // Chấm điểm mục tiêu: ưu tiên entity gần tâm màn hình nhất, kết hợp cự ly
            double score = dot * 2.5 - (dist / maxRange) * 0.8;
            if (score > bestScore) {
                bestScore = score;
                bestEntity = e;
            }
        }

        return bestEntity;
    }

    /**
     * Vị trí trọng tâm để khóa mục tiêu (ở giữa chiều cao thân người).
     */
    public static Vec3 getTargetCenter(LivingEntity entity) {
        return entity.position().add(0, entity.getBbHeight() * 0.52, 0);
    }

    /**
     * Bộ lọc thực thể hợp lệ để khóa mục tiêu.
     */
    public static boolean isTargetable(LivingEntity living, Player player) {
        if (living == null || !living.isAlive() || living.isRemoved()) return false;
        if (living == player) return false;
        if (living.isSpectator()) return false;
        if (living.isInvisible()) return false;
        if (living.isPassenger() && living.getRootVehicle() == player.getRootVehicle()) return false;
        if (player.isPassenger() && player.getVehicle() == living) return false;

        // Quái vật & Bosses
        if (living instanceof Enemy) return true;

        // Bù nhìn tập đánh
        if (living instanceof ArmorStand) return true;

        // Người chơi khác (trong multiplayer)
        if (living instanceof Player otherPlayer) {
            return !otherPlayer.isCreative() && !otherPlayer.isSpectator();
        }

        // Quái vật đang thù địch nhắm vào người chơi
        if (living instanceof Mob mob && mob.getTarget() == player) {
            return true;
        }

        // Entity vừa bị người chơi tấn công gần đây (< 5s)
        if (living.getLastHurtByMob() == player && (player.tickCount - living.getLastHurtByMobTimestamp()) < 100) {
            return true;
        }

        return false;
    }

    /**
     * Kiểm tra tia nhìn thẳng (Line of Sight) xem có bị block cản trở không.
     */
    public static boolean hasLineOfSight(Minecraft mc, Player player, Vec3 from, Vec3 to) {
        if (mc.level == null) return false;
        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }
}
