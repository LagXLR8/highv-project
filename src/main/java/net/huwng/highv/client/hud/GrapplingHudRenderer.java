package net.huwng.highv.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.huwng.highv.HighV;
import net.huwng.highv.client.HighVClientConfig;
import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.event.ClientInputHandler;
import net.huwng.highv.item.GrapplingHookItem;
import net.huwng.highv.item.ModItems;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Giao diện HUD nhắm bắn và nhận diện điểm an toàn của Dây Móc (Grappling Hook)
 * theo phong cách Armored Core 6 (AC6 FCS Reticle):
 *
 * 1. 3D Billboard Vector Reticle:
 *    - Render trực tiếp tại điểm va chạm / mục tiêu trong không gian 3D.
 *    - SAFE LEDGE: Màu Cyan Neon (#00E5FF), 4 góc ngoặc [ ], đỉnh tam giác chỉ gờ an toàn,
 *      thông số cự ly mét, nhãn [ SAFE LEDGE ] và trạng thái FCS // ANCHOR READY.
 *    - WALL / ANCHOR POINT: Màu Tech Teal (#29B6F6), 4 góc ngoặc chuẩn, nhãn [ ANCHOR POINT ].
 *    - ENTITY TARGET: Màu Amber/Orange Neon (#FF9100), khung dày, tên thực thể mục tiêu, [ TARGET LOCK ].
 *    - HOOK ATTACHED: Màu Electric White/Aqua (#E0F7FA), vòng kim cương xoay cơ học, [ ENGAGED ].
 *    - HOOK LAUNCHING: Màu Electric Gold (#FFD600), nhãn [ DEPLOYING ].
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public class GrapplingHudRenderer {

    private GrapplingHudRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!HighVClientConfig.ENABLE_GRAPPLING_CROSSHAIR.get()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        boolean holdingHook = player.getOffhandItem().is(ModItems.GRAPPLING_HOOK.get())
                || player.getMainHandItem().is(ModItems.GRAPPLING_HOOK.get());
        if (!holdingHook) return;

        // ── Xác định trạng thái mục tiêu ──────────────────────────────────────
        GrapplingHookEntity hook = GrapplingHookItem.findActiveHook(mc.level, player);
        Vec3 worldTarget;
        boolean isAttached = false;
        boolean isFlying = false;
        boolean isEntity = false;
        boolean isSafeLedge = false;
        Entity targetEntity = null;
        double dist;

        if (hook != null) {
            if (hook.isAttached()) {
                worldTarget = hook.getAttachPoint();
                isAttached = true;
                dist = player.getEyePosition().distanceTo(worldTarget);
                int hookedId = hook.getHookedEntityId();
                if (hookedId >= 0) {
                    targetEntity = mc.level.getEntity(hookedId);
                    isEntity = (targetEntity != null);
                }
            } else {
                Vec3 ct = hook.getClientTarget();
                worldTarget = ct != null ? ct : hook.position();
                isFlying = true;
                dist = player.getEyePosition().distanceTo(worldTarget);
                int hookedId = hook.getHookedEntityId();
                if (hookedId >= 0) {
                    targetEntity = mc.level.getEntity(hookedId);
                    isEntity = (targetEntity != null);
                }
            }
        } else {
            ClientInputHandler.TargetResult target = ClientInputHandler.lastTarget;
            if (target == null) return;
            worldTarget = target.position();
            isEntity = target.isEntity();
            targetEntity = target.entity();
            isSafeLedge = target.isSafeLedge();
            dist = target.distance() > 0.1 ? target.distance() : player.getEyePosition().distanceTo(worldTarget);
        }

        if (worldTarget == null) return;

        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();
        double camDist = camPos.distanceTo(worldTarget);
        if (camDist < 0.2) return;

        // ── Render 3D Billboard Reticle ──────────────────────────────────────
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(worldTarget.x - camPos.x, worldTarget.y - camPos.y, worldTarget.z - camPos.z);
        pose.mulPose(new Quaternionf(cam.rotation()));

        // Scale giữ tỉ lệ hiển thị cân đối trên màn hình theo phong cách buồng lái AC6
        float scale = (float) Math.max(0.38f, camDist * 0.040f);
        pose.scale(scale, scale, scale);

        Matrix4f mat = pose.last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Màu sắc & Nhãn thông số
        float r, g, b, a;
        int textColor, subColor;
        String headerText, subText;

        if (isAttached) {
            r = 0.88f; g = 1.0f; b = 1.0f; a = 0.95f; // Electric White/Aqua
            textColor = 0xFFE0F7FA;
            subColor = 0xAA80DEEA;
            headerText = "[ ENGAGED ]";
            subText = "PULLING // ACTIVE";
        } else if (isFlying) {
            r = 1.0f; g = 0.84f; b = 0.15f; a = 0.90f; // Electric Gold
            textColor = 0xFFFFD600;
            subColor = 0xAAFFE082;
            headerText = "[ DEPLOYING ]";
            subText = "TRAJECTORY // LOCKED";
        } else if (isEntity) {
            r = 1.0f; g = 0.58f; b = 0.08f; a = 0.95f; // Amber/Orange Neon
            textColor = 0xFFFF9100;
            subColor = 0xAAFFA726;
            headerText = "[ TARGET LOCK ]";
            subText = targetEntity != null ? targetEntity.getDisplayName().getString().toUpperCase() : "TARGET";
        } else if (isSafeLedge) {
            r = 0.0f; g = 0.92f; b = 1.0f; a = 0.95f; // Cyan Neon
            textColor = 0xFF00E5FF;
            subColor = 0xAA00E5FF;
            headerText = "[ SAFE LEDGE ]";
            subText = "FCS // ANCHOR READY";
        } else {
            r = 0.10f; g = 0.78f; b = 0.95f; a = 0.85f; // Tech Teal (Wall/Solid)
            textColor = 0xFF29B6F6;
            subColor = 0xAA0288D1;
            headerText = "[ ANCHOR POINT ]";
            subText = "SURFACE // SOLID";
        }

        float boxRadius = isEntity ? 0.70f : (isSafeLedge ? 0.62f : 0.56f);
        float armLen    = isEntity ? 0.30f : 0.24f;
        float thick     = isEntity ? 0.045f : 0.032f;

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

        // Đỉnh tam giác chỉ gờ an toàn (Safe Ledge Notch)
        if (isSafeLedge) {
            float notchW = 0.12f;
            float notchH = 0.09f;
            float notchY = -boxRadius - 0.04f;
            drawLine(bb, mat, -notchW, notchY, 0f, notchY - notchH, thick, r, g, b, a);
            drawLine(bb, mat, 0f, notchY - notchH, notchW, notchY, thick, r, g, b, a);

            // Kim cương định vị trung tâm cho gờ an toàn
            float dSize = 0.18f;
            drawLine(bb, mat, 0, -dSize, dSize, 0, 0.022f, r, g, b, 0.80f);
            drawLine(bb, mat, dSize, 0, 0, dSize, 0.022f, r, g, b, 0.80f);
            drawLine(bb, mat, 0, dSize, -dSize, 0, 0.022f, r, g, b, 0.80f);
            drawLine(bb, mat, -dSize, 0, 0, -dSize, 0.022f, r, g, b, 0.80f);
        }

        // Kim cương xoay cơ học khi dây móc đã neo chặt (ENGAGED)
        if (isAttached) {
            float rotRad = (float) Math.toRadians((System.currentTimeMillis() / 20.0) % 360.0);
            float dSize = 0.38f;
            float cos = (float) Math.cos(rotRad) * dSize;
            float sin = (float) Math.sin(rotRad) * dSize;

            drawLine(bb, mat, cos, sin, -sin, cos, 0.028f, r, g, b, 0.85f);
            drawLine(bb, mat, -sin, cos, -cos, -sin, 0.028f, r, g, b, 0.85f);
            drawLine(bb, mat, -cos, -sin, sin, -cos, 0.028f, r, g, b, 0.85f);
            drawLine(bb, mat, sin, -cos, cos, sin, 0.028f, r, g, b, 0.85f);
        }

        // Tâm crosshair nhỏ
        float cSize = isEntity ? 0.08f : 0.05f;
        float cThick = 0.02f;
        drawRect(bb, mat, -cSize, -cThick, cSize, cThick, r, g, b, a);
        drawRect(bb, mat, -cThick, -cSize, cThick, cSize, r, g, b, a);

        BufferUploader.drawWithShader(bb.buildOrThrow());

        // ── Render Văn bản thông số FCS (Nhãn, Cự ly, Trạng thái) ───────────
        Font font = mc.font;
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        pose.pushPose();
        pose.scale(0.015f, -0.015f, 0.015f);

        String distStr = String.format("%.0fm", dist);

        // Nhãn tiêu đề phía trên
        font.drawInBatch(headerText, -font.width(headerText) / 2.0f, -60, textColor,
                false, pose.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 15728880);

        // Cự ly mét phía dưới
        font.drawInBatch(distStr, -font.width(distStr) / 2.0f, 50, textColor,
                false, pose.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 15728880);

        // Trạng thái phụ
        font.drawInBatch(subText, -font.width(subText) / 2.0f, 63, subColor,
                false, pose.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 15728880);

        bufferSource.endBatch();
        pose.popPose();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        pose.popPose();
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
