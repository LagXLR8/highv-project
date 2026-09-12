package net.huwng.highv.client;

import net.huwng.highv.HighV;
import net.huwng.highv.client.animation.DashAnimationHandler;
import net.huwng.highv.client.animation.DriftAnimationHandler;
import net.huwng.highv.client.animation.MomentumAnimationHandler;
import net.huwng.highv.client.animation.WallstrideAnimationHandler;
import net.huwng.highv.client.renderer.entity.GrapplingHookRenderer;
import net.huwng.highv.entity.ModEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-side setup.
 *
 * Bus phân loại:
 *  MOD BUS   — EntityRenderersEvent, RegisterGuiLayersEvent,
 *              RegisterClientReloadListenersEvent
 *  GAME BUS  — ScreenEvent (→ @EventBusSubscriber riêng bên dưới)
 */
public class ClientSetup {

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::onRegisterRenderers);
        modEventBus.addListener(ClientSetup::onRegisterGuiLayers);
        modEventBus.addListener(ClientSetup::onRegisterReloadListeners);
        modEventBus.addListener(ModKeyMappings::register);
        // ScreenEvent KHÔNG đăng ký trên modEventBus — xem ScreenHandler bên dưới

        MomentumAnimationHandler.init();
        WallstrideAnimationHandler.init();
        DashAnimationHandler.init();
        DriftAnimationHandler.init();
        net.huwng.highv.client.hud.PilotHudInertiaHandler.init();
        KatanaArmLiveTuner.init();

        // Tự động bật hiển thị cánh tay khi chém với Better Combat trong góc nhìn thứ nhất
        ensureBetterCombatArmVisible();
    }

    /**
     * Đảm bảo Better Combat chỉ hiển thị cánh tay phải vung theo kiếm trong góc nhìn thứ nhất,
     * không hiển thị thêm cánh tay trái.
     */
    public static void ensureBetterCombatArmVisible() {
        try {
            if (net.bettercombat.client.BetterCombatClientMod.config != null) {
                net.bettercombat.client.BetterCombatClientMod.config.isShowingArmsInFirstPerson = true;
                net.bettercombat.client.BetterCombatClientMod.config.isShowingOtherHandFirstPerson = false;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(
                ModEntities.GRAPPLING_HOOK.get(),
                GrapplingHookRenderer::new
        );
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HighV.id("motion_blur"), new MotionBlurOverlay());
        event.registerAboveAll(HighV.id("speed_hud"),   new SpeedHudOverlay());
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (ResourceManagerReloadListener) rm -> SpeedLinesShader.load()
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    // ScreenEvent.Init.Pre → GAME BUS (NeoForge.EVENT_BUS)
    // ────────────────────────────────────────────────────────────────────────
    @EventBusSubscriber(modid = HighV.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
    public static class ScreenHandler {
        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            SpeedLinesShader.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        }
    }
}