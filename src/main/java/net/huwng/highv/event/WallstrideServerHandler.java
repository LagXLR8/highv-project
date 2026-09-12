package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.enchantment.ModEnchantments;
import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.item.GrapplingHookItem;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Xử lý enchant "Wallstride" trên giày.
 *
 * Cho phép người chơi chạy dọc theo tường khi đang ở trên không (airborne).
 * - Duy trì động năng cam kết (committed speed): không bị ma sát không khí vanilla
 *   (air drag 0.91) làm suy hao tốc độ dẫn đến rơi tức thì.
 * - Pháp tuyến chuẩn từ mặt khối va chạm và tiếp tuyến song song tuyệt đối với tường.
 * - Soft-cling giữ khoảng cách an toàn ~0.38m tránh cọ xát hitbox khối (horizontalCollision).
 */
@EventBusSubscriber(modid = HighV.MOD_ID)
public final class WallstrideServerHandler {

    /** Tầm bắn tia dò tường sang 2 bên, block. */
    private static final double WALL_DETECT_RANGE = 1.1;

    /** Tốc độ ngang tối thiểu để đủ điều kiện BẮT ĐẦU bám tường, block/tick. 0.20 ≈ 4.0 b/s (sprint-jump ~0.35). */
    private static final double MIN_ENTRY_SPEED = 0.20;

    /** Tốc độ chạy tường cơ sở, block/tick. 0.38 ≈ 7.6 b/s (~27.4 km/h) — tốc độ parkour chuẩn. */
    private static final double BASE_WALLRUN_SPEED = 0.38;

    /** Giá trị mặc định của attribute minecraft:gravity (block/tick^2) — trừ đi để đưa về 0. */
    private static final double DEFAULT_GRAVITY = 0.08;

    /** ID của AttributeModifier triệt tiêu trọng lực lúc bám tường. */
    private static final ResourceLocation NO_GRAVITY_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(HighV.MOD_ID, "wallstride_no_gravity");

    /** Thời lượng bám tường liên tục tối đa, tick. Public — HUD client đọc để vẽ thanh thời gian. 80 tick = 4s. */
    public static final int MAX_WALLRUN_TICKS = 80;

    /**
     * Số tick "ân hạn" cho phép rời tường (mất tường) mà KHÔNG
     * bị coi là kết thúc phiên bám — nếu bám lại cùng phía trong khoảng này,
     * thời lượng vẫn tính tiếp từ mốc bắt đầu cũ thay vì reset về 0.
     */
    private static final int GRACE_TICKS = 6;

    /** Cooldown áp dụng riêng cho 1 phía (trái/phải) sau khi phía đó chạm MAX_WALLRUN_TICKS, tick. 50 tick = 2.5s. */
    private static final int WALLRUN_COOLDOWN_TICKS = 50;

    /** Bật log debug. Tắt khi đã ổn. */
    private static final boolean DEBUG = false;

    /** Phía tường, dùng để phân biệt cooldown/phiên bám trái và phải độc lập nhau. */
    private enum Side { LEFT, RIGHT }

    /** Kết quả dò tường: normal hướng ra ngoài + phía tường tương ứng + khoảng cách từ tâm player tới mặt tường. */
    private record WallHit(Vec3 outwardNormal, Side side, double distance) {}

    /** Kết quả raycast đơn lẻ */
    private record RayResult(Vec3 hitPos, Direction face, double distance) {}

    /** Wall normal HƯỚNG RA NGOÀI (từ tường về phía player) của tick gần nhất, null nếu không bám tường. */
    private static final Map<UUID, Vec3> wallOutwardNormal = new HashMap<>();

    /** Vận tốc chạy tường cam kết (block/tick), bảo lưu động năng và không bị air drag triệt tiêu. */
    private static final Map<UUID, Double> wallRunSpeed = new HashMap<>();

    /** Tick tuyệt đối (player.tickCount) lúc BẮT ĐẦU phiên bám tường hiện tại — để tính đã bám bao lâu. */
    private static final Map<UUID, Integer> wallRunStartTick = new HashMap<>();

    /** Phía tường của phiên bám hiện tại (kể cả khi đang trong grace, tạm thời không bám). */
    private static final Map<UUID, Side> wallRunSide = new HashMap<>();

    /** Tick tuyệt đối lần CUỐI thực sự bám tường (dùng để tính grace còn hiệu lực hay không). */
    private static final Map<UUID, Integer> lastAttachedTick = new HashMap<>();

    /** Tick (tuyệt đối) mà tới lúc đó Wallstride tạm ngừng hoàn toàn — dùng cho Ricochet. */
    private static final Map<UUID, Integer> suppressedUntilTick = new HashMap<>();

    /** Tick (tuyệt đối) mà tới lúc đó phía TRÁI còn đang cooldown, theo từng người chơi. */
    private static final Map<UUID, Integer> leftCooldownUntilTick = new HashMap<>();

    /** Tick (tuyệt đối) mà tới lúc đó phía PHẢI còn đang cooldown, theo từng người chơi. */
    private static final Map<UUID, Integer> rightCooldownUntilTick = new HashMap<>();

    private WallstrideServerHandler() {}

    /**
     * Wall normal hướng ra ngoài (đã unit-length, chỉ có x/z) nếu player hiện
     * đang bám tường nhờ Wallstride; null nếu không.
     */
    public static Vec3 getOutwardWallNormal(ServerPlayer player) {
        return wallOutwardNormal.get(player.getUUID());
    }

    /**
     * Tạm ngừng Wallstride hoàn toàn (không dò tường, không bám) trong
     * {@code ticks} tick tiếp theo. Gọi từ RicochetServerHandler ngay sau khi
     * đẩy người chơi ra khỏi tường, để không bị bám dính lại ngay lập tức.
     */
    public static void suppressFor(ServerPlayer player, int ticks) {
        UUID id = player.getUUID();
        suppressedUntilTick.put(id, player.tickCount + ticks);
        clearSession(id);
        removeNoGravity(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide) return;

        UUID id = player.getUUID();

        if (ModEnchantments.getWallstrideLevel(player) <= 0) {
            clearSession(id);
            removeNoGravity(player);
            return;
        }

        if (player.tickCount < suppressedUntilTick.getOrDefault(id, 0)) {
            clearSession(id);
            removeNoGravity(player);
            return;
        }

        // Giả định: wall-run chỉ áp dụng khi đang lơ lửng/rơi (kỹ năng trên không).
        // Đứng đất là dừng CỨNG — không tính grace.
        if (player.onGround()) {
            clearSession(id);
            removeNoGravity(player);
            return;
        }

        // Ưu tiên Grappling Hook: nếu hook đang bám vào điểm neo (block / entity),
        // nhường quyền hoàn toàn cho lực kéo của dây cáp để người chơi có thể kéo mình ra khỏi tường.
        GrapplingHookEntity hook = GrapplingHookItem.findActiveHook(player.serverLevel(), player);
        if (hook != null && hook.isAttached()) {
            boolean wasRunning = wallRunStartTick.containsKey(id);
            suppressFor(player, 8);
            if (wasRunning) {
                // Đang bám tường mà hook kéo đi -> đồng bộ ngay vận tốc hiện tại (do hook kéo) xuống client
                player.connection.send(new ClientboundSetEntityMotionPacket(player));
            }
            return;
        }

        WallHit hit = detectWall(player, id);

        Vec3 currentVel = player.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(currentVel.x * currentVel.x + currentVel.z * currentVel.z);

        boolean isAlreadyRunning = wallRunStartTick.containsKey(id);
        boolean speedOk = isAlreadyRunning || horizontalSpeed >= MIN_ENTRY_SPEED;

        if (hit == null || !speedOk) {
            Integer last = lastAttachedTick.get(id);
            wallOutwardNormal.remove(id);
            removeNoGravity(player);
            if (last == null || player.tickCount - last > GRACE_TICKS) {
                clearSession(id);
            }
            return;
        }

        Integer last = lastAttachedTick.get(id);
        boolean continuingSession = wallRunStartTick.containsKey(id)
                && last != null
                && (player.tickCount - last) <= GRACE_TICKS
                && wallRunSide.get(id) == hit.side();

        if (!continuingSession) {
            wallRunStartTick.put(id, player.tickCount);
            wallRunSide.put(id, hit.side());
        }
        lastAttachedTick.put(id, player.tickCount);

        if (player.tickCount - wallRunStartTick.get(id) >= MAX_WALLRUN_TICKS) {
            if (DEBUG) HighV.LOGGER.info("[Wallstride] hết thời lượng bám tường tối đa ({}) -> tự rớt + cooldown", hit.side());
            setCooldown(id, hit.side(), player.tickCount + WALLRUN_COOLDOWN_TICKS);
            clearSession(id);
            removeNoGravity(player);
            return;
        }

        // Quản lý vận tốc chạy tường cam kết (bảo lưu động năng không bao giờ rớt dưới BASE_WALLRUN_SPEED)
        double speed = wallRunSpeed.getOrDefault(id, 0.0);
        if (speed <= 0.0) {
            speed = Math.max(BASE_WALLRUN_SPEED, horizontalSpeed);
        } else {
            if (speed > BASE_WALLRUN_SPEED) {
                speed = Math.max(BASE_WALLRUN_SPEED, speed * 0.99);
            }
        }
        wallRunSpeed.put(id, speed);

        Vec3 outwardNormal = hit.outwardNormal();
        wallOutwardNormal.put(id, outwardNormal);
        applyNoGravity(player);

        Vec3 tangent = computeTangentTowardLook(player, outwardNormal);

        // Soft-cling: Giữ khoảng cách lý tưởng quanh ~0.38 block để không cọ sát hitbox
        // gây horizontalCollision nhưng cũng không bị trôi tuột ra xa tường
        Vec3 clingAdjust = Vec3.ZERO;
        if (hit.distance() > 0.44) {
            clingAdjust = outwardNormal.scale(-0.03); // Hút nhẹ vào tường
        } else if (hit.distance() < 0.34) {
            clingAdjust = outwardNormal.scale(0.03);  // Đẩy nhẹ ra tránh cọ hitbox
        }

        Vec3 newVel = new Vec3(
                tangent.x * speed + clingAdjust.x,
                0.0,
                tangent.z * speed + clingAdjust.z
        );
        player.setDeltaMovement(newVel);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.fallDistance = 0.0f;

        BhopServerHandler.syncMomentumToCurrentVelocity(player);

        if (DEBUG) {
            HighV.LOGGER.info("[Wallstride] bám tường {}, tốc độ {} b/s",
                    hit.side(), speed * 20.0);
        }
    }

    /** Gắn AttributeModifier triệt tiêu trọng lực (nếu chưa có). */
    private static void applyNoGravity(ServerPlayer player) {
        AttributeInstance attr = player.getAttribute(Attributes.GRAVITY);
        if (attr == null) return;
        if (attr.getModifier(NO_GRAVITY_MODIFIER_ID) != null) return;
        attr.addTransientModifier(new AttributeModifier(
                NO_GRAVITY_MODIFIER_ID, -DEFAULT_GRAVITY, AttributeModifier.Operation.ADD_VALUE));
    }

    /** Gỡ AttributeModifier triệt tiêu trọng lực (không lỗi nếu chưa có). */
    private static void removeNoGravity(ServerPlayer player) {
        AttributeInstance attr = player.getAttribute(Attributes.GRAVITY);
        if (attr == null) return;
        attr.removeModifier(NO_GRAVITY_MODIFIER_ID);
    }

    /**
     * Dò tường sang phải rồi sang trái (vuông góc hướng nhìn, ngang).
     * Bắn 2 tia tại ngực và hông để tránh tuột khi nhảy hoặc mép khối.
     */
    private static WallHit detectWall(ServerPlayer player, UUID id) {
        double yawRad = Math.toRadians(player.getYRot());
        // Trong hệ trục Minecraft: X là Đông (+X), Z là Nam (+Z).
        // Nhìn góc yaw (Nam = 0, Tây = 90, Bắc = 180, Đông = -90):
        // Vector bên PHẢI chuẩn của player là (-cos(yaw), 0, -sin(yaw)).
        Vec3 right = new Vec3(-Math.cos(yawRad), 0, -Math.sin(yawRad));
        Vec3 left = right.scale(-1);

        Vec3 originChest = player.position().add(0, player.getBbHeight() * 0.65, 0);
        Vec3 originWaist = player.position().add(0, player.getBbHeight() * 0.35, 0);

        RayResult rightHit = raycastSolid(player, originChest, right);
        if (rightHit == null) rightHit = raycastSolid(player, originWaist, right);

        RayResult leftHit = raycastSolid(player, originChest, left);
        if (leftHit == null) leftHit = raycastSolid(player, originWaist, left);

        int now = player.tickCount;
        boolean rightOnCooldown = now < rightCooldownUntilTick.getOrDefault(id, 0);
        boolean leftOnCooldown = now < leftCooldownUntilTick.getOrDefault(id, 0);

        if (rightHit != null && !rightOnCooldown) {
            Vec3 normal;
            if (rightHit.face().getAxis().isHorizontal()) {
                normal = new Vec3(rightHit.face().getStepX(), 0, rightHit.face().getStepZ());
            } else {
                normal = right.scale(-1);
            }
            return new WallHit(normal, Side.RIGHT, rightHit.distance());
        }
        if (leftHit != null && !leftOnCooldown) {
            Vec3 normal;
            if (leftHit.face().getAxis().isHorizontal()) {
                normal = new Vec3(leftHit.face().getStepX(), 0, leftHit.face().getStepZ());
            } else {
                normal = left.scale(-1);
            }
            return new WallHit(normal, Side.LEFT, leftHit.distance());
        }
        return null;
    }

    /** Đặt cooldown cho 1 phía cụ thể tới tick tuyệt đối {@code untilTick}. */
    private static void setCooldown(UUID id, Side side, int untilTick) {
        if (side == Side.LEFT) {
            leftCooldownUntilTick.put(id, untilTick);
        } else {
            rightCooldownUntilTick.put(id, untilTick);
        }
    }

    private static RayResult raycastSolid(ServerPlayer player, Vec3 from, Vec3 dir) {
        Level level = player.level();
        Vec3 to = from.add(dir.scale(WALL_DETECT_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockState state = level.getBlockState(hit.getBlockPos());
        if (!state.isSolid()) return null;
        return new RayResult(hit.getLocation(), hit.getDirection(), from.distanceTo(hit.getLocation()));
    }

    /**
     * Tiếp tuyến (song song mặt tường, ngang, unit-length) — chọn giữa 2
     * chiều khả dĩ sao cho gần nhất với hướng đang nhìn ngang (theo yaw).
     */
    private static Vec3 computeTangentTowardLook(Player player, Vec3 outwardNormal) {
        Vec3 tangent = new Vec3(-outwardNormal.z, 0, outwardNormal.x);

        double yawRad = Math.toRadians(player.getYRot());
        Vec3 look = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));

        if (tangent.dot(look) < 0) tangent = tangent.scale(-1);
        return tangent;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        clearSession(id);
        suppressedUntilTick.remove(id);
        leftCooldownUntilTick.remove(id);
        rightCooldownUntilTick.remove(id);
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            removeNoGravity(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        UUID id = event.getEntity().getUUID();
        clearSession(id);
        suppressedUntilTick.remove(id);
        leftCooldownUntilTick.remove(id);
        rightCooldownUntilTick.remove(id);
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            removeNoGravity(serverPlayer);
        }
    }

    /**
     * Xoá trạng thái PHIÊN BÁM hiện tại (mốc bắt đầu, phía, lần bám cuối,
     * wall normal, committed speed).
     */
    private static void clearSession(UUID id) {
        wallOutwardNormal.remove(id);
        wallRunStartTick.remove(id);
        wallRunSide.remove(id);
        lastAttachedTick.remove(id);
        wallRunSpeed.remove(id);
    }
}
