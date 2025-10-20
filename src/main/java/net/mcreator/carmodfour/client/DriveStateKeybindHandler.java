package net.mcreator.carmodfour.client;

import net.mcreator.carmodfour.CarmodfourMod;
import net.mcreator.carmodfour.network.DriveStateChangePacket;
import net.mcreator.carmodfour.entity.CardemoEntity;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = CarmodfourMod.MODID, value = Dist.CLIENT)
public class DriveStateKeybindHandler {

    // -------------------------------------------------------------------------
    // KEY MAPPINGS
    // -------------------------------------------------------------------------
    public static final KeyMapping CYCLE_DRIVE_KEY = new KeyMapping(
            "key.carmodfour.cycle_drive_state",
            GLFW.GLFW_KEY_O,
            "key.categories.carmodfour"
    );

    public static final KeyMapping HORN_KEY = new KeyMapping(
            "key.carmodfour.vehicle_horn",
            GLFW.GLFW_KEY_H,
            "key.categories.carmodfour"
    );

    // -------------------------------------------------------------------------
    // REGISTER
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(CYCLE_DRIVE_KEY);
        event.register(HORN_KEY);
    }

    // -------------------------------------------------------------------------
    // STATE
    // -------------------------------------------------------------------------
    private static boolean prevHornDown = false;

    // -------------------------------------------------------------------------
    // INPUT HANDLER — drive state cycling
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // (1) Cycle drive states using O key (original behavior)
        if (CYCLE_DRIVE_KEY.consumeClick()) {
            player.sendSystemMessage(Component.literal("§a[Client] O key pressed!"));

            if (player.getVehicle() instanceof CardemoEntity car) {
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

                CarmodfourMod.PACKET_HANDLER.sendToServer(
                        new DriveStateChangePacket(car.getId(), next.name())
                );
            } else {
                player.sendSystemMessage(Component.literal("§c[Client] You are not in a car."));
            }
        }
    }

    // -------------------------------------------------------------------------
    // CLIENT TICK HANDLER — single horn press sound
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        if (!(player.getVehicle() instanceof CardemoEntity car)) {
            prevHornDown = false;
            return;
        }

        boolean isDown = HORN_KEY.isDown();

        // Only trigger once per press
        if (isDown && !prevHornDown) {
            if (car.level.isClientSide) {
                car.level.playLocalSound(
                        car.getX(),
                        car.getY(),
                        car.getZ(),
                        SoundEvents.PILLAGER_AMBIENT, // single “Hrnngh!”
                        SoundSource.PLAYERS,
                        1.0f,
                        1.0f,
                        false
                );
            }
        }

        prevHornDown = isDown;
    }
}
