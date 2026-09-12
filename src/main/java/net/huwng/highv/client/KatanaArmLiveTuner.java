package net.huwng.highv.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import net.huwng.highv.HighV;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Katana & Arm Live-Tuner:
 * Cho phép người chơi điều chỉnh vị trí, góc xoay và tỉ lệ của Katana + cánh tay
 * trực tiếp trên màn hình game theo thời gian thực (Real-time) mà KHÔNG cần khởi động lại.
 *
 * Tính năng chính:
 * 1. Bấm F8: Mở/tắt HUD điều khiển trực quan trên màn hình.
 * 2. Phím mũi tên & PageUp/PageDown: Di chuyển/xoay kiếm và tay mượt mà từng pixel.
 * 3. Bấm ENTER: Lưu ngay các thông số ra file config/highv_arm_tuning.json.
 * 4. Tự động Hot-Reload: Bất kỳ khi nào file highv_arm_tuning.json được lưu (Ctrl+S),
 *    game sẽ tự động nạp lại trong 0.2 giây!
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public class KatanaArmLiveTuner {

    public enum TuningMode {
        HAND_POS("1. VỊ TRÍ BÀN TAY (Hand Pos)", "Dịch vị trí cẳng tay & bàn tay trên màn hình", 0.2f),
        WRIST_ROT("2. GÓC XOAY CỔ TAY (Wrist Rot)", "X: Gập/ngửa, Y: Vặn trục cẳng tay, Z: Nghiêng (°)", 5.0f),
        KATANA_ROT("3. GÓC XOAY KIẾM (Katana Rot)", "Góc xoay thanh kiếm trên bàn tay (°)", 5.0f),
        KATANA_SCALE("4. SCALE KIẾM (Katana Scale)", "Tỉ lệ phóng to/thu nhỏ thanh kiếm", 0.05f),
        CUBE_MARKER("5. TÂM CUBE CHUÔI (Cube Marker)", "Tọa độ khối cube chuôi kiếm Blockbench", 0.5f),
        HAND_OFFSET("6. BÙ TỌA ĐỘ TAY (Hand Offset)", "Dịch bù tinh chỉnh phụ cho bàn tay", 0.1f);

        public final String title;
        public final String desc;
        public float step;

        TuningMode(String title, String desc, float defaultStep) {
            this.title = title;
            this.desc = desc;
            this.step = defaultStep;
        }
    }

    public static boolean tunerActive = false;
    public static int currentModeIndex = 0;
    public static boolean hasLoadedFromConfig = false;
    private static long lastSaveSuccessTime = 0;
    private static long lastFileModified = 0;
    private static int tickCounter = 0;

    public static long getLastFileModified() {
        return lastFileModified;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static List<File> getConfigLocations() {
        List<File> list = new ArrayList<>();
        try {
            File fmlConfig = FMLPaths.CONFIGDIR.get().resolve("highv_arm_tuning.json").toFile();
            list.add(fmlConfig);
        } catch (Throwable ignored) {
        }
        list.add(new File("config/highv_arm_tuning.json"));
        list.add(new File("e:/siucap/highv-project/config/highv_arm_tuning.json"));
        list.add(new File("e:/siucap/highv-project/run/client/config/highv_arm_tuning.json"));
        list.add(new File("../config/highv_arm_tuning.json"));
        list.add(new File("../../config/highv_arm_tuning.json"));
        return list;
    }

    private static final List<File> MODEL_LOCATIONS = List.of(
            new File("src/main/resources/assets/highv/models/item/template_longsword.json"),
            new File("../src/main/resources/assets/highv/models/item/template_longsword.json"),
            new File("../../src/main/resources/assets/highv/models/item/template_longsword.json"),
            new File("e:/siucap/highv-project/src/main/resources/assets/highv/models/item/template_longsword.json")
    );

    public static void init() {
        File targetFile = findExistingConfigFile();
        if (targetFile != null && targetFile.exists()) {
            lastFileModified = targetFile.lastModified();
            loadFile(targetFile, false);
        }
    }

    // Lưu các giá trị mặc định để có thể bấm phím R reset bất cứ lúc nào
    private static final float DEF_HAND_X = 6.0f, DEF_HAND_Y = 1.4f, DEF_HAND_Z = -10.0f;
    private static final float DEF_OFFSET_X = 0.0f, DEF_OFFSET_Y = 0.0f, DEF_OFFSET_Z = 2.0f;
    private static final float DEF_WRIST_X = -80.0f, DEF_WRIST_Y = 180.0f, DEF_WRIST_Z = 80.0f;
    private static final float DEF_KAT_ROTX = 0.0f, DEF_KAT_ROTY = 90.0f, DEF_KAT_ROTZ = 0.0f;
    private static final float DEF_KAT_SCX = 1.36f, DEF_KAT_SCY = 1.36f, DEF_KAT_SCZ = 0.6f;
    private static final float DEF_CUBE_X = 5.0f, DEF_CUBE_Y = 0.0f, DEF_CUBE_Z = 10.0f;

    // ─────────────────────────────────────────────────────────────────────────
    // 1. XỬ LÝ PHÍM BẤM TRỰC TIẾP (INPUT HANDLING)
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            // Đang mở chat hoặc menu GUI thì không chặn phím
            return;
        }

        int key = event.getKey();
        int action = event.getAction();

        // Phím bật/tắt HUD (F8)
        if (key == GLFW.GLFW_KEY_F8 && action == GLFW.GLFW_PRESS) {
            tunerActive = !tunerActive;
            if (mc.player != null) {
                if (tunerActive) {
                    mc.player.displayClientMessage(
                            Component.literal("§b[High V] §fĐã bật §eKatana Live-Tuner HUD§f! Dùng các phím mũi tên để căn chỉnh."),
                            false
                    );
                } else {
                    mc.player.displayClientMessage(
                            Component.literal("§7[High V] Đã tắt Katana Live-Tuner HUD."),
                            false
                    );
                }
            }
            return;
        }

        // Nếu HUD đang bật, bắt các phím điều khiển
        if (!tunerActive) {
            return;
        }

        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) {
            return;
        }

        TuningMode[] modes = TuningMode.values();
        TuningMode mode = modes[currentModeIndex];

        switch (key) {
            // Chuyển mục chỉnh
            case GLFW.GLFW_KEY_TAB -> {
                if (action == GLFW.GLFW_PRESS) {
                    currentModeIndex = (currentModeIndex + 1) % modes.length;
                }
            }
            case GLFW.GLFW_KEY_1 -> currentModeIndex = 0;
            case GLFW.GLFW_KEY_2 -> currentModeIndex = 1;
            case GLFW.GLFW_KEY_3 -> currentModeIndex = 2;
            case GLFW.GLFW_KEY_4 -> currentModeIndex = 3;
            case GLFW.GLFW_KEY_5 -> currentModeIndex = 4;
            case GLFW.GLFW_KEY_6 -> currentModeIndex = 5;

            // Điều chỉnh trục X (Mũi tên Trái / Phải)
            case GLFW.GLFW_KEY_LEFT -> modifyValue(mode, 0, -mode.step);
            case GLFW.GLFW_KEY_RIGHT -> modifyValue(mode, 0, mode.step);

            // Điều chỉnh trục Y (Mũi tên Xuống / Lên)
            case GLFW.GLFW_KEY_DOWN -> modifyValue(mode, 1, -mode.step);
            case GLFW.GLFW_KEY_UP -> modifyValue(mode, 1, mode.step);

            // Điều chỉnh trục Z (PageDown / PageUp hoặc O / P)
            case GLFW.GLFW_KEY_PAGE_DOWN, GLFW.GLFW_KEY_O -> modifyValue(mode, 2, -mode.step);
            case GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_P -> modifyValue(mode, 2, mode.step);

            // Thay đổi bước nhảy (Step: dấu [ / ] hoặc , / .)
            case GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_COMMA -> {
                if (action == GLFW.GLFW_PRESS) {
                    mode.step = Math.max(0.01f, round2(mode.step * 0.5f));
                }
            }
            case GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_PERIOD -> {
                if (action == GLFW.GLFW_PRESS) {
                    mode.step = Math.min(45.0f, round2(mode.step * 2.0f));
                }
            }

            // Lưu ra file JSON
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (action == GLFW.GLFW_PRESS) {
                    saveToFile(mc);
                }
            }

            // Nạp lại từ file JSON
            case GLFW.GLFW_KEY_F7 -> {
                if (action == GLFW.GLFW_PRESS) {
                    reloadFromFile(mc);
                }
            }

            // Reset mục hiện tại về mặc định
            case GLFW.GLFW_KEY_R -> {
                if (action == GLFW.GLFW_PRESS) {
                    resetCurrentMode(mode, mc);
                }
            }
        }
    }

    private static void modifyValue(TuningMode mode, int axis, float delta) {
        switch (mode) {
            case HAND_POS -> {
                if (axis == 0) FirstPersonSwordArmRenderer.handPosX = round2(FirstPersonSwordArmRenderer.handPosX + delta);
                if (axis == 1) FirstPersonSwordArmRenderer.handPosY = round2(FirstPersonSwordArmRenderer.handPosY + delta);
                if (axis == 2) FirstPersonSwordArmRenderer.handPosZ = round2(FirstPersonSwordArmRenderer.handPosZ + delta);
            }
            case WRIST_ROT -> {
                if (axis == 0) FirstPersonSwordArmRenderer.wristRotX = round2(FirstPersonSwordArmRenderer.wristRotX + delta);
                if (axis == 1) FirstPersonSwordArmRenderer.wristRotY = round2(FirstPersonSwordArmRenderer.wristRotY + delta);
                if (axis == 2) FirstPersonSwordArmRenderer.wristRotZ = round2(FirstPersonSwordArmRenderer.wristRotZ + delta);
            }
            case KATANA_ROT -> {
                if (axis == 0) FirstPersonSwordArmRenderer.katanaRotX = round2(FirstPersonSwordArmRenderer.katanaRotX + delta);
                if (axis == 1) FirstPersonSwordArmRenderer.katanaRotY = round2(FirstPersonSwordArmRenderer.katanaRotY + delta);
                if (axis == 2) FirstPersonSwordArmRenderer.katanaRotZ = round2(FirstPersonSwordArmRenderer.katanaRotZ + delta);
            }
            case KATANA_SCALE -> {
                if (axis == 0) FirstPersonSwordArmRenderer.katanaScaleX = Math.max(0.01f, round2(FirstPersonSwordArmRenderer.katanaScaleX + delta));
                if (axis == 1) FirstPersonSwordArmRenderer.katanaScaleY = Math.max(0.01f, round2(FirstPersonSwordArmRenderer.katanaScaleY + delta));
                if (axis == 2) FirstPersonSwordArmRenderer.katanaScaleZ = Math.max(0.01f, round2(FirstPersonSwordArmRenderer.katanaScaleZ + delta));
            }
            case CUBE_MARKER -> {
                if (axis == 0) FirstPersonSwordArmRenderer.cubeMarkerX = round2(FirstPersonSwordArmRenderer.cubeMarkerX + delta);
                if (axis == 1) FirstPersonSwordArmRenderer.cubeMarkerY = round2(FirstPersonSwordArmRenderer.cubeMarkerY + delta);
                if (axis == 2) FirstPersonSwordArmRenderer.cubeMarkerZ = round2(FirstPersonSwordArmRenderer.cubeMarkerZ + delta);
            }
            case HAND_OFFSET -> {
                if (axis == 0) FirstPersonSwordArmRenderer.handOffsetX = round2(FirstPersonSwordArmRenderer.handOffsetX + delta);
                if (axis == 1) FirstPersonSwordArmRenderer.handOffsetY = round2(FirstPersonSwordArmRenderer.handOffsetY + delta);
                if (axis == 2) FirstPersonSwordArmRenderer.handOffsetZ = round2(FirstPersonSwordArmRenderer.handOffsetZ + delta);
            }
        }
    }

    private static void resetCurrentMode(TuningMode mode, Minecraft mc) {
        switch (mode) {
            case HAND_POS -> {
                FirstPersonSwordArmRenderer.handPosX = DEF_HAND_X;
                FirstPersonSwordArmRenderer.handPosY = DEF_HAND_Y;
                FirstPersonSwordArmRenderer.handPosZ = DEF_HAND_Z;
            }
            case WRIST_ROT -> {
                FirstPersonSwordArmRenderer.wristRotX = DEF_WRIST_X;
                FirstPersonSwordArmRenderer.wristRotY = DEF_WRIST_Y;
                FirstPersonSwordArmRenderer.wristRotZ = DEF_WRIST_Z;
            }
            case KATANA_ROT -> {
                FirstPersonSwordArmRenderer.katanaRotX = DEF_KAT_ROTX;
                FirstPersonSwordArmRenderer.katanaRotY = DEF_KAT_ROTY;
                FirstPersonSwordArmRenderer.katanaRotZ = DEF_KAT_ROTZ;
            }
            case KATANA_SCALE -> {
                FirstPersonSwordArmRenderer.katanaScaleX = DEF_KAT_SCX;
                FirstPersonSwordArmRenderer.katanaScaleY = DEF_KAT_SCY;
                FirstPersonSwordArmRenderer.katanaScaleZ = DEF_KAT_SCZ;
            }
            case CUBE_MARKER -> {
                FirstPersonSwordArmRenderer.cubeMarkerX = DEF_CUBE_X;
                FirstPersonSwordArmRenderer.cubeMarkerY = DEF_CUBE_Y;
                FirstPersonSwordArmRenderer.cubeMarkerZ = DEF_CUBE_Z;
            }
            case HAND_OFFSET -> {
                FirstPersonSwordArmRenderer.handOffsetX = DEF_OFFSET_X;
                FirstPersonSwordArmRenderer.handOffsetY = DEF_OFFSET_Y;
                FirstPersonSwordArmRenderer.handOffsetZ = DEF_OFFSET_Z;
            }
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§6[High V] Đã reset " + mode.title + " về mặc định."), true);
        }
    }

    private static float round2(float val) {
        return Math.round(val * 100.0f) / 100.0f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. VẼ GIAO DIỆN BẢNG ĐIỀU KHIỂN (HUD OVERLAY)
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!tunerActive) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        GuiGraphics gfx = event.getGuiGraphics();
        Font font = mc.font;

        int startX = 10;
        int startY = 10;
        int width = 310;
        int height = 185;

        // Nền đen bán trong suốt phong cách Cyberpunk Dark Glass
        gfx.fill(startX, startY, startX + width, startY + height, 0xD00A0E17);
        // Viền Neon Cyan
        gfx.renderOutline(startX, startY, width, height, 0xFF00E5FF);

        // Header
        gfx.fill(startX, startY, startX + width, startY + 22, 0x5000E5FF);
        gfx.drawString(font, "⚡ HIGH V — KATANA & ARM LIVE TUNER ⚡", startX + 8, startY + 7, 0xFFFFD700, true);
        gfx.drawString(font, "[F8: Đóng/Mở]", startX + width - 75, startY + 7, 0xFFAAAAAA, false);

        int lineY = startY + 28;
        TuningMode[] modes = TuningMode.values();

        for (int i = 0; i < modes.length; i++) {
            TuningMode m = modes[i];
            boolean selected = (i == currentModeIndex);

            int titleColor = selected ? 0xFF00FFCC : 0xFF888888;
            int valColor = selected ? 0xFFFFFFFF : 0xFFAAAAAA;

            String prefix = selected ? "▶ " : "  ";
            gfx.drawString(font, prefix + m.title, startX + 8, lineY, titleColor, true);

            // Tọa độ tương ứng
            String valuesStr = switch (m) {
                case HAND_POS -> String.format("X: %.2f  Y: %.2f  Z: %.2f",
                        FirstPersonSwordArmRenderer.handPosX, FirstPersonSwordArmRenderer.handPosY, FirstPersonSwordArmRenderer.handPosZ);
                case WRIST_ROT -> String.format("X: %.1f°  Y: %.1f°  Z: %.1f°",
                        FirstPersonSwordArmRenderer.wristRotX, FirstPersonSwordArmRenderer.wristRotY, FirstPersonSwordArmRenderer.wristRotZ);
                case KATANA_ROT -> String.format("X: %.1f°  Y: %.1f°  Z: %.1f°",
                        FirstPersonSwordArmRenderer.katanaRotX, FirstPersonSwordArmRenderer.katanaRotY, FirstPersonSwordArmRenderer.katanaRotZ);
                case KATANA_SCALE -> String.format("X: %.2f  Y: %.2f  Z: %.2f",
                        FirstPersonSwordArmRenderer.katanaScaleX, FirstPersonSwordArmRenderer.katanaScaleY, FirstPersonSwordArmRenderer.katanaScaleZ);
                case CUBE_MARKER -> String.format("X: %.2f  Y: %.2f  Z: %.2f",
                        FirstPersonSwordArmRenderer.cubeMarkerX, FirstPersonSwordArmRenderer.cubeMarkerY, FirstPersonSwordArmRenderer.cubeMarkerZ);
                case HAND_OFFSET -> String.format("X: %.2f  Y: %.2f  Z: %.2f",
                        FirstPersonSwordArmRenderer.handOffsetX, FirstPersonSwordArmRenderer.handOffsetY, FirstPersonSwordArmRenderer.handOffsetZ);
            };

            int strWidth = font.width(valuesStr);
            gfx.drawString(font, valuesStr, startX + width - strWidth - 10, lineY, valColor, false);

            lineY += 13;
        }

        // Dải phân cách
        gfx.fill(startX + 8, lineY + 2, startX + width - 8, lineY + 3, 0x40FFFFFF);
        lineY += 7;

        // Thông tin mục đang chọn và bước nhảy
        TuningMode cur = modes[currentModeIndex];
        gfx.drawString(font, "Bước nhảy hiện tại: ±" + cur.step + "  ([ / ]: đổi bước)", startX + 8, lineY, 0xFFFFAA00, false);
        lineY += 12;

        // Hướng dẫn phím
        gfx.drawString(font, "• [← / →]: Chỉnh trục X    • [↓ / ↑]: Chỉnh trục Y", startX + 8, lineY, 0xFFCCCCCC, false);
        lineY += 11;
        gfx.drawString(font, "• [PgUp / PgDn hoặc O / P]: Chỉnh trục Z", startX + 8, lineY, 0xFFCCCCCC, false);
        lineY += 11;
        gfx.drawString(font, "• [TAB hoặc 1..6]: Đổi mục  • [ENTER]: LƯU RA FILE", startX + 8, lineY, 0xFF00FF7F, false);
        lineY += 11;
        gfx.drawString(font, "• [F7]: Nạp lại từ file     • [R]: Reset mục này", startX + 8, lineY, 0xFF70C0FF, false);

        // Hiển thị banner lưu thành công
        if (System.currentTimeMillis() - lastSaveSuccessTime < 2500) {
            gfx.fill(startX, startY + height - 20, startX + width, startY + height, 0xCC00AA44);
            gfx.drawString(font, "✔ ĐÃ LƯU THÀNH CÔNG VÀO FILE CONFIG!", startX + 35, startY + height - 15, 0xFFFFFFFF, true);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. TỰ ĐỘNG HOT-RELOAD TỪ FILE JSON (CLIENT TICK)
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        tickCounter++;
        if (tickCounter % 30 == 0) {
            ClientSetup.ensureBetterCombatArmVisible();
        }

        if (tickCounter % 6 != 0) {
            // Kiểm tra mỗi ~300ms
            return;
        }

        File targetFile = findExistingConfigFile();
        if (targetFile != null && targetFile.exists()) {
            long modTime = targetFile.lastModified();
            if (lastFileModified == 0) {
                lastFileModified = modTime;
                // Nạp dữ liệu lúc khởi động
                loadFile(targetFile, false);
            } else if (modTime > lastFileModified) {
                lastFileModified = modTime;
                // Tự động hot-reload khi người dùng bấm Ctrl + S trong VS Code
                loadFile(targetFile, true);
            }
        }
    }

    private static File findExistingConfigFile() {
        for (File f : getConfigLocations()) {
            if (f != null && f.exists()) return f;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. ĐỌC & GHI FILE JSON CONFIG
    // ─────────────────────────────────────────────────────────────────────────
    public static void saveToFile(Minecraft mc) {
        JsonObject root = new JsonObject();
        root.addProperty("_huong_dan", "File cấu hình vị trí tay và Katana. Sửa và bấm Ctrl+S trong VS Code để game tự động Hot-Reload trong 0.2s! Hoặc bấm F8 trong game để chỉnh bằng phím.");

        JsonObject handPos = new JsonObject();
        handPos.addProperty("x", FirstPersonSwordArmRenderer.handPosX);
        handPos.addProperty("y", FirstPersonSwordArmRenderer.handPosY);
        handPos.addProperty("z", FirstPersonSwordArmRenderer.handPosZ);
        root.add("hand_position", handPos);

        JsonObject handOff = new JsonObject();
        handOff.addProperty("x", FirstPersonSwordArmRenderer.handOffsetX);
        handOff.addProperty("y", FirstPersonSwordArmRenderer.handOffsetY);
        handOff.addProperty("z", FirstPersonSwordArmRenderer.handOffsetZ);
        root.add("hand_offset", handOff);

        JsonObject wristRot = new JsonObject();
        wristRot.addProperty("x", FirstPersonSwordArmRenderer.wristRotX);
        wristRot.addProperty("y", FirstPersonSwordArmRenderer.wristRotY);
        wristRot.addProperty("z", FirstPersonSwordArmRenderer.wristRotZ);
        root.add("wrist_rotation", wristRot);

        JsonObject katRot = new JsonObject();
        katRot.addProperty("x", FirstPersonSwordArmRenderer.katanaRotX);
        katRot.addProperty("y", FirstPersonSwordArmRenderer.katanaRotY);
        katRot.addProperty("z", FirstPersonSwordArmRenderer.katanaRotZ);
        root.add("katana_rotation", katRot);

        JsonObject katScale = new JsonObject();
        katScale.addProperty("x", FirstPersonSwordArmRenderer.katanaScaleX);
        katScale.addProperty("y", FirstPersonSwordArmRenderer.katanaScaleY);
        katScale.addProperty("z", FirstPersonSwordArmRenderer.katanaScaleZ);
        root.add("katana_scale", katScale);

        JsonObject cubeMarker = new JsonObject();
        cubeMarker.addProperty("x", FirstPersonSwordArmRenderer.cubeMarkerX);
        cubeMarker.addProperty("y", FirstPersonSwordArmRenderer.cubeMarkerY);
        cubeMarker.addProperty("z", FirstPersonSwordArmRenderer.cubeMarkerZ);
        cubeMarker.addProperty("rot_angle", FirstPersonSwordArmRenderer.cubeRotAngle);
        cubeMarker.addProperty("rot_axis", FirstPersonSwordArmRenderer.cubeRotAxis);
        root.add("cube_marker", cubeMarker);

        String jsonStr = GSON.toJson(root);

        // Lưu vào tất cả các vị trí config tiềm năng
        boolean savedAny = false;
        for (File f : getConfigLocations()) {
            if (f == null) continue;
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (FileWriter writer = new FileWriter(f, StandardCharsets.UTF_8)) {
                    writer.write(jsonStr);
                    lastFileModified = f.lastModified();
                    savedAny = true;
                }
            } catch (Exception ignored) {
            }
        }

        if (savedAny) {
            hasLoadedFromConfig = true;
            syncToTemplateModel();
            lastSaveSuccessTime = System.currentTimeMillis();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§a[High V] ✔ Đã lưu thông số vào config/highv_arm_tuning.json!"),
                        false
                );
            }
        }
    }

    private static void syncToTemplateModel() {
        for (File f : MODEL_LOCATIONS) {
            if (!f.exists()) continue;
            try {
                JsonObject root;
                try (FileReader reader = new FileReader(f, StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(reader).getAsJsonObject();
                }
                if (root != null && root.has("elements")) {
                    JsonArray elements = root.getAsJsonArray("elements");
                    if (!elements.isEmpty()) {
                        JsonObject elem = elements.get(0).getAsJsonObject();
                        float cx = FirstPersonSwordArmRenderer.cubeMarkerX;
                        float cy = FirstPersonSwordArmRenderer.cubeMarkerY;
                        float cz = FirstPersonSwordArmRenderer.cubeMarkerZ;

                        JsonArray from = new JsonArray();
                        from.add(round2(cx - 1.0f));
                        from.add(round2(cy - 1.0f));
                        from.add(round2(cz - 1.0f));
                        elem.add("from", from);

                        JsonArray to = new JsonArray();
                        to.add(round2(cx + 1.0f));
                        to.add(round2(cy + 1.0f));
                        to.add(round2(cz + 1.0f));
                        elem.add("to", to);

                        if (elem.has("rotation")) {
                            JsonObject rot = elem.getAsJsonObject("rotation");
                            JsonArray orig = new JsonArray();
                            orig.add(round2(cx));
                            orig.add(round2(cy));
                            orig.add(round2(cz));
                            rot.add("origin", orig);
                            rot.addProperty("angle", FirstPersonSwordArmRenderer.cubeRotAngle);
                            rot.addProperty("axis", FirstPersonSwordArmRenderer.cubeRotAxis);
                        }

                        try (FileWriter writer = new FileWriter(f, StandardCharsets.UTF_8)) {
                            GSON.toJson(root, writer);
                        }
                        FirstPersonSwordArmRenderer.lastFileModified = f.lastModified();
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void reloadFromFile(Minecraft mc) {
        File file = findExistingConfigFile();
        if (file != null && file.exists()) {
            loadFile(file, true);
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§e[High V] ⚡ Đã nạp lại thông số từ " + file.getPath()),
                        false
                );
            }
        } else {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§c[High V] Chưa tìm thấy file config/highv_arm_tuning.json! Bấm ENTER để tạo mới."),
                        false
                );
            }
        }
    }

    private static void loadFile(File file, boolean notifyPlayer) {
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            if (root.has("hand_position")) {
                JsonObject obj = root.getAsJsonObject("hand_position");
                if (obj.has("x")) FirstPersonSwordArmRenderer.handPosX = obj.get("x").getAsFloat();
                if (obj.has("y")) FirstPersonSwordArmRenderer.handPosY = obj.get("y").getAsFloat();
                if (obj.has("z")) FirstPersonSwordArmRenderer.handPosZ = obj.get("z").getAsFloat();
            }
            if (root.has("hand_offset")) {
                JsonObject obj = root.getAsJsonObject("hand_offset");
                if (obj.has("x")) FirstPersonSwordArmRenderer.handOffsetX = obj.get("x").getAsFloat();
                if (obj.has("y")) FirstPersonSwordArmRenderer.handOffsetY = obj.get("y").getAsFloat();
                if (obj.has("z")) FirstPersonSwordArmRenderer.handOffsetZ = obj.get("z").getAsFloat();
            }
            if (root.has("wrist_rotation")) {
                JsonObject obj = root.getAsJsonObject("wrist_rotation");
                if (obj.has("x")) FirstPersonSwordArmRenderer.wristRotX = obj.get("x").getAsFloat();
                if (obj.has("y")) FirstPersonSwordArmRenderer.wristRotY = obj.get("y").getAsFloat();
                if (obj.has("z")) FirstPersonSwordArmRenderer.wristRotZ = obj.get("z").getAsFloat();
            }
            if (root.has("katana_rotation")) {
                JsonObject obj = root.getAsJsonObject("katana_rotation");
                if (obj.has("x")) FirstPersonSwordArmRenderer.katanaRotX = obj.get("x").getAsFloat();
                if (obj.has("y")) FirstPersonSwordArmRenderer.katanaRotY = obj.get("y").getAsFloat();
                if (obj.has("z")) FirstPersonSwordArmRenderer.katanaRotZ = obj.get("z").getAsFloat();
            }
            if (root.has("katana_scale")) {
                JsonObject obj = root.getAsJsonObject("katana_scale");
                if (obj.has("x")) FirstPersonSwordArmRenderer.katanaScaleX = obj.get("x").getAsFloat();
                if (obj.has("y")) FirstPersonSwordArmRenderer.katanaScaleY = obj.get("y").getAsFloat();
                if (obj.has("z")) FirstPersonSwordArmRenderer.katanaScaleZ = obj.get("z").getAsFloat();
            }
            if (root.has("cube_marker")) {
                JsonObject obj = root.getAsJsonObject("cube_marker");
                if (obj.has("x")) FirstPersonSwordArmRenderer.cubeMarkerX = obj.get("x").getAsFloat();
                if (obj.has("y")) FirstPersonSwordArmRenderer.cubeMarkerY = obj.get("y").getAsFloat();
                if (obj.has("z")) FirstPersonSwordArmRenderer.cubeMarkerZ = obj.get("z").getAsFloat();
                if (obj.has("rot_angle")) FirstPersonSwordArmRenderer.cubeRotAngle = obj.get("rot_angle").getAsFloat();
                if (obj.has("rot_axis")) FirstPersonSwordArmRenderer.cubeRotAxis = obj.get("rot_axis").getAsString();
            }

            hasLoadedFromConfig = true;

            if (notifyPlayer) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("§e[High V] ⚡ Đã tự động Hot-Reload vị trí tay & Katana!"),
                            true // Hiện trên Action Bar
                    );
                }
            }
        } catch (Exception ignored) {
        }
    }
}
