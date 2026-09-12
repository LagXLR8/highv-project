package net.huwng.highv.mixin;

import net.huwng.highv.client.camera.CameraFeelState;
import net.huwng.highv.client.camera.ScreenShakeManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rung nhẹ camera + hủy idle-sway khi người chơi vung tay. */
@Mixin(LocalPlayer.class)
public abstract class HandSwingShakeMixin {

    @Shadow public abstract boolean isLocalPlayer();

    @Inject(method = "swing", at = @At("RETURN"))
    private void highv$onSwing(InteractionHand hand, CallbackInfo ci) {
        if (!isLocalPlayer()) return;

        double now = System.nanoTime() / 1_000_000_000.0;
        ScreenShakeManager.INSTANCE.addPositionlessShake(ScreenShakeManager.HAND_SWING_TRAUMA, now);
        CameraFeelState.INSTANCE.notifyPlayerActed();
    }
}
