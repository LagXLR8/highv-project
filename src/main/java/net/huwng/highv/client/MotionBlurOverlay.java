package net.huwng.highv.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Screen-space vignette overlay — pseudo motion blur.
 * 4 gradient quads từ 4 cạnh vào tâm.
 * Alpha tăng với intensity^2 → không rõ ở speed thấp.
 */
public class MotionBlurOverlay implements LayeredDraw.Layer {

    @Override
    public void render(GuiGraphics gfx, DeltaTracker delta) {
        if (!HighVClientConfig.ENABLE_MOTION_BLUR_VIGNETTE.get()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        double speed = SpeedEffectSystem.smoothedSpeed;
        boolean isSliding = net.huwng.highv.client.animation.DriftAnimationHandler.isSyncedSliding(mc.player.getUUID());

        // Xuất hiện từ tốc độ 7.5 b/s trở lên hoặc khi đang trượt
        if (speed < 7.5 && !isSliding) return;

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        // Tính toán độ đậm (alpha) điện ảnh rõ nét:
        // - 8 b/s -> 15 b/s: 0.10 -> 0.25
        // - 20 b/s -> 35 b/s: 0.30 -> 0.52
        // - Khi trượt: tối thiểu 0.38
        float speedRatio = (float) Math.min(1.0, Math.max(0.0, (speed - 7.5) / 32.5));
        float alpha = (float) (speedRatio * 0.52f);
        if (isSliding) {
            alpha = Math.max(alpha, 0.38f);
        }
        if (alpha < 0.05f) return;

        int vigW = (int)(W * 0.38f);
        int vigH = (int)(H * 0.38f);

        PoseStack ps = gfx.pose();
        ps.pushPose();

        // Triệt tiêu dao động quán tính của Pilot HUD để vignette luôn đứng yên cố định ở 4 mép màn hình
        net.huwng.highv.client.hud.PilotHudInertiaHandler.undoSway(ps);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableDepthTest();

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f mat = ps.last().pose();

        gradQuad(bb, mat, 0,       0, vigW, H,    alpha, true,  false);
        gradQuad(bb, mat, W-vigW,  0, vigW, H,    alpha, true,  true);
        gradQuad(bb, mat, 0,       0, W,    vigH, alpha, false, false);
        gradQuad(bb, mat, 0,    H-vigH, W,  vigH, alpha, false, true);

        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        ps.popPose();
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
