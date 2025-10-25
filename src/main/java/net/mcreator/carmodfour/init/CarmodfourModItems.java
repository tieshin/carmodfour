
/*
 *    MCreator note: This file will be REGENERATED on each build.
 */
package net.mcreator.carmodfour.init;

import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.common.ForgeSpawnEggItem;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTab;

import net.mcreator.carmodfour.item.ExamplegeckolibitemItem;
import net.mcreator.carmodfour.item.CarKeyItem;
import net.mcreator.carmodfour.item.CARSPAWNERItem;
import net.mcreator.carmodfour.CarmodfourMod;

public class CarmodfourModItems {
	public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, CarmodfourMod.MODID);
	public static final RegistryObject<Item> CARSPAWNER = REGISTRY.register("carspawner", () -> new CARSPAWNERItem());
	public static final RegistryObject<Item> EXAMPLEGECKOLIBITEM = REGISTRY.register("examplegeckolibitem", () -> new ExamplegeckolibitemItem());
}
