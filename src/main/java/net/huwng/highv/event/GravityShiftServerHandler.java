package net.huwng.highv.event;

import net.huwng.highv.HighV;
import net.huwng.highv.enchantment.ModEnchantments;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;

/**
 * Xử lý enchant "Gravity Shift" trên giày.
 *
 * Khác với Bhopping/Dash, đây là enchant đơn giản: chỉ cần chặn hoàn toàn
 * fall damage, không cần vật lý tuỳ chỉnh — nên xử lý gọn trong
 * LivingFallEvent thay vì cần data component vanilla (feather falling chỉ
 * GIẢM damage, không triệt tiêu hoàn toàn được, nên vẫn cần code riêng).
 *
 * Khi giày có Gravity Shift: damage rơi bị triệt tiêu 100% (damageMultiplier
 * = 0) VÀ fallDistance được reset về 0, để không "dồn" lại cho lần rơi kế
 * tiếp nếu vì lý do gì đó player tạm thời tháo giày.
 */
@EventBusSubscriber(modid = HighV.MOD_ID)
public final class GravityShiftServerHandler {

    private GravityShiftServerHandler() {}

    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (ModEnchantments.getGravityShiftLevel(player) <= 0) return;

        event.setDamageMultiplier(0.0f);
        event.setDistance(0.0f);
    }
}
