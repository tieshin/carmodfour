package net.mcreator.carmodfour.network;

import net.mcreator.carmodfour.entity.CardemoEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * ============================================================================
 *  HeadlightBrightnessPacket.java — Client → Server packet
 * ============================================================================
 *
 *  Purpose:
 *  --------
 *  • Sent whenever the player presses the "L" key while driving.
 *  • Cycles the car's headlight brightness mode (L0 → L1 → L2 → L3 → L0).
 *  • Valid only when the player is currently riding a CardemoEntity.
 *
 *  Client flow:
 *      DriveStateKeybindHandler → (press L) →
 *          CarmodfourMod.PACKET_HANDLER.sendToServer(new HeadlightBrightnessPacket(carId));
 *
 *  Server flow:
 *      handle() → retrieve car entity → call cycleHeadlightMode() on it.
 * ============================================================================
 */
public class HeadlightBrightnessPacket {
    private final int entityId;

    // -------------------------------------------------------------------------
    // CONSTRUCTOR
    // -------------------------------------------------------------------------
    public HeadlightBrightnessPacket(int entityId) {
        this.entityId = entityId;
    }

    // -------------------------------------------------------------------------
    // ENCODE / DECODE
    // -------------------------------------------------------------------------
    public static void encode(HeadlightBrightnessPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    public static HeadlightBrightnessPacket decode(FriendlyByteBuf buf) {
        return new HeadlightBrightnessPacket(buf.readInt());
    }

    // -------------------------------------------------------------------------
    // HANDLE (Server-side only)
    // -------------------------------------------------------------------------
    public static void handle(HeadlightBrightnessPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // ✅ Use field access (not level()) — correct for server side
            Entity entity = player.level.getEntity(msg.entityId);
            if (entity instanceof CardemoEntity car) {
                car.cycleHeadlightMode(); // Advance brightness (L0→L1→L2→L3→L0)
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
