package net.huwng.highv.client;

import net.huwng.highv.HighV;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/**
 * Quản lý post-processing shader speed lines — NeoForge 1.21.1.
 *
 * Cách tiếp cận giống animespeedlines (Fabric/Satin) nhưng dùng PostChain vanilla:
 *   • load()   — tạo PostChain từ assets/highv/shaders/post/speed_lines.json
 *   • render() — inject uniforms rồi gọi postChain.process()
 *   • tick()   — cập nhật STime counter
 *
 * PostChain.passes là private → dùng reflection một lần lúc load() để cache danh sách.
 */
public final class SpeedLinesShader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpeedLinesShader.class);

    private static final ResourceLocation EFFECT_ID =
            HighV.id("shaders/post/speed_lines.json");

    private static PostChain      postChain    = null;
    private static List<PostPass> cachedPasses = Collections.emptyList();
    private static int            ticks        = 0;

    private SpeedLinesShader() {}

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Load hoặc reload PostChain. Gọi sau resource reload. */
    public static void load() {
        close();
        Minecraft mc = Minecraft.getInstance();
        try {
            postChain = new PostChain(
                    mc.getTextureManager(),
                    mc.getResourceManager(),
                    mc.getMainRenderTarget(),
                    EFFECT_ID
            );
            postChain.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            cachedPasses = reflectPasses(postChain);
            LOGGER.info("[HighV] speed_lines shader loaded ({} passes)", cachedPasses.size());
        } catch (IOException e) {
            LOGGER.error("[HighV] Cannot load speed_lines shader: {}", e.getMessage());
            postChain    = null;
            cachedPasses = Collections.emptyList();
        }
    }

    /** Giải phóng tài nguyên GPU. */
    public static void close() {
        if (postChain != null) {
            postChain.close();
            postChain = null;
        }
        cachedPasses = Collections.emptyList();
    }

    /** Gọi mỗi client tick để STime uniform chạy đúng. */
    public static void tick() { ticks++; }

    /** Gọi khi window resize. */
    public static void resize(int width, int height) {
        if (postChain != null) postChain.resize(width, height);
    }

    // ── Render ───────────────────────────────────────────────────────────────

    /**
     * Chạy shader pipeline.
     * Gọi trong RenderLevelStageEvent.Stage.AFTER_LEVEL (xem SpeedLinesRenderer).
     */
    public static void render(float partialTick) {
        if (postChain == null || cachedPasses.isEmpty()) return;
        if (SpeedEffectSystem.intensity <= 0.005) return;

        Minecraft mc = Minecraft.getInstance();

        // ── BiasAngle / BiasWeight: hướng velocity projected lên screen ──────
        float biasAngle  = 0f;
        float biasWeight = 0f;
        if (mc.player != null) {
            var velocity = mc.player.getDeltaMovement();
            var vehicle  = mc.player.getVehicle();
            if (vehicle != null) velocity = vehicle.getDeltaMovement();

            if (velocity.lengthSqr() > 1e-6) {
                var eye     = mc.player.getLookAngle();
                var worldUp = new net.minecraft.world.phys.Vec3(0, 1, 0);
                var right   = worldUp.cross(eye).normalize();
                var vel     = velocity.normalize();
                double vDotEye = vel.dot(eye);
                var proj = vel.subtract(eye.scale(vDotEye));
                if (proj.length() > 1e-6) {
                    proj = proj.normalize();
                    biasAngle  = (float) Math.atan2(proj.cross(right).dot(eye), right.dot(proj));
                    biasWeight = (float)(1.0 - Math.abs(vDotEye));
                }
            }
        }

        // ── Inject uniforms vào mỗi pass ─────────────────────────────────────
        float sTime  = (ticks + partialTick) / 20f;
        float weight = (float) SpeedEffectSystem.intensity;

        for (PostPass pass : cachedPasses) {
            var effect = pass.getEffect();
            if (effect == null) continue;
            setUniform(effect, "STime",      sTime);
            setUniform(effect, "Weight",     weight);
            setUniform(effect, "BiasAngle",  biasAngle);
            setUniform(effect, "BiasWeight", biasWeight);
        }

        // ── Chạy pipeline ─────────────────────────────────────────────────────
        var main = mc.getMainRenderTarget();
        main.unbindWrite();
        postChain.process(partialTick);
        main.bindWrite(false);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void setUniform(net.minecraft.client.renderer.EffectInstance effect,
                                    String name, float value) {
        var u = effect.getUniform(name);
        if (u != null) u.set(value);
    }

    /**
     * Reflection một lần để lấy List<PostPass> từ PostChain private field.
     * Quét tất cả List-type fields → chọn field đầu tiên (luôn là "passes").
     */
    @SuppressWarnings("unchecked")
    private static List<PostPass> reflectPasses(PostChain chain) {
        for (Field f : PostChain.class.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object val = f.get(chain);
                if (val instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof PostPass) {
                    LOGGER.debug("[HighV] PostChain passes field: {}", f.getName());
                    return (List<PostPass>) val;
                }
            } catch (Exception ignored) {}
        }
        // Fallback: trả về empty và log warning
        LOGGER.warn("[HighV] PostChain.passes not found via reflection — uniforms skipped");
        return Collections.emptyList();
    }
}
