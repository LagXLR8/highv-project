package net.huwng.highv.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Cấu hình client cho các hiệu ứng hình ảnh, camera và giao diện HUD của HighV.
 * Riêng Motion Blur mặc định là TẮT (false) theo yêu cầu.
 */
public final class HighVClientConfig {

    public static final ModConfigSpec SPEC;

    // ── Motion Blur (Mặc định: TẮT / false) ──────────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_MOTION_BLUR_SHADER;
    public static final ModConfigSpec.BooleanValue ENABLE_MOTION_BLUR_VIGNETTE;

    // ── Speed Effects ────────────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_SPEED_LINES;
    public static final ModConfigSpec.BooleanValue ENABLE_SPEEDOMETER;

    // ── Wind Effects ─────────────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_WIND_STREAKS;
    public static final ModConfigSpec.BooleanValue ENABLE_WIND_TRAILS;

    // ── Camera & Feel Effects ────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_DYNAMIC_FOV;
    public static final ModConfigSpec.BooleanValue ENABLE_DYNAMIC_POV_CAMERA;
    public static final ModConfigSpec.BooleanValue ENABLE_CAMERA_FEEL;
    public static final ModConfigSpec.BooleanValue ENABLE_CAMERA_ROLL;
    public static final ModConfigSpec.BooleanValue ENABLE_SCREEN_SHAKE;

    // ── HUD Effects ──────────────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_GRAPPLING_CROSSHAIR;
    public static final ModConfigSpec.BooleanValue ENABLE_PILOT_HUD_INERTIA;
    public static final ModConfigSpec.BooleanValue ENABLE_ACTION_DURATION_HUD;

    // ── Sword & Weapon Effects ───────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_FIRST_PERSON_SWORD_ARM;
    public static final ModConfigSpec.BooleanValue ENABLE_PROCEDURAL_SWORD_MOTION;

    // ── Target Lock System (Armored Core 6 Style) ────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_TARGET_LOCK;
    public static final ModConfigSpec.BooleanValue REQUIRE_SHOULDER_CAMERA_FOR_LOCK;
    public static final ModConfigSpec.BooleanValue HARD_LOCK_BREAK_ON_MOUSE;
    public static final ModConfigSpec.DoubleValue TARGET_LOCK_RANGE;

    // ── Classic Shoulder Camera ──────────────────────────────────────────────
    public static final ModConfigSpec.DoubleValue SHOULDER_CAMERA_HEIGHT;
    public static final ModConfigSpec.DoubleValue SHOULDER_CAMERA_OFFSET;
    public static final ModConfigSpec.DoubleValue SHOULDER_CAMERA_DISTANCE;

    // ── Armored Core 6 Combat Camera ─────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_AC6_CAMERA;
    public static final ModConfigSpec.DoubleValue AC6_CAMERA_BASE_DISTANCE;
    public static final ModConfigSpec.DoubleValue AC6_CAMERA_BOOST_DISTANCE;
    public static final ModConfigSpec.DoubleValue AC6_CAMERA_HEIGHT;
    public static final ModConfigSpec.DoubleValue AC6_CAMERA_SHOULDER_OFFSET;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Cấu hình hiệu ứng hình ảnh, camera và giao diện của HighV").push("visual_effects");

        // Sword effects category
        builder.push("sword_effects");
        ENABLE_FIRST_PERSON_SWORD_ARM = builder
                .comment("Bật/tắt hiển thị cánh tay phải cầm kiếm ở góc nhìn thứ nhất (First-person right arm holding sword). Mặc định: true.")
                .define("enableFirstPersonSwordArm", true);
        ENABLE_PROCEDURAL_SWORD_MOTION = builder
                .comment("Bật/tắt tương tác chuyển động procedural (quán tính lia chuột, lắc lư bước chạy, nhịp thở, nhảy và tiếp đất) cho cánh tay & kiếm. Mặc định: true.")
                .define("enableProceduralSwordMotion", true);
        builder.pop();

        // Motion Blur category
        builder.push("motion_blur");
        ENABLE_MOTION_BLUR_SHADER = builder
                .comment("Bật/tắt hiệu ứng shader Motion Blur tích lũy khung hình (Post-chain accumulation shader).",
                         "Mặc định: false (TẮT).")
                .define("enableMotionBlurShader", false);

        ENABLE_MOTION_BLUR_VIGNETTE = builder
                .comment("Bật/tắt hiệu ứng bóng mờ viền màn hình khi tốc độ cao (Vignette motion blur overlay).",
                         "Mặc định: false (TẮT).")
                .define("enableMotionBlurVignette", false);
        builder.pop();

        // Speed effects category
        builder.push("speed_effects");
        ENABLE_SPEED_LINES = builder
                .comment("Bật/tắt hiệu ứng vệt sáng tốc độ cao (Speed Lines Shader và radial lines). Mặc định: true.")
                .define("enableSpeedLines", true);

        ENABLE_SPEEDOMETER = builder
                .comment("Bật/tắt hiển thị đồng hồ tốc độ (Speedometer HUD) cạnh thanh máu. Mặc định: true.")
                .define("enableSpeedometer", true);
        builder.pop();

        // Wind effects category
        builder.push("wind_effects");
        ENABLE_WIND_STREAKS = builder
                .comment("Bật/tắt hiệu ứng vệt gió lướt màn hình theo hướng động năng thực tế (Dynamic Wind Particle Streaks). Mặc định: true.")
                .define("enableWindStreaks", true);

        ENABLE_WIND_TRAILS = builder
                .comment("Bật/tắt dải lụa gió 3D khí động học bám theo cơ thể người chơi (Speed Wind Ribbon Trails khi > 15 m/s). Mặc định: true.")
                .define("enableWindTrails", true);
        builder.pop();

        // Camera effects category
        builder.push("camera_effects");
        ENABLE_DYNAMIC_FOV = builder
                .comment("Bật/tắt hiệu ứng góc nhìn động (Dynamic FOV) mở rộng mượt mà theo tốc độ, sprint, dash, drift và chém kiếm. Mặc định: true.")
                .define("enableDynamicFov", true);

        ENABLE_DYNAMIC_POV_CAMERA = builder
                .comment("Bật/tắt camera POV động (nhún nhịp bước chân, nghiêng rẽ, nảy khi tiếp đất và chém kiếm). Mặc định: true.")
                .define("enableDynamicPovCamera", true);

        ENABLE_CAMERA_FEEL = builder
                .comment("Bật/tắt hiệu ứng độ nặng và gia tốc camera (Camera Feel pitch/yaw). Mặc định: true.")
                .define("enableCameraFeel", true);

        ENABLE_CAMERA_ROLL = builder
                .comment("Bật/tắt góc nghiêng camera khi rẽ/strafe cua (Camera Roll/Tilt). Mặc định: true.")
                .define("enableCameraRoll", true);

        ENABLE_SCREEN_SHAKE = builder
                .comment("Bật/tắt hiệu ứng rung chấn camera (Screen Shake) do nổ, sấm sét, vung vũ khí. Mặc định: true.")
                .define("enableScreenShake", true);
        builder.pop();

        // HUD effects category
        builder.push("hud_effects");
        ENABLE_GRAPPLING_CROSSHAIR = builder
                .comment("Bật/tắt tâm ngắm 3D thông minh cho Grappling Hook. Mặc định: true.")
                .define("enableGrapplingCrosshair", true);

        ENABLE_PILOT_HUD_INERTIA = builder
                .comment("Bật/tắt hiệu ứng quán tính nón bảo hiểm phi công cho giao diện HUD (Pilot Visor Sway & Inertia). Mặc định: true.")
                .define("enablePilotHudInertia", true);

        ENABLE_ACTION_DURATION_HUD = builder
                .comment("Bật/tắt thanh hiển thị thời gian drift và wallstride dưới tâm ngắm. Mặc định: true.")
                .define("enableActionDurationHud", true);
        builder.pop();

        // Target lock category (Armored Core 6 style)
        builder.push("target_lock");
        ENABLE_TARGET_LOCK = builder
                .comment("Bật/tắt hệ thống Soft Lock & Hard Lock phong cách Armored Core 6. Mặc định: true.")
                .define("enableTargetLock", true);

        REQUIRE_SHOULDER_CAMERA_FOR_LOCK = builder
                .comment("Chỉ kích hoạt Target Lock khi đang bật Shoulder Camera (phím Alt). Nếu false, có thể dùng ở mọi góc nhìn. Mặc định: true.")
                .define("requireShoulderCameraForLock", true);

        HARD_LOCK_BREAK_ON_MOUSE = builder
                .comment("Tự động ngắt Hard Lock khi lia chuột mạnh (chuẩn console AC6). Nếu false, camera giữ bám mục tiêu liên tục. Mặc định: false.")
                .define("hardLockBreakOnMouse", false);

        TARGET_LOCK_RANGE = builder
                .comment("Khoảng cách quét và khóa mục tiêu tối đa (mét). Mặc định: 40.0.")
                .defineInRange("targetLockRange", 40.0, 10.0, 128.0);
        builder.pop();

        // Shoulder camera category (Classic)
        builder.push("shoulder_camera");
        SHOULDER_CAMERA_HEIGHT = builder
                .comment("Độ cao nâng camera qua vai so với tầm mắt người chơi (mét). Mặc định: 0.85.")
                .defineInRange("height", 0.85, 0.0, 3.0);

        SHOULDER_CAMERA_OFFSET = builder
                .comment("Độ lệch ngang qua vai của camera (mét). Mặc định: 0.70.")
                .defineInRange("sideOffset", 0.70, 0.0, 2.0);

        SHOULDER_CAMERA_DISTANCE = builder
                .comment("Khoảng cách góc nhìn thứ 3 của camera qua vai (mét). Mặc định: 4.0.")
                .defineInRange("distance", 4.0, 1.0, 12.0);
        builder.pop();

        // Armored Core 6 combat camera category
        builder.push("ac6_camera");
        ENABLE_AC6_CAMERA = builder
                .comment("Bật/tắt hệ thống Armored Core 6 Combat Camera (khoảng cách 3D 5.2m-6.4m, dynamic boost zoom và khóa mục tiêu Soft/Hard Lock). " +
                         "Nếu tắt (false - mặc định), sẽ sử dụng Shoulder Camera cổ điển ban đầu.")
                .define("enableAc6Camera", false);

        AC6_CAMERA_BASE_DISTANCE = builder
                .comment("Khoảng cách camera cơ sở trong góc nhìn thứ 3 AC6 (mét). Mặc định: 5.2.")
                .defineInRange("baseDistance", 5.2, 2.0, 16.0);

        AC6_CAMERA_BOOST_DISTANCE = builder
                .comment("Khoảng cách camera tối đa khi Dash / Boost tốc độ cao (mét). Mặc định: 6.4.")
                .defineInRange("boostDistance", 6.4, 3.0, 20.0);

        AC6_CAMERA_HEIGHT = builder
                .comment("Độ cao nâng camera trên đỉnh đầu nhân vật (mét). Mặc định: 0.75.")
                .defineInRange("cameraHeight", 0.75, 0.0, 3.0);

        AC6_CAMERA_SHOULDER_OFFSET = builder
                .comment("Độ lệch vai tinh tế (mét, 0 là chính giữa, 0.32 là lệch nhẹ thể thao). Mặc định: 0.32.")
                .defineInRange("shoulderOffset", 0.32, 0.0, 1.5);
        builder.pop();

        builder.pop();
        SPEC = builder.build();
    }

    private HighVClientConfig() {}
}
