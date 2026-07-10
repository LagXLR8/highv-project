package net.huwng.highv.network;

import net.huwng.highv.HighV;
import net.huwng.highv.network.packet.BhopInputPacket;
import net.huwng.highv.network.packet.GrapplingShootPacket;
import net.huwng.highv.network.packet.GrapplingStatePacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModNetwork {

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModNetwork::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(HighV.MOD_ID).versioned("1");

        registrar.playToServer(
                GrapplingShootPacket.TYPE,
                GrapplingShootPacket.STREAM_CODEC,
                GrapplingShootPacket::handle
        );

        registrar.playToServer(
                GrapplingStatePacket.TYPE,
                GrapplingStatePacket.STREAM_CODEC,
                GrapplingStatePacket::handle
        );

        registrar.playToServer(
                BhopInputPacket.TYPE,
                BhopInputPacket.STREAM_CODEC,
                BhopInputPacket::handle
        );
    }
}