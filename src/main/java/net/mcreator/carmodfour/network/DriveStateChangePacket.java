package net.mcreator.carmodfour.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.mcreator.carmodfour.entity.CardemoEntity;

import java.util.function.Supplier;

/**
 * Handles drive state changes sent from the client to the server.
 * When the player presses a keybind to change the drive mode,
 * this packet updates the corresponding CardemoEntity server-side.
 */
public class DriveStateChangePacket {
    private final int entityId;
    private final String newState;

    // --- Constructors ---
    public DriveStateChangePacket(int entityId, String newState) {
        this.entityId = entityId;
        this.newState = newState;
    }

    public DriveStateChangePacket(FriendlyByteBuf buffer) {
        this.entityId = buffer.readInt();
        this.newState = buffer.readUtf();
    }

    // --- Serialization ---
    public void toBytes(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeUtf(newState);
    }

    // --- Handling ---
    public static void handle(DriveStateChangePacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            Entity entity = player.level.getEntity(message.entityId);
            if (entity instanceof CardemoEntity car) {
                try {
                    CardemoEntity.DriveState newDriveState = CardemoEntity.DriveState.valueOf(message.newState);
                    car.setDriveState(newDriveState);
                } catch (IllegalArgumentException e) {
                    // Ignore invalid drive state values safely
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
