package net.mcreator.carmodfour;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkEvent;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import software.bernie.geckolib3.GeckoLib;

import net.mcreator.carmodfour.init.CarmodfourModItems;
import net.mcreator.carmodfour.init.CarmodfourModEntities;
import net.mcreator.carmodfour.init.CarmodfourModBlocks;
import net.mcreator.carmodfour.network.*;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.List;
import java.util.ArrayList;
import java.util.AbstractMap;

/**
 * =============================================================================
 *  Tieshin's Car Mod 1.19.2 ALPHA — Core Mod Entrypoint (Forge)
 * =============================================================================
 *
 * Provides:
 *   ✓ Registry initialization (Blocks, Items, Entities)
 *   ✓ GeckoLib initialization
 *   ✓ Network packet registration
 *   ✓ Server work queue processing
 *
 * This Alpha focuses heavily on vehicle mechanics:
 *   • Realistic terrain tilt & wall-aware roll
 *   • Headlight projection with invisible light blocks
 *   • Bump recoil and kinetic collision damage
 *   • Silent damage handling
 *   • Locking / ownership / door interaction systems
 *
 * Note:
 *   Internal modId remains "carmodfour" to preserve registry namespaces.
 * =============================================================================
 */
@Mod(CarmodfourMod.MODID)
public class CarmodfourMod {

    // -------------------------------------------------------------------------
    // INTERNAL NAMESPACE (unchanged to prevent registry breakage)
    // -------------------------------------------------------------------------
    public static final String MODID = "carmodfour";

    // Updated logger name to reflect new branding
    public static final Logger LOGGER = LogManager.getLogger("TieshinCarMod");

    // -------------------------------------------------------------------------
    // NETWORK CHANNEL SETUP
    // -------------------------------------------------------------------------
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel PACKET_HANDLER = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int messageID = 0;

    // -------------------------------------------------------------------------
    // CONSTRUCTOR — initialization entry point
    // -------------------------------------------------------------------------
    public CarmodfourMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

        // ✅ Register all core content
        CarmodfourModBlocks.register(bus);
        CarmodfourModItems.REGISTRY.register(bus);
        CarmodfourModEntities.REGISTRY.register(bus);

        // ✅ Initialize GeckoLib for animation support
        GeckoLib.initialize();

        // ✅ Subscribe to Forge’s event bus
        MinecraftForge.EVENT_BUS.register(this);

        // ✅ Register custom network packets
        registerPackets();

        // Updated log output
        LOGGER.info("[Tieshin's Car Mod] Initialization complete — Alpha mechanics engaged!");
    }

    // -------------------------------------------------------------------------
    // PACKET REGISTRATION
    // -------------------------------------------------------------------------
    private void registerPackets() {

        // --- Drive state changes (PARK / DRIVE / REVERSE)
        addNetworkMessage(
                DriveStateChangePacket.class,
                DriveStateChangePacket::toBytes,
                DriveStateChangePacket::new,
                DriveStateChangePacket::handle
        );

        // --- Steering input sync
        addNetworkMessage(
                SteeringInputPacket.class,
                SteeringInputPacket::toBytes,
                SteeringInputPacket::new,
                SteeringInputPacket::handle
        );

        // --- Brake control packet
        addNetworkMessage(
                BrakeControlPacket.class,
                BrakeControlPacket::encode,
                BrakeControlPacket::decode,
                BrakeControlPacket::handle
        );

        // --- Headlight flash packet
        addNetworkMessage(
                HeadlightFlashPacket.class,
                HeadlightFlashPacket::encode,
                HeadlightFlashPacket::decode,
                HeadlightFlashPacket::handle
        );

        // --- Headlight brightness packet
        addNetworkMessage(
                HeadlightBrightnessPacket.class,
                HeadlightBrightnessPacket::encode,
                HeadlightBrightnessPacket::decode,
                HeadlightBrightnessPacket::handle
        );

        LOGGER.info("[Tieshin's Car Mod] ✅ Network packets registered.");
    }

    // -------------------------------------------------------------------------
    // GENERIC NETWORK REGISTRATION WRAPPER
    // -------------------------------------------------------------------------
    public static <T> void addNetworkMessage(
            Class<T> messageType,
            BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, Supplier<NetworkEvent.Context>> handler
    ) {
        PACKET_HANDLER.registerMessage(messageID++, messageType, encoder, decoder, handler);
    }

    // -------------------------------------------------------------------------
    // SERVER WORK QUEUE (standard MCreator-style)
    // -------------------------------------------------------------------------
    private static final ConcurrentLinkedQueue<AbstractMap.SimpleEntry<Runnable, Integer>> workQueue =
            new ConcurrentLinkedQueue<>();

    public static void queueServerWork(int tickDelay, Runnable action) {
        workQueue.add(new AbstractMap.SimpleEntry<>(action, tickDelay));
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            List<AbstractMap.SimpleEntry<Runnable, Integer>> actions = new ArrayList<>();
            workQueue.forEach(entry -> {
                entry.setValue(entry.getValue() - 1);
                if (entry.getValue() <= 0)
                    actions.add(entry);
            });
            actions.forEach(e -> e.getKey().run());
            workQueue.removeAll(actions);
        }
    }
}