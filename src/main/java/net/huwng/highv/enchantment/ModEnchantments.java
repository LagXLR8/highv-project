package net.huwng.highv.enchantment;

import net.huwng.highv.HighV;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Registry key + helper cho enchantment "Bhopping".
 *
 * Bhopping là data-driven enchantment (định nghĩa trong
 * data/highv/enchantment/bhopping.json), gắn trên giày (feet armor).
 * Toàn bộ hiệu ứng gameplay (momentum, air-strafe, turn-break) được
 * xử lý bằng code trong BhopClientHandler / BhopServerHandler vì
 * effect component data-driven của vanilla không đủ để làm vật lý
 * di chuyển tuỳ chỉnh dạng này.
 */
public class ModEnchantments {

    public static final ResourceKey<Enchantment> BHOPPING =
            ResourceKey.create(Registries.ENCHANTMENT, HighV.id("bhopping"));

    /**
     * Trả về level của Bhopping trên giày người chơi đang mặc (0 nếu không có).
     * Dùng được cả ở client (để quyết định có gửi packet input hay không)
     * lẫn ở server (để tính toán momentum).
     */
    public static int getBhopLevel(Player player) {
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        if (boots.isEmpty()) return 0;

        Holder<Enchantment> holder = player.level()
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(BHOPPING)
                .orElse(null);
        if (holder == null) return 0;

        return boots.getEnchantmentLevel(holder);
    }
}