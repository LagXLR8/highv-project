package net.huwng.highv.network;

import net.huwng.highv.HighV;
import net.huwng.highv.network.packet.BhopInputPacket;
import net.huwng.highv.network.packet.DashRequestPacket;
import net.huwng.highv.network.packet.DriftInputPacket;
import net.huwng.highv.network.packet.DriftStateSyncPacket;
import net.huwng.highv.network.packet.GrapplingShootPacket;
import net.huwng.highv.network.packet.GrapplingStatePacket;
import net.huwng.highv.network.packet.RicochetRequestPacket;
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

        registrar.playToServer(
                DashRequestPacket.TYPE,
                DashRequestPacket.STREAM_CODEC,
                DashRequestPacket::handle
        );

        registrar.playToServer(
                DriftInputPacket.TYPE,
                DriftInputPacket.STREAM_CODEC,
                DriftInputPacket::handle
        );

        // Packet ĐẦU TIÊN của mod này đi chiều Server -> Client (mọi packet
        // khác ở trên đều là Client -> Server). Xem doc comment
        // DriftStateSyncPacket để biết lý do cần packet riêng thay vì suy ra
        // qua Pose.
        registrar.playToClient(
                DriftStateSyncPacket.TYPE,
                DriftStateSyncPacket.STREAM_CODEC,
                DriftStateSyncPacket::handle
        );

        registrar.playToServer(
                RicochetRequestPacket.TYPE,
                RicochetRequestPacket.STREAM_CODEC,
                RicochetRequestPacket::handle
        );

        registrar.playToServer(
                net.huwng.highv.network.packet.SlideJumpPacket.TYPE,
                net.huwng.highv.network.packet.SlideJumpPacket.STREAM_CODEC,
                net.huwng.highv.network.packet.SlideJumpPacket::handle
        );
    }
}