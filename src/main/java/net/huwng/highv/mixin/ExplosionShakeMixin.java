package net.huwng.highv.mixin;

import net.huwng.highv.client.camera.ScreenShakeManager;
import net.minecraft.world.level.Explosion;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rung camera khi có vụ nổ gần đó. */
@Mixin(Explosion.class)
public abstract class ExplosionShakeMixin {
    @Shadow @Final private double x;
    @Shadow @Final private double y;
    @Shadow @Final private double z;
    @Shadow @Final private float radius;

    @Inject(method = "finalizeExplosion", at = @At("RETURN"))
    private void highv$onExplosionFinalized(boolean spawnParticles, CallbackInfo ci) {
        double now = System.nanoTime() / 1_000_000_000.0;
        ScreenShakeManager.INSTANCE.addShake(
            ScreenShakeManager.EXPLOSION_TRAUMA,
            radius * 10.0,
            new Vector3d(x, y, z),
            now
        );
    }
}
