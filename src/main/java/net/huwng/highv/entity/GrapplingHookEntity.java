package net.huwng.highv.entity;

import net.huwng.highv.item.GrapplingHookItem;
import net.huwng.highv.item.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Grappling Hook entity.
 *
 * ═══ Lifecycle ═══
 *  1) FLYING   — bay thẳng đến targetPos, không gravity, tự retract sau MAX_LIFETIME tick
 *  2) ATTACHED — kéo player về attachPoint (block) hoặc hookedEntity (entity)
 *
 * ═══ Entity hook ═══
 *  Khi DATA_HOOKED_ENTITY_ID >= 0, hook đang bám vào entity đó.
 *  attachPoint được cập nhật mỗi tick theo entity position → dây theo entity.
 *  Pull physics giống block hook nhưng target position động.
 *
 * ═══ Pull physics (khi ATTACHED) ═══
 *  Mỗi tick chạy 3 bước theo thứ tự:
 *
 *  Bước 1 — applyGravityCancel():
 *    Counteract gravity server (-0.08/tick) để player không rơi tự do.
 *    Luôn chạy.
 *
 *  Bước 2a — applyWasdInertia() [khi KHÔNG nhìn về hook]:
 *    Player bấm WASD → thêm lực theo hướng đó trong world-space.
 *
 *  Bước 2b — applyHookPull() [khi nhìn về hook, dot >= LOOK_DOT_THRESHOLD]:
 *    Thêm PULL_FORCE về phía attachPoint.
 *
 *  Bước 3 — applyRopeConstraint():
 *    Pendulum constraint: loại radial velocity khi dây căng quá ROPE_LENGTH.
 */
public class GrapplingHookEntity extends Projectile {

    // ── Synced data ──────────────────────────────────────────────────────────
    private static final EntityDataAccessor<Boolean> DATA_ATTACHED =
            SynchedEntityData.defineId(GrapplingHookEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_TARGET_X =
            SynchedEntityData.defineId(GrapplingHookEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_TARGET_Y =
            SynchedEntityData.defineId(GrapplingHookEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_TARGET_Z =
            SynchedEntityData.defineId(GrapplingHookEntity.class, EntityDataSerializers.FLOAT);
    /** Owner ID — dùng được ở cả client và server (xem ghi chú DATA_OWNER_ID cũ) */
    private static final EntityDataAccessor<Integer> DATA_OWNER_ID =
            SynchedEntityData.defineId(GrapplingHookEntity.class, EntityDataSerializers.INT);
    /**
     * Entity ID của entity đang bị hook bám vào.
     * -1 = không bám entity (bám block hoặc đang bay).
     * Sync server→client để renderer dịch endpoint dây đúng vị trí.
     */
    private static final EntityDataAccessor<Integer> DATA_HOOKED_ENTITY_ID =
            SynchedEntityData.defineId(GrapplingHookEntity.class, EntityDataSerializers.INT);

    // ── Constants ────────────────────────────────────────────────────────────
    private static final float  PULL_FORCE   = GrapplingHookItem.PULL_FORCE;
    private static final float  ROPE_LENGTH  = GrapplingHookItem.ROPE_LENGTH;
    private static final int    MAX_RANGE    = GrapplingHookItem.MAX_RANGE;

    private static final double MAX_PULL_SPEED  = 3.0;
    private static final double MAX_UP_SPEED    = 2.5;
    private static final double ARRIVE_DIST     = 2.0;
    private static final double LOOK_DOT_THRESHOLD = 0.4;
    private static final double ABOVE_THRESHOLD = 0.15;
    private static final double BELOW_THRESHOLD = 0.15;
    private static final double WASD_FORCE      = 0.08;
    private static final int    MAX_LIFETIME    = 50;

    // ── State ─────────────────────────────────────────────────────────────────
    private Vec3    attachPoint    = null;
    private Vec3    targetPos      = null;
    private Vec3    clientLookDir  = null;
    private Vec3    clientInputDir = null;
    private int     lifetimeTicks  = 0;
    private int     settleTick     = 0;
    /** Entity đang bị hook bám vào (server-side reference) */
    private Entity  hookedEntity   = null;

    public GrapplingHookEntity(EntityType<? extends GrapplingHookEntity> type, Level level) {
        super(type, level);
        this.noCulling = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ATTACHED,        false);
        builder.define(DATA_TARGET_X,        Float.MAX_VALUE);
        builder.define(DATA_TARGET_Y,        Float.MAX_VALUE);
        builder.define(DATA_TARGET_Z,        Float.MAX_VALUE);
        builder.define(DATA_OWNER_ID,        -1);
        builder.define(DATA_HOOKED_ENTITY_ID, -1);
    }

    @Override
    public void setOwner(Entity owner) {
        super.setOwner(owner);
        this.entityData.set(DATA_OWNER_ID, owner != null ? owner.getId() : -1);
    }

    public boolean isOwnedBy(Player player) {
        return player != null && this.entityData.get(DATA_OWNER_ID) == player.getId();
    }

    // =========================================================================
    //  TICK
    // =========================================================================

    @Override
    public void tick() {
        super.tick();

        Player owner = getOwnerAsPlayer();
        if (owner == null) { discard(); return; }
        if (!owner.getOffhandItem().is(ModItems.GRAPPLING_HOOK.get())) { discard(); return; }

        lifetimeTicks++;
        if (lifetimeTicks > MAX_LIFETIME) { retract(); return; }

        if (!isAttached()) {
            if (position().distanceTo(owner.position()) > MAX_RANGE) { retract(); return; }
            tickFlying();
        } else {
            tickAttached(owner);
        }
    }

    // ── Phase 1: Bay thẳng đến target ───────────────────────────────────────

    private void tickFlying() {
        Vec3 vel = getDeltaMovement();

        if (targetPos != null) {
            Vec3 toTarget = targetPos.subtract(position());
            if (toTarget.length() < 0.5) { attach(targetPos, null); return; }
            double speed = Math.max(vel.length(), GrapplingHookItem.SHOOT_SPEED);
            vel = toTarget.normalize().scale(speed);
        }

        // Ưu tiên check entity hit trước block hit
        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult ehr) {
            onHitTargetEntity(ehr.getEntity());
            return;
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            onHitBlock((BlockHitResult) hit);
            return;
        }

        setDeltaMovement(vel);
        setPos(getX() + vel.x, getY() + vel.y, getZ() + vel.z);
        updateRotation();
    }

    // ── Bám vào entity ───────────────────────────────────────────────────────

    private void onHitTargetEntity(Entity target) {
        Vec3 point = target.position().add(0, target.getBbHeight() * 0.5, 0);
        hookedEntity = target;
        entityData.set(DATA_HOOKED_ENTITY_ID, target.getId());
        attach(point, target);
    }

    // ── Phase 2: Hook đã bám ────────────────────────────────────────────────

    private void tickAttached(Player player) {
        if (level().isClientSide()) return;

        // Nếu đang hook entity: cập nhật attachPoint theo entity position
        if (hookedEntity != null) {
            if (hookedEntity.isRemoved()) { retract(); return; }
            attachPoint = hookedEntity.position().add(0, hookedEntity.getBbHeight() * 0.5, 0);
            setPos(attachPoint.x, attachPoint.y, attachPoint.z);
        }

        settleTick++;

        double dist = attachPoint.distanceTo(player.position());
        if (dist < ARRIVE_DIST)        { discard(); return; }
        if (dist > ROPE_LENGTH * 1.5)  { retract(); return; }

        applyGravityCancel(player);

        boolean pulling = shouldPull(player);
        if (pulling) {
            applyHookPull(player);
        } else {
            applyWasdInertia(player);
        }

        applyRopeConstraint(player);
    }

    private boolean shouldPull(Player player) {
        if (attachPoint == null) return false;
        Vec3 dirToHook = attachPoint.subtract(player.position()).normalize();
        Vec3 look = clientLookDir != null ? clientLookDir : player.getLookAngle();
        return look.dot(dirToHook) >= LOOK_DOT_THRESHOLD;
    }

    // =========================================================================
    //  PHYSICS
    // =========================================================================

    private void applyGravityCancel(Player player) {
        if (attachPoint == null) return;
        Vec3 vel    = player.getDeltaMovement();
        Vec3 toHook = attachPoint.subtract(player.position()).normalize();
        double dirY = toHook.y;

        double newVy;
        if (dirY > ABOVE_THRESHOLD) {
            newVy = Math.min(vel.y + 0.08, MAX_UP_SPEED);
        } else if (dirY < -BELOW_THRESHOLD) {
            newVy = vel.y + dirY * PULL_FORCE;
            newVy = Math.max(newVy, -MAX_PULL_SPEED);
        } else {
            newVy = vel.y + 0.05;
            newVy = Math.max(newVy, -0.4);
            newVy = Math.min(newVy, MAX_UP_SPEED);
        }

        player.setDeltaMovement(vel.x, newVy, vel.z);
        player.hurtMarked   = true;
        player.fallDistance = 0f;
    }

    private void applyHookPull(Player player) {
        Vec3 toHook = attachPoint.subtract(player.position());
        if (toHook.length() < 0.01) return;

        Vec3   dir       = toHook.normalize();
        Vec3   vel       = player.getDeltaMovement();
        double prevSpeed = vel.length();

        double newVx = vel.x + dir.x * PULL_FORCE;
        double newVz = vel.z + dir.z * PULL_FORCE;
        double newVy = vel.y;
        if (dir.y > ABOVE_THRESHOLD) {
            newVy = vel.y + dir.y * PULL_FORCE;
        }

        double newSpeed = Math.sqrt(newVx * newVx + newVy * newVy + newVz * newVz);
        if (newSpeed > MAX_PULL_SPEED && newSpeed > prevSpeed) {
            double scale = Math.max(prevSpeed, MAX_PULL_SPEED) / newSpeed;
            newVx *= scale; newVy *= scale; newVz *= scale;
        }

        player.setDeltaMovement(newVx, newVy, newVz);
        player.hurtMarked = true;
    }

    private void applyWasdInertia(Player player) {
        if (clientInputDir == null || clientInputDir.lengthSqr() < 0.001) return;

        Vec3 vel   = player.getDeltaMovement();
        double newVx = vel.x + clientInputDir.x * WASD_FORCE;
        double newVz = vel.z + clientInputDir.z * WASD_FORCE;

        double hSpeed = Math.sqrt(newVx * newVx + newVz * newVz);
        if (hSpeed > MAX_PULL_SPEED) {
            double scale = MAX_PULL_SPEED / hSpeed;
            newVx *= scale; newVz *= scale;
        }

        player.setDeltaMovement(newVx, vel.y, newVz);
        player.hurtMarked = true;
    }

    private void applyRopeConstraint(Player player) {
        if (attachPoint == null) return;
        double dist = attachPoint.distanceTo(player.position());
        if (dist <= ROPE_LENGTH) return;

        Vec3 radialDir  = player.position().subtract(attachPoint).normalize();
        Vec3 vel        = player.getDeltaMovement();
        double radialVel = vel.dot(radialDir);

        if (radialVel > 0) {
            vel = vel.subtract(radialDir.scale(radialVel));
            player.setDeltaMovement(vel);
            player.hurtMarked = true;
        }

        double excess = dist - ROPE_LENGTH;
        if (excess > 0.05) {
            Vec3 corrected = player.position().subtract(radialDir.scale(excess * 0.3));
            player.setPos(corrected.x, corrected.y, corrected.z);
        }
    }

    // =========================================================================
    //  HIT DETECTION
    // =========================================================================

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        BlockState state = level().getBlockState(hit.getBlockPos());
        if (!state.isSolid()) { retract(); return; }
        attach(hit.getLocation(), null);
    }

    /**
     * Khi đang fly với targetEntity, canHitEntity trả về true cho entity đó.
     * Ngoài ra, các entity "rác" (ItemEntity, ArmorStand, item frame, xp orb...) đều bị bỏ qua.
     */
    @Override
    protected boolean canHitEntity(Entity entity) {
        if (entity == getOwner()) return false;
        // Bỏ qua các entity không phải target sống
        if (entity instanceof ItemEntity)  return false;
        if (entity instanceof ArmorStand)  return false;
        // Bỏ qua experience orb, item frame, painting, display entity
        String cn = entity.getClass().getSimpleName();
        if (cn.contains("ExperienceOrb") || cn.contains("ItemFrame")
                || cn.contains("Painting")   || cn.contains("Display")
                || cn.contains("Marker"))      return false;

        // targetEntityId được set từ GrapplingShootPacket khi player aim entity
        int targetId = entityData.get(DATA_HOOKED_ENTITY_ID);
        if (targetId < 0) return false; // không có target entity → không hit entity
        return entity.getId() == targetId && entity instanceof LivingEntity;
    }

    private void attach(Vec3 point, Entity entity) {
        attachPoint = point;
        entityData.set(DATA_ATTACHED, true);
        setDeltaMovement(Vec3.ZERO);
        setPos(point.x, point.y, point.z);
        targetPos = null;
        if (entity == null) {
            hookedEntity = null;
            entityData.set(DATA_HOOKED_ENTITY_ID, -1);
        }
        // Nếu entity != null, hookedEntity + DATA_HOOKED_ENTITY_ID đã được set bởi onHitTargetEntity
    }

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    public void setTarget(Vec3 target) {
        this.targetPos = target;
        entityData.set(DATA_TARGET_X, (float) target.x);
        entityData.set(DATA_TARGET_Y, (float) target.y);
        entityData.set(DATA_TARGET_Z, (float) target.z);
    }

    /**
     * Gọi từ GrapplingShootPacket khi player bắn vào entity.
     * Set target entity ID để canHitEntity biết entity nào được phép hit.
     */
    public void setTargetEntityId(int entityId) {
        entityData.set(DATA_HOOKED_ENTITY_ID, entityId);
    }

    public void updateClientInput(Vec3 look, Vec3 input) {
        this.clientLookDir  = look;
        this.clientInputDir = input;
    }

    public void retract() { discard(); }

    public boolean isAttached()        { return entityData.get(DATA_ATTACHED); }
    public Vec3    getAttachPoint()    { return attachPoint; }
    public int     getLifetimeTicks()  { return lifetimeTicks; }
    public int     getSettleTick()     { return settleTick; }
    /** Trả về entity ID đang bị hook, -1 nếu không có. Dùng được cả client. */
    public int     getHookedEntityId() { return entityData.get(DATA_HOOKED_ENTITY_ID); }

    public Vec3 getClientTarget() {
        float tx = entityData.get(DATA_TARGET_X);
        if (tx == Float.MAX_VALUE) return null;
        return new Vec3(tx, entityData.get(DATA_TARGET_Y), entityData.get(DATA_TARGET_Z));
    }

    private Player getOwnerAsPlayer() {
        Entity owner = getOwner();
        return owner instanceof Player p ? p : null;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSq) { return true; }

    // =========================================================================
    //  NBT
    // =========================================================================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Attached", isAttached());
        if (attachPoint != null) {
            tag.putDouble("AttachX", attachPoint.x);
            tag.putDouble("AttachY", attachPoint.y);
            tag.putDouble("AttachZ", attachPoint.z);
        }
        if (hookedEntity != null) tag.putInt("HookedEntityId", hookedEntity.getId());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.getBoolean("Attached")) {
            attachPoint = new Vec3(
                    tag.getDouble("AttachX"),
                    tag.getDouble("AttachY"),
                    tag.getDouble("AttachZ")
            );
            entityData.set(DATA_ATTACHED, true);
        }
    }
}
