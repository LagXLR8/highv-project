package net.huwng.highv.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks player speed mỗi client tick.
 * speed = horizontal distance/tick * 20 = blocks/second.
 *
 * Thresholds:
 *  LOW   >=  6 b/s → bắt đầu hiệu ứng
 *  HIGH  >= 20 b/s → full intensity
 */
public final class SpeedEffectSystem {

    public static final double SPEED_LOW  =  6.0;
    public static final double SPEED_HIGH = 70.0;

    /** Raw horizontal b/s, updated mỗi tick. */
    public static double rawSpeed      = 0.0;
    /** Smoothed speed dùng cho render. */
    public static double smoothedSpeed = 0.0;
    /** 0..1 intensity từ smoothedSpeed. */
    public static double intensity     = 0.0;

    // Camera shake state (noise via sin harmonics)
    public static double shakeOffsetX = 0.0;
    public static double shakeOffsetY = 0.0;
    private static double shakePhase  = 0.0;

    private static Vec3 prevPos = null;

    private SpeedEffectSystem() {}

    public static void tick() {
        Minecraft mc     = Minecraft.getInstance();
        Player    player = mc.player;

        if (player == null) {
            rawSpeed = smoothedSpeed = intensity = 0.0;
            prevPos  = null;
            shakeOffsetX = shakeOffsetY = 0.0;
            return;
        }

        Vec3 pos = player.position();
        if (prevPos != null) {
            double dx = pos.x - prevPos.x;
            double dz = pos.z - prevPos.z;
            rawSpeed = Math.sqrt(dx * dx + dz * dz) * 20.0;
        } else {
            rawSpeed = 0.0;
        }
        prevPos = pos;

        // Bổ sung vận tốc tức thời để bắt kịp ngay các cú dash, slide, grappling hook không bị trễ
        Vec3 move = player.getDeltaMovement();
        double instantSpeed = Math.sqrt(move.x * move.x + move.z * move.z) * 20.0;
        rawSpeed = Math.max(rawSpeed, instantSpeed);

        // Exponential smoothing: tăng nhanh, giảm chậm
        double alpha = rawSpeed > smoothedSpeed ? 0.35 : 0.12;
        smoothedSpeed += alpha * (rawSpeed - smoothedSpeed);

        // Intensity 0..1
        if (smoothedSpeed <= SPEED_LOW)  intensity = 0.0;
        else if (smoothedSpeed >= SPEED_HIGH) intensity = 1.0;
        else intensity = (smoothedSpeed - SPEED_LOW) / (SPEED_HIGH - SPEED_LOW);

        // Camera shake: noise-based, quadratic intensity
        if (intensity > 0.05) {
            shakePhase += 0.15 * (1.0 + intensity * 3.0);
            double mag = intensity * intensity * 1.8;
            shakeOffsetX = (Math.sin(shakePhase * 1.7)  * 0.6 +
                            Math.sin(shakePhase * 3.1)  * 0.3 +
                            Math.sin(shakePhase * 5.3)  * 0.1) * mag;
            shakeOffsetY = (Math.sin(shakePhase * 1.3 + 1.0) * 0.6 +
                            Math.sin(shakePhase * 2.9 + 0.5) * 0.3 +
                            Math.sin(shakePhase * 4.7 + 2.0) * 0.1) * mag;
        } else {
            shakeOffsetX *= 0.85;
            shakeOffsetY *= 0.85;
        }

        SpeedLinesShader.tick();
    }

    public static String getSpeedString() {
        return String.format("%.1f b/s", smoothedSpeed);
    }

    public static int getSpeedPercent() {
        return (int) Math.min(100, smoothedSpeed / SPEED_HIGH * 100.0);
    }
}
