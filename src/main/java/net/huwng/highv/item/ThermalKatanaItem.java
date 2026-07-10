package net.huwng.highv.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;

/**
 * Thermal Katana.
 *
 * Damage gốc RẤT thấp khi đứng yên (tổng 5.0), nhưng sẽ được cộng thêm
 * damage lớn dựa theo tốc độ di chuyển ngang của người chơi lúc đánh trúng
 * (chạy, rơi, momentum từ grappling hook...). Xem {@link net.huwng.highv.event.ThermalKatanaCombatHandler}
 * cho logic scale damage theo tốc độ.
 *
 * Animation vung/chém giờ do Better Combat đảm nhiệm — item này không còn
 * renderer/model GeckoLib riêng, chỉ là SwordItem chuẩn để Better Combat
 * tự nhận diện và áp animation combo.
 */
public class ThermalKatanaItem extends SwordItem {

    /** Damage cộng thêm base (trước tier bonus). Tổng damage đứng yên = 1.0 (base) + 2.0 (đây) + 2.0 (tier IRON) = 5.0 */
    private static final float BASE_DAMAGE_ADD = 2.0f;
    private static final float ATTACK_SPEED    = -0.6f;

    public ThermalKatanaItem() {
        super(Tiers.IRON, new Item.Properties()
                .attributes(SwordItem.createAttributes(Tiers.IRON, BASE_DAMAGE_ADD, ATTACK_SPEED))
                .durability(300));
    }
}
