package net.huwng.highv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.huwng.highv.HighV;
import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.item.GrapplingHookItem;
import net.huwng.highv.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;

/**
 * Animation tay trái (offhand) cầm Grappling Hook ở góc nhìn thứ nhất.
 *
 * State machine (mỗi tick, chỉ áp dụng khi offhand đang cầm grappling hook):
 *
 *  HIDDEN   — offhand không cầm hook, hoặc không có hook nào đang bay/bám
 *             của player này → ẩn hoàn toàn tay + item (cancel RenderHandEvent).
 *  EXTEND   — hook đang bay (tồn tại, chưa attached) → tay+item hiện ra, nội
 *             suy (ease-out) từ ẩn tới pose EXTEND (lấy từ Blockbench) trong
 *             EXTEND_DURATION tick.
 *  JERK     — đúng lúc hook.isAttached() chuyển false→true → giật 1 lần:
 *             nội suy từ pose EXTEND sang pose SETTLE (cũng lấy từ
 *             Blockbench), có overshoot rồi ổn định (ease-out-back).
 *  SETTLE   — sau khi giật xong, giữ nguyên pose SETTLE + lắc lư nhẹ liên
 *             tục (sin wave biên độ nhỏ trên rotation Y / translation Y)
 *             cho tới khi hook bị huỷ (discard/retract).
 *
 * Toàn bộ state chỉ tồn tại client-side, không cần đồng bộ network vì chỉ
 * ảnh hưởng tới render first-person của chính người chơi.
 *
 * LƯU Ý KỸ THUẬT:
 *  - RenderHandEvent fires TRƯỚC khi vanilla tự quyết định vẽ gì cho tay đó.
 *    Mình cancel hẳn rồi tự bọc 1 cặp pushPose()/popPose() riêng để áp toàn
 *    bộ pose (rotation X/Y/Z + translation X/Y/Z, lấy từ Blockbench) cho cả
 *    tay và item — xem mục CANCEL TOÀN BỘ phía dưới.
 *  - Dấu (+/-) và đơn vị translation lấy nguyên từ panel Blockbench (pixel,
 *    được chia /16 khi áp dụng). Nếu trong game thấy lệch hướng, khả năng
 *    cao là do offhand đang được game tính là tay phải/trái khác với lúc
 *    bạn dựng trong Blockbench (xem getMainArm().getOpposite() bên dưới) —
 *    chứ không phải do code đổi dấu sai.
 *  - VANILLA MẶC ĐỊNH KHÔNG VẼ TAY KHI ĐANG CẦM ITEM: ItemInHandRenderer
 *    chỉ gọi renderPlayerArm(...) khi stack rỗng. Để hiện tay thật (mesh da
 *    người chơi) cùng với grappling hook, mình gọi LẠI chính method đó bằng
 *    tay — method này là private nên cần mở quyền qua Access Transformer
 *    (xem src/main/resources/META-INF/accesstransformer.cfg, đã đăng ký
 *    trong build.gradle). Đây cũng chính là kỹ thuật mod Punchy dùng (qua
 *    Mixin @Invoker) để hiện tay khi cầm item — chỉ khác là mình dùng AT
 *    thay vì thêm cả hệ Mixin vào project cho 1 method duy nhất.
 *  - CANCEL TOÀN BỘ + TỰ VẼ LẠI CẢ TAY VÀ ITEM: bản trước chỉ translate
 *    rồi để vanilla "tiếp tục" vẽ item sau khi mình gọi renderPlayerArm —
 *    nhưng renderPlayerArm tự push/pop PoseStack nội bộ, nên rất dễ làm
 *    item-render phía sau (do vanilla tự gọi) bị lệch hoặc không hiện. Giờ
 *    mình cancel hẳn, tự bọc 1 push/pop của riêng mình, rồi gọi LẦN LƯỢT cả
 *    renderPlayerArm và renderItem (cũng đã public sẵn trong vanilla) bên
 *    trong cùng 1 cặp push/pop đó — đảm bảo tay và item luôn cùng transform,
 *    không phụ thuộc vào việc vanilla "tiếp tục" làm gì sau event.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public class GrapplingArmAnimator {

    // ── Pose lấy từ Blockbench (Display Transform: rotation độ, translation
    //    theo pixel — sẽ tự chia /16 khi áp dụng, đúng convention vanilla
    //    ItemTransform). Thứ tự rotation áp dụng: X rồi Y rồi Z.
    // EXTEND — lúc đang bay (hook tồn tại, chưa attached)
    //lên xuống        //trái phải            //xoay qua lại
    private static final float EXTEND_ROT_X = -17f, EXTEND_ROT_Y = -2f,  EXTEND_ROT_Z = -25f;
    //trái phải       //lên xuống            //trước sau
    private static final float EXTEND_POS_X = 5f,  EXTEND_POS_Y = 4f,   EXTEND_POS_Z = -3f;

    // SETTLE — sau khi hook bám điểm, giữ nguyên tới khi hook bị huỷ
    private static final float SETTLE_ROT_X = -50f, SETTLE_ROT_Y = -20f, SETTLE_ROT_Z = -90f;
    private static final float SETTLE_POS_X = -3f,  SETTLE_POS_Y = -2f, SETTLE_POS_Z = 3f;

    private static final int   EXTEND_DURATION = 5; // ticks chuyển từ ẩn → pose EXTEND
    private static final int   JERK_DURATION   = 7; // ticks chuyển từ EXTEND → SETTLE (có overshoot)

    private static final float SWAY_ROT_AMPLITUDE = 3f;    // lắc lư nhẹ trên rotation Y (độ)
    private static final float SWAY_POS_AMPLITUDE = 0.3f;  // lắc lư nhẹ trên translation Y (pixel)
    private static final float SWAY_SPEED         = 0.07f; // tốc độ lắc lư (rad/tick)

    // ── State (client-only, 1 local player) ────────────────────────────────
    private static boolean active      = false;
    private static int     extendTicks = 0;
    private static boolean jerking     = false;
    private static int     jerkTick    = 0;
    private static boolean wasAttached = false;
    private static float   swayPhase   = 0f;

    // =========================================================================
    //  TICK — cập nhật state machine
    // =========================================================================
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc     = Minecraft.getInstance();
        Player    player = mc.player;

        if (player == null || mc.level == null
                || !player.getOffhandItem().is(ModItems.GRAPPLING_HOOK.get())) {
            reset();
            return;
        }

        GrapplingHookEntity hook = GrapplingHookItem.findActiveHook(mc.level, player);
        if (hook == null) {
            // Không còn hook nào đang bay/bám của player này → ẩn tay+item.
            reset();
            return;
        }

        active = true;

        boolean attached = hook.isAttached();

        if (attached && !wasAttached) {
            // Hook vừa bám điểm → trigger giật 1 lần
            jerking  = true;
            jerkTick = 0;
        }
        wasAttached = attached;

        if (!attached) {
            extendTicks = Math.min(extendTicks + 1, EXTEND_DURATION);
        } else if (jerking) {
            jerkTick++;
            if (jerkTick >= JERK_DURATION) jerking = false;
        } else {
            swayPhase += SWAY_SPEED;
        }
    }

    private static void reset() {
        active      = false;
        extendTicks = 0;
        jerking     = false;
        jerkTick    = 0;
        wasAttached = false;
        swayPhase   = 0f;
    }

    // =========================================================================
    //  RENDER — ẩn tay hoặc áp transform animation
    // =========================================================================
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.OFF_HAND) return;
        if (!event.getItemStack().is(ModItems.GRAPPLING_HOOK.get())) return;

        // Tự kiểm soát hoàn toàn việc vẽ — xem LƯU Ý KỸ THUẬT ở đầu file.
        event.setCanceled(true);

        if (!active) return;

        Minecraft mc     = Minecraft.getInstance();
        Player    player = mc.player;
        if (player == null) return;

        ItemInHandRenderer renderer = mc.getEntityRenderDispatcher().getItemInHandRenderer();
        if (renderer == null) return;

        float              partial = event.getPartialTick();
        PoseStack          pose    = event.getPoseStack();
        MultiBufferSource  buffer  = event.getMultiBufferSource();
        int                light   = event.getPackedLight();
        ItemStack          stack   = event.getItemStack();

        float rotX, rotY, rotZ, posX, posY, posZ;

        if (jerking) {
            // Giật 1 lần: nội suy từ pose EXTEND sang pose SETTLE, có overshoot
            // rồi ổn định (ease-out-back) — đúng cảm giác "giật rồi lắc nhẹ".
            float t     = Math.min(1f, (jerkTick + partial) / JERK_DURATION);
            float eased = easeOutBack(t);
            rotX = lerp(EXTEND_ROT_X, SETTLE_ROT_X, eased);
            rotY = lerp(EXTEND_ROT_Y, SETTLE_ROT_Y, eased);
            rotZ = lerp(EXTEND_ROT_Z, SETTLE_ROT_Z, eased);
            posX = lerp(EXTEND_POS_X, SETTLE_POS_X, eased);
            posY = lerp(EXTEND_POS_Y, SETTLE_POS_Y, eased);
            posZ = lerp(EXTEND_POS_Z, SETTLE_POS_Z, eased);
        } else if (wasAttached) {
            // Đã giật xong, giữ pose SETTLE + lắc lư nhẹ liên tục cho tới khi
            // hook mất hoặc thả RMB.
            float swayRot = (float) Math.sin(swayPhase) * SWAY_ROT_AMPLITUDE;
            float swayPos = (float) Math.cos(swayPhase * 0.7f) * SWAY_POS_AMPLITUDE;
            rotX = SETTLE_ROT_X;
            rotY = SETTLE_ROT_Y + swayRot;
            rotZ = SETTLE_ROT_Z;
            posX = SETTLE_POS_X;
            posY = SETTLE_POS_Y + swayPos;
            posZ = SETTLE_POS_Z;
        } else {
            // Vừa bấm RMB, hook chưa bám → từ ẩn (0) nội suy ra pose EXTEND.
            float t     = Math.min(1f, (extendTicks + partial) / EXTEND_DURATION);
            float eased = easeOutCubic(t);
            rotX = EXTEND_ROT_X * eased;
            rotY = EXTEND_ROT_Y * eased;
            rotZ = EXTEND_ROT_Z * eased;
            posX = EXTEND_POS_X * eased;
            posY = EXTEND_POS_Y * eased;
            posZ = EXTEND_POS_Z * eased;
        }

        pose.pushPose();
        // Translation tính theo pixel (giống Blockbench/JSON display transform)
        // nên chia /16 để ra đơn vị block mà PoseStack dùng.
        pose.translate(posX / 16f, posY / 16f, posZ / 16f);
        // Thứ tự X → Y → Z, khớp với cách vanilla ItemTransform#apply áp dụng.
        pose.mulPose(Axis.XP.rotationDegrees(rotX));
        pose.mulPose(Axis.YP.rotationDegrees(rotY));
        pose.mulPose(Axis.ZP.rotationDegrees(rotZ));

        // Vẽ tay thật (mesh da người chơi) — vanilla bỏ qua bước này khi tay
        // không trống, nên gọi lại bằng tay qua method đã mở public bởi AT.
        HumanoidArm arm = player.getMainArm().getOpposite(); // offhand luôn đối diện main arm
        renderer.renderPlayerArm(pose, buffer, light, event.getEquipProgress(), event.getSwingProgress(), arm);

        // Vẽ item ngay sau, trên CÙNG pose (đã xoay+dịch ở trên) để luôn khớp
        // vị trí với tay. renderItem vốn đã public trong vanilla, không cần AT.
        boolean            leftHanded = arm == HumanoidArm.LEFT;
        ItemDisplayContext context    = leftHanded
                ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        renderer.renderItem(player, stack, context, leftHanded, pose, buffer, light);

        pose.popPose();
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float easeOutCubic(float t) { return 1f - (float) Math.pow(1f - t, 3); }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
    }
}