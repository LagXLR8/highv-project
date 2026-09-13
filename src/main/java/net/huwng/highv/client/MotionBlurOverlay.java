package net.huwng.highv.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Screen-space vignette overlay — pseudo motion blur.
 * Cố định 100% trên mặt phẳng màn hình (screen-fixed), không bị nghiêng theo camera hay HUD.
 */
public class MotionBlurOverlay implements LayeredDraw.Layer {

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
            targetAlpha = 0.42f;
        } else if (speed >= 5.0 || isSprinting) {
            // Khi chạy nước rút (sprint ~ 5.6 b/s) -> targetAlpha ~ 0.18
            // Khi chạy 15 b/s -> targetAlpha ~ 0.35
            // Khi chạy >= 30 b/s -> targetAlpha ~ 0.52
            float factor = (float) Math.min(1.0, Math.max(0.0, (speed - 5.0) / 25.0));
            targetAlpha = 0.16f + factor * 0.36f;
        }

        // Damping mượt mà để khi dừng lại hoặc đổi tốc độ vignette không bị biến mất đột ngột
        currentVignetteAlpha = (float) net.huwng.highv.client.camera.CameraFeelMath.damp(currentVignetteAlpha, targetAlpha, 0.09, dt);

        if (currentVignetteAlpha < 0.01f) return;

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        int vigW = (int)(W * 0.38f);
        int vigH = (int)(H * 0.38f);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableDepthTest();

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // MA TRẬN IDENTITY MỚI 100%:
        // Hoàn toàn độc lập với PoseStack, không bị nghiêng bởi Dynamic POV hay xoay theo HUD helmet sway!
        Matrix4f screenMat = new Matrix4f();

        gradQuad(bb, screenMat, 0,       0, vigW, H,    currentVignetteAlpha, true,  false);
        gradQuad(bb, screenMat, W-vigW,  0, vigW, H,    currentVignetteAlpha, true,  true);
        gradQuad(bb, screenMat, 0,       0, W,    vigH, currentVignetteAlpha, false, false);
        gradQuad(bb, screenMat, 0,    H-vigH, W,  vigH, currentVignetteAlpha, false, true);

        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void gradQuad(BufferBuilder bb, Matrix4f m,
                                  int x, int y, int w, int h, float a,
                                  boolean horiz, boolean flip) {
        float a0 = flip ? 0 : a, a1 = flip ? a : 0;
        if (horiz) {
            bb.addVertex(m, x,   y,   0).setColor(0f,0f,0f,a0);
            bb.addVertex(m, x,   y+h, 0).setColor(0f,0f,0f,a0);
            bb.addVertex(m, x+w, y+h, 0).setColor(0f,0f,0f,a1);
            bb.addVertex(m, x+w, y,   0).setColor(0f,0f,0f,a1);
        } else {
            bb.addVertex(m, x,   y,   0).setColor(0f,0f,0f,a0);
            bb.addVertex(m, x,   y+h, 0).setColor(0f,0f,0f,a1);
            bb.addVertex(m, x+w, y+h, 0).setColor(0f,0f,0f,a1);
            bb.addVertex(m, x+w, y,   0).setColor(0f,0f,0f,a0);
        }
    }
}
