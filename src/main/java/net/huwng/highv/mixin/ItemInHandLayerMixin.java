package net.huwng.highv.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.huwng.highv.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ngăn chặn Better Combat (hoặc các layer renderer khác) tự ý render thêm một
 * thiết bị Grappling Hook thứ hai ở tay trái của người chơi ở góc nhìn thứ nhất (FPP)
 * khi đang chém vũ khí (như Thermal Katana).
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void highv$suppressFirstPersonOffhandHook(
            LivingEntity entity, ItemStack stack, ItemDisplayContext displayContext,
            HumanoidArm arm, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
            CallbackInfo ci) {
        if (arm == HumanoidArm.LEFT && stack.is(ModItems.GRAPPLING_HOOK.get())) {
            Minecraft mc = Minecraft.getInstance();
            if (entity == mc.player && mc.options.getCameraType().isFirstPerson()) {
                ci.cancel();
            }
        }
    }
}
