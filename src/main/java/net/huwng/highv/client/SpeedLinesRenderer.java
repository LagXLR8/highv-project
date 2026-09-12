package net.huwng.highv.client;

import net.huwng.highv.HighV;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.client.event.ScreenshotEvent;
import net.minecraft.client.Minecraft;

/**
 * Kết nối SpeedLinesShader vào vòng lặp game:
 *
 *  • Tick      — cập nhật bộ đếm thời gian cho STime
 *  • AFTER_LEVEL render stage — chạy PostChain shader sau khi world render xong,
 *                               giống cách Satin dùng ShaderEffectRenderCallback
 *
 * Resource reload và window resize được xử lý qua ClientSetup
 * (RegisterClientReloadListenersEvent và ScreenEvent.Init).
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public class SpeedLinesRenderer {

    /**
     * Chạy shader sau khi level render xong (trước GUI).
     * Stage.AFTER_LEVEL = tương đương PostWorldRenderCallbackV2 trên Fabric.
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!HighVClientConfig.ENABLE_SPEED_LINES.get()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        SpeedLinesShader.render(partialTick);
    }
}
