package net.huwng.highv.client.wind;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.huwng.highv.HighV;
import net.huwng.highv.client.SpeedEffectSystem;
import net.huwng.highv.client.animation.DashAnimationHandler;
import net.huwng.highv.client.animation.DriftAnimationHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Hiệu ứng vệt gió động học (Dynamic Wind Particle Streaks) trên màn hình người chơi.
 *
 * Lấy cảm hứng từ dynamic-wind-particles nhưng cải tiến vượt trội:
 *  - Đổi chiều luồng gió xé (Wind Vector) 100% THEO HƯỚNG ĐỘNG NĂNG THỰC TẾ (Kinetic Momentum):
 *      + Strafe / Bhop sang Phải: Động năng dạt sang Phải -> Gió xé tạt mạnh từ Phải sang Trái trên màn hình.
 *      + Strafe / Bhop sang Trái: Động năng dạt sang Trái -> Gió xé tạt mạnh từ Trái sang Phải trên màn hình.
 *      + Slide Jump / Bay vọt lên: Động năng hướng Lên -> Gió xé lao thẳng từ Trên chúc xuống Dưới.
 *      + Rơi tự do / Bổ nhào: Động năng hướng Xuống -> Gió xé bốc ngược từ Dưới lên Trên.
 *      + Dash lùi / Trượt lùi: Động năng lùi ra Sau -> Gió xé thổi từ Sau phóng vượt qua camera.
 *      + Chạy thẳng: Gió xé lao thẳng từ tâm tỏa ra hai bên ngoại vi màn hình.
 *  - Texture wind_streak.png khí động học mềm mại, trong suốt tự nhiên, không hạt vuông rời rạc.
 *  - Billboard Ribbon Math: Các mặt quad luôn xoay vuông góc với góc nhìn camera (Cross-Product).
 *  - Nội suy làm mượt (Vector Slerp): Hướng gió chuyển hướng mềm mại khi người chơi đổi hướng hoặc vung chuột.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class DynamicWindStreakRenderer {

    private static final ResourceLocation WIND_TEXTURE = HighV.id("textures/particle/wind_streak.png");

    private static final int MAX_PARTICLES = 70;
    private static final double MIN_SPEED_BS = 15.0;

    /** Pool đối tượng particle để tránh cấp phát bộ nhớ rác (GC pressure). */
    private static class Streak {
        boolean active;
        double x, y, z;          // Tọa độ thế giới thực
        double vx, vy, vz;       // Vận tốc hạt
        double dirX, dirY, dirZ; // Vector hướng kéo dài của vệt gió
        float age;               // Tuổi thọ hiện tại (giây)
        float lifetime;          // Tổng thời gian sống (giây)
        float length;            // Chiều dài vệt gió (m)
        float size;              // Chiều rộng vệt gió (m)
        float maxAlpha;          // Độ mờ tối đa
    }

    private static final List<Streak> pool = new ArrayList<>(MAX_PARTICLES);
    static {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            pool.add(new Streak());
        }
    }

    private static long lastFrameNanos = 0;
    private static float spawnAccumulator = 0f;
    private static Vec3 smoothMotionDir = new Vec3(0, 0, 1);
    private static Vec3 prevPlayerPos = null;
    private static boolean wasDashingLastFrame = false;

    private DynamicWindStreakRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !net.huwng.highv.client.HighVClientConfig.ENABLE_WIND_STREAKS.get()) {
            clearAll();
            return;
        }

        long nowNanos = System.nanoTime();
        if (lastFrameNanos == 0) {
            lastFrameNanos = nowNanos;
            return;
        }
        float dt = (float) ((nowNanos - lastFrameNanos) / 1.0e9);
        lastFrameNanos = nowNanos;
        // Giới hạn dt để tránh nhảy vọt khung hình khi giật lag
        dt = Math.min(0.06f, Math.max(0.001f, dt));

        Player player = mc.player;
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();

        // Kiểm tra dịch chuyển tức thời (teleport)
        if (prevPlayerPos != null && player.position().distanceToSqr(prevPlayerPos) > 64.0) {
            clearAll();
        }
        prevPlayerPos = player.position();

        // 1. Cập nhật động năng và hướng di chuyển thực tế
        updateMotionAndSpawn(player, cam, camPos, dt);

        // 2. Cập nhật vị trí và thời gian sống các hạt
        updateParticles(camPos, dt);

        // 3. Render các vệt gió lên màn hình
        renderParticles(event.getPoseStack(), camPos, cam);
    }

    /**
     * Tính toán vector động năng thực tế (Kinetic Momentum) và phát sinh vệt gió theo hướng đó.
     */
    private static void updateMotionAndSpawn(Player player, Camera cam, Vec3 camPos, float dt) {
        if (player.isSpectator()) return;

        Vec3 vel = player.getDeltaMovement();
        if (player.getVehicle() != null) {
            vel = player.getVehicle().getDeltaMovement();
        }
        double speedBs = vel.length() * 20.0;
        if (SpeedEffectSystem.rawSpeed > speedBs) {
            speedBs = SpeedEffectSystem.rawSpeed;
        }

        boolean isDashing = DashAnimationHandler.isActive(player.getUUID());
        boolean isDrifting = DriftAnimationHandler.isSyncedSliding(player.getUUID());

        // Hướng động năng di chuyển mục tiêu
        Vec3 targetDir;
        if (vel.lengthSqr() > 1e-4) {
            targetDir = vel.normalize();
        } else {
            // Khi đứng yên hoặc trôi rất chậm, lấy theo hướng nhìn của người chơi
            targetDir = player.getViewVector(1.0f);
        }

        // Nội suy mượt mà hướng động năng (giúp vệt gió quét mượt khi bẻ lái / air-strafe)
        double lerpFactor = isDashing ? 0.40 : 0.20;
        smoothMotionDir = smoothMotionDir.add(targetDir.subtract(smoothMotionDir).scale(lerpFactor));
        if (smoothMotionDir.lengthSqr() > 1e-5) {
            smoothMotionDir = smoothMotionDir.normalize();
        } else {
            smoothMotionDir = targetDir;
        }

        // Chỉ xuất hiện khi tốc độ > 15 m/s (15 b/s)
        boolean active = speedBs > MIN_SPEED_BS;
        if (!active) {
            wasDashingLastFrame = isDashing;
            return;
        }

        // Xung lực Dash: Khi vừa kích hoạt dash và tốc độ > 15m/s, bắn tung tóe ngay lập tức 1 luồng vệt gió đậm
        if (isDashing && !wasDashingLastFrame) {
            spawnBurst(cam, camPos, smoothMotionDir, 14);
        }
        wasDashingLastFrame = isDashing;

        // Tốc độ sinh hạt tỷ lệ thuận với vận tốc vượt ngưỡng
        double speedRatio = Math.min(1.0, Math.max(0.0, (speedBs - MIN_SPEED_BS) / 25.0));
        float spawnRate = (float) (14.0 + speedRatio * 42.0); // 14 -> 56 hạt/giây
        if (isDashing) spawnRate *= 1.45f;

        spawnAccumulator += dt * spawnRate;
        while (spawnAccumulator >= 1.0f) {
            spawnAccumulator -= 1.0f;
            spawnSingleStreak(cam, camPos, smoothMotionDir, speedBs, speedRatio, isDashing);
        }
    }

    /**
     * Sinh một vệt gió duy nhất đón đầu luồng khí động học theo hướng động năng.
     */
    private static void spawnSingleStreak(Camera cam, Vec3 camPos, Vec3 motionDir, double speedBs, double speedRatio, boolean isDashing) {
        Streak s = findFreeStreak();
        if (s == null) return;

        // Thiết lập hệ tọa độ trực chuẩn (Orthonormal Basis) vuông góc với hướng di chuyển
        Vec3 upRef = Math.abs(motionDir.y) < 0.92 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 basisU = motionDir.cross(upRef).normalize();
        Vec3 basisV = motionDir.cross(basisU).normalize();

        // Bán kính phân bổ xung quanh góc nhìn của người chơi
        double angle = Math.random() * Math.PI * 2.0;
        boolean isFirstPerson = !cam.isDetached();
        double minRadius = isFirstPerson ? 0.35 : 0.65;
        double maxRadius = isFirstPerson ? 2.10 : 2.80;
        double radius = minRadius + Math.random() * (maxRadius - minRadius);

        Vec3 radialOffset = basisU.scale(Math.cos(angle) * radius).add(basisV.scale(Math.sin(angle) * radius));

        // Vị trí sinh hạt: Sinh đón đầu phía trước hướng người chơi đang trôi tới
        double spawnDistAhead = 1.2 + Math.random() * (isDashing ? 4.5 : 3.6);
        Vec3 spawnPos = camPos.add(motionDir.scale(spawnDistAhead)).add(radialOffset);

        // Vận tốc hạt: Thổi NGƯỢC CHIỀU với hướng động năng (gió tạt ngược chiều di chuyển)
        double relativeSpeed = (speedBs * 0.60) + 7.5 + Math.random() * 4.0;
        Vec3 particleVel = motionDir.scale(-relativeSpeed);

        s.active = true;
        s.x = spawnPos.x;
        s.y = spawnPos.y;
        s.z = spawnPos.z;
        s.vx = particleVel.x;
        s.vy = particleVel.y;
        s.vz = particleVel.z;

        // Hướng kéo dài của vệt gió: Trùng với trục động năng motionDir
        s.dirX = motionDir.x;
        s.dirY = motionDir.y;
        s.dirZ = motionDir.z;

        s.age = 0f;
        s.lifetime = (float) (0.16 + Math.random() * (isDashing ? 0.12 : 0.16)); // 160ms - 320ms
        s.length = (float) (0.75 + speedRatio * 1.15 + (isDashing ? 0.6 : 0.0));
        s.size = (float) (0.040 + speedRatio * 0.030); // 0.04m -> 0.07m
        s.maxAlpha = (float) (0.24 + speedRatio * 0.16 + (isDashing ? 0.12 : 0.0)); // 0.24 -> 0.45
    }

    /**
     * Bắn tung một đợt vệt gió tức thời (ví dụ khi Dash).
     */
    private static void spawnBurst(Camera cam, Vec3 camPos, Vec3 motionDir, int count) {
        for (int i = 0; i < count; i++) {
            spawnSingleStreak(cam, camPos, motionDir, 32.0, 1.0, true);
        }
    }

    /**
     * Cập nhật vị trí bay và tuổi thọ của tất cả các hạt gió.
     */
    private static void updateParticles(Vec3 camPos, float dt) {
        for (Streak s : pool) {
            if (!s.active) continue;

            s.age += dt;
            if (s.age >= s.lifetime) {
                s.active = false;
                continue;
            }

            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.z += s.vz * dt;

            // Xóa hạt nếu bay quá xa camera (> 18 blocks)
            double dx = s.x - camPos.x;
            double dy = s.y - camPos.y;
            double dz = s.z - camPos.z;
            if ((dx * dx + dy * dy + dz * dz) > 324.0) {
                s.active = false;
            }
        }
    }

    /**
     * Render các vệt gió dạng Textured Billboard Quads hướng vuông góc với camera.
     */
    private static void renderParticles(PoseStack pose, Vec3 camPos, Camera cam) {
        boolean hasActive = false;
        for (Streak s : pool) {
            if (s.active) {
                hasActive = true;
                break;
            }
        }
        if (!hasActive) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, WIND_TEXTURE);

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        Matrix4f mat = pose.last().pose();

        for (Streak s : pool) {
            if (!s.active) continue;

            // Tính Alpha đường cong hình chuông mềm mại: Fade in lúc mới sinh và Fade out lúc kết thúc
            float progress = s.age / s.lifetime;
            float alphaFactor = (float) Math.sin(progress * Math.PI);
            int alpha = (int) (alphaFactor * s.maxAlpha * 255.0f);
            if (alpha <= 2) continue;

            // Tọa độ hạt tương đối so với camera
            double relX = s.x - camPos.x;
            double relY = s.y - camPos.y;
            double relZ = s.z - camPos.z;

            // Vector nhìn từ camera tới hạt
            double toCamX = -relX;
            double toCamY = -relY;
            double toCamZ = -relZ;
            double toCamLen = Math.sqrt(toCamX * toCamX + toCamY * toCamY + toCamZ * toCamZ);
            if (toCamLen > 1e-4) {
                toCamX /= toCamLen;
                toCamY /= toCamLen;
                toCamZ /= toCamLen;
            } else {
                toCamY = 1.0;
            }

            // Vector pháp tuyến ngang (Side vector) = Hướng vệt gió x Vector nhìn camera
            double sideX = s.dirY * toCamZ - s.dirZ * toCamY;
            double sideY = s.dirZ * toCamX - s.dirX * toCamZ;
            double sideZ = s.dirX * toCamY - s.dirY * toCamX;
            double sideLen = Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);

            if (sideLen > 1e-4) {
                sideX /= sideLen;
                sideY /= sideLen;
                sideZ /= sideLen;
            } else {
                // Fallback nếu hướng nhìn trùng khít với hướng vệt gió
                sideX = 0.0;
                sideY = 1.0;
                sideZ = 0.0;
            }

            // Bán kính chiều dài và chiều rộng vệt gió
            double halfLen = s.length * 0.5;
            double halfWid = s.size * 0.5;

            double hLx = s.dirX * halfLen;
            double hLy = s.dirY * halfLen;
            double hLz = s.dirZ * halfLen;

            double hWx = sideX * halfWid;
            double hWy = sideY * halfWid;
            double hWz = sideZ * halfWid;

            // 4 đỉnh Quad (Trắng tinh khiết tự nhiên 255, 255, 255)
            // Đỉnh 1: Đầu + Cánh Phải (UV 1, 0)
            bb.addVertex(mat, (float) (relX + hLx + hWx), (float) (relY + hLy + hWy), (float) (relZ + hLz + hWz))
                    .setUv(1.0f, 0.0f).setColor(255, 255, 255, alpha);

            // Đỉnh 2: Đuôi + Cánh Phải (UV 0, 0)
            bb.addVertex(mat, (float) (relX - hLx + hWx), (float) (relY - hLy + hWy), (float) (relZ - hLz + hWz))
                    .setUv(0.0f, 0.0f).setColor(255, 255, 255, alpha);

            // Đỉnh 3: Đuôi + Cánh Trái (UV 0, 1)
            bb.addVertex(mat, (float) (relX - hLx - hWx), (float) (relY - hLy - hWy), (float) (relZ - hLz - hWz))
                    .setUv(0.0f, 1.0f).setColor(255, 255, 255, alpha);

            // Đỉnh 4: Đầu + Cánh Trái (UV 1, 1)
            bb.addVertex(mat, (float) (relX + hLx - hWx), (float) (relY + hLy - hWy), (float) (relZ + hLz - hWz))
                    .setUv(1.0f, 1.0f).setColor(255, 255, 255, alpha);
        }

        MeshData mesh = bb.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static Streak findFreeStreak() {
        for (Streak s : pool) {
            if (!s.active) return s;
        }
        return null;
    }

    private static void clearAll() {
        for (Streak s : pool) {
            s.active = false;
        }
        spawnAccumulator = 0f;
        lastFrameNanos = 0;
    }
}
