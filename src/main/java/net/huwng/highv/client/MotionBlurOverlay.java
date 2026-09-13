package net.huwng.highv.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.huwng.highv.HighV;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Screen-space radial vignette overlay — cinematic high-speed tunnel vision.
 * Sử dụng texture radial vignette và ma trận Identity cố định 100% trên mặt phẳng màn hình,
 * hoàn toàn không bị nghiêng/lắc theo camera hay HUD.
 */
public class MotionBlurOverlay implements LayeredDraw.Layer {

    private static final ResourceLocation VIGNETTE_TEXTURE =
            HighV.id("textures/gui/speed_vignette.png");

    private static float currentVignetteAlpha = 0.0f;
    private static long lastRenderNanos = -1;

    @Override
    public void render(GuiGraphics gfx, DeltaTracker delta) {
        if (!HighVClientConfig.ENABLE_MOTION_BLUR_VIGNETTE.get()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        // 1. Tính delta time mượt mà
        long now = System.nanoTime();
        float dt = (lastRenderNanos < 0) ? 0.016f : (float) Math.min((now - lastRenderNanos) / 1_000_000_000.0, 0.1);
        lastRenderNanos = now;

        // 2. Đo tốc độ thực tế (kết hợp cả smoothedSpeed và instant movement)
        Vec3 move = mc.player.getDeltaMovement();
        double instantHorizSpeed = Math.sqrt(move.x * move.x + move.z * move.z) * 20.0;
        double speed = Math.max(SpeedEffectSystem.smoothedSpeed, instantHorizSpeed);

        boolean isSliding = net.huwng.highv.client.animation.DriftAnimationHandler.isSyncedSliding(mc.player.getUUID());
        boolean isSprinting = mc.player.isSprinting() && instantHorizSpeed > 3.0;

        // 3. Tính toán target alpha mượt mà
        float targetAlpha = 0.0f;
        if (isSliding) {
            targetAlpha = 0.55f;
        } else if (speed >= 5.0 || isSprinting) {
            // Khi chạy nước rút (sprint ~ 5.6 b/s) -> targetAlpha ~ 0.22
            // Khi chạy 15 b/s -> targetAlpha ~ 0.45
            // Khi chạy >= 30 b/s -> targetAlpha ~ 0.65
            float factor = (float) Math.min(1.0, Math.max(0.0, (speed - 5.0) / 25.0));
            targetAlpha = 0.22f + factor * 0.43f;
        }

        // Damping mượt mà để khi dừng lại hoặc đổi tốc độ vignette không bị biến mất đột ngột
        currentVignetteAlpha = (float) net.huwng.highv.client.camera.CameraFeelMath.damp(currentVignetteAlpha, targetAlpha, 0.09, dt);

        if (currentVignetteAlpha < 0.01f) return;

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, VIGNETTE_TEXTURE);
        RenderSystem.disableDepthTest();

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        // MA TRẬN IDENTITY MỚI 100%:
        // Cố định hoàn toàn trên mặt phẳng màn hình phẳng, KHÔNG bị lắc theo camera hay xoay theo HUD!
        Matrix4f screenMat = new Matrix4f();

        bb.addVertex(screenMat, 0.0f, 0.0f, 0.0f).setUv(0.0f, 0.0f).setColor(1.0f, 1.0f, 1.0f, currentVignetteAlpha);
        bb.addVertex(screenMat, 0.0f, (float) H, 0.0f).setUv(0.0f, 1.0f).setColor(1.0f, 1.0f, 1.0f, currentVignetteAlpha);
        bb.addVertex(screenMat, (float) W, (float) H, 0.0f).setUv(1.0f, 1.0f).setColor(1.0f, 1.0f, 1.0f, currentVignetteAlpha);
        bb.addVertex(screenMat, (float) W, 0.0f, 0.0f).setUv(1.0f, 0.0f).setColor(1.0f, 1.0f, 1.0f, currentVignetteAlpha);

        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
