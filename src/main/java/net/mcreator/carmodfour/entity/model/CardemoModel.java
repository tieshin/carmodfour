package net.mcreator.carmodfour.entity.model;

import software.bernie.geckolib3.model.AnimatedGeoModel;

import net.minecraft.resources.ResourceLocation;

import net.mcreator.carmodfour.entity.CardemoEntity;

public class CardemoModel extends AnimatedGeoModel<CardemoEntity> {
	@Override
	public ResourceLocation getAnimationResource(CardemoEntity entity) {
		return new ResourceLocation("carmodfour", "animations/car_demo.animation.json");
	}

	@Override
	public ResourceLocation getModelResource(CardemoEntity entity) {
		return new ResourceLocation("carmodfour", "geo/car_demo.geo.json");
	}

	@Override
	public ResourceLocation getTextureResource(CardemoEntity entity) {
		return new ResourceLocation("carmodfour", "textures/entities/" + entity.getTexture() + ".png");
	}

}
