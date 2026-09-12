package net.huwng.highv.client.camera;

import net.huwng.highv.HighV;
import net.huwng.highv.client.ModKeyMappings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Bấm một cái Alt = đảo trạng thái bật/tắt shoulder camera (không phải giữ
 * phím). Dùng {@code KeyMapping#consumeClick()} — API vanilla chuẩn cho
 * đúng kiểu "một lần bấm = một lần trigger", tự động không bị lặp lại khi
 * giữ phím lâu.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class ShoulderCameraInputHandler {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (ModKeyMappings.TOGGLE_SHOULDER_CAMERA.consumeClick()) {
            ShoulderCameraState.INSTANCE.toggle();
            if (net.huwng.highv.client.HighVClientConfig.ENABLE_AC6_CAMERA.get()) {
                if (!ShoulderCameraState.INSTANCE.isEnabled() && net.huwng.highv.client.HighVClientConfig.REQUIRE_SHOULDER_CAMERA_FOR_LOCK.get()) {
                    TargetLockHandler.disengageHardLock();
                }
            }
        }

        while (ModKeyMappings.HARD_LOCK.consumeClick()) {
            if (!net.huwng.highv.client.HighVClientConfig.ENABLE_AC6_CAMERA.get()) {
                continue;
            }
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null) {
                // Nếu chưa bật Shoulder Camera mà bấm Hard Lock -> tự động kích hoạt Shoulder Camera
                if (!ShoulderCameraState.INSTANCE.isEnabled() && net.huwng.highv.client.HighVClientConfig.REQUIRE_SHOULDER_CAMERA_FOR_LOCK.get()) {
                    ShoulderCameraState.INSTANCE.toggle();
                    if (mc.options.getCameraType().isFirstPerson()) {
                        mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                    }
                }
                TargetLockHandler.toggleHardLock(mc.player);
            }
        }
    }
}
