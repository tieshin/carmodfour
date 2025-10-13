package net.mcreator.carmodfour.item.model;

import software.bernie.geckolib3.model.AnimatedGeoModel;

import net.minecraft.resources.ResourceLocation;

import net.mcreator.carmodfour.item.CARSPAWNERItem;

public class CARSPAWNERItemModel extends AnimatedGeoModel<CARSPAWNERItem> {
	@Override
	public ResourceLocation getAnimationResource(CARSPAWNERItem animatable) {
		return new ResourceLocation("carmodfour", "animations/car_demo.animation.json");
	}

	@Override
	public ResourceLocation getModelResource(CARSPAWNERItem animatable) {
		return new ResourceLocation("carmodfour", "geo/car_demo.geo.json");
	}

	@Override
	public ResourceLocation getTextureResource(CARSPAWNERItem animatable) {
		return new ResourceLocation("carmodfour", "textures/items/cardemo.png");
	}
}
