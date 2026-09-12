package net.huwng.highv.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.huwng.highv.HighV;
import net.huwng.highv.client.camera.ShoulderCameraState;
import net.huwng.highv.client.camera.TargetLockHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Giao diện HUD nhắm mục tiêu phong cách Armored Core 6 (AC6):
 *
 * 1. 3D Billboard Target Reticle (RenderLevelStageEvent.AFTER_PARTICLES):
 *    - Khung ngắm bám theo mục tiêu trong không gian 3D.
 *    - SOFT LOCK: Màu Cyan Neon (#00E5FF), 4 góc ngoặc thanh mảnh, cự ly mét.
 *    - HARD LOCK: Màu Amber/Orange Neon (#FF9100), khung khóa dày, diamond xoay cơ học,
 *      chữ [LOCKED], tên quái vật, cự ly và thanh máu (HP Bar) trực quan.
 *
 * 2. 2D FCS Center Reticle (RenderGuiEvent.Post):
 *    - Vòng tròn FCS quét mục tiêu ở giữa màn hình chuẩn phong cách buồng lái AC6.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class TargetLockHudRenderer {

    private TargetLockHudRenderer() {}

    // ─────────────────────────────────────────────────────────────────────────
    // 1. 3D BILLBOARD TARGET RETICLE (Render trực tiếp tại vị trí mục tiêu)
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!TargetLockHandler.isTargetingActive()) return;

        LivingEntity target = TargetLockHandler.getActiveTarget();
        if (target == null || !target.isAlive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean isHard = TargetLockHandler.isHardLocked();
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();
        Vec3 targetPos = TargetLockHandler.getTargetCenter(target);
        double dist = camPos.distanceTo(targetPos);
        if (dist < 0.2) return;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();

        // Di chuyển tới tâm mục tiêu và xoay luôn hướng về phía camera
        pose.translate(targetPos.x - camPos.x, targetPos.y - camPos.y, targetPos.z - camPos.z);
        pose.mulPose(new Quaternionf(cam.rotation()));

        // Scale để kích thước trên màn hình giữ tỉ lệ hợp lý theo cự ly
        float scale = (float) Math.max(0.40f, dist * 0.042f);
        pose.scale(scale, scale, scale);

        Matrix4f mat = pose.last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Màu sắc AC6
        float r = isHard ? 1.0f : 0.0f;
        float g = isHard ? 0.58f : 0.90f;
        float b = isHard ? 0.08f : 1.0f;
        float a = isHard ? 0.95f : 0.85f;

        float boxRadius = isHard ? 0.65f : 0.80f;
        float armLen    = isHard ? 0.32f : 0.28f;
        float thick     = isHard ? 0.045f : 0.032f;

        // Vẽ 4 góc ngoặc [  ]
        // Góc trên - trái
        drawRect(bb, mat, -boxRadius, -boxRadius, -boxRadius + armLen, -boxRadius + thick, r, g, b, a);
        drawRect(bb, mat, -boxRadius, -boxRadius, -boxRadius + thick, -boxRadius + armLen, r, g, b, a);

        // Góc trên - phải
        drawRect(bb, mat, boxRadius - armLen, -boxRadius, boxRadius, -boxRadius + thick, r, g, b, a);
        drawRect(bb, mat, boxRadius - thick, -boxRadius, boxRadius, -boxRadius + armLen, r, g, b, a);

        // Góc dưới - trái
        drawRect(bb, mat, -boxRadius, boxRadius - thick, -boxRadius + armLen, boxRadius, r, g, b, a);
        drawRect(bb, mat, -boxRadius, boxRadius - armLen, -boxRadius + thick, boxRadius, r, g, b, a);

        // Góc dưới - phải
        drawRect(bb, mat, boxRadius - armLen, boxRadius - thick, boxRadius, boxRadius, r, g, b, a);
        drawRect(bb, mat, boxRadius - thick, boxRadius - armLen, boxRadius, boxRadius, r, g, b, a);

        // Tâm crosshair nhỏ
        float cSize = isHard ? 0.08f : 0.05f;
        float cThick = 0.02f;
        drawRect(bb, mat, -cSize, -cThick, cSize, cThick, r, g, b, a);
        drawRect(bb, mat, -cThick, -cSize, cThick, cSize, r, g, b, a);

        // Kim cương xoay cơ học bên trong khi HARD LOCK
        if (isHard) {
            float rotRad = (float) Math.toRadians(TargetLockHandler.lockRingAngle);
            float dSize = 0.42f;
            float cos = (float) Math.cos(rotRad) * dSize;
            float sin = (float) Math.sin(rotRad) * dSize;

            drawLine(bb, mat, cos, sin, -sin, cos, 0.028f, r, g, b, 0.8f);
            drawLine(bb, mat, -sin, cos, -cos, -sin, 0.028f, r, g, b, 0.8f);
            drawLine(bb, mat, -cos, -sin, sin, -cos, 0.028f, r, g, b, 0.8f);
            drawLine(bb, mat, sin, -cos, cos, sin, 0.028f, r, g, b, 0.8f);

            // Thanh máu mục tiêu (HP Bar)
            float hpRatio = Mth.clamp(target.getHealth() / Math.max(1.0f, target.getMaxHealth()), 0.0f, 1.0f);
            float barW = 0.90f;
            float barH = 0.07f;
            float barY = boxRadius + 0.12f;

            // Nền đen thanh máu
            drawRect(bb, mat, -barW * 0.5f, barY, barW * 0.5f, barY + barH, 0.1f, 0.1f, 0.1f, 0.75f);
            // Lượng máu còn lại (chuyển dần từ Cam sang Đỏ khi yếu máu)
            float hpR = hpRatio > 0.4f ? 1.0f : 1.0f;
            float hpG = hpRatio > 0.4f ? 0.65f * hpRatio : 0.15f;
            float hpB = 0.05f;
            drawRect(bb, mat, -barW * 0.5f, barY, -barW * 0.5f + barW * hpRatio, barY + barH, hpR, hpG, hpB, 0.95f);
        }

        BufferUploader.drawWithShader(bb.buildOrThrow());

        // ── Render Văn bản thông số (Tên, Cự ly, Nhãn LOCKED) ──
        Font font = mc.font;
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        pose.pushPose();
        // Lật trục Y của PoseStack cho phù hợp với Font draw
        pose.scale(0.015f, -0.015f, 0.015f);

        String distStr = String.format("%.0fm", dist);
        int textColor = isHard ? 0xFFFF9100 : 0xFF00E5FF;

        if (isHard) {
            String lockText = "[ LOCKED ]";
            font.drawInBatch(lockText, -font.width(lockText) / 2.0f, -70, 0xFFFF3D00,
                    false, pose.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 15728880);

            String name = target.getDisplayName().getString();
            font.drawInBatch(name, -font.width(name) / 2.0f, -56, 0xFFFFFFFF,
                    false, pose.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 15728880);

            font.drawInBatch(distStr, -font.width(distStr) / 2.0f, 62, textColor,
                    false, pose.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 15728880);
        } else {
            font.drawInBatch(distStr, -font.width(distStr) / 2.0f, 48, textColor,
                    false, pose.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 15728880);
        }

        bufferSource.endBatch();
        pose.popPose();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. 2D FCS CENTER RETICLE (Vòng ngắm trung tâm FCS trên màn hình 2D)
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!TargetLockHandler.isTargetingActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        GuiGraphics gfx = event.getGuiGraphics();
        int screenW = gfx.guiWidth();
        int screenH = gfx.guiHeight();
        float cx = screenW / 2.0f;
        float cy = screenH / 2.0f;

        boolean isHard = TargetLockHandler.isHardLocked();
        boolean hasSoft = TargetLockHandler.getSoftLockedTarget() != null;

        float r = isHard ? 1.0f : (hasSoft ? 0.0f : 0.8f);
        float g = isHard ? 0.55f : (hasSoft ? 0.90f : 0.85f);
        float b = isHard ? 0.08f : (hasSoft ? 1.0f : 0.9f);
        float a = isHard ? 0.75f : (hasSoft ? 0.55f : 0.30f);

        PoseStack ps = gfx.pose();
        ps.pushPose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f mat = ps.last().pose();

        // Vẽ vòng tròn FCS tâm ngắm (bán kính ~55px)
        float ringRadius = 55.0f;
        int segments = 28;
        for (int i = 0; i < segments; i++) {
            // Ngắt quãng vòng tròn tạo cảm giác HUD công nghệ (segmented circle)
            if (i % 7 == 0) continue;

            float theta1 = (float) (2.0 * Math.PI * i / segments);
            float theta2 = (float) (2.0 * Math.PI * (i + 1) / segments);

            float x1 = cx + (float) Math.cos(theta1) * ringRadius;
            float y1 = cy + (float) Math.sin(theta1) * ringRadius;
            float x2 = cx + (float) Math.cos(theta2) * ringRadius;
            float y2 = cy + (float) Math.sin(theta2) * ringRadius;

            bb.addVertex(mat, x1, y1, 0).setColor(r, g, b, a);
            bb.addVertex(mat, x2, y2, 0).setColor(r, g, b, a);
        }

        // 4 vạch ngấn định hướng (Crosshair Ticks)
        float tickInner = ringRadius - 6.0f;
        float tickOuter = ringRadius + 6.0f;
        // Trái
        bb.addVertex(mat, cx - tickOuter, cy, 0).setColor(r, g, b, a);
        bb.addVertex(mat, cx - tickInner, cy, 0).setColor(r, g, b, a);
        // Phải
        bb.addVertex(mat, cx + tickInner, cy, 0).setColor(r, g, b, a);
        bb.addVertex(mat, cx + tickOuter, cy, 0).setColor(r, g, b, a);
        // Trên
        bb.addVertex(mat, cx, cy - tickOuter, 0).setColor(r, g, b, a);
        bb.addVertex(mat, cx, cy - tickInner, 0).setColor(r, g, b, a);
        // Dưới
        bb.addVertex(mat, cx, cy + tickInner, 0).setColor(r, g, b, a);
        bb.addVertex(mat, cx, cy + tickOuter, 0).setColor(r, g, b, a);

        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.disableBlend();

        // Nhãn chỉ báo FCS dưới vòng tròn ngắm
        Font font = mc.font;
        String modeLabel = isHard ? "HARD LOCK // ENGAGED" : (hasSoft ? "FCS // TARGET ACQUIRED" : "FCS // STANDBY");
        int labelColor = isHard ? 0xFFFF9100 : (hasSoft ? 0xFF00E5FF : 0x88AAAAAA);
        gfx.drawString(font, modeLabel, (int) (cx - font.width(modeLabel) / 2.0f), (int) (cy + ringRadius + 8), labelColor, false);

        ps.popPose();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper drawing functions
    // ─────────────────────────────────────────────────────────────────────────
    private static void drawRect(BufferBuilder bb, Matrix4f mat, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        bb.addVertex(mat, x1, y1, 0).setColor(r, g, b, a);
        bb.addVertex(mat, x1, y2, 0).setColor(r, g, b, a);
        bb.addVertex(mat, x2, y2, 0).setColor(r, g, b, a);
        bb.addVertex(mat, x2, y1, 0).setColor(r, g, b, a);
    }

    private static void drawLine(BufferBuilder bb, Matrix4f mat, float x1, float y1, float x2, float y2, float thick, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001f) return;
        float nx = -dy / len * (thick * 0.5f);
        float ny =  dx / len * (thick * 0.5f);

        bb.addVertex(mat, x1 + nx, y1 + ny, 0).setColor(r, g, b, a);
        bb.addVertex(mat, x1 - nx, y1 - ny, 0).setColor(r, g, b, a);
        bb.addVertex(mat, x2 - nx, y2 - ny, 0).setColor(r, g, b, a);
        bb.addVertex(mat, x2 + nx, y2 + ny, 0).setColor(r, g, b, a);
    }
}
