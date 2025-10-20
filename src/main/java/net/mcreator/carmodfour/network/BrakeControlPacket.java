package net.mcreator.carmodfour.network;

import net.mcreator.carmodfour.entity.CardemoEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class BrakeControlPacket {
    private final int entityId;
    private final boolean braking;

    public BrakeControlPacket(int entityId, boolean braking) {
        this.entityId = entityId;
        this.braking = braking;
    }

    public static void encode(BrakeControlPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeBoolean(msg.braking);
    }

    public static BrakeControlPacket decode(FriendlyByteBuf buf) {
        return new BrakeControlPacket(buf.readInt(), buf.readBoolean());
    }

    public static void handle(BrakeControlPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender == null) return;
            if (!(sender.level instanceof ServerLevel serverLevel)) return;

            Entity e = serverLevel.getEntity(msg.entityId);
            if (!(e instanceof CardemoEntity car)) return;

            // Only allow the controlling passenger to affect braking
            if (!car.hasPassenger(sender)) return;

            // Server-authoritative: braking ON should cancel accelerating
            car.setBraking(msg.braking);
            if (msg.braking) {
                car.setAccelerating(false);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
