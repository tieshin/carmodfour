package net.mcreator.carmodfour.init;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingEvent;

import net.mcreator.carmodfour.entity.CardemoEntity;

@Mod.EventBusSubscriber
public class EntityAnimationFactory {

    @SubscribeEvent
    public static void onEntityTick(LivingEvent.LivingTickEvent event) {
        if (event != null && event.getEntity() instanceof CardemoEntity syncable) {
            String animation = syncable.getSyncedAnimation();

            if (animation != null && !animation.equals("undefined")) {
                // Reset the synced animation so it doesn’t trigger repeatedly
                syncable.setAnimation("undefined");

                // Correctly apply the animation to the entity
                syncable.setAnimationProcedure(animation);
            }
        }
    }
}
