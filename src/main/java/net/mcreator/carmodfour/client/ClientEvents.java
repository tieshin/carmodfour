package net.mcreator.carmodfour.client;

import net.mcreator.carmodfour.CarmodfourMod;
import net.mcreator.carmodfour.network.DriveStateChangePacket;
import net.mcreator.carmodfour.entity.CardemoEntity;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = CarmodfourMod.MODID, value = Dist.CLIENT)
public class ClientEvents {

    // Up arrow = next drive state, Down arrow = previous drive state
    public static final KeyMapping DRIVE_UP_KEY = new KeyMapping(
            "key.carmodfour.drive_up",
            GLFW.GLFW_KEY_UP,
            "key.categories.carmodfour"
    );

    public static final KeyMapping DRIVE_DOWN_KEY = new KeyMapping(
            "key.carmodfour.drive_down",
            GLFW.GLFW_KEY_DOWN,
            "key.categories.carmodfour"
    );

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(DRIVE_UP_KEY);
        event.register(DRIVE_DOWN_KEY);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        if (player.getVehicle() instanceof CardemoEntity car) {

            // UP arrow: cycle forward
            if (DRIVE_UP_KEY.consumeClick()) {
                cycleDriveState(car, true);
            }

            // DOWN arrow: cycle backward
            if (DRIVE_DOWN_KEY.consumeClick()) {
                cycleDriveState(car, false);
            }
        }
    }

    private static void cycleDriveState(CardemoEntity car, boolean forward) {
        CardemoEntity.DriveState current = car.getDriveState();
        CardemoEntity.DriveState[] values = CardemoEntity.DriveState.values();
        int index = current.ordinal();
        index = forward ? (index + 1) % values.length : (index - 1 + values.length) % values.length;
        CardemoEntity.DriveState next = values[index];

        // Send the new state to the server
        CarmodfourMod.PACKET_HANDLER.sendToServer(
                new DriveStateChangePacket(car.getId(), next.name())
        );
    }
}