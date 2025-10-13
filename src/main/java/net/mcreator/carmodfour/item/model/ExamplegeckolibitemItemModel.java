package net.mcreator.carmodfour.item.model;

import software.bernie.geckolib3.model.AnimatedGeoModel;

import net.minecraft.resources.ResourceLocation;

import net.mcreator.carmodfour.item.ExamplegeckolibitemItem;

public class ExamplegeckolibitemItemModel extends AnimatedGeoModel<ExamplegeckolibitemItem> {
	@Override
	public ResourceLocation getAnimationResource(ExamplegeckolibitemItem animatable) {
		return new ResourceLocation("carmodfour", "animations/car_key.animation.json");
	}

	@Override
	public ResourceLocation getModelResource(ExamplegeckolibitemItem animatable) {
		return new ResourceLocation("carmodfour", "geo/car_key.geo.json");
	}

	@Override
	public ResourceLocation getTextureResource(ExamplegeckolibitemItem animatable) {
		return new ResourceLocation("carmodfour", "textures/items/carkey.png");
	}
}
