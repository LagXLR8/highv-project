package net.huwng.highv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.huwng.highv.HighV;
import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.item.GrapplingHookItem;
import net.huwng.highv.item.ModItems;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Render cánh tay trái từ Outer 3D Player Model vào góc nhìn thứ nhất (FPP) khi sử dụng Grappling Hook.
 *
 * - Thay vì vẽ một cánh tay giả định/tĩnh trên màn hình HUD, hệ thống render trực tiếp cánh tay thật
 *   (skin thật + 3D sleeve + thiết bị Grappling Hook gauntlet) của outer player model trong 3D world space.
 * - Cánh tay xoay mượt mà theo Procedural IK (tính toán trong PlayerModelMixin) chỉ thẳng về phía hook.
 * - Đầu dây của GrapplingHookRenderer xuất phát tại đúng vị trí cổ tay/nòng móc (handX, handY, handZ)
 *   và bám theo chuyển động thực tế của cánh tay thay vì bị ghim cứng vào một điểm cố định trên camera.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public class GrapplingArmAnimator {

    /**
     * Khi Grappling Hook đang hoạt động, ẩn tay phụ (offhand) mặc định của vanilla
     * để nhường quyền hiển thị cho cánh tay 3D ngoài thế giới.
     */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.OFF_HAND) return;
        if (!event.getItemStack().is(ModItems.GRAPPLING_HOOK.get())) return;

        // Luôn ẩn item trên tay phụ khi cầm Grappling Hook ở góc nhìn thứ nhất:
        // - Khi không sử dụng: màn hình thông thoáng, không hiện item che khuất
        // - Khi sử dụng: onRenderLevel sẽ tự vẽ cánh tay 3D + găng súng nhắm chuẩn xác về phía hook
        event.setCanceled(true);
    }

    /**
     * Render cánh tay trái và mô hình Grappling Hook của người chơi ở góc nhìn thứ nhất
     * trực tiếp trong 3D world space (Stage.AFTER_ENTITIES).
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;
        if (!mc.options.getCameraType().isFirstPerson()) return;

        GrapplingHookEntity hook = GrapplingHookItem.findActiveHook(mc.level, player);
        if (hook == null || hook.isRemoved()) return;

        ItemStack offhandStack = player.getOffhandItem();
        if (!offhandStack.is(ModItems.GRAPPLING_HOOK.get())) return;

        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        PlayerRenderer playerRenderer = (PlayerRenderer) dispatcher.getRenderer(clientPlayer);
        if (playerRenderer == null) return;

        PlayerModel<AbstractClientPlayer> model = playerRenderer.getModel();

        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();

        double px = Mth.lerp(pt, player.xo != 0 ? player.xo : player.getX(), player.getX());
        double py = Mth.lerp(pt, player.yo != 0 ? player.yo : player.getY(), player.getY());
        double pz = Mth.lerp(pt, player.zo != 0 ? player.zo : player.getZ(), player.getZ());

        // Trong góc nhìn thứ nhất, hướng thân (bodyYaw) đồng bộ chính xác theo hướng nhìn của camera
        float bodyYaw = player.getViewYRot(pt);

        // Đẩy cánh tay lên trên và về phía trước một khoảng tự nhiên, không bị sát mặt hay quá thấp
        double fwdDist = 0.22;
        double upOffset = 0.10;
        double bodyYawRad = Math.toRadians(bodyYaw);
        double fwdX = -Math.sin(bodyYawRad) * fwdDist;
        double fwdZ =  Math.cos(bodyYawRad) * fwdDist;

        // Cập nhật Procedural IK trên player model
        model.setupAnim(clientPlayer, 0.0f, 0.0f, clientPlayer.tickCount + pt, 0.0f, player.getViewXRot(pt));

        PoseStack pose = event.getPoseStack();
        pose.pushPose();

        // Biến đổi tọa độ vào hệ quy chiếu của người chơi (chuẩn LivingEntityRenderer)
        pose.translate(px + fwdX - camPos.x, py + upOffset - camPos.y, pz + fwdZ - camPos.z);
        pose.mulPose(Axis.YP.rotationDegrees(180.0f - bodyYaw));
        pose.scale(-0.9375f, -0.9375f, 0.9375f);
        pose.translate(0.0f, -1.501f, 0.0f);

        // Thu nhỏ cánh tay cho thon gọn, cân đối với tay phải ở góc nhìn thứ nhất (scale 0.82 quanh khớp vai)
        pose.translate(5.0f / 16.0f, 2.0f / 16.0f, 0.0f);
        pose.scale(0.82f, 0.82f, 0.82f);
        pose.translate(-5.0f / 16.0f, -2.0f / 16.0f, 0.0f);

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        ResourceLocation skinTexture = clientPlayer.getSkin().texture();
        int light = dispatcher.getPackedLightCoords(player, pt);

        // Lưu lại cờ hiển thị ban đầu (tránh bị Better Combat ẩn tay phụ khi chém vũ khí 2 tay như Katana)
        boolean prevArmVisible = model.leftArm.visible;
        boolean prevArmSkipDraw = model.leftArm.skipDraw;
        boolean prevSleeveVisible = model.leftSleeve.visible;
        boolean prevSleeveSkipDraw = model.leftSleeve.skipDraw;

        model.leftArm.visible = true;
        model.leftArm.skipDraw = false;
        model.leftSleeve.visible = true;
        model.leftSleeve.skipDraw = false;

        // 1. Render cánh tay trái thật (layer chính của skin người chơi)
        model.leftArm.render(pose, buffer.getBuffer(RenderType.entitySolid(skinTexture)), light, OverlayTexture.NO_OVERLAY);
        // 2. Render lớp áo ngoài (layer 3D sleeve của skin người chơi)
        model.leftSleeve.render(pose, buffer.getBuffer(RenderType.entityTranslucent(skinTexture)), light, OverlayTexture.NO_OVERLAY);

        // 3. Render thiết bị móc kéo (Grappling Hook gauntlet) trên cổ tay người chơi
        pose.pushPose();
        model.translateToHand(HumanoidArm.LEFT, pose);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0f));
        pose.mulPose(Axis.YP.rotationDegrees(180.0f));
        pose.translate(-1.0f / 16.0f, 0.125f, -0.625f);

        dispatcher.getItemInHandRenderer().renderItem(
                player,
                offhandStack,
                ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                true,
                pose,
                buffer,
                light
        );
        pose.popPose();

        buffer.endBatch();
        pose.popPose();

        // Khôi phục lại trạng thái ban đầu của model
        model.leftArm.visible = prevArmVisible;
        model.leftArm.skipDraw = prevArmSkipDraw;
        model.leftSleeve.visible = prevSleeveVisible;
        model.leftSleeve.skipDraw = prevSleeveSkipDraw;
    }
}