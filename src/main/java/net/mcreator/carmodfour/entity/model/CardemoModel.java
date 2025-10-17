package net.mcreator.carmodfour.entity.model;

import software.bernie.geckolib3.model.AnimatedGeoModel;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.processor.IBone;

import net.minecraft.resources.ResourceLocation;
import net.mcreator.carmodfour.entity.CardemoEntity;

@SuppressWarnings("deprecation") // setLivingAnimations is deprecated in 3.1.39, but still correct for 1.19.2
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
        return new ResourceLocation("carmodfour",
                "textures/entities/" + entity.getTexture() + ".png");
    }

    @SuppressWarnings("deprecation")
    public void setLivingAnimations(CardemoEntity entity, Integer uniqueID, AnimationEvent<CardemoEntity> event) {
        super.setLivingAnimations(entity, uniqueID, event);

        // Get the top-level "car" bone from your model
        IBone root = this.getAnimationProcessor().getBone("car");
        if (root != null) {
            // Convert entity pitch to radians for GeckoLib
            float pitch = entity.getXRot();

            // Smooth out rotation to prevent snapping
            float current = root.getRotationX();
            float target = (float) Math.toRadians(pitch);
            float smooth = current + (target - current) * 0.2F; // smaller factor = smoother

            root.setRotationX(smooth);
        }
    }
}
