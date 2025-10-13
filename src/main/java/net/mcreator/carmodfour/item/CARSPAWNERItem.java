package net.mcreator.carmodfour.item;

import software.bernie.geckolib3.util.GeckoLibUtil;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.IAnimatable;

import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.block.model.ItemTransforms;

import net.mcreator.carmodfour.entity.CardemoEntity;
import net.mcreator.carmodfour.init.CarmodfourModEntities;
import net.mcreator.carmodfour.item.renderer.CARSPAWNERItemRenderer;

import java.util.function.Consumer;
import java.util.List;

public class CARSPAWNERItem extends Item implements IAnimatable {
    private final AnimationFactory factory = GeckoLibUtil.createFactory(this);
    public static ItemTransforms.TransformType transformType;
    public String animationprocedure = "empty";

    public CARSPAWNERItem() {
        super(new Item.Properties()
                .tab(CreativeModeTab.TAB_MISC)
                .stacksTo(1)
                .rarity(Rarity.UNCOMMON));
    }

    // ------------------------------------------------------------
    // 🚗 ENTITY SPAWN / DESPAWN LOGIC
    // ------------------------------------------------------------
    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!world.isClientSide) {
            double radius = 5.0D;
            List<CardemoEntity> nearbyCars = world.getEntitiesOfClass(CardemoEntity.class,
                    player.getBoundingBox().inflate(radius));

            if (!nearbyCars.isEmpty()) {
                for (CardemoEntity car : nearbyCars) {
                    car.remove(Entity.RemovalReason.DISCARDED);
                }
                player.displayClientMessage(Component.literal("Removed existing car."), true);
                return InteractionResultHolder.success(stack);
            }

            Vec3 look = player.getLookAngle();
            Vec3 spawnPos = player.position().add(look.scale(2.5D));
            spawnPos = new Vec3(spawnPos.x, player.getY(), spawnPos.z);

            CardemoEntity car = new CardemoEntity(CarmodfourModEntities.CARDEMO.get(), world);
            car.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), 0);
            world.addFreshEntity(car);

            player.displayClientMessage(Component.literal("Spawned new car."), true);
            return InteractionResultHolder.success(stack);
        }

        return InteractionResultHolder.pass(stack);
    }

    // ------------------------------------------------------------
    // 🎨 CLIENT RENDERER
    // ------------------------------------------------------------
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private final BlockEntityWithoutLevelRenderer renderer = new CARSPAWNERItemRenderer();

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return renderer;
            }
        });
    }

    public void getTransformType(ItemTransforms.TransformType type) {
        transformType = type;
    }

    // ------------------------------------------------------------
    // 🌀 ANIMATION CONTROL (Non-Deprecated GeckoLib 3 Syntax)
    // ------------------------------------------------------------
    private <P extends Item & IAnimatable> PlayState idlePredicate(AnimationEvent<P> event) {
        if (animationprocedure.equals("empty")) {
            event.getController().setAnimation(
                    new AnimationBuilder().addAnimation("Brake_down", EDefaultLoopTypes.LOOP)
            );
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    private <P extends Item & IAnimatable> PlayState procedurePredicate(AnimationEvent<P> event) {
        if (!animationprocedure.equals("empty")) {
            event.getController().setAnimation(
                    new AnimationBuilder().addAnimation(animationprocedure, EDefaultLoopTypes.PLAY_ONCE)
            );
            animationprocedure = "empty";
        }
        return PlayState.CONTINUE;
    }

    @Override
    public void registerControllers(AnimationData data) {
        data.addAnimationController(new AnimationController<>(this, "idleController", 0, this::idlePredicate));
        data.addAnimationController(new AnimationController<>(this, "procedureController", 0, this::procedurePredicate));
    }

    @Override
    public AnimationFactory getFactory() {
        return factory;
    }
}
