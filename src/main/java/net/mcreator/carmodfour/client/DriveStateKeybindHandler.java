package net.mcreator.carmodfour.client;

import net.mcreator.carmodfour.CarmodfourMod;
import net.mcreator.carmodfour.network.DriveStateChangePacket;
import net.mcreator.carmodfour.entity.CardemoEntity;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = CarmodfourMod.MODID, value = Dist.CLIENT)
public class DriveStateKeybindHandler {

    public static final KeyMapping CYCLE_DRIVE_KEY = new KeyMapping(
            "key.carmodfour.cycle_drive_state",
            GLFW.GLFW_KEY_O,
            "key.categories.carmodfour"
    );

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(CYCLE_DRIVE_KEY);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        if (CYCLE_DRIVE_KEY.consumeClick()) {
            player.sendSystemMessage(Component.literal("§a[Client] O key pressed!"));

            if (player.getVehicle() instanceof CardemoEntity car) {
                // Cycle the drive state
                CardemoEntity.DriveState current = car.getDriveState();
                CardemoEntity.DriveState next;
                switch (current) {
                    case PARK -> next = CardemoEntity.DriveState.DRIVE;
                    case DRIVE -> next = CardemoEntity.DriveState.REVERSE;
                    case REVERSE -> next = CardemoEntity.DriveState.PARK;
                    default -> next = CardemoEntity.DriveState.PARK;
                }

                CarmodfourMod.LOGGER.info("Client pressed O while riding car ID {}. Cycling from {} to {}",
                        car.getId(), current.name(), next.name());

                // Send the new state to the server
                CarmodfourMod.PACKET_HANDLER.sendToServer(
                        new DriveStateChangePacket(car.getId(), next.name())
                );
            } else {
                player.sendSystemMessage(Component.literal("§c[Client] You are not in a car."));
            }
        }
    }
}
