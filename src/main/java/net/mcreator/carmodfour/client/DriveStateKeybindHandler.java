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

    public static final KeyMapping LEFT_SIGNAL_KEY = new KeyMapping(
            "key.carmodfour.signal_left",
            GLFW.GLFW_KEY_LEFT,
            "key.categories.carmodfour"
    );

    public static final KeyMapping RIGHT_SIGNAL_KEY = new KeyMapping(
            "key.carmodfour.signal_right",
            GLFW.GLFW_KEY_RIGHT,
            "key.categories.carmodfour"
    );

    // -------------------------------------------------------------------------
    // REGISTER
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(CYCLE_DRIVE_KEY);
        event.register(HORN_KEY);
        event.register(DRIVE_UP_KEY);
        event.register(DRIVE_DOWN_KEY);
        event.register(LEFT_SIGNAL_KEY);
        event.register(RIGHT_SIGNAL_KEY);
    }

    // -------------------------------------------------------------------------
    // STATE
    // -------------------------------------------------------------------------
    private static boolean prevHornDown = false;

    // -------------------------------------------------------------------------
    // TURN SIGNAL STATE (client-only)
    // -------------------------------------------------------------------------
    private static boolean leftSignalOn = false;
    private static boolean rightSignalOn = false;
    private static int signalTick = 0;
    private static boolean signalVisible = true;

    // -------------------------------------------------------------------------
    // BRAKE LIGHT INTENSITY (client-only, smooth fade)
    // -------------------------------------------------------------------------
    private static boolean braking = false;
    private static float brakeIntensity = 0.0f; // 0 = off, 1 = full glow

    // -------------------------------------------------------------------------
    // INPUT HANDLER — drive state cycling + lever sounds + signals + brakes
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // ---------------------------------------------------------------------
        // Legacy O-key cycle (P -> D -> R -> P)
        // ---------------------------------------------------------------------
        if (CYCLE_DRIVE_KEY.consumeClick()) {
            if (player.getVehicle() instanceof CardemoEntity car) {
                CardemoEntity.DriveState current = car.getDriveState();
                CardemoEntity.DriveState next = CardemoEntity.DriveState.PARK;

                switch (current) {
                    case PARK:
                        next = CardemoEntity.DriveState.DRIVE;
                        break;
                    case DRIVE:
                        next = CardemoEntity.DriveState.REVERSE;
                        break;
                    case REVERSE:
                        next = CardemoEntity.DriveState.PARK;
                        break;
                    default:
                        break;
                }

                player.level.playLocalSound(
                        car.getX(), car.getY(), car.getZ(),
                        SoundEvents.LEVER_CLICK, SoundSource.PLAYERS,
                        0.8f, 1.0f, false
                );

                CarmodfourMod.PACKET_HANDLER.sendToServer(
                        new DriveStateChangePacket(car.getId(), next.name())
                );
            } else {
                player.sendSystemMessage(Component.literal("§c[Client] You are not in a car."));
            }
        }

        // ---------------------------------------------------------------------
        // Turn signal toggles (client-only visual/audio)
        // ---------------------------------------------------------------------
        if (LEFT_SIGNAL_KEY.consumeClick()) {
            leftSignalOn = !leftSignalOn;
            if (leftSignalOn) rightSignalOn = false;
            signalTick = 0;
            signalVisible = true;
            player.playSound(SoundEvents.LEVER_CLICK, 0.5f, leftSignalOn ? 1.0f : 0.85f);
        }

        if (RIGHT_SIGNAL_KEY.consumeClick()) {
            rightSignalOn = !rightSignalOn;
            if (rightSignalOn) leftSignalOn = false;
            signalTick = 0;
            signalVisible = true;
            player.playSound(SoundEvents.LEVER_CLICK, 0.5f, rightSignalOn ? 1.0f : 0.85f);
        }

        // ---------------------------------------------------------------------
        // Up/Down arrows imitate a gated shifter with lever sounds
        // ---------------------------------------------------------------------
        if (DRIVE_UP_KEY.consumeClick()) {
            if (player.getVehicle() instanceof CardemoEntity car) {
                player.level.playLocalSound(
                        car.getX(), car.getY(), car.getZ(),
                        SoundEvents.LEVER_CLICK, SoundSource.PLAYERS,
                        0.9f, 1.2f, false
                );

                CardemoEntity.DriveState current = car.getDriveState();
                CardemoEntity.DriveState next = current;

                switch (current) {
                    case PARK:
                        next = CardemoEntity.DriveState.DRIVE;
                        break;
                    case DRIVE:
                        next = CardemoEntity.DriveState.REVERSE;
                        break;
                    case REVERSE:
                        next = CardemoEntity.DriveState.REVERSE;
                        break;
                    default:
                        break;
                }

                CarmodfourMod.PACKET_HANDLER.sendToServer(
                        new DriveStateChangePacket(car.getId(), next.name())
                );
            }
        }

        if (DRIVE_DOWN_KEY.consumeClick()) {
            if (player.getVehicle() instanceof CardemoEntity car) {
                player.level.playLocalSound(
                        car.getX(), car.getY(), car.getZ(),
                        SoundEvents.LEVER_CLICK, SoundSource.PLAYERS,
                        0.9f, 0.8f, false
                );

                CardemoEntity.DriveState current = car.getDriveState();
                CardemoEntity.DriveState next = current;

                switch (current) {
                    case REVERSE:
                        next = CardemoEntity.DriveState.DRIVE;
                        break;
                    case DRIVE:
                        next = CardemoEntity.DriveState.PARK;
                        break;
                    case PARK:
                        next = CardemoEntity.DriveState.PARK;
                        break;
                    default:
                        break;
                }

                CarmodfourMod.PACKET_HANDLER.sendToServer(
                        new DriveStateChangePacket(car.getId(), next.name())
                );
            }
        }

        // ---------------------------------------------------------------------
        // Brake key detection (S key)
        // ---------------------------------------------------------------------
        if (event.getKey() == GLFW.GLFW_KEY_S) {
            braking = (event.getAction() != GLFW.GLFW_RELEASE);
        }
    }

    // -------------------------------------------------------------------------
    // CLIENT TICK — horn press sound + signal blink cadence + brake fade
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.level == null || mc.isPaused() || mc.screen != null) return;

        // --- Horn one-shot when key goes down ---
        CardemoEntity car = (player.getVehicle() instanceof CardemoEntity c) ? c : null;
        if (car != null) {
            boolean isDown = HORN_KEY.isDown();
            if (isDown && !prevHornDown) {
                car.level.playLocalSound(
                        car.getX(), car.getY(), car.getZ(),
                        SoundEvents.PILLAGER_AMBIENT,
                        SoundSource.PLAYERS,
                        1.0f, 1.0f, false
                );
            }
            prevHornDown = isDown;
        } else {
            prevHornDown = false;
        }

        // --- Turn Signal Flicker (client-only) ---
        if (leftSignalOn || rightSignalOn) {
            signalTick++;
            if (signalTick >= 10) {
                signalTick = 0;
                signalVisible = !signalVisible;
                if (!mc.isPaused() && mc.screen == null && mc.level != null) {
                    float pitch = signalVisible ? 1.0f : 0.8f;
                    player.playSound(SoundEvents.UI_BUTTON_CLICK, 0.35f, pitch);
                }
            }
        } else {
            signalVisible = true;
        }

        // --- Smooth brake light fade ---
        float target = braking ? 1.0f : 0.0f;
        float rate = braking ? 0.25f : 0.15f;
        brakeIntensity += (target - brakeIntensity) * rate;
        if (brakeIntensity < 0.001f) brakeIntensity = 0.0f;
        if (brakeIntensity > 0.999f) brakeIntensity = 1.0f;
    }

    // -------------------------------------------------------------------------
    // ACCESSORS
    // -------------------------------------------------------------------------
    public static boolean isLeftSignalVisible() {
        return leftSignalOn && signalVisible;
    }

    public static boolean isRightSignalVisible() {
        return rightSignalOn && signalVisible;
    }

    public static boolean isLeftSignalOn() {
        return leftSignalOn;
    }

    public static boolean isRightSignalOn() {
        return rightSignalOn;
    }

    public static boolean isBraking() {
        return braking;
    }

    public static float getBrakeIntensity() {
        return brakeIntensity;
    }
}
