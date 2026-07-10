package net.huwng.highv.item;

import net.huwng.highv.entity.GrapplingHookEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Grappling Hook item.
 *
 * Item này chỉ là carrier — toàn bộ logic bắn/thu hồi được xử lý bởi:
 *  - ClientInputHandler: detect input + target ở client
 *  - GrapplingShootPacket: spawn entity trên server
 *  - GrapplingStatePacket: update pull state + look direction
 *
 * use() không còn được dùng để bắn (tránh double-fire với ClientInputHandler).
 */
public class GrapplingHookItem extends Item {

    public static final float SHOOT_SPEED = 6.0f;
    public static final float PULL_FORCE  = 0.35f;
    public static final float ROPE_LENGTH = 32f;
    public static final int   MAX_RANGE   = 64;

    public GrapplingHookItem() {
        super(new Properties().stacksTo(1).durability(250));
    }

    /** Tìm hook entity active của player trong level */
    public static GrapplingHookEntity findActiveHook(Level level, Player player) {
        return level.getEntitiesOfClass(
                GrapplingHookEntity.class,
                player.getBoundingBox().inflate(MAX_RANGE + 5),
                e -> e.isOwnedBy(player) && !e.isRemoved()
        ).stream().findFirst().orElse(null);
    }
}