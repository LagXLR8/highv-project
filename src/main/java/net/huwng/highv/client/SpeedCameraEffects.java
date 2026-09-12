package net.huwng.highv.client;

import net.huwng.highv.HighV;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * FOV boost + camera shake injection.
 *
 * FOV: dùng setNewFovModifier() — API đúng cho NeoForge 1.21.1.
 * Camera shake: inject pitch/yaw offset từ SpeedEffectSystem vào ViewportEvent.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public class SpeedCameraEffects {

    private static final float MAX_FOV_BOOST = 15f; // +15 độ ở full intensity

    @SubscribeEvent
    public static void onComputeFov(ComputeFovModifierEvent event) {
        if (!HighVClientConfig.ENABLE_DYNAMIC_FOV.get()) return;
        double intensity = SpeedEffectSystem.intensity;
        if (intensity <= 0.0) return;

        // Cubic ease-in: FOV không tăng sớm ở speed thấp
        float eased = (float)(intensity * intensity * intensity);
        float boost  = eased * MAX_FOV_BOOST;

        // setNewFovModifier: API đúng trong NeoForge 1.21.1
        event.setNewFovModifier(event.getNewFovModifier() + boost / 70f);
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!HighVClientConfig.ENABLE_SCREEN_SHAKE.get()) return;
        double sx = SpeedEffectSystem.shakeOffsetX;
        double sy = SpeedEffectSystem.shakeOffsetY;
        if (Math.abs(sx) < 0.001 && Math.abs(sy) < 0.001) return;

        event.setPitch((float)(event.getPitch() + sx));
        event.setYaw(  (float)(event.getYaw()   + sy));
    }
}
