package net.mcreator.carmodfour.network;

import net.mcreator.carmodfour.entity.CardemoEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class HornSoundPacket {
    private final int entityId;

    public HornSoundPacket(int entityId) {
        this.entityId = entityId;
    }

    public HornSoundPacket(FriendlyByteBuf buf) {
        this.entityId = buf.readInt();
    }

    public static void encode(HornSoundPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    public static void handle(HornSoundPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender(); // Server side (because sendToServer)
            if (sender == null) return;
            var level = sender.getLevel();
            var entity = level.getEntity(msg.entityId);
            if (entity instanceof CardemoEntity car) {
                // Broadcast to all players near the car
                level.playSound(
                        null,                      // null = send to all nearby
                        car.getX(), car.getY(), car.getZ(),
                        SoundEvents.PILLAGER_AMBIENT,
                        SoundSource.PLAYERS,
                        1.0f,
                        1.0f
                );
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
