package net.mcreator.carmodfour.entity;

import software.bernie.geckolib3.util.GeckoLibUtil;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.IAnimatable;

import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.network.PlayMessages;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Difficulty;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;

import net.mcreator.carmodfour.init.CarmodfourModEntities;

public class CardemoEntity extends Mob implements IAnimatable {

    public static final EntityDataAccessor<Boolean> SHOOT =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<String> ANIMATION =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<String> TEXTURE =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> VEHICLE_STATE =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);

    public enum VehicleState {
        LOCKED, UNLOCKED, ENGINE_OFF, ENGINE_ON
    }

    private final AnimationFactory factory = GeckoLibUtil.createFactory(this);
    private String animationProcedure = "empty";
    private Player owner = null;

    // Right side seat offset: right (+X), slightly up (+Y), slightly forward (+Z)
    private static final Vec3 DRIVER_OFFSET = new Vec3(0.25, 0.45, 0.3);

    // Camera interpolation tracking
    @OnlyIn(Dist.CLIENT)
    private long entryStartTime = 0L;

    public void setOwner(Player player) { if (owner == null) owner = player; }
    public Player getOwner() { return owner; }
    public boolean isOwner(Player player) { return owner != null && owner.getUUID().equals(player.getUUID()); }

    public VehicleState getState() {
        try { return VehicleState.valueOf(this.entityData.get(VEHICLE_STATE)); }
        catch (IllegalArgumentException e) { return VehicleState.LOCKED; }
    }

    public void setState(VehicleState state) { this.entityData.set(VEHICLE_STATE, state.name()); }
    public boolean isLocked() { return getState() == VehicleState.LOCKED; }
    public boolean isEngineOn() { return getState() == VehicleState.ENGINE_ON; }
    public void setLocked(boolean value) { setState(value ? VehicleState.LOCKED : VehicleState.UNLOCKED); }
    public void setEngineOn(boolean value) { setState(value ? VehicleState.ENGINE_ON : VehicleState.ENGINE_OFF); }

    public String getTexture() { return this.entityData.get(TEXTURE); }
    public String getSyncedAnimation() { return this.entityData.get(ANIMATION); }
    public void setAnimation(String name) { this.entityData.set(ANIMATION, name); }

    public CardemoEntity(PlayMessages.SpawnEntity packet, Level world) {
        this(CarmodfourModEntities.CARDEMO.get(), world);
    }

    public CardemoEntity(EntityType<CardemoEntity> type, Level world) {
        super(type, world);
        xpReward = 0;
        setNoAi(false);
        setNoGravity(false);
    }

    @Override
    protected void registerGoals() {}

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(SHOOT, false);
        this.entityData.define(ANIMATION, "undefined");
        this.entityData.define(TEXTURE, "cardemo");
        this.entityData.define(VEHICLE_STATE, VehicleState.LOCKED.name());
    }

    @Override
    public void tick() {
        super.tick();

        // Gravity & friction
        if (!this.isOnGround()) {
            this.setDeltaMovement(this.getDeltaMovement().add(0, -0.08, 0));
        } else {
            Vec3 motion = this.getDeltaMovement();
            this.setDeltaMovement(new Vec3(motion.x * 0.5, 0, motion.z * 0.5));
        }

        // Smooth camera orientation on client
        if (level.isClientSide) {
            handleSmoothCameraLock();
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void handleSmoothCameraLock() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        if (player.getVehicle() == this) {
            long currentTime = System.currentTimeMillis();
            if (entryStartTime == 0L) entryStartTime = currentTime;

            float elapsed = (currentTime - entryStartTime) / 1000f;
            float duration = 1.25f; // 1.25 seconds for full alignment

            float carYaw = this.getYRot();
            float playerYaw = player.getYRot();

            if (elapsed < duration) {
                float t = elapsed / duration;
                float newYaw = playerYaw + (carYaw - playerYaw) * t;
                player.setYRot(newYaw);
                player.yRotO = newYaw;
            } else {
                // Done interpolating
                player.setYRot(carYaw);
                player.yRotO = carYaw;
            }
        } else {
            entryStartTime = 0L; // reset when exiting
        }
    }

    @Override
    public void positionRider(Entity passenger) {
        if (this.hasPassenger(passenger)) {
            Vec3 rotatedOffset = DRIVER_OFFSET.yRot((float) Math.toRadians(-this.getYRot()));
            Vec3 targetPos = this.position().add(rotatedOffset);
            passenger.setPos(targetPos.x, targetPos.y, targetPos.z);
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level.isClientSide) {
            if (this.isLocked()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("Vehicle is locked."), true);
                player.playNotifySound(
                        ForgeRegistries.SOUND_EVENTS.getValue(new net.minecraft.resources.ResourceLocation("minecraft:block.iron_door.close")),
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f
                );
                return InteractionResult.FAIL;
            }

            if (this.getPassengers().isEmpty()) {
                player.startRiding(this, true);
                setOwner(player);
                setAnimationProcedure("player_enter");
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.sidedSuccess(this.level.isClientSide);
    }

    public static void init() {
        SpawnPlacements.register(CarmodfourModEntities.CARDEMO.get(),
                SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (entityType, world, reason, pos, random) ->
                        world.getDifficulty() != Difficulty.PEACEFUL &&
                                Mob.checkMobSpawnRules(entityType, world, reason, pos, random));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.ARMOR, 0)
                .add(Attributes.ATTACK_DAMAGE, 0)
                .add(Attributes.FOLLOW_RANGE, 16);
    }

    private <E extends IAnimatable> PlayState movementPredicate(AnimationEvent<E> event) {
        if (this.animationProcedure.equals("empty")) {
            event.getController().setAnimation(new AnimationBuilder().addAnimation("brake_down", EDefaultLoopTypes.LOOP));
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    private <E extends IAnimatable> PlayState procedurePredicate(AnimationEvent<E> event) {
        if (!this.animationProcedure.equals("empty") &&
                event.getController().getAnimationState().equals(software.bernie.geckolib3.core.AnimationState.Stopped)) {
            event.getController().setAnimation(new AnimationBuilder().addAnimation(this.animationProcedure, EDefaultLoopTypes.PLAY_ONCE));
            this.animationProcedure = "empty";
            event.getController().markNeedsReload();
        }
        return PlayState.CONTINUE;
    }

    @Override
    public void registerControllers(AnimationData data) {
        data.addAnimationController(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        data.addAnimationController(new AnimationController<>(this, "procedure", 4, this::procedurePredicate));
    }

    @Override
    public AnimationFactory getFactory() { return this.factory; }
    public void setAnimationProcedure(String animation) { this.animationProcedure = animation; }
    public String getAnimationProcedure() { return this.animationProcedure; }
}
