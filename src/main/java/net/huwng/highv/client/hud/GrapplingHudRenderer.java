package net.huwng.highv.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.huwng.highv.HighV;
import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.event.ClientInputHandler;
import net.huwng.highv.item.GrapplingHookItem;
import net.huwng.highv.item.ModItems;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Billboard crosshair 3D — luôn overlay lên terrain (no depth test).
 *
 * ── Màu crosshair ──
 *  Xanh lam (0.0, 1.0, 0.9) — aim vào block có thể hook
 *  Đỏ      (1.0, 0.2, 0.2) — aim vào entity hookable
 *  Trắng   (1.0, 1.0, 1.0) — hook đang attached (block hoặc entity)
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public class GrapplingHudRenderer {

    private static final ResourceLocation CROSSHAIR_TEX =
            HighV.id("textures/entity/crosshair.png");

    private static final float SIZE = 0.40f;

    // Màu theo loại target
    private static final float[] COLOR_BLOCK  = {0.0f, 1.0f, 0.9f, 0.85f}; // xanh lam
    private static final float[] COLOR_ENTITY = {1.0f, 0.2f, 0.2f, 0.90f}; // đỏ
    private static final float[] COLOR_ATTACH = {1.0f, 1.0f, 1.0f, 0.70f}; // trắng khi attached

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft   mc     = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (!player.getOffhandItem().is(ModItems.GRAPPLING_HOOK.get())) return;

        // ── Xác định target position và màu ─────────────────────────────────
        Vec3    worldTarget;
        float[] color;

        GrapplingHookEntity hook = GrapplingHookItem.findActiveHook(mc.level, player);
        if (hook != null) {
            // Hook đang hoạt động → crosshair ở vị trí hook
            if (hook.isAttached()) {
                worldTarget = hook.getAttachPoint();
            } else {
                Vec3 ct = hook.getClientTarget();
                worldTarget = ct != null ? ct : hook.position();
            }
            // Màu trắng khi hook đang attached, xanh/đỏ khi đang bay
            if (hook.isAttached()) {
                color = COLOR_ATTACH;
            } else {
                // Đang bay — dùng màu theo loại target ban đầu
                color = (hook.getHookedEntityId() >= 0) ? COLOR_ENTITY : COLOR_BLOCK;
            }
        } else {
            // Hook chưa bắn → dùng lastTarget từ ClientInputHandler
            ClientInputHandler.TargetResult target = ClientInputHandler.lastTarget;
            if (target == null) return;
            worldTarget = target.position();
            color       = target.isEntity() ? COLOR_ENTITY : COLOR_BLOCK;
        }

        if (worldTarget == null) return;

        // ── Render billboard ─────────────────────────────────────────────────
        Camera cam    = mc.gameRenderer.getMainCamera();
        Vec3   camPos = cam.getPosition();

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(
                worldTarget.x - camPos.x,
                worldTarget.y - camPos.y,
                worldTarget.z - camPos.z
        );
        pose.mulPose(new Quaternionf(cam.rotation()));

        Matrix4f mat = pose.last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, CROSSHAIR_TEX);
        RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        float h = SIZE * 0.5f;
        bb.addVertex(mat, -h, -h, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255);
        bb.addVertex(mat,  h, -h, 0f).setUv(1f, 1f).setColor(255, 255, 255, 255);
        bb.addVertex(mat,  h,  h, 0f).setUv(1f, 0f).setColor(255, 255, 255, 255);
        bb.addVertex(mat, -h,  h, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255);

        BufferUploader.drawWithShader(bb.build());

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        pose.popPose();
    }
}
