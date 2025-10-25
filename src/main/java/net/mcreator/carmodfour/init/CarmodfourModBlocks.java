package net.mcreator.carmodfour.init;

import net.mcreator.carmodfour.CarmodfourMod;
import net.mcreator.carmodfour.block.InvisibleHeadlightBlock;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.block.SoundType;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * CarmodfourModBlocks — Registers all custom blocks for the mod.
 *
 * This ensures that INVIS_HEADLIGHT is actually registered and accessible
 * both through Forge registries and in-game commands.
 */
public class CarmodfourModBlocks {

    // -------------------------------------------------------------------------
    // REGISTRY SETUP
    // -------------------------------------------------------------------------
    public static final DeferredRegister<Block> REGISTRY =
            DeferredRegister.create(ForgeRegistries.BLOCKS, CarmodfourMod.MODID);

    // -------------------------------------------------------------------------
    // INVISIBLE HEADLIGHT BLOCK
    // -------------------------------------------------------------------------
    public static final RegistryObject<Block> INVIS_HEADLIGHT = REGISTRY.register(
            "invis_headlight",
            // ✅ Uses the no-argument constructor from InvisibleHeadlightBlock
            InvisibleHeadlightBlock::new
    );

    // -------------------------------------------------------------------------
    // REGISTRATION ENTRYPOINT
    // -------------------------------------------------------------------------
    public static void register(IEventBus eventBus) {
        REGISTRY.register(eventBus);
    }
}
