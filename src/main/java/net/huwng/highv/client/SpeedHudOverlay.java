package net.huwng.highv.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Speed HUD overlay:
 *  1) Speed lines — 48 radial lines từ center khi tốc độ cao
 *  2) Speedometer — bên trái thanh máu, hiển thị b/s + % bar
 */
public class SpeedHudOverlay implements LayeredDraw.Layer {

    private static final int   LINE_COUNT   = 60;
    private static final float LINE_MIN_LEN = 0.04f;
    private static final float LINE_MAX_LEN = 0.35f;

    private static final float[] LINE_ANGLES  = new float[LINE_COUNT];
    private static final float[] LINE_OFFSETS = new float[LINE_COUNT];

    static {
        Random rng = new Random(0xDEADBEEF);
        for (int i = 0; i < LINE_COUNT; i++) {
            LINE_ANGLES[i]  = (float)(i * (Math.PI * 2.0 / LINE_COUNT))
                             + (float)(rng.nextGaussian() * 0.08);
            LINE_OFFSETS[i] = 0.08f + rng.nextFloat() * 0.12f;
        }
    }

    @Override
    public void render(GuiGraphics gfx, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        double speed     = SpeedEffectSystem.smoothedSpeed;
        double intensity = SpeedEffectSystem.intensity;

        renderSpeedLines(gfx, mc, intensity);
        renderSpeedometer(gfx, mc, speed, intensity);
    }

    // ── Speed lines ─────────────────────────────────────────────────────────
    private void renderSpeedLines(GuiGraphics gfx, Minecraft mc, double intensity) {
        if (intensity <= 0.01) return;

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        float cx = W * 0.5f, cy = H * 0.5f;
        float ref = (float) Math.sqrt(cx * cx + cy * cy);
        float alpha = (float)(intensity * intensity * 0.65);

        PoseStack ps = gfx.pose();
        ps.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f mat = ps.last().pose();

        for (int i = 0; i < LINE_COUNT; i++) {
            float ang   = LINE_ANGLES[i];
            float cos   = (float) Math.cos(ang);
            float sin   = (float) Math.sin(ang);
            float inner = (LINE_OFFSETS[i] + LINE_MIN_LEN * (float) intensity)       * ref;
            float outer = (LINE_OFFSETS[i] + LINE_MAX_LEN * (float)(intensity*intensity)) * ref;

            bb.addVertex(mat, cx + cos * inner, cy + sin * inner, 0).setColor(1f,1f,1f,alpha);
            bb.addVertex(mat, cx + cos * outer, cy + sin * outer, 0).setColor(1f,1f,1f,0f);
        }

        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.disableBlend();
        ps.popPose();
    }

    private void renderSpeedometer(GuiGraphics gfx, Minecraft mc,
                                    double speed, double intensity) {
        if (speed < 0.5) return;

        int  W    = mc.getWindow().getGuiScaledWidth();
        int  H    = mc.getWindow().getGuiScaledHeight();
        Font font = mc.font;

        int barW  = 30;
        int barH  = 6;
        int xBase = W / 2 - 105 - barW - 8;
        int yBase = H - 29;

        int percent  = SpeedEffectSystem.getSpeedPercent();
        int barFill  = (int)(barW * percent / 100.0);
        int barColor = speedColor(percent);

        gfx.fill(xBase, yBase, xBase + barW, yBase + barH, 0x88000000);
        if (barFill > 0)
            gfx.fill(xBase, yBase, xBase + barFill, yBase + barH, barColor | 0xCC000000);
        gfx.renderOutline(xBase - 1, yBase - 1, barW + 2, barH + 2, 0x88FFFFFF);

        String text     = SpeedEffectSystem.getSpeedString();
        int    textX    = xBase + barW / 2 - font.width(text) / 2;
        int    textY    = yBase - font.lineHeight - 1;
        int    textColor = intensity > 0.5 ? 0xFFFF4444 : 0xFFFFFFFF;
        gfx.drawString(font, text, textX, textY, textColor, true);

        String pctText = percent + "%";
        gfx.drawString(font, pctText, xBase + barW + 3, yBase, 0xAAFFFFFF, false);
    }

    private static int speedColor(int pct) {
        if (pct < 60) {
            int r = (int)(0x40 + (pct / 60f) * (0xFF - 0x40));
            return (r << 16) | (0xFF << 8);
        }
        int g = (int)(0xFF * (1f - (pct - 60) / 40f));
        return (0xFF << 16) | (g << 8);
    }
}
