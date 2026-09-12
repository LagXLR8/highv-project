package net.huwng.highv.mixin;

import net.huwng.highv.client.camera.CameraFeelState;
import net.huwng.highv.client.camera.CameraFrameInput;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Áp pitch/yaw (trục X/Y) tính bởi {@link CameraFeelState} lên
 * {@code Camera} thật, ngay trong lúc nó tự setup mỗi frame.
 *
 * Camera vanilla không có khái niệm "roll" — phần roll (trục Z) được xử lý
 * riêng ở {@link CameraFeelRollMixin}, áp thẳng lên ma trận view lúc vẽ,
 * dùng lại offset đã cache từ lần compute() ở đây (một frame chỉ compute
 * một lần).
 *
 * Viết cho MC 1.21.1 / NeoForge cụ thể (không đa version như bản gốc mình
 * tham khảo cơ chế) — nếu sau này port lên version khác của MC, chữ ký
 * {@code setup(...)} / {@code getXRot()} / {@code getYRot()} / vector hằng số
 * FORWARDS-UP-LEFT có thể cần đổi lại theo mapping của bản đó.
 */
@Mixin(Camera.class)
public abstract class CameraFeelMixin {

    private static final Vector3f FORWARDS = new Vector3f(0, 0, -1);
    private static final Vector3f UP = new Vector3f(0, 1, 0);
    private static final Vector3f LEFT = new Vector3f(-1, 0, 0);

    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Shadow private Vector3f forwards;
    @Shadow private Vector3f up;
    @Shadow private Vector3f left;

    @Shadow public abstract float getXRot();
    @Shadow public abstract float getYRot();
    @Shadow public abstract Vec3 getPosition();
    @Shadow public abstract Quaternionf rotation();
    @Shadow protected abstract void move(float zoom, float dy, float dx);
    @Shadow protected abstract void setPosition(Vec3 pos);

    private double highv$lastFrameTime = -1;

    @Inject(method = "setup", at = @At("RETURN"))
    private void highv$onCameraSetup(
        BlockGetter area, Entity entity, boolean thirdPerson, boolean inverseView, float tickDelta,
        CallbackInfo ci
    ) {
        if (net.huwng.highv.client.HighVClientConfig.ENABLE_CAMERA_FEEL.get()) {
            highv$applyCameraFeel(entity, thirdPerson, inverseView);
        }

        if (thirdPerson && !inverseView && net.huwng.highv.client.camera.ShoulderCameraState.INSTANCE.isEnabled()) {
            if (net.huwng.highv.client.HighVClientConfig.ENABLE_AC6_CAMERA.get()) {
                highv$applyArmoredCoreCamera(area, entity);
            } else {
                highv$applyClassicShoulderCamera(area, entity, tickDelta);
            }
        }
    }

    private void highv$applyArmoredCoreCamera(BlockGetter level, Entity entity) {
        float dy = (float) net.huwng.highv.client.camera.ArmoredCoreCameraHandler.currentHeight;
        float dx = (float) net.huwng.highv.client.camera.ArmoredCoreCameraHandler.currentSide;

        // Dịch chuyển camera 3D thực tế theo độ cao và độ lệch vai thể thao
        this.move(0.0F, dy, dx);

        // Kiểm tra an toàn chống kẹt trong block
        if (level != null && entity != null) {
            BlockPos bp = BlockPos.containing(this.getPosition());
            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                Vec3 eyePos = entity.getEyePosition();
                Vec3 toEye = eyePos.subtract(this.getPosition());
                double dist = toEye.length();
                if (dist > 0.05) {
                    Vec3 dir = toEye.normalize();
                    for (double step = 0.1; step < dist; step += 0.1) {
                        Vec3 test = this.getPosition().add(dir.scale(step));
                        BlockPos tbp = BlockPos.containing(test);
                        if (level.getBlockState(tbp).getCollisionShape(level, tbp).isEmpty()) {
                            this.setPosition(test);
                            break;
                        }
                    }
                }
            }
        }

        // Áp góc chúc nhẹ xuống để tâm ngắm luôn nhìn thẳng vào mục tiêu phía trước
        float pitchComp = (float) net.huwng.highv.client.camera.ArmoredCoreCameraHandler.currentDownwardPitch;
        highv$addRotation(pitchComp, 0.0F);
    }

    private void highv$applyClassicShoulderCamera(BlockGetter level, Entity entity, float tickDelta) {
        if (level == null || entity == null) return;

        // 1. Tọa độ mắt thực tế của người chơi (vị trí an toàn ban đầu)
        Vec3 eyePos = new Vec3(
            Mth.lerp((double)tickDelta, entity.xo, entity.getX()),
            Mth.lerp((double)tickDelta, entity.yo, entity.getY()) + (double)Mth.lerp(tickDelta, this.eyeHeightOld, this.eyeHeight),
            Mth.lerp((double)tickDelta, entity.zo, entity.getZ())
        );

        // Vector hướng nhìn thẳng của camera
        Vec3 forwardsVec = new Vec3(this.forwards.x(), this.forwards.y(), this.forwards.z());

        // Vector vuông góc sang phải theo phương ngang (Horizontal Right)
        Vec3 lookH = new Vec3(this.forwards.x(), 0.0, this.forwards.z());
        Vec3 rightVec = lookH.lengthSqr() > 1.0e-5
            ? lookH.normalize().cross(new Vec3(0.0, 1.0, 0.0)).normalize()
            : new Vec3(1.0, 0.0, 0.0);

        // 2. Chống xuyên trần nhà (Ceiling clearance)
        double verticalOffset = net.huwng.highv.client.camera.ShoulderCameraState.INSTANCE.getCurrentVerticalOffset();
        Vec3 upCheck = eyePos.add(0.0, verticalOffset + 0.15, 0.0);
        HitResult upHit = level.clip(new ClipContext(eyePos, upCheck, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
        double actualVertical = verticalOffset;
        if (upHit.getType() != HitResult.Type.MISS) {
            double dist = Math.sqrt(upHit.getLocation().distanceToSqr(eyePos));
            actualVertical = Math.max(0.0, dist - 0.20);
        }
        Vec3 elevatedPos = eyePos.add(0.0, actualVertical, 0.0);

        // 3. Chống xuyên tường bên cạnh (Side wall clearance)
        // Trong ShoulderCameraState, âm = vai phải, dương = vai trái
        double sideOffset = -net.huwng.highv.client.camera.ShoulderCameraState.INSTANCE.getCurrentSideOffset();
        double sideSign = Math.signum(sideOffset);
        double actualSide = sideOffset;
        if (Math.abs(sideOffset) > 0.001) {
            Vec3 sideCheck = elevatedPos.add(rightVec.scale(sideOffset + sideSign * 0.15));
            HitResult sideHit = level.clip(new ClipContext(elevatedPos, sideCheck, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (sideHit.getType() != HitResult.Type.MISS) {
                double dist = Math.sqrt(sideHit.getLocation().distanceToSqr(elevatedPos));
                actualSide = sideSign * Math.max(0.0, dist - 0.20);
            }
        }
        Vec3 safeAnchor = elevatedPos.add(rightVec.scale(actualSide));

        // 4. Chống xuyên tường phía sau camera (Detached third-person distance raycast)
        float scale = entity instanceof LivingEntity living ? living.getScale() : 1.0F;
        double targetDist = net.huwng.highv.client.camera.ShoulderCameraState.getBaseDistance() * scale;
        double actualDist = targetDist;

        // Dùng 8 tia ở 8 góc hộp bao quanh anchor để chống góc nhọn/cột tường lọt vào
        for (int i = 0; i < 8; i++) {
            float f1 = (float)((i & 1) * 2 - 1);
            float f2 = (float)((i >> 1 & 1) * 2 - 1);
            float f3 = (float)((i >> 2 & 1) * 2 - 1);
            Vec3 corner = safeAnchor.add(f1 * 0.12, f2 * 0.12, f3 * 0.12);
            Vec3 rayEnd = corner.subtract(forwardsVec.scale(actualDist));
            HitResult hit = level.clip(new ClipContext(corner, rayEnd, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (hit.getType() != HitResult.Type.MISS) {
                double d = Math.sqrt(hit.getLocation().distanceToSqr(corner));
                if (d < actualDist) {
                    actualDist = Math.max(0.0, d - 0.15);
                }
            }
        }

        Vec3 finalCamPos = safeAnchor.subtract(forwardsVec.scale(actualDist));

        // 5. Kiểm tra an toàn tuyệt đối: nếu vị trí vẫn rơi vào khối đặc, kéo giật về phía mắt
        BlockPos finalBlockPos = BlockPos.containing(finalCamPos);
        if (!level.getBlockState(finalBlockPos).getCollisionShape(level, finalBlockPos).isEmpty()) {
            Vec3 toEye = eyePos.subtract(finalCamPos);
            double distToEye = toEye.length();
            if (distToEye > 0.05) {
                Vec3 dir = toEye.normalize();
                for (double step = 0.1; step < distToEye; step += 0.1) {
                    Vec3 test = finalCamPos.add(dir.scale(step));
                    BlockPos testBlock = BlockPos.containing(test);
                    if (level.getBlockState(testBlock).getCollisionShape(level, testBlock).isEmpty()) {
                        finalCamPos = test;
                        break;
                    }
                }
            }
        }

        // Cập nhật vị trí Camera vật lý thật trong thế giới 3D
        this.setPosition(finalCamPos);
    }

    private void highv$applyCameraFeel(Entity entity, boolean thirdPerson, boolean inverseView) {
        Entity vehicle = entity.getVehicle();
        Entity controlled = vehicle != null ? vehicle : entity;

        CameraFrameInput input = new CameraFrameInput(
            new org.joml.Vector3d(controlled.getDeltaMovement().x, controlled.getDeltaMovement().y, controlled.getDeltaMovement().z),
            getXRot(),
            getYRot()
        );
        input.perspective = thirdPerson
            ? (inverseView ? CameraFrameInput.Perspective.THIRD_PERSON_MIRRORED : CameraFrameInput.Perspective.THIRD_PERSON)
            : CameraFrameInput.Perspective.FIRST_PERSON;
        input.ridingMount = vehicle instanceof Animal;
        input.ridingVehicle = vehicle instanceof VehicleEntity;

        if (entity instanceof LivingEntity living) {
            input.flying = living.isFallFlying();
            input.swimming = entity.isSwimming();
            input.sprinting = entity.isSprinting();
        }

        if (!Minecraft.getInstance().isPaused()) {
            double now = System.nanoTime() / 1_000_000_000.0;
            double dt = highv$lastFrameTime < 0 ? 0 : Math.min(now - highv$lastFrameTime, 0.25);
            highv$lastFrameTime = now;

            CameraFeelState.INSTANCE.compute(input, dt);
            net.huwng.highv.client.camera.ShoulderCameraState.INSTANCE.update(dt);
            if (net.huwng.highv.client.HighVClientConfig.ENABLE_AC6_CAMERA.get()) {
                net.huwng.highv.client.camera.TargetLockHandler.update(dt);
            }
        }

        double oldXRot = getXRot();
        double oldYRot = getYRot();

        org.joml.Vector3d offset = CameraFeelState.INSTANCE.getCachedOffset();
        double newXRot = oldXRot + offset.x;
        double newYRot = oldYRot + offset.y;

        highv$addRotation((float) (newXRot - oldXRot), (float) (newYRot - oldYRot));
    }

    private void highv$addRotation(float pitchDelta, float yawDelta) {
        xRot += pitchDelta;
        yRot += yawDelta;

        Quaternionf quat = rotation();
        quat.premul(new Quaternionf().rotationAxis(yawDelta * (float) (Math.PI / 180.0), 0, -1, 0));
        quat.rotateAxis(pitchDelta * (float) (Math.PI / 180.0), -1, 0, 0);

        FORWARDS.rotate(quat, forwards);
        UP.rotate(quat, up);
        LEFT.rotate(quat, left);
    }
}
