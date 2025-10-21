package net.mcreator.carmodfour.network;

import net.mcreator.carmodfour.entity.CardemoEntity;
import net.mcreator.carmodfour.client.renderer.CardemoRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class HeadlightFlashPacket {
    private final int entityId;

    public HeadlightFlashPacket(int entityId) {
        this.entityId = entityId;
    }

    public static void encode(HeadlightFlashPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    public static HeadlightFlashPacket decode(FriendlyByteBuf buf) {
        return new HeadlightFlashPacket(buf.readInt());
    }

    public static void handle(HeadlightFlashPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                Entity e = mc.level.getEntity(msg.entityId);
                if (e instanceof CardemoEntity car) {
                    CardemoRenderer.triggerHeadlightFlash(car);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
