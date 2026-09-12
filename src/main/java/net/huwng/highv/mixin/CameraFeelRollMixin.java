package net.huwng.highv.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.huwng.highv.client.camera.CameraFeelState;
import net.huwng.highv.client.camera.ScreenShakeManager;
import net.huwng.highv.client.camera.ShoulderCameraState;
import net.minecraft.client.CameraType;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Camera vanilla không tự xoay roll được, nên phần roll (trục Z) tính bởi
 * {@link CameraFeelState} (strafe roll + turning roll) được áp trực tiếp lên
 * ma trận view ngay trước khi world được vẽ, cộng thêm screen shake hiện tại
 * từ {@link ScreenShakeManager}.
 *
 * Offset chính (pitch/yaw) đã được {@code CameraFeelMixin} tính và cache
 * trong cùng frame này rồi — ở đây chỉ đọc lại phần .z, KHÔNG compute lại.
 *
 * Shoulder camera CŨNG được áp ở đây (translate ma trận view) thay vì di
 * chuyển vị trí Camera thật — lần trước mình thử shadow method
 * {@code Camera#move(double,double,double)} để làm việc đó nhưng method đó
 * không tồn tại trong Camera ở MC 1.21.1 thật (crash lúc khởi động). Cách
 * này chỉ dùng {@code PoseStack#translate} (API public, chắc chắn tồn tại)
 * + {@code Minecraft.getInstance().options.getCameraType()} để biết đang ở
 * góc nhìn thứ 3 hay không — không còn đoán field/method riêng tư nào của
 * Camera nữa.
 *
 * Giới hạn: vì đây chỉ là dịch ma trận vẽ (không phải dịch Camera thật),
 * offset này không tự né va chạm tường như zoom góc nhìn thứ 3 của vanilla.
 */
@Mixin(GameRenderer.class)
public abstract class CameraFeelRollMixin {

    @Shadow @Final private Camera mainCamera;

    @Inject(method = "bobHurt", at = @At("HEAD"))
    private void highv$applyRoll(PoseStack matrices, float partialTick, CallbackInfo ci) {
        Vector3d physicsOffset = CameraFeelState.INSTANCE.getCachedOffset();

        Vector3d cameraPos = new Vector3d(mainCamera.getPosition().x, mainCamera.getPosition().y, mainCamera.getPosition().z);
        double now = System.nanoTime() / 1_000_000_000.0;
        Vector3d shakeOffset = ScreenShakeManager.INSTANCE.resolve(cameraPos, now);

        CameraType cameraType = Minecraft.getInstance().options.getCameraType();

        double rollDegrees = 0.0;
        if (net.huwng.highv.client.HighVClientConfig.ENABLE_CAMERA_ROLL.get()) {
            rollDegrees += physicsOffset.z;
        }
        if (net.huwng.highv.client.HighVClientConfig.ENABLE_SCREEN_SHAKE.get()) {
            rollDegrees += shakeOffset.z;
        }
        if (rollDegrees != 0) {
            matrices.mulPose(Axis.of(new Vector3f(0, 0, 1)).rotationDegrees((float) rollDegrees));
        }

        // Áp dụng chuyển động Camera POV động (Head Bobbing, Strafe Roll, Landing Cushion, Slash Recoil)
        if (!this.highv$isRenderingItemInHand && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                net.huwng.highv.client.camera.DynamicPovHandler.updateCamera(mc.player, partialTick);
                net.huwng.highv.client.camera.DynamicPovHandler.applyCameraPov(matrices);
            }
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean highv$isRenderingItemInHand = false;

    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void highv$onStartRenderItemInHand(Camera camera, float partialTick, org.joml.Matrix4f projectionMatrix, CallbackInfo ci) {
        this.highv$isRenderingItemInHand = true;
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void highv$onEndRenderItemInHand(Camera camera, float partialTick, org.joml.Matrix4f projectionMatrix, CallbackInfo ci) {
        this.highv$isRenderingItemInHand = false;
    }

    /**
     * Triệt tiêu hoàn toàn view bobbing mặc định của Minecraft đối với bàn tay và camera,
     * để hệ thống Dynamic POV độc lập tự quản lý chuyển động mượt mà.
     */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void highv$cancelHandBobView(PoseStack poseStack, float partialTicks, CallbackInfo ci) {
        if (this.highv$isRenderingItemInHand) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                net.minecraft.world.item.ItemStack main = mc.player.getMainHandItem();
                if (main.is(net.huwng.highv.item.ModItems.THERMAL_KATANA.get()) || main.getItem() instanceof net.huwng.highv.item.ThermalKatanaItem) {
                    ci.cancel();
                }
            }
        } else {
            // Thay thế bobView thô của vanilla bằng hệ thống POV động chuẩn điện ảnh
            if (Minecraft.getInstance().options.getCameraType().isFirstPerson()
                    && net.huwng.highv.client.HighVClientConfig.ENABLE_DYNAMIC_POV_CAMERA.get()) {
                ci.cancel();
            }
        }
    }
}

