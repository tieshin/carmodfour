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

import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import net.mcreator.carmodfour.item.renderer.ExamplegeckolibitemItemRenderer;
import net.mcreator.carmodfour.entity.CardemoEntity;

import java.util.function.Consumer;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

public class ExamplegeckolibitemItem extends Item implements IAnimatable {
    public AnimationFactory factory = GeckoLibUtil.createFactory(this);
    public String animationprocedure = "empty";
    public static ItemTransforms.TransformType transformType;

    // Client HUD state
    @OnlyIn(Dist.CLIENT) private boolean prevInCar = false;
    @OnlyIn(Dist.CLIENT) private boolean prevEngineOn = false;
    @OnlyIn(Dist.CLIENT) private boolean showEngineOff = false;

    public ExamplegeckolibitemItem() {
        super(new Item.Properties().tab(CreativeModeTab.TAB_MISC).stacksTo(64).rarity(Rarity.COMMON));
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private final BlockEntityWithoutLevelRenderer renderer = new ExamplegeckolibitemItemRenderer();
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() { return renderer; }
        });
    }

    public void getTransformType(ItemTransforms.TransformType type) { this.transformType = type; }

    // -------------------------------------------------
    // Car key core logic (lock/unlock + engine toggle)
    // -------------------------------------------------
    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!world.isClientSide) {
            if (player.getVehicle() instanceof CardemoEntity car) {
                boolean newEngineState = !car.isEngineOn();
                car.setEngineOn(newEngineState);

                if (newEngineState) {
                    // Engine turned ON: display once
                    Component msg = Component.literal("Engine ")
                            .append(Component.literal("On").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                    player.displayClientMessage(msg, true);
                    showEngineOff = false;
                } else {
                    // Engine turned OFF: persistent
                    showEngineOff = true;
                    Component msg = Component.literal("Engine ")
                            .append(Component.literal("Off").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                    player.displayClientMessage(msg, true);
                }

                world.playSound(null, car.getX(), car.getY(), car.getZ(),
                        newEngineState
                                ? net.minecraft.sounds.SoundEvents.FURNACE_FIRE_CRACKLE
                                : net.minecraft.sounds.SoundEvents.LEVER_CLICK,
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        1.0f, newEngineState ? 1.0f : 0.8f);
            } else {
                List<CardemoEntity> nearbyCars = world.getEntitiesOfClass(
                        CardemoEntity.class,
                        player.getBoundingBox().inflate(5.0)
                );

                for (CardemoEntity car : nearbyCars) {
                    if (car.getOwner() == null) car.setOwner(player);

                    if (car.isOwner(player)) {
                        boolean nowLocked = !car.isLocked();
                        car.setLocked(nowLocked);

                        player.displayClientMessage(
                                nowLocked
                                        ? Component.literal("Car Locked").withStyle(ChatFormatting.RED)
                                        : Component.literal("Car Unlocked").withStyle(ChatFormatting.GREEN),
                                true
                        );

                        world.playSound(null, car.getX(), car.getY(), car.getZ(),
                                nowLocked
                                        ? net.minecraft.sounds.SoundEvents.IRON_DOOR_CLOSE
                                        : net.minecraft.sounds.SoundEvents.IRON_DOOR_OPEN,
                                net.minecraft.sounds.SoundSource.PLAYERS,
                                1.0f, 1.0f);
                    }
                }
            }
        }

        return InteractionResultHolder.sidedSuccess(stack, world.isClientSide);
    }

    // -------------------------------------------------
    // Client tick: persistent HUD for Engine Off
    // -------------------------------------------------
    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Player player = mc.player;
        if (player == null) return;

        Entity vehicle = player.getVehicle();

        if (vehicle instanceof CardemoEntity car) {
            boolean engineOn = car.isEngineOn();

            // Entering car
            if (!prevInCar) {
                prevInCar = true;
                prevEngineOn = engineOn;

                if (!engineOn) {
                    showEngineOff = true;
                    player.displayClientMessage(Component.literal("Engine ")
                            .append(Component.literal("Off").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)), true);
                }
            }

            // Off → On / On → Off
            if (!prevEngineOn && engineOn) {
                // Engine turned on: display once
                Component msg = Component.literal("Engine ")
                        .append(Component.literal("On").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                player.displayClientMessage(msg, true);
                showEngineOff = false;
            } else if (prevEngineOn && !engineOn) {
                // Engine turned off: persistent
                showEngineOff = true;
                Component msg = Component.literal("Engine ")
                        .append(Component.literal("Off").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                player.displayClientMessage(msg, true);
            }

            // Keep Engine Off persistent
            if (!engineOn && showEngineOff) {
                player.displayClientMessage(Component.literal("Engine ")
                        .append(Component.literal("Off").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)), true);
            }

            prevEngineOn = engineOn;

        } else {
            // Exiting car
            if (prevInCar) player.displayClientMessage(Component.empty(), true);
            prevInCar = false;
            prevEngineOn = false;
            showEngineOff = false;
        }
    }

    // -------------------------------------------------
    // Animation logic (unchanged)
    // -------------------------------------------------
    private <P extends Item & IAnimatable> PlayState idlePredicate(AnimationEvent<P> event) {
        if (this.transformType != null && this.animationprocedure.equals("empty")) {
            event.getController().setAnimation(new AnimationBuilder().addAnimation("new", EDefaultLoopTypes.LOOP));
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    private <P extends Item & IAnimatable> PlayState procedurePredicate(AnimationEvent<P> event) {
        if (this.transformType != null) {
            if (!this.animationprocedure.equals("empty") &&
                    event.getController().getAnimationState().equals(software.bernie.geckolib3.core.AnimationState.Stopped)) {
                event.getController().setAnimation(new AnimationBuilder().addAnimation(this.animationprocedure, EDefaultLoopTypes.PLAY_ONCE));
                if (event.getController().getAnimationState().equals(software.bernie.geckolib3.core.AnimationState.Stopped)) {
                    this.animationprocedure = "empty";
                    event.getController().markNeedsReload();
                }
            }
        }
        return PlayState.CONTINUE;
    }

    public void setupAnimationState(ExamplegeckolibitemItemRenderer renderer, ItemStack stack, PoseStack matrixStack, float aimProgress) {}

    @Override
    public void registerControllers(AnimationData data) {
        AnimationController procedureController = new AnimationController(this, "procedureController", 0, this::procedurePredicate);
        data.addAnimationController(procedureController);
        AnimationController idleController = new AnimationController<>(this, "idleController", 0, this::idlePredicate);
        data.addAnimationController(idleController);
    }

    @Override
    public AnimationFactory getFactory() { return this.factory; }
}
