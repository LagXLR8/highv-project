package net.huwng.highv.mixin;

import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.item.GrapplingHookItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Procedural Arm IK cho cánh tay trái khi sử dụng Grappling Hook.
 *
 * Chèn tại TAIL của setupAnim():
 *  - Đè lên toàn bộ animation di chuyển / chiến đấu (Bhop, Wallstride, Dash, Walking, Idle)
 *    của Player Animator và vanilla trên cánh tay trái.
 *  - Tính toán góc quay 3D chính xác để cánh tay trái giơ nòng súng móc kéo chỉ thẳng
 *    về phía thực thể GrapplingHookEntity (hoặc block / mob bị hook bám vào).
 *  - Sử dụng trọng số aimWeight chuyển tiếp mượt mà (smooth blend) khi bắn và thu hồi dây.
 *  - Tự động đồng bộ góc xoay sang leftSleeve (lớp áo ngoài của skin).
 */
@Mixin(value = PlayerModel.class, priority = 2000)
public abstract class PlayerModelMixin<T extends LivingEntity> extends HumanoidModel<T> {

    @Shadow @Final public ModelPart leftSleeve;

    public PlayerModelMixin(ModelPart root) {
        super(root);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void highv$proceduralGrapplingArm(T entity, float limbSwing, float limbSwingAmount,
                                             float ageInTicks, float netHeadYaw, float headPitch,
                                             CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;

        GrapplingHookEntity hook = GrapplingHookItem.findActiveHook(player.level(), player);

        if (hook == null || hook.isRemoved()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        float pt = (mc.getTimer() != null) ? mc.getTimer().getGameTimeDeltaPartialTick(false) : 0.0f;

        // Tọa độ mục tiêu phía hook (world space, nội suy mượt mà theo pt)
        double targetX = Mth.lerp(pt, hook.xo != 0 ? hook.xo : hook.getX(), hook.getX());
        double targetY = Mth.lerp(pt, hook.yo != 0 ? hook.yo : hook.getY(), hook.getY());
        double targetZ = Mth.lerp(pt, hook.zo != 0 ? hook.zo : hook.getZ(), hook.getZ());

        int hookedId = hook.getHookedEntityId();
        if (hookedId >= 0 && player.level() != null) {
            Entity hookedEnt = player.level().getEntity(hookedId);
            if (hookedEnt != null) {
                targetX = Mth.lerp(pt, hookedEnt.xo != 0 ? hookedEnt.xo : hookedEnt.getX(), hookedEnt.getX());
                targetY = Mth.lerp(pt, hookedEnt.yo != 0 ? hookedEnt.yo : hookedEnt.getY(), hookedEnt.getY()) + hookedEnt.getBbHeight() * 0.5;
                targetZ = Mth.lerp(pt, hookedEnt.zo != 0 ? hookedEnt.zo : hookedEnt.getZ(), hookedEnt.getZ());
            }
        }

        // Tọa độ khớp vai trái của người chơi trong world space
        double px = Mth.lerp(pt, player.xo != 0 ? player.xo : player.getX(), player.getX());
        double py = Mth.lerp(pt, player.yo != 0 ? player.yo : player.getY(), player.getY());
        double pz = Mth.lerp(pt, player.zo != 0 ? player.zo : player.getZ(), player.getZ());

        boolean isLocalFpp = (player == mc.player && mc.options.getCameraType().isFirstPerson());
        float bodyYaw = isLocalFpp ? player.getViewYRot(pt) : Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot);
        double bodyYawRad = Math.toRadians(bodyYaw);

        double cosYaw = Math.cos(bodyYawRad);
        double sinYaw = Math.sin(bodyYawRad);

        // Khớp vai trái nằm ở phía bên trái cơ thể (+Left vector * 0.3125)
        // Trong hệ tọa độ Minecraft: Left vector khi yaw=0 (nhìn Nam +Z) là (+X, 0, 0) = (cosYaw, 0, sinYaw)
        double shSide = 0.3125;
        double shHeight = player.isCrouching() ? 1.15 : 1.375;
        double fwdDist = isLocalFpp ? 0.22 : 0.0;
        double upOffset = isLocalFpp ? 0.10 : 0.0;

        double fwdX = -sinYaw * fwdDist;
        double fwdZ =  cosYaw * fwdDist;

        double shX = px + cosYaw * shSide + fwdX;
        double shY = py + shHeight + upOffset;
        double shZ = pz + sinYaw * shSide + fwdZ;

        // Vector từ khớp vai trái tới hook trong world space
        double dx = targetX - shX;
        double dy = targetY - shY;
        double dz = targetZ - shZ;

        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.05) return;

        Vec3 vel = hook.getDeltaMovement();
        double velLen = vel.length();
        Vec3 shootDir = (velLen > 0.1) ? vel.scale(1.0 / velLen) : player.getLookAngle();

        // Khi hook vừa bắn (< 3.0m) và chưa bám, hướng ngắm cánh tay bám theo hướng bắn shootDir
        // để không bị giật ngang 90 độ sang phải (do hook vừa sinh ra tại eyePos ở giữa đầu)
        if (!hook.isAttached() && dist < 3.0) {
            double blend = Math.max(0.0, Math.min(1.0, (dist - 0.4) / 2.6));
            dx = Mth.lerp(blend, shootDir.x, dx / dist);
            dy = Mth.lerp(blend, shootDir.y, dy / dist);
            dz = Mth.lerp(blend, shootDir.z, dz / dist);
            double newDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (newDist > 1e-4) {
                dx /= newDist;
                dy /= newDist;
                dz /= newDist;
            }
        } else {
            dx /= dist;
            dy /= dist;
            dz /= dist;
        }

        // Chuyển đổi vector hướng sang hệ tọa độ của cơ thể người chơi (Body Local Space)
        // Forward: (-sinYaw, 0, cosYaw)
        // Left:    ( cosYaw, 0, sinYaw)
        double localFwd  = -dx * sinYaw + dz * cosYaw;
        double localLeft =  dx * cosYaw + dz * sinYaw;
        double localUp   =  dy;

        // 1. Góc Pitch (nâng/hạ cánh tay)
        double horizDist = Math.sqrt(localFwd * localFwd + localLeft * localLeft);
        float elevation = (float) Math.atan2(localUp, Math.max(1e-4, horizDist));
        // Cánh tay duỗi ngang thẳng tới trước là -PI/2; ngước lên thì trừ thêm elevation
        float targetXRot = -(float) (Math.PI * 0.5) - elevation;

        // 2. Góc Yaw (xoay ngang cánh tay theo hướng hook)
        // Lưu ý: với cánh tay trái trong ModelPart, xoay sang trái (localLeft > 0) là góc yRot âm
        float targetYRot = -(float) Math.atan2(localLeft, Math.max(1e-4, localFwd));

        // 3. Góc Roll nhẹ (Z rotation) giúp khớp vai không bị xoắn gập méo mó
        float targetZRot = (float) Mth.clamp(-localLeft * 0.15, -0.3, 0.3);

        // Áp dụng góc xoay IK đè lên animation hiện tại
        this.leftArm.xRot = targetXRot;
        this.leftArm.yRot = targetYRot;
        this.leftArm.zRot = targetZRot;

        // Đồng bộ toàn bộ transform sang lớp áo ngoài (leftSleeve)
        this.leftSleeve.copyFrom(this.leftArm);
    }
}
