package net.huwng.highv.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Keybinding riêng cho Drift — CỐ Ý KHÔNG dùng chung với phím Sneak (dù
 * người chơi có bind Sneak thành Ctrl hay không), vì phím Sneak vốn đã được
 * ClientInputHandler dùng làm cạnh kích hoạt cho Dash. Nếu Drift dùng chung
 * phím đó, mỗi lần định trượt sẽ vô tình bắn luôn 1 phát Dash — đây chính là
 * xung đột đã phát hiện ở lần trước. Tách phím riêng (mặc định phím C,
 * người chơi vẫn có thể tự bind lại trong Options > Controls) giải quyết dứt
 * điểm việc đó — Drift và Dash giờ hoàn toàn độc lập nhau.
 *
 * TOGGLE_SHOULDER_CAMERA: bật/tắt camera qua vai ở góc nhìn thứ 3, mặc định
 * phím Left Alt (InputConstants.KEY_LALT — đã sửa lại theo đúng tên hằng số
 * thật sau khi build lần đầu báo lỗi compile, trước đó mình đoán nhầm là
 * KEY_LMENU).
 */
public final class ModKeyMappings {

    private static final String CATEGORY = "key.categories.highv";

    public static final KeyMapping DRIFT = new KeyMapping(
            "key.highv.drift",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_C,
            CATEGORY
    );

    public static final KeyMapping TOGGLE_SHOULDER_CAMERA = new KeyMapping(
            "key.highv.toggle_shoulder_camera",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_LALT,
            CATEGORY
    );

    public static final KeyMapping HARD_LOCK = new KeyMapping(
            "key.highv.hard_lock",
            InputConstants.Type.MOUSE,
            InputConstants.MOUSE_BUTTON_MIDDLE,
            CATEGORY
    );

    private ModKeyMappings() {}

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(DRIFT);
        event.register(TOGGLE_SHOULDER_CAMERA);
        event.register(HARD_LOCK);
    }
}
