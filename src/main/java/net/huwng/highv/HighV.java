package net.huwng.highv;

import net.huwng.highv.entity.ModEntities;
import net.huwng.highv.item.ModItems;
import net.huwng.highv.network.ModNetwork;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.huwng.highv.client.HighVClientConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

@Mod(HighV.MOD_ID)
public class HighV {

    public static final String MOD_ID = "highv";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public HighV(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.ITEMS.register(modEventBus);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModNetwork.register(modEventBus);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, HighVClientConfig.SPEC);
            net.huwng.highv.client.ClientSetup.register(modEventBus);
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
