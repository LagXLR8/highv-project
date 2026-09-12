package net.huwng.highv.mixin;

import net.huwng.highv.client.camera.ScreenShakeManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rung camera khi sét đánh gần đó (một cú rung mạnh gần, một tiếng "sấm" rền xa hơn). */
@Mixin(LightningBolt.class)
public abstract class ThunderShakeMixin {

    @Inject(method = "spawnFire", at = @At("RETURN"))
    private void highv$onLightningStrike(int fireCount, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Vec3 pos = self.position();
        Vector3d jomlPos = new Vector3d(pos.x, pos.y, pos.z);
        double now = System.nanoTime() / 1_000_000_000.0;

        ScreenShakeManager.INSTANCE.addShake(ScreenShakeManager.EXPLOSION_TRAUMA, 16.0, jomlPos, now);
        ScreenShakeManager.INSTANCE.addShake(ScreenShakeManager.THUNDER_TRAUMA, 192.0, jomlPos, now);
    }
}
