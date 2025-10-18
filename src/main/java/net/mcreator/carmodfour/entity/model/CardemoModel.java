package net.mcreator.carmodfour.entity.model;

import software.bernie.geckolib3.model.AnimatedGeoModel;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.processor.IBone;
import net.minecraft.resources.ResourceLocation;
import net.mcreator.carmodfour.entity.CardemoEntity;

public class CardemoModel extends AnimatedGeoModel<CardemoEntity> {

    @Override
    public ResourceLocation getAnimationResource(CardemoEntity entity) {
        // No actual animations used — placeholder JSON
        return new ResourceLocation("carmodfour", "animations/blank.animation.json");
    }

    @Override
    public ResourceLocation getModelResource(CardemoEntity entity) {
        return new ResourceLocation("carmodfour", "geo/car_demo.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CardemoEntity entity) {
        return new ResourceLocation("carmodfour", "textures/entities/" + entity.getTexture() + ".png");
    }

    // No @Override — GeckoLib 3.1.39 quirk
    public void setLivingAnimations(CardemoEntity entity, Integer uniqueID, AnimationEvent<CardemoEntity> event) {
        super.setLivingAnimations(entity, uniqueID, event);

        // === DOOR VISUAL TEST ===
        // Fetch right and left door bones
        IBone rightDoor = this.getAnimationProcessor().getBone("r_door");
        IBone leftDoor  = this.getAnimationProcessor().getBone("l_door");

        // Determine the target angle — open = 45°, closed = 0°
        float doorTargetDeg = entity.isDoorOpen() ? 45F : 0F;
        float doorTargetRad = (float) Math.toRadians(doorTargetDeg);

        // Smoothly interpolate the current door rotation toward the target
        float smoothing = 0.25F;

        if (rightDoor != null) {
            float current = rightDoor.getRotationY();
            float updated = current + (doorTargetRad - current) * smoothing;
            rightDoor.setRotationY(updated);
        }

        if (leftDoor != null) {
            float current = leftDoor.getRotationY();
            float updated = current + (-doorTargetRad - current) * smoothing;
            leftDoor.setRotationY(updated);
        }
    }
}
