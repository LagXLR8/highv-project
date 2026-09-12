package net.huwng.highv.client.camera;

import net.huwng.highv.HighV;
import net.huwng.highv.client.SpeedEffectSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Bật/tắt post-chain {@code shaders/post/motion_blur.json} (frame
 * accumulation — blend frame hiện tại với "history" frame trước đó, tạo vệt
 * mờ theo đúng hướng mọi thứ đang di chuyển trên màn hình, không phải
 * vignette tĩnh) dựa theo {@link SpeedEffectSystem#intensity}.
 *
 * CỐ Ý không đẩy uniform {@code Decay} thay đổi theo tốc độ mỗi frame —
 * việc đó cần biết chính xác API nội bộ để lấy lại {@code EffectInstance}
 * đang active từ {@code PostChain} (không phải API public rõ ràng, mình
 * không verify được chắc chắn như 2 lần trước gây crash). Thay vào đó độ
 * "mờ" là cố định trong JSON (field {@code Decay}, hiện 0.55 — chỉnh trực
 * tiếp trong {@code motion_blur.json} nếu muốn mờ nhiều/ít hơn), chỉ có
 * BẬT/TẮT là động theo tốc độ (dùng {@code loadEffect}/{@code
 * shutdownEffect} — API public, đã có tiền lệ dùng rộng rãi trong modding).
 *
 * Có hysteresis (ngưỡng bật khác ngưỡng tắt) để không nhấp nháy bật/tắt liên
 * tục khi tốc độ dao động quanh 1 mốc.
 *
 * GIỚI HẠN ĐÃ BIẾT: vanilla cũng dùng chung "1 slot" post-chain này cho các
 * hiệu ứng khác (vd. nausea/buồn nôn). Nếu 2 hiệu ứng cùng cố active một
 * lúc, cái này có thể ghi đè cái kia hoặc ngược lại — chưa xử lý việc "xếp
 * chồng" nhiều post-chain, báo mình nếu gặp xung đột thực tế.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class RealMotionBlurHandler {

    private static final ResourceLocation EFFECT =
        ResourceLocation.fromNamespaceAndPath(HighV.MOD_ID, "shaders/post/motion_blur.json");

    private static final double ON_THRESHOLD = 0.25;
    private static final double OFF_THRESHOLD = 0.08;

    private static boolean active = false;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null || mc.player == null || !net.huwng.highv.client.HighVClientConfig.ENABLE_MOTION_BLUR_SHADER.get()) {
            disable(mc);
            return;
        }

        double intensity = SpeedEffectSystem.intensity;
        if (!active && intensity >= ON_THRESHOLD) {
            mc.gameRenderer.loadEffect(EFFECT);
            active = true;
        } else if (active && intensity <= OFF_THRESHOLD) {
            disable(mc);
        }
    }

    private static void disable(Minecraft mc) {
        if (!active) return;
        mc.gameRenderer.shutdownEffect();
        active = false;
    }
}
