package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.client.SpeedEffectSystem;
import net.huwng.highv.client.ModKeyMappings;
import net.huwng.highv.client.animation.DashAnimationHandler;
import net.huwng.highv.client.KatanaMotionDynamicsHandler;
import net.huwng.highv.client.hud.PilotHudInertiaHandler;
import net.huwng.highv.enchantment.ModEnchantments;
import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.item.GrapplingHookItem;
import net.huwng.highv.item.ModItems;
import net.huwng.highv.network.packet.BhopInputPacket;
import net.huwng.highv.network.packet.DashRequestPacket;
import net.huwng.highv.network.packet.DriftInputPacket;
import net.huwng.highv.network.packet.GrapplingShootPacket;
import net.huwng.highv.network.packet.GrapplingStatePacket;
import net.huwng.highv.network.packet.RicochetRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public class ClientInputHandler {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final double SCAN_RANGE = GrapplingHookItem.MAX_RANGE;

    /**
     * Góc aim assist tối đa cho entity (half-angle của cone).
     * Entity nằm trong cone này đều được xét, dù crosshair không trỏ vào hitbox.
     * 12° cho cảm giác "magnet" rõ nhưng không quá rộng.
     */
    private static final double ENTITY_ASSIST_ANGLE_DEG = 12.0;
    private static final double ENTITY_ASSIST_DOT =
            Math.cos(Math.toRadians(ENTITY_ASSIST_ANGLE_DEG));

    // Block scan giữ nguyên
    private static final float SCAN_ANGLE = 8f;
    private static final int   SCAN_GRID  = 3;

    // ── State ─────────────────────────────────────────────────────────────────
    private static boolean wasHoldingRMB    = false;
    private static boolean hookShotThisTick = false;

    /** Có đang gửi bhop input packet từ tick trước không — dùng để gửi 1 packet
     *  "tắt" cuối cùng khi player tháo giày Bhopping ra giữa chừng. */
    private static boolean wasSendingBhopInput = false;

    /** Có đang gửi drift input packet từ tick trước không — dùng để gửi 1 packet
     *  "tắt" cuối cùng khi player tháo giày Drift ra giữa chừng. */
    private static boolean wasSendingDriftInput = false;

    /** Trạng thái Shift tick trước — dùng để phát hiện cạnh lên (vừa bấm) cho Dash. */
    private static boolean wasHoldingShift = false;

    /** Trạng thái Space tick trước — dùng để phát hiện cạnh lên (vừa bấm) cho Ricochet. */
    private static boolean wasHoldingJumpForRicochet = false;

    /** Trạng thái Space tick trước — dùng để phát hiện cạnh lên cho Slide-Jump. */
    private static boolean wasHoldingJumpForSlideJump = false;

    public static TargetResult lastTarget = null;

    public record TargetResult(Vec3 position, Entity entity) {
        public boolean isEntity() { return entity != null; }
    }

    // =========================================================================
    //  TICK
    // =========================================================================

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc     = Minecraft.getInstance();
        Player    player = mc.player;

        SpeedEffectSystem.tick();

        if (player == null || mc.level == null) {
            wasHoldingRMB = false; hookShotThisTick = false; lastTarget = null;
            wasSendingBhopInput = false; wasHoldingShift = false; wasHoldingJumpForRicochet = false;
            wasHoldingJumpForSlideJump = false;
            wasSendingDriftInput = false;
            return;
        }

        tickBhopInput(mc, player);
        tickDashInput(mc, player);
        tickDriftInput(mc, player);
        tickRicochetInput(mc, player);

        if (!player.getOffhandItem().is(ModItems.GRAPPLING_HOOK.get())) {
            if (wasHoldingRMB) {
                sendState(false, Vec3.ZERO, Vec3.ZERO);
                wasHoldingRMB = false; hookShotThisTick = false;
            }
            lastTarget = null;
            return;
        }

        boolean             isHoldingRMB = mc.options.keyUse.isDown();
        GrapplingHookEntity hook         = GrapplingHookItem.findActiveHook(mc.level, player);

        if (hook == null) {
            lastTarget = detectTarget(mc, player);
        } else {
            lastTarget = null;
        }

        if (isHoldingRMB && !wasHoldingRMB) hookShotThisTick = false;

        if (isHoldingRMB) {
            if (hook == null && !hookShotThisTick) {
                if (lastTarget != null && lastTarget.isEntity()) {
                    PacketDistributor.sendToServer(
                            GrapplingShootPacket.forEntity(lastTarget.entity().getId()));
                } else if (lastTarget != null) {
                    Vec3 p = lastTarget.position();
                    PacketDistributor.sendToServer(GrapplingShootPacket.forBlock(p.x, p.y, p.z));
                } else {
                    PacketDistributor.sendToServer(GrapplingShootPacket.noTarget());
                }
                hookShotThisTick = true;

            } else if (hook != null) {
                Vec3 look  = player.getLookAngle();
                Vec3 input = buildWasdWorldVec(mc.options, player);
                sendState(true, look, input);
            }
        }

        if (!isHoldingRMB && wasHoldingRMB) {
            sendState(false, Vec3.ZERO, Vec3.ZERO);
            hookShotThisTick = false;
        }

        wasHoldingRMB = isHoldingRMB;
    }

    // =========================================================================
    //  TARGET DETECTION
    // =========================================================================

    private static TargetResult detectTarget(Minecraft mc, Player player) {
        Vec3 eye  = player.getEyePosition();
        Vec3 look = player.getLookAngle();

        // Entity ưu tiên hơn block
        TargetResult entityTarget = detectEntity(mc, player, eye, look);
        if (entityTarget != null) return entityTarget;

        Vec3 blockPos = detectBlock(mc, player, look);
        return blockPos != null ? new TargetResult(blockPos, null) : null;
    }

    /**
     * Detect entity gần crosshair nhất bằng angular distance thuần.
     *
     * Cách hoạt động:
     *  1. Lấy tất cả entity hookable trong sphere SCAN_RANGE quanh eye
     *  2. Với mỗi entity, tính góc giữa look vector và hướng eye→entity_center
     *  3. Entity trong cone ENTITY_ASSIST_ANGLE_DEG (12°) đều được xét
     *     — KHÔNG cần ray xuyên vào hitbox
     *  4. Chọn entity có góc nhỏ nhất (gần crosshair nhất về góc nhìn)
     *  5. Kiểm tra line of sight: ray từ eye đến entity_center không bị block chặn
     *
     * Lý do bỏ ray-AABB intersection:
     *  Ray xuyên AABB = phải aim thẳng vào hitbox → không có assist thực sự.
     *  Angular distance = "entity nào gần crosshair nhất về góc" → có assist rõ ràng.
     */
    private static TargetResult detectEntity(Minecraft mc, Player player, Vec3 eye, Vec3 look) {
        AABB searchBox = new AABB(eye, eye).inflate(SCAN_RANGE);
        List<Entity> candidates = mc.level.getEntities(player, searchBox,
                e -> isHookableEntity(e, player));
        if (candidates.isEmpty()) return null;

        Entity best      = null;
        double bestDot   = ENTITY_ASSIST_DOT; // chỉ xét entity có dot >= ngưỡng này
        Vec3   bestPoint = null;

        for (Entity e : candidates) {
            // Dùng center của entity (giữa chiều cao)
            Vec3   center = e.position().add(0, e.getBbHeight() * 0.5, 0);
            Vec3   toEnt  = center.subtract(eye);
            double dist   = toEnt.length();
            if (dist < 0.1 || dist > SCAN_RANGE) continue;

            // Angular distance: dot product giữa look và hướng đến entity center
            // dot càng gần 1.0 → entity càng nằm chính giữa crosshair
            double dot = toEnt.normalize().dot(look);
            if (dot < bestDot) continue; // ngoài cone hoặc thua candidate tốt hơn

            // Line of sight: ray đến center của entity không bị block chặn
            // Dùng center chứ không phải hit point trên AABB — tránh edge case khi
            // entity đứng sát tường và ray vào AABB bị chặn nhưng center thì không
            if (!hasLineOfSight(mc, player, eye, center)) continue;

            bestDot   = dot;
            best      = e;
            bestPoint = center;
        }

        return best != null ? new TargetResult(bestPoint, best) : null;
    }

    /** Line of sight: ray từ from đến to không bị block solid chặn */
    private static boolean hasLineOfSight(Minecraft mc, Player player, Vec3 from, Vec3 to) {
        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * Entity hookable: LivingEntity, không phải owner, không phải hook entity,
     * không phải ArmorStand, không phải Display/Marker.
     */
    public static boolean isHookableEntity(Entity e, Player owner) {
        if (e == owner)                       return false;
        if (e instanceof GrapplingHookEntity) return false;
        if (e instanceof ItemEntity)          return false;
        if (e instanceof ArmorStand)          return false;
        if (!(e instanceof LivingEntity))     return false;
        String cn = e.getClass().getSimpleName();
        return !cn.contains("Display") && !cn.contains("Marker");
    }

    // =========================================================================
    //  BLOCK DETECTION (giữ nguyên)
    // =========================================================================

    private static Vec3 detectBlock(Minecraft mc, Player player, Vec3 look) {
        Vec3   eye   = player.getEyePosition();
        Vec3   best  = null;
        double bestD = Double.MAX_VALUE;
        Vec3   up    = Math.abs(look.y) < 0.99 ? new Vec3(0,1,0) : new Vec3(1,0,0);
        Vec3   right = look.cross(up).normalize();
        Vec3   upDir = right.cross(look).normalize();
        int    half  = SCAN_GRID / 2;

        Vec3 r = rayCastBlock(mc, player, look);
        if (r != null) { double d = r.distanceTo(eye); if (d < bestD) { bestD=d; best=r; } }

        for (int gi = 0; gi < SCAN_GRID; gi++) {
            for (int gj = 0; gj < SCAN_GRID; gj++) {
                float offR = ((float)(gi-half) / Math.max(half,1)) * SCAN_ANGLE;
                float offU = ((float)(gj-half) / Math.max(half,1)) * SCAN_ANGLE;
                Vec3 dir   = rotateDir(look, right, upDir, offR, offU);
                Vec3 res   = rayCastBlock(mc, player, dir);
                if (res == null) continue;
                double d = res.distanceTo(eye);
                if (d < bestD) { bestD=d; best=res; }
            }
        }
        return best;
    }

    private static Vec3 rayCastBlock(Minecraft mc, Player player, Vec3 dir) {
        Vec3 from = player.getEyePosition();
        Vec3 to   = from.add(dir.scale(SCAN_RANGE));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        return state.isSolid() ? hit.getLocation() : null;
    }

    private static Vec3 rotateDir(Vec3 base, Vec3 axisR, Vec3 axisU, float degR, float degU) {
        return base.add(axisR.scale(Math.tan(Math.toRadians(degR))))
                .add(axisU.scale(Math.tan(Math.toRadians(degU)))).normalize();
    }

    // =========================================================================
    //  WASD → World-space vector
    // =========================================================================

    private static Vec3 buildWasdWorldVec(Options opts, Player player) {
        float fwd = 0f, side = 0f;
        if (opts.keyUp.isDown())    fwd  += 1f;
        if (opts.keyDown.isDown())  fwd  -= 1f;
        if (opts.keyLeft.isDown())  side -= 1f;
        if (opts.keyRight.isDown()) side += 1f;
        if (fwd == 0f && side == 0f) return Vec3.ZERO;

        double yaw = Math.toRadians(player.getYRot());
        double fx  = -Math.sin(yaw) * fwd  - Math.cos(yaw) * side;
        double fz  =  Math.cos(yaw) * fwd  - Math.sin(yaw) * side;
        double len = Math.sqrt(fx*fx + fz*fz);
        return len > 0.001 ? new Vec3(fx/len, 0, fz/len) : Vec3.ZERO;
    }

    // =========================================================================
    //  BHOPPING INPUT
    // =========================================================================

    /**
     * Gửi trạng thái phím W/A/D/Space + yaw hiện tại lên server mỗi client
     * tick, CHỈ khi giày người chơi đang mặc có enchant Bhopping (level > 0).
     * BhopServerHandler dùng dữ liệu này để tính momentum/air-strafe/turn-break.
     *
     * Khi người chơi tháo giày Bhopping ra giữa chừng (hoặc chết/respawn),
     * gửi thêm đúng 1 packet "tắt" (tất cả false) để server dọn state kịp,
     * tránh bị kẹt input cũ (vd đang giữ forward=true thì tháo giày ra).
     */
    private static void tickBhopInput(Minecraft mc, Player player) {
        boolean hasBhop = ModEnchantments.getBhopLevel(player) > 0;

        if (!hasBhop) {
            if (wasSendingBhopInput) {
                PacketDistributor.sendToServer(
                        new BhopInputPacket(false, false, false, false, player.getYRot()));
                wasSendingBhopInput = false;
            }
            return;
        }

        Options opts = mc.options;
        boolean forward = opts.keyUp.isDown();
        boolean left    = opts.keyLeft.isDown();
        boolean right   = opts.keyRight.isDown();
        boolean jump    = opts.keyJump.isDown();

        PacketDistributor.sendToServer(
                new BhopInputPacket(forward, left, right, jump, player.getYRot()));
        wasSendingBhopInput = true;
    }

    // =========================================================================
    //  DRIFT INPUT
    // =========================================================================

    /**
     * Gửi trạng thái giữ phím Drift (mặc định Left Ctrl, xem ModKeyMappings.DRIFT
     * — KHÔNG phải phím Sneak vanilla, tách riêng khỏi Dash) mỗi client tick,
     * CHỈ khi giày đang mặc có enchant Drift. DriftServerHandler dùng giá trị
     * này để biết có nên bắt đầu/tiếp tục trượt hay không.
     */
    private static void tickDriftInput(Minecraft mc, Player player) {
        boolean hasDrift = ModEnchantments.getDriftLevel(player) > 0;

        if (!hasDrift) {
            if (wasSendingDriftInput) {
                PacketDistributor.sendToServer(new DriftInputPacket(false));
                wasSendingDriftInput = false;
            }
            return;
        }

        boolean holding = ModKeyMappings.DRIFT.isDown();
        PacketDistributor.sendToServer(new DriftInputPacket(holding));
        wasSendingDriftInput = true;

        // Slide-Jump: khi đang trượt mà người chơi bấm Space (cạnh lên)
        boolean isSliding = net.huwng.highv.client.animation.DriftAnimationHandler.isSyncedSliding(player.getUUID());
        boolean jumpDown = mc.options.keyJump.isDown();
        if (isSliding && jumpDown && !wasHoldingJumpForSlideJump) {
            Vec3 curV = player.getDeltaMovement();
            player.setDeltaMovement(curV.x, 1.08, curV.z);
            PacketDistributor.sendToServer(new net.huwng.highv.network.packet.SlideJumpPacket());
        }
        wasHoldingJumpForSlideJump = jumpDown;
    }

    // =========================================================================
    //  DASH INPUT
    // =========================================================================

    /**
     * Phát hiện cạnh lên của phím Shift (vừa bấm, không phải giữ) khi đang
     * mặc giày có enchant Dash. Hướng dash:
     *  - Có giữ A/S/D → dash theo hướng tổ hợp phím đó (ngang, theo yaw),
     *    giữ nhiều phím thì cộng hướng (vd A+S → dash chéo trái-sau). W
     *    KHÔNG được tính vào tổ hợp này nữa.
     *  - Không giữ A/S/D nào (kể cả khi đang giữ W, hoặc không giữ phím di
     *    chuyển nào cả) → dash theo đúng hướng đang NHÌN (3D, kể cả
     *    lên/xuống nếu ngước lên trời/cúi xuống đất). Nghĩa là W+Shift giờ
     *    sẽ dash theo hướng nhìn chứ không còn bị ép về phía trước ngang.
     */
    private static int lastClientDashTick = -100;

    private static void tickDashInput(Minecraft mc, Player player) {
        boolean hasDash = ModEnchantments.getDashLevel(player) > 0;
        if (!hasDash) {
            wasHoldingShift = false;
            return;
        }

        Options opts = mc.options;
        boolean holdingShift = opts.keyShift.isDown();

        // Không cho phép Dash khi đang trong trạng thái Drift / Trượt
        boolean isSliding = net.huwng.highv.client.animation.DriftAnimationHandler.isSyncedSliding(player.getUUID());
        if (isSliding) {
            wasHoldingShift = holdingShift;
            return;
        }

        if (holdingShift && !wasHoldingShift) {
            int now = player.tickCount;
            if (now < lastClientDashTick || now - lastClientDashTick >= 7) {
                lastClientDashTick = now;
                Vec3 dir = computeDashDirection(mc, player);
                PacketDistributor.sendToServer(
                        new DashRequestPacket((float) dir.x, (float) dir.y, (float) dir.z));

                DashAnimationHandler.Pose pose = computeDashAnimationCategory(mc, player);
                if (player instanceof net.minecraft.client.player.AbstractClientPlayer acp) {
                    DashAnimationHandler.trigger(acp, pose);
                }
                PilotHudInertiaHandler.triggerDashImpulse(dir, pose);
                KatanaMotionDynamicsHandler.triggerDashImpulse(pose);
            }
        }

        wasHoldingShift = holdingShift;
    }

    @SubscribeEvent
    public static void onClientPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        lastClientDashTick = -100;
        wasHoldingShift = false;
    }

    /**
     * Suy ra 1 trong 4 hướng animation (front/back/left/right) từ ĐÚNG các
     * phím A/S/D đang giữ tại thời điểm dash kích hoạt — dùng chung logic
     * đọc phím với computeDashDirection() để đảm bảo animation luôn khớp
     * đúng hướng vật lý thật sự của cú dash.
     *
     * Không giữ A/S/D nào (kể cả chỉ giữ W hoặc không giữ gì) -> FRONT, vì
     * lúc đó dash phóng thẳng theo hướng nhìn (xem computeDashDirection),
     * gần nhất với "phía trước" thân người trong 4 animation có sẵn.
     *
     * Có giữ tổ hợp (vd A+S chéo trái-sau) -> quy về góc so với trục
     * forward/right của thân người, rồi chọn 1 trong 4 hướng cardinal gần
     * góc đó nhất (chia 4 cung 90°).
     */
    private static DashAnimationHandler.Pose computeDashAnimationCategory(Minecraft mc, Player player) {
        Options opts = mc.options;
        boolean back  = opts.keyDown.isDown();
        boolean left  = opts.keyRight.isDown();
        boolean right = opts.keyLeft.isDown();

        double localForward = back ? -1.0 : 0.0;
        double localRight = (right ? 1.0 : 0.0) - (left ? 1.0 : 0.0);
        if (localForward == 0.0 && localRight == 0.0) {
            return DashAnimationHandler.Pose.FRONT;
        }

        double angleDeg = Math.toDegrees(Math.atan2(localRight, localForward));
        // 0° = front, 90° = right, ±180° = back, -90° = left.
        if (angleDeg > -45 && angleDeg <= 45)  return DashAnimationHandler.Pose.FRONT;
        if (angleDeg > 45  && angleDeg <= 135) return DashAnimationHandler.Pose.RIGHT;
        if (angleDeg > 135 || angleDeg <= -135) return DashAnimationHandler.Pose.BACK;
        return DashAnimationHandler.Pose.LEFT;
    }

    /**
     * Hướng dash: tổ hợp A/S/D (ngang) nếu có giữ ít nhất 1 trong 3 phím đó,
     * ngược lại (kể cả khi chỉ giữ W, hoặc không giữ phím di chuyển nào)
     * dùng thẳng hướng nhìn 3D. W KHÔNG còn góp phần vào hướng tổ hợp nữa.
     */
    private static Vec3 computeDashDirection(Minecraft mc, Player player) {
        Options opts = mc.options;
        boolean back  = opts.keyDown.isDown();
        boolean left  = opts.keyRight.isDown();
        boolean right = opts.keyLeft.isDown();

        if (back || left || right) {
            double yawRad = Math.toRadians(player.getYRot());
            double forwardX = -Math.sin(yawRad), forwardZ = Math.cos(yawRad);
            double rightX   =  Math.cos(yawRad), rightZ   = Math.sin(yawRad);

            double wishX = 0, wishZ = 0;
            if (back)  { wishX -= forwardX; wishZ -= forwardZ; }
            if (left)  { wishX -= rightX;   wishZ -= rightZ;   }
            if (right) { wishX += rightX;   wishZ += rightZ;   }

            double len = Math.sqrt(wishX * wishX + wishZ * wishZ);
            if (len > 1.0e-6) return new Vec3(wishX / len, 0, wishZ / len);
        }

        return player.getLookAngle();
    }

    // =========================================================================
    //  RICOCHET INPUT
    // =========================================================================

    /**
     * Phát hiện cạnh lên của phím Space (vừa bấm, không phải giữ) khi đang
     * mặc giày có enchant Ricochet. Gửi packet trigger — server tự quyết
     * định có tác dụng gì hay không (chỉ có tác dụng nếu đang bám tường nhờ
     * Wallstride, xem RicochetServerHandler).
     */
    private static void tickRicochetInput(Minecraft mc, Player player) {
        boolean hasRicochet = ModEnchantments.getRicochetLevel(player) > 0;
        if (!hasRicochet) {
            wasHoldingJumpForRicochet = false;
            return;
        }

        boolean holdingJump = mc.options.keyJump.isDown();
        if (holdingJump && !wasHoldingJumpForRicochet) {
            PacketDistributor.sendToServer(new RicochetRequestPacket());
        }
        wasHoldingJumpForRicochet = holdingJump;
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private static void sendState(boolean pulling, Vec3 look, Vec3 input) {
        PacketDistributor.sendToServer(new GrapplingStatePacket(
                pulling, look.x, look.y, look.z, input.x, input.z));
    }
}