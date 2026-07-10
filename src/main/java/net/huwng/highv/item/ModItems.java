package net.huwng.highv.item;

import net.huwng.highv.HighV;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, HighV.MOD_ID);

    public static final DeferredHolder<Item, GrapplingHookItem> GRAPPLING_HOOK =
            ITEMS.register("grappling_hook", GrapplingHookItem::new);

    public static final DeferredHolder<Item, ThermalKatanaItem> THERMAL_KATANA =
            ITEMS.register("thermal_katana", ThermalKatanaItem::new);
}
