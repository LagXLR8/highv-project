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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
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

    public record TargetResult(Vec3 position, Entity entity, boolean isSafeLedge, double distance) {
        public TargetResult(Vec3 position, Entity entity) {
            this(position, entity, false, 0.0);
        }
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

        boolean holdingHook = player.getOffhandItem().is(ModItems.GRAPPLING_HOOK.get())
                || player.getMainHandItem().is(ModItems.GRAPPLING_HOOK.get());

        if (!holdingHook) {
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

        return detectSafeBlock(mc, player, eye, look);
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
            if (!hasLineOfSight(mc, player, eye, center)) continue;

            bestDot   = dot;
            best      = e;
            bestPoint = center;
        }

        return best != null ? new TargetResult(bestPoint, best, false, bestPoint.distanceTo(eye)) : null;
    }

    /** Line of sight: ray từ from đến to không bị block solid chặn */
    private static boolean hasLineOfSight(Minecraft mc, Player player, Vec3 from, Vec3 to) {
        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
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
    //  SMART SAFE-POINT BLOCK DETECTION
    // =========================================================================

    /**
     * Thuật toán tìm điểm neo an toàn thông minh:
     * 1. Lọc bỏ hoàn toàn các bề mặt nguy hiểm (Lava, Lửa, Bụi gai, Băng tuyết lún, Xương rồng, Magma).
     * 2. Quét đa tầng đồng tâm (Concentric Ray Cone) gồm tia trực diện và 3 vòng nón (3.5°, 7.0°, 11.0°).
     * 3. Chấm điểm thông minh:
     *    - Ưu tiên tia gần tâm ngắm (Angular dot product ^ 3.5).
     *    - Ưu tiên gờ an toàn đứng được (Safe Ledge có 2 block không khí phía trên: x1.85).
     *    - Ưu tiên bờ tường vuông góc (Horizontal Wall cho Wallstride: x1.35).
     *    - Ưu tiên cao độ (các điểm trên cao tạo đà đu và vượt chướng ngại vật).
     *    - Phạt nặng điểm rơi mặt đất sát chân khi người chơi đang nhìn ngang hoặc ngước lên.
     */
    private static TargetResult detectSafeBlock(Minecraft mc, Player player, Vec3 eye, Vec3 look) {
        Level level = mc.level;
        if (level == null) return null;

        Vec3 up = Math.abs(look.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 right = look.cross(up).normalize();
        Vec3 upDir = right.cross(look).normalize();

        TargetResult bestTarget = null;
        double bestScore = -1.0;

        // 1. Ray trực diện (Direct Crosshair Ray)
        BlockHitResult directHit = rayCastCollider(level, player, eye, look);
        if (directHit != null) {
            double score = scoreHit(level, player, eye, look, directHit, true);
            if (score > 0) {
                boolean safe = isSafeLedge(level, directHit.getBlockPos(), directHit.getDirection());
                double dist = directHit.getLocation().distanceTo(eye);
                bestTarget = new TargetResult(directHit.getLocation(), null, safe, dist);
                bestScore = score;
            }
        }

        // 2. Vòng nón quét đồng tâm (Concentric Ray Cones)
        float[] ringAngles = {3.5f, 7.0f, 11.0f};
        int[] ringSamples = {8, 12, 12};

        for (int r = 0; r < ringAngles.length; r++) {
            float radiusDeg = ringAngles[r];
            int samples = ringSamples[r];
            double rRad = Math.toRadians(radiusDeg);
            double cosR = Math.cos(rRad);
            double sinR = Math.sin(rRad);

            for (int i = 0; i < samples; i++) {
                double phi = 2.0 * Math.PI * i / samples;
                double cosPhi = Math.cos(phi);
                double sinPhi = Math.sin(phi);

                Vec3 dir = look.scale(cosR)
                        .add(right.scale(sinR * cosPhi))
                        .add(upDir.scale(sinR * sinPhi))
                        .normalize();

                BlockHitResult hit = rayCastCollider(level, player, eye, dir);
                if (hit == null) continue;

                double score = scoreHit(level, player, eye, look, hit, false);
                if (score > bestScore) {
                    boolean safe = isSafeLedge(level, hit.getBlockPos(), hit.getDirection());
                    double dist = hit.getLocation().distanceTo(eye);
                    bestTarget = new TargetResult(hit.getLocation(), null, safe, dist);
                    bestScore = score;
                }
            }
        }

        return bestTarget;
    }

    private static BlockHitResult rayCastCollider(Level level, Player player, Vec3 from, Vec3 dir) {
        Vec3 to = from.add(dir.scale(SCAN_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static double scoreHit(Level level, Player player, Vec3 eye, Vec3 look, BlockHitResult hit, boolean isDirect) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);

        if (isHazardBlock(level, pos, state)) return -1.0;

        Direction face = hit.getDirection();
        if (face == Direction.UP && isHazardBlock(level, pos.above(), level.getBlockState(pos.above()))) {
            return -1.0;
        }

        Vec3 loc = hit.getLocation();
        Vec3 toHit = loc.subtract(eye);
        double dist = toHit.length();
        if (dist < 1.8 || dist > SCAN_RANGE) return -1.0;

        double dot = toHit.normalize().dot(look);
        if (dot < 0.70) return -1.0;

        // Càng gần tâm ngắm điểm càng cao
        double alignScore = Math.pow(dot, 3.5);

        // Đánh giá cự ly
        double distFactor;
        if (dist < 3.5) {
            distFactor = 0.25 + 0.35 * (dist / 3.5);
        } else if (dist <= 32.0) {
            distFactor = 1.0;
        } else {
            distFactor = Math.max(0.5, 1.0 - (dist - 32.0) / 40.0);
        }

        // Phạt mặt đất ngay dưới chân khi nhìn ngang hoặc ngước lên
        if (look.y > -0.45 && loc.y < eye.y - 1.0 && dist < 4.5) {
            alignScore *= 0.15;
        }

        // Hệ số bề mặt
        double surfaceBonus = 1.0;
        if (isSafeLedge(level, pos, face)) {
            surfaceBonus = 1.85;
        } else if (face.getAxis().isHorizontal()) {
            surfaceBonus = 1.35;
        }

        // Hệ số cao độ
        double heightBonus = 1.0;
        if (loc.y > eye.y) {
            heightBonus += Math.min(0.40, (loc.y - eye.y) * 0.04);
        }

        double directBonus = isDirect ? 1.30 : 1.0;

        return alignScore * distFactor * surfaceBonus * heightBonus * directBonus;
    }

    private static boolean isHazardBlock(Level level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.LAVA) || state.getFluidState().is(Fluids.LAVA)) return true;
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return true;
        if (state.is(Blocks.MAGMA_BLOCK)) return true;
        if (state.is(Blocks.CACTUS)) return true;
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return true;
        if (state.is(Blocks.WITHER_ROSE)) return true;
        if (state.is(Blocks.POWDER_SNOW)) return true;
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) return true;
        return false;
    }

    private static boolean isSafeLedge(Level level, BlockPos pos, Direction face) {
        if (face != Direction.UP) return false;
        BlockPos above1 = pos.above();
        BlockPos above2 = pos.above(2);
        BlockState state1 = level.getBlockState(above1);
        BlockState state2 = level.getBlockState(above2);

        if (!state1.getCollisionShape(level, above1).isEmpty()) return false;
        if (!state2.getCollisionShape(level, above2).isEmpty()) return false;
        if (isHazardBlock(level, above1, state1) || isHazardBlock(level, above2, state2)) return false;
        if (!state1.getFluidState().isEmpty()) return false;

        return true;
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