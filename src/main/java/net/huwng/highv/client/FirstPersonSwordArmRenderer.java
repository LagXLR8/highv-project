package net.huwng.highv.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.huwng.highv.HighV;
import net.huwng.highv.item.ModItems;
import net.huwng.highv.item.ThermalKatanaItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;

/**
 * Procedural First-Person Sword Arm Renderer:
 * Bám chặt 100% theo khối cube đánh dấu chuôi kiếm trong
 * template_longsword.json.
 *
 * KHÔNG sử dụng renderPlayerArm của vanilla vì phương thức đó chứa các góc xoay
 * đấm punch
 * cố định (ZP 120°, XP 200°, YP -135°) làm đảo lộn hệ trục tọa độ.
 * Thay vào đó, hệ thống render trực tiếp model.rightArm & model.rightSleeve
 * trên hệ trục
 * Cartesian chuẩn, khóa tâm bàn tay vào đúng khối cube marker và giữ scale
 * chuẩn 1.0.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public class FirstPersonSwordArmRenderer {

    // =========================================================================
    // 1. VỊ TRÍ BÀN TAY (HAND POSITION):
    // Có thể chỉnh trực tiếp ở đây HOẶC sửa "translation" trong
    // template_longsword.json
    // - handPosX: Dịch sang PHẢI (+) hoặc sang TRÁI (-)
    // - handPosY: Dịch LÊN TRÊN (+) hoặc XUỐNG DƯỚI (-)
    // - handPosZ: Dịch LÙI VỀ PHÍA NGƯỜI CHƠI (+) hoặc ĐẨY RA XA (-)
    // =========================================================================
    public static float handPosX = 6f;
    public static float handPosY = 1.4f;
    public static float handPosZ = -10f;

    // Dịch bù vị trí bàn tay (vi chỉnh nhanh trong code, không bao giờ bị file JSON
    // ghi đè):
    public static float handOffsetX = 0.0f;
    public static float handOffsetY = 0.0f;
    public static float handOffsetZ = 2.0f;

    // =========================================================================
    // 2. XOAY CỔ TAY (WRIST ROTATION - ĐƠN VỊ: ĐỘ):
    // - wristRotZ: Xoay ngang cẳng tay / cổ tay
    // - wristRotY: Xoay vặn trục cẳng tay (úp/ngửa lòng bàn tay)
    // - wristRotX: Gật cổ tay lên (+) hoặc gập cổ tay xuống (-)
    // =========================================================================
    public static float wristRotZ = 80.0f;
    public static float wristRotY = 180.0f;
    public static float wristRotX = -80.0f;

    // Các thông số góc xoay và scale thanh kiếm (tự động đọc từ
    // Các thông số góc xoay và scale thanh kiếm (tự động đọc từ template_longsword.json)
    public static float katanaRotX = 0f;
    public static float katanaRotY = 90f;
    public static float katanaRotZ = 0f;

    public static float katanaScaleX = 1.36f;
    public static float katanaScaleY = 1.36f;
    public static float katanaScaleZ = 0.6f;

    // Tọa độ tâm khối cube marker (tự động đọc từ elements[0] trong JSON)
    public static float cubeMarkerX = 5.0f;
    public static float cubeMarkerY = 0f;
    public static float cubeMarkerZ = 10f;

    public static float cubeRotAngle = 22.5f;
    public static String cubeRotAxis = "z";

    private static long lastReadTime = 0;
    public static long lastFileModified = 0;

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND)
            return;

        // Kiểm tra cấu hình client
        if (!HighVClientConfig.ENABLE_FIRST_PERSON_SWORD_ARM.get())
            return;

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty())
            return;

        boolean isKatana = stack.is(ModItems.THERMAL_KATANA.get()) || stack.getItem() instanceof ThermalKatanaItem;
        if (!isKatana)
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui)
            return;
        if (!mc.options.getCameraType().isFirstPerson())
            return;
        if (!(mc.player instanceof AbstractClientPlayer clientPlayer))
            return;
        if (clientPlayer.isInvisible())
            return;

        PlayerRenderer playerRenderer = (PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(clientPlayer);
        if (playerRenderer == null)
            return;

        PlayerModel<AbstractClientPlayer> model = playerRenderer.getModel();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        ResourceLocation skinTexture = clientPlayer.getSkin().texture();
        int light = event.getPackedLight();

        float swingProgress = event.getSwingProgress();
        float equipProgress = event.getEquipProgress();

        PoseStack pose = event.getPoseStack();
        pose.pushPose();

        // ── 1. Động học viewmodel chuẩn vanilla (bobbing, equip, attack swing) ──
        float f5 = -0.4F * Mth.sin(Mth.sqrt(swingProgress) * (float) Math.PI);
        float f6 = 0.2F * Mth.sin(Mth.sqrt(swingProgress) * (float) (Math.PI * 2));
        float f10 = -0.2F * Mth.sin(swingProgress * (float) Math.PI);
        pose.translate(f5, f6, f10);

        pose.translate(0.56F, -0.52F + equipProgress * -0.6F, -0.72F);

        float f11 = Mth.sin(swingProgress * swingProgress * (float) Math.PI);
        pose.mulPose(Axis.YP.rotationDegrees(45.0F + f11 * -20.0F));
        float f12 = Mth.sin(Mth.sqrt(swingProgress) * (float) Math.PI);
        pose.mulPose(Axis.ZP.rotationDegrees(-f12 * 20.0F));
        pose.mulPose(Axis.XP.rotationDegrees(-f12 * 80.0F));
        pose.mulPose(Axis.YP.rotationDegrees(-45.0F));

        // ── 2. Procedural Katana & Arm: Khối Cube trên kiếm đi theo bàn tay ──
        // Hủy render mặc định của vanilla để hệ thống procedural tự quản lý cả tay và kiếm:
        // Bàn tay là gốc (Anchor), khối cube trên kiếm bám chính xác vào lòng bàn tay
        event.setCanceled(true);

        updateCubeMarker(mc);

            // Cập nhật động học procedural (Inertia, Sway, Bước chạy, Nhịp thở, Nhảy & Tiếp đất)
            KatanaMotionDynamicsHandler.update(clientPlayer, event.getPartialTick());

            // Vị trí gốc bàn tay trên màn hình (Hand Anchor)
            pose.translate((handPosX + handOffsetX) / 16.0f, (handPosY + handOffsetY) / 16.0f,
                    (handPosZ + handOffsetZ) / 16.0f);

            // Áp dụng độ lệch chuyển động procedural lên cụm tay & kiếm
            KatanaMotionDynamicsHandler.applyMotion(pose);

            // Xoay cổ tay (úp/ngửa bàn tay, xoay ngang, gật lên xuống)
            if (wristRotZ != 0.0f)
                pose.mulPose(Axis.ZP.rotationDegrees(wristRotZ));
            if (wristRotX != 0.0f)
                pose.mulPose(Axis.XP.rotationDegrees(wristRotX));
            if (wristRotY != 0.0f)
                pose.mulPose(Axis.YP.rotationDegrees(wristRotY));

            // ── 2A. Render Cánh tay người chơi (Gắn vào vị trí bàn tay) ──
            pose.pushPose();
            pose.translate(6.0f / 16.0f, -11.5f / 16.0f, 0.0f);
            pose.scale(1.0f, 1.0f, 1.0f);

            boolean prevArmVis = model.rightArm.visible;
            boolean prevArmSkip = model.rightArm.skipDraw;
            boolean prevSleeveVis = model.rightSleeve.visible;
            boolean prevSleeveSkip = model.rightSleeve.skipDraw;

            model.rightArm.visible = true;
            model.rightArm.skipDraw = false;
            model.rightSleeve.visible = true;
            model.rightSleeve.skipDraw = false;

            float origX = model.rightArm.xRot;
            float origY = model.rightArm.yRot;
            float origZ = model.rightArm.zRot;

            model.rightArm.xRot = 0.0f;
            model.rightArm.yRot = 0.0f;
            model.rightArm.zRot = 0.0f;
            model.rightSleeve.xRot = 0.0f;
            model.rightSleeve.yRot = 0.0f;
            model.rightSleeve.zRot = 0.0f;

            VertexConsumer solidBuffer = bufferSource.getBuffer(RenderType.entitySolid(skinTexture));
            model.rightArm.render(pose, solidBuffer, light, OverlayTexture.NO_OVERLAY);

            VertexConsumer translucentBuffer = bufferSource.getBuffer(RenderType.entityTranslucent(skinTexture));
            model.rightSleeve.render(pose, translucentBuffer, light, OverlayTexture.NO_OVERLAY);

            model.rightArm.xRot = origX;
            model.rightArm.yRot = origY;
            model.rightArm.zRot = origZ;
            model.rightSleeve.xRot = origX;
            model.rightSleeve.yRot = origY;
            model.rightSleeve.zRot = origZ;

            model.rightArm.visible = prevArmVis;
            model.rightArm.skipDraw = prevArmSkip;
            model.rightSleeve.visible = prevSleeveVis;
            model.rightSleeve.skipDraw = prevSleeveSkip;
            pose.popPose();

            // ── 2B. Render Thanh Kiếm (Khối cube trên kiếm bám chính xác vào lòng bàn tay)
            // ──
            pose.pushPose();
            // Góc xoay thanh kiếm khi cầm trong tay (từ template_longsword.json)
            pose.mulPose(Axis.XP.rotationDegrees(katanaRotX));
            pose.mulPose(Axis.YP.rotationDegrees(katanaRotY));
            pose.mulPose(Axis.ZP.rotationDegrees(katanaRotZ));

            // Góc xoay của khối cube marker (nếu có khai báo trong JSON)
            if (cubeRotAngle != 0.0f) {
                switch (cubeRotAxis) {
                    case "x" -> pose.mulPose(Axis.XP.rotationDegrees(-cubeRotAngle));
                    case "y" -> pose.mulPose(Axis.YP.rotationDegrees(-cubeRotAngle));
                    case "z" -> pose.mulPose(Axis.ZP.rotationDegrees(-cubeRotAngle));
                }
            }

            // Tỉ lệ scale thanh kiếm (từ template_longsword.json)
            pose.scale(katanaScaleX, katanaScaleY, katanaScaleZ);

            // Dịch ngược tọa độ khối cube marker: khối cube trên kiếm bám chặt vào tâm bàn
            // tay (0, 0, 0)
            float elemX = (cubeMarkerX - 8.0f) / 16.0f;
            float elemY = (cubeMarkerY - 8.0f) / 16.0f;
            float elemZ = (cubeMarkerZ - 8.0f) / 16.0f;
            pose.translate(-elemX, -elemY, -elemZ);

            // Render model thanh kiếm
            mc.getItemRenderer().renderStatic(
                    clientPlayer,
                    stack,
                    ItemDisplayContext.NONE,
                    false,
                    pose,
                    bufferSource,
                    clientPlayer.level(),
                    light,
                    OverlayTexture.NO_OVERLAY,
                    0);
            pose.popPose();

            pose.popPose();
    }

    /**
     * Đọc tọa độ khối cube marker và cấu hình display từ template_longsword.json
     * (tự động cập nhật mỗi khi lưu file).
     */
    private static void updateCubeMarker(Minecraft mc) {
        if (KatanaArmLiveTuner.tunerActive)
            return;

        long now = System.currentTimeMillis();
        if (now - lastReadTime < 800)
            return;
        lastReadTime = now;

        File directFile = new File("src/main/resources/assets/highv/models/item/template_longsword.json");
        if (!directFile.exists()) {
            directFile = new File("../src/main/resources/assets/highv/models/item/template_longsword.json");
        }
        if (!directFile.exists()) {
            directFile = new File("../../src/main/resources/assets/highv/models/item/template_longsword.json");
        }
        if (!directFile.exists()) {
            directFile = new File(
                    "e:/siucap/highv-project/src/main/resources/assets/highv/models/item/template_longsword.json");
        }

        if (directFile.exists()) {
            long modified = directFile.lastModified();
            if (modified != lastFileModified) {
                // Nếu lần đầu đọc (khởi động game) và đã nạp dữ liệu từ highv_arm_tuning.json, giữ nguyên thông số user đã chỉnh
                if (lastFileModified == 0 && KatanaArmLiveTuner.hasLoadedFromConfig) {
                    lastFileModified = modified;
                    return;
                }
                lastFileModified = modified;
                try (Reader reader = new FileReader(directFile)) {
                    parseLongswordModel(reader);
                } catch (Exception ignored) {
                }
            }
            return;
        }

        // Fallback qua resource manager nếu không thấy file trực tiếp VÀ chưa nạp từ config
        if (!KatanaArmLiveTuner.hasLoadedFromConfig && lastFileModified == 0) {
            lastFileModified = 1;
            try {
                ResourceLocation loc = HighV.id("models/item/template_longsword.json");
                var res = mc.getResourceManager().getResource(loc);
                if (res.isPresent()) {
                    try (Reader reader = res.get().openAsReader()) {
                        parseLongswordModel(reader);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void parseLongswordModel(Reader reader) {
        try {
            JsonObject root = GsonHelper.parse(reader);

            // Đọc cấu hình display.firstperson_righthand nếu có
            if (root.has("display")) {
                JsonObject display = root.getAsJsonObject("display");
                if (display.has("firstperson_righthand")) {
                    JsonObject fp = display.getAsJsonObject("firstperson_righthand");
                    if (fp.has("translation")) {
                        JsonArray trans = fp.getAsJsonArray("translation");
                        if (trans.size() >= 3) {
                            handPosX = trans.get(0).getAsFloat();
                            handPosY = trans.get(1).getAsFloat();
                            handPosZ = trans.get(2).getAsFloat();
                        }
                    }
                    if (fp.has("rotation")) {
                        JsonArray rot = fp.getAsJsonArray("rotation");
                        if (rot.size() >= 3) {
                            katanaRotX = rot.get(0).getAsFloat();
                            katanaRotY = rot.get(1).getAsFloat();
                            katanaRotZ = rot.get(2).getAsFloat();
                        }
                    }
                    if (fp.has("scale")) {
                        JsonArray sc = fp.getAsJsonArray("scale");
                        if (sc.size() >= 3) {
                            katanaScaleX = sc.get(0).getAsFloat();
                            katanaScaleY = sc.get(1).getAsFloat();
                            katanaScaleZ = sc.get(2).getAsFloat();
                        }
                    }
                }
            }

            // Đọc khối cube marker đầu tiên trong elements (chỉ đọc nếu chưa nạp từ config)
            if (!KatanaArmLiveTuner.hasLoadedFromConfig && root.has("elements")) {
                JsonArray elements = root.getAsJsonArray("elements");
                if (!elements.isEmpty()) {
                    JsonObject elem = elements.get(0).getAsJsonObject();
                    JsonArray from = elem.getAsJsonArray("from");
                    JsonArray to = elem.getAsJsonArray("to");
                    if (from != null && to != null && from.size() >= 3 && to.size() >= 3) {
                        float fx = from.get(0).getAsFloat();
                        float fy = from.get(1).getAsFloat();
                        float fz = from.get(2).getAsFloat();
                        float tx = to.get(0).getAsFloat();
                        float ty = to.get(1).getAsFloat();
                        float tz = to.get(2).getAsFloat();
                        cubeMarkerX = (fx + tx) * 0.5f;
                        cubeMarkerY = (fy + ty) * 0.5f;
                        cubeMarkerZ = (fz + tz) * 0.5f;
                    }
                    if (elem.has("rotation")) {
                        JsonObject rot = elem.getAsJsonObject("rotation");
                        cubeRotAngle = rot.has("angle") ? rot.get("angle").getAsFloat() : 0.0f;
                        cubeRotAxis = rot.has("axis") ? rot.get("axis").getAsString().toLowerCase() : "y";
                    } else {
                        cubeRotAngle = 0.0f;
                    }
                    if (elem.has("wrist_rotation")) {
                        JsonArray wr = elem.getAsJsonArray("wrist_rotation");
                        if (wr.size() >= 3) {
                            wristRotX = wr.get(0).getAsFloat();
                            wristRotY = wr.get(1).getAsFloat();
                            wristRotZ = wr.get(2).getAsFloat();
                        }
                    }
                }
            }

            // Hỗ trợ cấu hình góc xoay cổ tay bổ sung từ file JSON (tự động cập nhật
            // real-time khi lưu file)
            if (root.has("wrist_rotation")) {
                JsonArray wr = root.getAsJsonArray("wrist_rotation");
                if (wr.size() >= 3) {
                    wristRotX = wr.get(0).getAsFloat();
                    wristRotY = wr.get(1).getAsFloat();
                    wristRotZ = wr.get(2).getAsFloat();
                }
            } else if (root.has("arm_rotation")) {
                JsonArray ar = root.getAsJsonArray("arm_rotation");
                if (ar.size() >= 3) {
                    wristRotX = ar.get(0).getAsFloat();
                    wristRotY = ar.get(1).getAsFloat();
                    wristRotZ = ar.get(2).getAsFloat();
                }
            }

            // Hỗ trợ cấu hình vị trí bàn tay trực tiếp từ JSON nếu muốn
            if (root.has("hand_position")) {
                JsonArray hp = root.getAsJsonArray("hand_position");
                if (hp.size() >= 3) {
                    handPosX = hp.get(0).getAsFloat();
                    handPosY = hp.get(1).getAsFloat();
                    handPosZ = hp.get(2).getAsFloat();
                }
            }
            if (root.has("hand_offset")) {
                JsonArray ho = root.getAsJsonArray("hand_offset");
                if (ho.size() >= 3) {
                    handOffsetX = ho.get(0).getAsFloat();
                    handOffsetY = ho.get(1).getAsFloat();
                    handOffsetZ = ho.get(2).getAsFloat();
                }
            }
        } catch (Exception ignored) {
        }
    }
}
