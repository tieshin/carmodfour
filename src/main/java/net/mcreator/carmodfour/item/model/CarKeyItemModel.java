package net.mcreator.carmodfour.item.model;

import software.bernie.geckolib3.model.AnimatedGeoModel;

import net.minecraft.resources.ResourceLocation;

import net.mcreator.carmodfour.item.CarKeyItem;

public class CarKeyItemModel extends AnimatedGeoModel<CarKeyItem> {
	@Override
	public ResourceLocation getAnimationResource(CarKeyItem animatable) {
		return new ResourceLocation("carmodfour", "animations/car_key.animation.json");
	}

	@Override
	public ResourceLocation getModelResource(CarKeyItem animatable) {
		return new ResourceLocation("carmodfour", "geo/car_key.geo.json");
	}

	@Override
	public ResourceLocation getTextureResource(CarKeyItem animatable) {
		return new ResourceLocation("carmodfour", "textures/items/carkey.png");
	}
}
