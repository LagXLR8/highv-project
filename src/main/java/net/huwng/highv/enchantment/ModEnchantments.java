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

    public static final ResourceKey<Enchantment> DASH =
            ResourceKey.create(Registries.ENCHANTMENT, HighV.id("dash"));

    public static final ResourceKey<Enchantment> DRIFT =
            ResourceKey.create(Registries.ENCHANTMENT, HighV.id("drift"));

    public static final ResourceKey<Enchantment> GRAVITY_SHIFT =
            ResourceKey.create(Registries.ENCHANTMENT, HighV.id("gravity_shift"));

    public static final ResourceKey<Enchantment> RICOCHET =
            ResourceKey.create(Registries.ENCHANTMENT, HighV.id("ricochet"));

    public static final ResourceKey<Enchantment> WALLSTRIDE =
            ResourceKey.create(Registries.ENCHANTMENT, HighV.id("wallstride"));

    /**
     * Trả về level của Dash trên giày người chơi đang mặc (0 nếu không có).
     * Giống hệt cơ chế lookup của getBhopLevel — dùng được cả client lẫn server.
     */
    public static int getDashLevel(Player player) {
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        if (boots.isEmpty()) return 0;

        Holder<Enchantment> holder = player.level()
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(DASH)
                .orElse(null);
        if (holder == null) return 0;

        return boots.getEnchantmentLevel(holder);
    }

    /**
     * Trả về level của Drift trên giày người chơi đang mặc (0 nếu không có).
     * Dùng được cả client (client-side animation prediction) lẫn server
     * (vật lý trượt) — giống hệt cơ chế lookup của getBhopLevel/getDashLevel.
     */
    public static int getDriftLevel(Player player) {
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        if (boots.isEmpty()) return 0;

        Holder<Enchantment> holder = player.level()
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(DRIFT)
                .orElse(null);
        if (holder == null) return 0;

        return boots.getEnchantmentLevel(holder);
    }

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

    /**
     * Trả về level của Gravity Shift trên giày người chơi đang mặc (0 nếu không có).
     */
    public static int getGravityShiftLevel(Player player) {
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        if (boots.isEmpty()) return 0;

        Holder<Enchantment> holder = player.level()
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(GRAVITY_SHIFT)
                .orElse(null);
        if (holder == null) return 0;

        return boots.getEnchantmentLevel(holder);
    }

    /**
     * Trả về level của Ricochet trên giày người chơi đang mặc (0 nếu không có).
     */
    public static int getRicochetLevel(Player player) {
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        if (boots.isEmpty()) return 0;

        Holder<Enchantment> holder = player.level()
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(RICOCHET)
                .orElse(null);
        if (holder == null) return 0;

        return boots.getEnchantmentLevel(holder);
    }

    /**
     * Trả về level của Wallstride trên giày người chơi đang mặc (0 nếu không có).
     */
    public static int getWallstrideLevel(Player player) {
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        if (boots.isEmpty()) return 0;

        Holder<Enchantment> holder = player.level()
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(WALLSTRIDE)
                .orElse(null);
        if (holder == null) return 0;

        return boots.getEnchantmentLevel(holder);
    }
}