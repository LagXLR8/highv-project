package net.huwng.highv.client.hud;

import net.huwng.highv.HighV;
import net.huwng.highv.client.animation.DriftAnimationHandler;
import net.huwng.highv.client.animation.WallstrideAnimationHandler;
import net.huwng.highv.event.DriftServerHandler;
import net.huwng.highv.event.WallstrideServerHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Thanh ngang phía dưới crosshair, hiện khi CHÍNH người chơi (client) đang
 * Drift (trượt) hoặc Wallstride (bám tường) — vơi dần theo thời gian còn
 * lại của MAX_SLIDE_TICKS/MAX_WALLRUN_TICKS (đều 5s, xem
 * DriftServerHandler/WallstrideServerHandler).
 *
 * KHÔNG cần packet riêng từ server cho việc này: Drift đã có tín hiệu
 * đồng bộ chính xác qua DriftAnimationHandler.isSyncedSliding() (từ
 * DriftStateSyncPacket). Wallstride CHƯA từng đồng bộ trạng thái riêng cho
 * client (xem doc comment WallstrideAnimationHandler) — client tự dò lại y
 * hệt điều kiện bên server để quyết định animation, và HUD này tái dùng
 * đúng kết quả đó (WallstrideAnimationHandler.isActive()) cho CHÍNH người
 * chơi. Vì đây là HUD của chính mình, sai số nhỏ (nếu có) giữa lúc client
 * tự nhận biết và lúc server thực sự tính không đáng ngại — chỉ là hiển thị,
 * không ảnh hưởng gì tới vật lý thật (vật lý luôn do server quyết định).
 *
 * Mốc "bắt đầu" được ghi nhận LOCAL (theo player.tickCount phía client) mỗi
 * khi vừa CHUYỂN từ không active -> active — không đọc trực tiếp tick bắt
 * đầu thật của server (không có sẵn, và cũng không cần thiết cho một thanh
 * HUD chỉ mang tính hiển thị).
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class ActionDurationHudHandler {

    private static final int BAR_WIDTH = 40;
    private static final int BAR_HEIGHT = 3;

    /** Khoảng cách từ tâm màn hình xuống mép trên thanh, pixel (GUI scale). */
    private static final int OFFSET_BELOW_CROSSHAIR = 12;

    private static final int COLOR_BACKGROUND = 0x80000000;
    private static final int COLOR_FILLED     = 0xFFFFFFFF;

    private static boolean wasActive = false;
    private static int startTick = 0;
    private static int activeMaxTicks = 0;

    private ActionDurationHudHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            wasActive = false;
            return;
        }

        boolean sliding = DriftAnimationHandler.isSyncedSliding(player.getUUID());
        boolean wallRunning = !sliding && WallstrideAnimationHandler.isActive(player.getUUID());
        boolean active = sliding || wallRunning;

        if (active && !wasActive) {
            startTick = player.tickCount;
            activeMaxTicks = sliding ? DriftServerHandler.MAX_SLIDE_TICKS : WallstrideServerHandler.MAX_WALLRUN_TICKS;
        }
        wasActive = active;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !wasActive || activeMaxTicks <= 0) return;
        if (mc.options.hideGui || !net.huwng.highv.client.HighVClientConfig.ENABLE_ACTION_DURATION_HUD.get()) return;

        int elapsed = player.tickCount - startTick;
        double fraction = 1.0 - clamp01((double) elapsed / activeMaxTicks);

        GuiGraphics gg = event.getGuiGraphics();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int x = screenW / 2 - BAR_WIDTH / 2;
        int y = screenH / 2 + OFFSET_BELOW_CROSSHAIR;

        gg.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, COLOR_BACKGROUND);

        int filledWidth = (int) Math.round(BAR_WIDTH * fraction);
        if (filledWidth > 0) {
            gg.fill(x, y, x + filledWidth, y + BAR_HEIGHT, COLOR_FILLED);
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
