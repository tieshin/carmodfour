package net.mcreator.carmodfour.network;

import net.mcreator.carmodfour.entity.CardemoEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Handles steering inputs (W, A, S, D) sent from the client to the server.
 * Updates input state flags; movement is processed per tick in the entity itself.
 */
public class SteeringInputPacket {
    private final int entityId;
    private final String key;
    private final boolean pressed; // true if key is pressed, false if released

    public SteeringInputPacket(int entityId, String key, boolean pressed) {
        this.entityId = entityId;
        this.key = key;
        this.pressed = pressed;
    }

    public SteeringInputPacket(FriendlyByteBuf buffer) {
        this.entityId = buffer.readInt();
        this.key = buffer.readUtf();
        this.pressed = buffer.readBoolean();
    }

    public void toBytes(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeUtf(key);
        buffer.writeBoolean(pressed);
    }

    public static void handle(SteeringInputPacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // ✅ Corrected field reference here (level, not level())
            Entity entity = player.level.getEntity(message.entityId);
            if (!(entity instanceof CardemoEntity car)) return;

            if (!car.isEngineOn() || car.getDriveState() != CardemoEntity.DriveState.DRIVE) return;

            switch (message.key.toUpperCase()) {
                case "W" -> car.setAccelerating(message.pressed);
                case "S" -> car.setBraking(message.pressed);
                case "A" -> car.setTurningLeft(message.pressed);
                case "D" -> car.setTurningRight(message.pressed);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
