package net.mcreator.carmodfour.client;

import net.mcreator.carmodfour.CarmodfourMod;
import net.mcreator.carmodfour.entity.CardemoEntity;
import net.mcreator.carmodfour.network.SteeringInputPacket;

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
public class SteeringKeybindHandler {

    public static final KeyMapping STEER_FORWARD = new KeyMapping("key.carmodfour.steer_forward", GLFW.GLFW_KEY_W, "key.categories.carmodfour");
    public static final KeyMapping STEER_BACKWARD = new KeyMapping("key.carmodfour.steer_backward", GLFW.GLFW_KEY_S, "key.categories.carmodfour");
    public static final KeyMapping STEER_LEFT = new KeyMapping("key.carmodfour.steer_left", GLFW.GLFW_KEY_A, "key.categories.carmodfour");
    public static final KeyMapping STEER_RIGHT = new KeyMapping("key.carmodfour.steer_right", GLFW.GLFW_KEY_D, "key.categories.carmodfour");

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(STEER_FORWARD);
        event.register(STEER_BACKWARD);
        event.register(STEER_LEFT);
        event.register(STEER_RIGHT);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        if (player.getVehicle() instanceof CardemoEntity car) {
            boolean engineOn = car.isEngineOn();
            boolean inDrive = car.getDriveState() == CardemoEntity.DriveState.DRIVE;
            if (!engineOn || !inDrive) return;

            // Press
            if (event.getAction() == GLFW.GLFW_PRESS) {
                sendPacket(car, event.getKey(), true);
            }
            // Release
            else if (event.getAction() == GLFW.GLFW_RELEASE) {
                sendPacket(car, event.getKey(), false);
            }
        }
    }

    private static void sendPacket(CardemoEntity car, int key, boolean pressed) {
        String keyChar = switch (key) {
            case GLFW.GLFW_KEY_W -> "W";
            case GLFW.GLFW_KEY_S -> "S";
            case GLFW.GLFW_KEY_A -> "A";
            case GLFW.GLFW_KEY_D -> "D";
            default -> null;
        };
        if (keyChar != null) {
            CarmodfourMod.PACKET_HANDLER.sendToServer(new SteeringInputPacket(car.getId(), keyChar, pressed));
        }
    }
}
