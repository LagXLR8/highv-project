package net.huwng.highv.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.huwng.highv.HighV;
import net.huwng.highv.client.HighVClientConfig;
import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.event.ClientInputHandler;
import net.huwng.highv.item.GrapplingHookItem;
import net.huwng.highv.item.ModItems;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Tâm ngắm Dây Móc (Grappling Hook):
 * - Thiết kế tối giản: 1 ô vuông xoay nhẹ rõ ràng ở vị trí điểm neo / mục tiêu.
 * - Tuyệt đối không bị culling, không phụ thuộc tick timing.
 * - Luôn hiển thị rõ ràng trên mọi địa hình.
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
        if (player == null || mc.level == null || mc.options.hideGui) return;

        boolean holdingHook = player.getOffhandItem().is(ModItems.GRAPPLING_HOOK.get())
                || player.getMainHandItem().is(ModItems.GRAPPLING_HOOK.get());
        if (!holdingHook) return;

        // ── Xác định tọa độ mục tiêu ──────────────────────────────────────────
        GrapplingHookEntity hook = GrapplingHookItem.findActiveHook(mc.level, player);
        Vec3 worldTarget = null;
        boolean isAttached = false;
        boolean isEntity = false;

        if (hook != null) {
            if (hook.isAttached()) {
                worldTarget = hook.getAttachPoint();
                if (worldTarget == null) worldTarget = hook.getClientTarget();
                if (worldTarget == null) worldTarget = hook.position();
                isAttached = true;
            } else {
                Vec3 ct = hook.getClientTarget();
                worldTarget = ct != null ? ct : hook.position();
            }
            int hookedId = hook.getHookedEntityId();
            if (hookedId >= 0) {
                Entity targetEntity = mc.level.getEntity(hookedId);
                isEntity = (targetEntity != null);
            }
        } else {
            ClientInputHandler.TargetResult target = ClientInputHandler.lastTarget;
            if (target != null) {
                worldTarget = target.position();
                isEntity = target.isEntity();
            } else {
                // Fallback tức thì theo tia nhìn thẳng nếu lastTarget chưa kịp cập nhật từ client tick
                Vec3 eye = player.getEyePosition();
                Vec3 look = player.getLookAngle();
                BlockHitResult hit = mc.level.clip(new ClipContext(
                        eye, eye.add(look.scale(GrapplingHookItem.MAX_RANGE)),
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    BlockState state = mc.level.getBlockState(hit.getBlockPos());
                    if (!state.isAir() && (state.isSolid() || !state.getCollisionShape(mc.level, hit.getBlockPos()).isEmpty())) {
                        worldTarget = hit.getLocation();
                    }
                }
            }
        }

        if (worldTarget == null) return;

        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();
        double camDist = camPos.distanceTo(worldTarget);
        if (camDist < 0.2) return;

        // ── Render 3D Billboard Ô Vuông ──────────────────────────────────────
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(worldTarget.x - camPos.x, worldTarget.y - camPos.y, worldTarget.z - camPos.z);
        pose.mulPose(new Quaternionf(cam.rotation()));

        // Xoay nhẹ nhàng quanh trục Z
        float rotAngle = (float) ((System.currentTimeMillis() / 35.0) % 360.0);
        pose.mulPose(Axis.ZP.rotationDegrees(rotAngle));

        // Scale giữ kích thước rõ nét trên màn hình theo cự ly
        float scale = (float) Math.max(0.42f, camDist * 0.044f);
        pose.scale(scale, scale, scale);

        Matrix4f mat = pose.last().pose();

        // Cấu hình RenderSystem chuẩn xác: tắt culling và depth mask để không bao giờ bị biến mất
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Màu sắc nổi bật, rõ ràng
        float r, g, b, a;
        if (isAttached) {
            r = 0.95f; g = 1.0f; b = 1.0f; a = 1.0f; // Electric White khi đã neo
        } else if (isEntity) {
            r = 1.0f; g = 0.55f; b = 0.0f; a = 1.0f; // Amber Neon khi ngắm thực thể
        } else {
            r = 0.0f; g = 0.95f; b = 1.0f; a = 1.0f; // Cyan Neon khi ngắm điểm neo / gờ an toàn
        }

        float s = isEntity ? 0.52f : 0.46f;
        float thick = 0.055f;

        // Vẽ 4 cạnh viền ô vuông (Counter-Clockwise CCW winding)
        // Trên
        drawRect(bb, mat, -s, -s, s, -s + thick, r, g, b, a);
        // Dưới
        drawRect(bb, mat, -s, s - thick, s, s, r, g, b, a);
        // Trái
        drawRect(bb, mat, -s, -s + thick, -s + thick, s - thick, r, g, b, a);
        // Phải
        drawRect(bb, mat, s - thick, -s + thick, s, s - thick, r, g, b, a);

        // Lớp nền mờ bên trong ô vuông để luôn nổi bật trên mọi bề mặt
        drawRect(bb, mat, -s + thick, -s + thick, s - thick, s - thick, r, g, b, 0.20f);

        // Chấm định vị tâm nhỏ ở chính giữa
        float dot = 0.050f;
        drawRect(bb, mat, -dot, -dot, dot, dot, r, g, b, a);

        BufferUploader.drawWithShader(bb.buildOrThrow());

        // Phục hồi trạng thái RenderSystem
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /**
     * Vẽ hình chữ nhật với thứ tự đỉnh CCW (Counter-Clockwise) chuẩn OpenGL:
     * (x1, y1) -> (x2, y1) -> (x2, y2) -> (x1, y2)
     */
    private static void drawRect(BufferBuilder bb, Matrix4f mat, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        bb.addVertex(mat, x1, y1, 0).setColor(r, g, b, a);
        bb.addVertex(mat, x2, y1, 0).setColor(r, g, b, a);
        bb.addVertex(mat, x2, y2, 0).setColor(r, g, b, a);
        bb.addVertex(mat, x1, y2, 0).setColor(r, g, b, a);
    }
}
