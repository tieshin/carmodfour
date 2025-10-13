package net.mcreator.carmodfour.item;

import software.bernie.geckolib3.util.GeckoLibUtil;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.IAnimatable;

import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;

import net.mcreator.carmodfour.entity.CardemoEntity;

import java.util.List;

public class CarKeyItem extends Item implements IAnimatable {
    private final AnimationFactory factory = GeckoLibUtil.createFactory(this);

    public CarKeyItem() {
        super(new Item.Properties().tab(CreativeModeTab.TAB_MISC).stacksTo(64).rarity(Rarity.COMMON));
    }

    // --------------------------
    // Right-click to toggle nearby car locks
    // --------------------------
    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!world.isClientSide) {
            List<CardemoEntity> nearbyCars = world.getEntitiesOfClass(
                    CardemoEntity.class,
                    new AABB(
                            player.getX() - 5, player.getY() - 5, player.getZ() - 5,
                            player.getX() + 5, player.getY() + 5, player.getZ() + 5
                    )
            );

            for (CardemoEntity car : nearbyCars) {
                if (car.getOwner() == null) car.setOwner(player);

                if (car.isOwner(player)) {
                    boolean nowLocked = !car.isLocked();
                    car.setLocked(nowLocked);

                    world.playSound(null, car.getX(), car.getY(), car.getZ(),
                            nowLocked ? SoundEvents.IRON_DOOR_CLOSE : SoundEvents.IRON_DOOR_OPEN,
                            SoundSource.PLAYERS, 1.0f, 1.0f);

                    world.addParticle(
                            nowLocked ? ParticleTypes.SMOKE : ParticleTypes.HAPPY_VILLAGER,
                            car.getX(), car.getY() + car.getBbHeight() + 0.2, car.getZ(),
                            0.0, 0.05, 0.0
                    );
                }
            }
        }

        return InteractionResultHolder.sidedSuccess(stack, world.isClientSide);
    }

    // --------------------------
    // Geckolib animation logic (per-stack)
    // --------------------------
    private <P extends Item & IAnimatable> PlayState idlePredicate(AnimationEvent<P> event) {
        ItemStack stack = event.getAnimatable().getDefaultInstance();
        String animationProcedure = stack.getOrCreateTag().getString("animationProcedure");

        if (animationProcedure.isEmpty()) {
            event.getController().setAnimation(new AnimationBuilder().addAnimation("new", EDefaultLoopTypes.LOOP));
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    private <P extends Item & IAnimatable> PlayState procedurePredicate(AnimationEvent<P> event) {
        ItemStack stack = event.getAnimatable().getDefaultInstance();
        String animationProcedure = stack.getOrCreateTag().getString("animationProcedure");

        if (!animationProcedure.isEmpty() &&
                event.getController().getAnimationState().equals(software.bernie.geckolib3.core.AnimationState.Stopped)) {
            event.getController().setAnimation(new AnimationBuilder().addAnimation(animationProcedure, EDefaultLoopTypes.PLAY_ONCE));
            stack.getOrCreateTag().putString("animationProcedure", "");
            event.getController().markNeedsReload();
        }
        return PlayState.CONTINUE;
    }

    @Override
    public void registerControllers(AnimationData data) {
        data.addAnimationController(new AnimationController<>(this, "procedureController", 0, this::procedurePredicate));
        data.addAnimationController(new AnimationController<>(this, "idleController", 0, this::idlePredicate));
    }

    @Override
    public AnimationFactory getFactory() {
        return this.factory;
    }
}
