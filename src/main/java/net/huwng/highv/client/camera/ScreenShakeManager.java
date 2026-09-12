package net.huwng.highv.client.camera;

import org.joml.SimplexNoise;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Quản lý các "cú rung" camera dạng trauma (giảm dần theo bình phương thời
 * gian còn lại), tùy chọn giới hạn theo bán kính quanh một vị trí (vd. tâm
 * vụ nổ). Gọi {@link #addShake} để bơm một cú rung, gọi
 * {@link #resolve(Vector3d, double)} mỗi frame để lấy offset hiện tại.
 */
public final class ScreenShakeManager {
    public static final ScreenShakeManager INSTANCE = new ScreenShakeManager();

    private ScreenShakeManager() {}

    private static final double MAX_INTENSITY = 2.5;
    private static final double MAX_FREQUENCY = 6.0;

    public static final double EXPLOSION_TRAUMA = 1.0;
    public static final double THUNDER_TRAUMA = 0.05;
    public static final double HAND_SWING_TRAUMA = 0.03;

    private static final class Shake {
        double trauma;
        double radius;
        double frequency = 1.0;
        double durationSeconds = 2.0;
        Vector3d position; // null = không giới hạn theo vị trí
        double startTime;
    }

    private final List<Shake> active = new ArrayList<>();
    private final Vector3d resolvedOffset = new Vector3d();
    private double clock = 0;

    public void addShake(double trauma, double radius, Vector3d position, double now) {
        Shake shake = new Shake();
        shake.trauma = trauma;
        shake.radius = radius;
        shake.position = position;
        shake.startTime = now;
        active.add(shake);
    }

    public void addPositionlessShake(double trauma, double now) {
        addShake(trauma, Double.POSITIVE_INFINITY, null, now);
    }

    /** Tính lại offset hiện tại từ toàn bộ shake đang hoạt động, dọn shake đã hết hạn. */
    public Vector3d resolve(Vector3d cameraPosition, double now) {
        clock = now;
        resolvedOffset.set(0, 0, 0);
        if (!net.huwng.highv.client.HighVClientConfig.ENABLE_SCREEN_SHAKE.get()) {
            active.clear();
            return resolvedOffset;
        }
        double totalIntensity = 0;
        float sampleBase = (float) (now * MAX_FREQUENCY);

        Iterator<Shake> it = active.iterator();
        while (it.hasNext()) {
            Shake shake = it.next();
            double progress = shake.durationSeconds > 0
                ? CameraFeelMath.clamp01((now - shake.startTime) / shake.durationSeconds)
                : 1.0;

            if (progress >= 1.0) {
                it.remove();
                continue;
            }

            double decay = 1.0 - progress;
            double intensity = CameraFeelMath.clamp01(shake.trauma) * (decay * decay);

            if (shake.position != null) {
                double distance = cameraPosition.distance(shake.position);
                double distanceFactor = 1.0 - Math.min(1.0, distance / shake.radius);
                intensity *= distanceFactor * distanceFactor;
            }

            if (intensity <= 0 || !Double.isFinite(intensity)) continue;

            float sample = (float) (sampleBase * shake.frequency);
            resolvedOffset.add(
                SimplexNoise.noise(sample, -69) * intensity,
                SimplexNoise.noise(sample, -420) * intensity,
                SimplexNoise.noise(sample, -1337) * intensity
            );
            totalIntensity += intensity;
        }

        if (totalIntensity > 1.0) resolvedOffset.div(totalIntensity);

        resolvedOffset.mul(MAX_INTENSITY);
        return resolvedOffset;
    }
}
