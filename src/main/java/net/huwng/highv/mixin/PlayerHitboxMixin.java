package net.huwng.highv.mixin;

import net.huwng.highv.client.animation.DriftAnimationHandler;
import net.huwng.highv.event.DriftServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hitbox RIÊNG cho Drift (slide) — không mượn dimensions của bất kỳ Pose có
 * sẵn nào (trước đây mượn tạm Pose.SWIMMING, xem lịch sử ở doc comment
 * DriftServerHandler/DriftStateSyncPacket).
 *
 * Chèn thẳng vào {@code Player#getDefaultDimensions(Pose)} — KHÔNG PHẢI
 * {@code getDimensions(Pose)}. Từ 1.21, {@code LivingEntity#getDimensions}
 * đã là {@code final} (tự gọi getDefaultDimensions() rồi nhân thêm
 * attribute "scale" lên trên); Player override getDefaultDimensions() để
 * cung cấp bảng theo Pose, KHÔNG tự override getDimensions() nữa — thử
 * @Inject vào "getDimensions" ban đầu bị crash ngay lúc load class vì
 * Player.class không có method đó (chỉ kế thừa bản final của LivingEntity).
 * getDefaultDimensions() vẫn là nguồn quyết định cả bounding box (qua
 * refreshDimensions()) VÀ eye height (qua EntityDimensions#eyeHeight()),
 * nên chỉ cần override đúng 1 chỗ này là đủ, không cần thêm mixin riêng cho
 * eye height.
 *
 * Pose của player KHÔNG bị đụng vào trong lúc trượt (khác hẳn cách cũ) — vì
 * vậy không còn tranh chấp với Player.updatePlayerPose() (vanilla tự đánh
 * giá lại Pose mỗi tick dựa trên isVisuallySwimming()/isCrouching()/...).
 * Dimensions trả về chỉ phụ thuộc vào tín hiệu "đang trượt" (server: xem
 * DriftServerHandler.isSliding(); client: xem
 * DriftAnimationHandler.isSyncedSliding()), không phụ thuộc Pose truyền vào.
 *
 * Áp dụng cho MỌI subclass của Player (ServerPlayer, LocalPlayer,
 * RemoteClientPlayer, ...) vì các lớp đó không tự override lại
 * getDefaultDimensions(Pose) — mixin chèn vào thân method gốc trong Player
 * là đủ.
 */
@Mixin(Player.class)
public abstract class PlayerHitboxMixin {

    /**
     * Kích thước hitbox lúc trượt. Tự chọn số đo — không bắt buộc phải
     * giống bất kỳ Pose vanilla nào. Chỉnh 2 số này nếu cần khớp animation
     * slide.json hơn (rộng, cao) tính bằng block.
     */
    private static final EntityDimensions HIGHV_DRIFT_DIMENSIONS = EntityDimensions.scalable(0.6F, 0.4F);

    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    private void highv$driftDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        Player self = (Player) (Object) this;

        boolean sliding = self instanceof ServerPlayer serverPlayer
                ? DriftServerHandler.isSliding(serverPlayer)
                : DriftAnimationHandler.isSyncedSliding(self.getUUID());

        if (sliding) {
            cir.setReturnValue(HIGHV_DRIFT_DIMENSIONS);
        }
    }
}
