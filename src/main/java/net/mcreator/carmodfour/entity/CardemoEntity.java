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

import net.minecraftforge.network.PlayMessages;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;

import net.mcreator.carmodfour.init.CarmodfourModEntities;

public class CardemoEntity extends Mob implements IAnimatable {

    public static final EntityDataAccessor<Boolean> SHOOT = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<String> ANIMATION = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<String> TEXTURE = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> VEHICLE_STATE = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DOOR_OPEN = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DRIVE_MODE = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);

    public enum VehicleState { LOCKED, UNLOCKED, ENGINE_OFF, ENGINE_ON }
    public enum DriveState { PARK, DRIVE, REVERSE }

    private final AnimationFactory factory = GeckoLibUtil.createFactory(this);
    private String animationProcedure = "empty";
    private Player owner = null;
    private static final Vec3 DRIVER_OFFSET = new Vec3(0.25, 0.45, 0.3);

    private boolean accelerating = false;
    private boolean braking = false;
    private boolean turningLeft = false;
    private boolean turningRight = false;

    private double currentSpeed = 0.0;
    private float currentTurnRate = 0.0f;

    private static final double MAX_SPEED = 0.35;
    private static final double ACCEL_FACTOR = 0.08;
    private static final double BRAKE_FACTOR = 0.25;
    private static final double DRAG = 0.01;
    private static final double IDLE_SPEED = 0.02;
    private static final float MAX_TURN_RATE = 4.0f;
    private static final float TURN_ACCEL = 0.25f;

    // === Custom hitbox ===
    private static final float HITBOX_WIDTH = 2.5f;
    private static final float HITBOX_HEIGHT = 1.0f;
    private static final float HITBOX_LENGTH = 2.5f;

    @OnlyIn(Dist.CLIENT)
    private long entryStartTime = 0L;

    public CardemoEntity(PlayMessages.SpawnEntity packet, Level world) {
        this(CarmodfourModEntities.CARDEMO.get(), world);
    }

    public CardemoEntity(EntityType<CardemoEntity> type, Level world) {
        super(type, world);
        xpReward = 0;
        setNoAi(false);
        setNoGravity(false);
        this.maxUpStep = 1.1f; // smoother climb over slabs/stairs
    }

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

    public DriveState getDriveState() {
        try { return DriveState.valueOf(this.entityData.get(DRIVE_MODE)); }
        catch (IllegalArgumentException e) { return DriveState.PARK; }
    }
    public void setDriveState(DriveState state) { this.entityData.set(DRIVE_MODE, state.name()); }

    public boolean isDoorOpen() { return this.entityData.get(DOOR_OPEN); }
    public void setDoorOpen(boolean open) { this.entityData.set(DOOR_OPEN, open); }

    public String getTexture() { return this.entityData.get(TEXTURE); }
    public String getSyncedAnimation() { return this.entityData.get(ANIMATION); }
    public void setAnimation(String name) { this.entityData.set(ANIMATION, name); }

    public void setAnimationProcedure(String animation) { setAnimation(animation); }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(SHOOT, false);
        this.entityData.define(ANIMATION, "undefined");
        this.entityData.define(TEXTURE, "cardemo");
        this.entityData.define(VEHICLE_STATE, VehicleState.LOCKED.name());
        this.entityData.define(DOOR_OPEN, false);
        this.entityData.define(DRIVE_MODE, DriveState.PARK.name());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("DriveState", getDriveState().name());
        tag.putString("VehicleState", getState().name());
        tag.putBoolean("DoorOpen", isDoorOpen());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("DriveState")) setDriveState(DriveState.valueOf(tag.getString("DriveState")));
        if (tag.contains("VehicleState")) setState(VehicleState.valueOf(tag.getString("VehicleState")));
        if (tag.contains("DoorOpen")) setDoorOpen(tag.getBoolean("DoorOpen"));
    }

    public void setAccelerating(boolean accelerating) { this.accelerating = accelerating; }
    public void setBraking(boolean braking) { this.braking = braking; }
    public void setTurningLeft(boolean turningLeft) { this.turningLeft = turningLeft; }
    public void setTurningRight(boolean turningRight) { this.turningRight = turningRight; }

    @Override
    public void tick() {
        super.tick();
        float tickDelta = 1f;

        if (!level.isClientSide) {
            if (isEngineOn()) {
                DriveState mode = getDriveState();

                switch (mode) {
                    case DRIVE -> handleDriveMode(tickDelta);
                    case REVERSE -> handleReverseMode(tickDelta);
                    case PARK -> handleParkMode();
                }
            }
        }

        if (level.isClientSide) {
            Minecraft mc = Minecraft.getInstance();
            renderDriveStateHotbar(mc);
        }
    }

    private void handleDriveMode(float tickDelta) {
        double targetMax = MAX_SPEED;

        if (!this.getPassengers().isEmpty()) {
            if (accelerating) currentSpeed += ACCEL_FACTOR * (targetMax - currentSpeed) * tickDelta;
            else if (braking) currentSpeed -= BRAKE_FACTOR * currentSpeed * tickDelta;
            else { currentSpeed -= DRAG * tickDelta; if (currentSpeed < IDLE_SPEED) currentSpeed = IDLE_SPEED; }
        } else {
            double decayPerTick = (currentSpeed - IDLE_SPEED) / (5.0 / tickDelta);
            currentSpeed -= decayPerTick;
        }

        currentSpeed = Math.max(0, Math.min(currentSpeed, targetMax));

        float desiredTurn = 0f;
        if (turningLeft && !turningRight) desiredTurn = -MAX_TURN_RATE;
        else if (turningRight && !turningLeft) desiredTurn = MAX_TURN_RATE;

        updateTurnAndMotion(desiredTurn, tickDelta, false);
    }

    private void handleReverseMode(float tickDelta) {
        double targetMax = MAX_SPEED * 0.5;
        double accelFactor = ACCEL_FACTOR * 0.75;

        if (!this.getPassengers().isEmpty()) {
            if (accelerating) currentSpeed += accelFactor * (targetMax - currentSpeed) * tickDelta;
            else if (braking) currentSpeed -= BRAKE_FACTOR * currentSpeed * tickDelta;
            else { currentSpeed -= DRAG * tickDelta; if (currentSpeed < IDLE_SPEED) currentSpeed = IDLE_SPEED; }
        } else {
            double decayPerTick = (currentSpeed - IDLE_SPEED) / (5.0 / tickDelta);
            currentSpeed -= decayPerTick;
        }

        currentSpeed = Math.max(0, Math.min(currentSpeed, targetMax));

        float desiredTurn = 0f;
        if (turningLeft && !turningRight) desiredTurn = -MAX_TURN_RATE;
        else if (turningRight && !turningLeft) desiredTurn = MAX_TURN_RATE;

        desiredTurn *= -1;

        updateTurnAndMotion(desiredTurn, tickDelta, true);
    }

    private void handleParkMode() {
        currentSpeed = 0.0;
        currentTurnRate *= 0.5f;
        if (Math.abs(currentTurnRate) < 0.01f) currentTurnRate = 0f;
    }

    private void updateTurnAndMotion(float desiredTurn, float tickDelta, boolean reverse) {
        float turnDiff = desiredTurn - currentTurnRate;
        float turnStep = TURN_ACCEL * tickDelta;
        if (Math.abs(turnDiff) < turnStep) currentTurnRate = desiredTurn;
        else currentTurnRate += Math.signum(turnDiff) * turnStep;

        if (!turningLeft && !turningRight) {
            float speedRatio = (float)(currentSpeed / MAX_SPEED);
            float dynamicCenterFactor = 0.85f + 0.15f * (1 - speedRatio);
            currentTurnRate *= dynamicCenterFactor;
            if (Math.abs(currentTurnRate) < 0.01f) currentTurnRate = 0f;
        }

        this.setYRot(this.getYRot() + currentTurnRate * tickDelta);

        double yawRad = Math.toRadians(this.getYRot());
        double motionX = reverse ? Math.sin(yawRad) * currentSpeed : -Math.sin(yawRad) * currentSpeed;
        double motionZ = reverse ? -Math.cos(yawRad) * currentSpeed : Math.cos(yawRad) * currentSpeed;

        // Adjust vertical motion for smoother slope climbing
        double verticalBoost = 0.0;
        if (this.onGround && currentSpeed > 0.05) {
            verticalBoost = 0.1 * Math.min(1.0, currentSpeed / MAX_SPEED);
        }

        Vec3 motion = new Vec3(motionX, this.getDeltaMovement().y + verticalBoost, motionZ);
        this.setDeltaMovement(motion);
        this.hasImpulse = true;
        this.move(MoverType.SELF, motion);

        applyTerrainTilt(yawRad, tickDelta);
    }

    // === Hitbox shape ===
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.fixed(HITBOX_WIDTH, HITBOX_HEIGHT);
    }

    @Override
    public void refreshDimensions() {
        super.refreshDimensions();
        double half = HITBOX_WIDTH / 2.0;
        this.setBoundingBox(new AABB(
                this.getX() - half,
                this.getY(),
                this.getZ() - (HITBOX_LENGTH / 2.0),
                this.getX() + half,
                this.getY() + HITBOX_HEIGHT,
                this.getZ() + (HITBOX_LENGTH / 2.0)
        ));
    }

    private void applyTerrainTilt(double yawRad, float tickDelta) {
        double sampleDistance = 0.8;
        Vec3 forwardVec = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));

        Vec3 frontPos = this.position().add(forwardVec.scale(sampleDistance));
        Vec3 backPos = this.position().add(forwardVec.scale(-sampleDistance));

        double frontY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) frontPos.x, (int) frontPos.z);
        double backY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) backPos.x, (int) backPos.z);

        double dy = frontY - backY;
        double dx = sampleDistance * 2;

        float targetPitch = (float)Math.toDegrees(Math.atan2(dy, dx)) * -1F;
        targetPitch = Math.max(-30F, Math.min(30F, targetPitch));

        float smoothing = 0.3F;
        float newPitch = this.getXRot() + (targetPitch - this.getXRot()) * smoothing;
        this.setXRot(newPitch);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderDriveStateHotbar(Minecraft mc) {
        Player player = mc.player;
        if (player == null) return;
        if (player.getVehicle() != this || !isEngineOn()) return;

        String hotbarText = "";
        hotbarText += getDriveState() == DriveState.PARK ? "( 1. P )" : "1. P";
        hotbarText += " || ";
        hotbarText += getDriveState() == DriveState.DRIVE ? "( 2. D )" : "2. D";
        hotbarText += " || ";
        hotbarText += getDriveState() == DriveState.REVERSE ? "( 3. R )" : "3. R";

        mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(hotbarText), false);
    }

    @Override
    public void positionRider(Entity passenger) {
        if (this.hasPassenger(passenger)) {
            Vec3 rotatedOffset = DRIVER_OFFSET.yRot((float)Math.toRadians(-this.getYRot()));
            Vec3 targetPos = this.position().add(rotatedOffset);
            passenger.setPos(targetPos.x, targetPos.y, targetPos.z);
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        boolean sneaking = player.isShiftKeyDown();
        boolean client = this.level.isClientSide;

        if (sneaking) {
            if (!client) {
                if (isLocked()) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("Car is locked."), true);
                    return InteractionResult.FAIL;
                }

                if (isDoorOpen()) {
                    setAnimation("r_door_close");
                    setDoorOpen(false);
                } else {
                    setAnimation("r_door_open");
                    setDoorOpen(true);
                }
            }
            return InteractionResult.SUCCESS;
        }

        if (!client) {
            if (isLocked()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("Car is locked."), true);
                return InteractionResult.FAIL;
            }

            if (!isDoorOpen()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("Door is shut."), true);
                return InteractionResult.FAIL;
            }

            if (this.getPassengers().isEmpty()) {
                player.startRiding(this, true);
                setOwner(player);
                setAnimation("player_enter");
                return InteractionResult.SUCCESS;
            }
        }

        return InteractionResult.sidedSuccess(client);
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
            event.getController().setAnimation(
                    new AnimationBuilder().addAnimation("brake_down", EDefaultLoopTypes.LOOP)
            );
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    private <E extends IAnimatable> PlayState procedurePredicate(AnimationEvent<E> event) {
        if (!this.animationProcedure.equals("empty") &&
                event.getController().getAnimationState() ==
                        software.bernie.geckolib3.core.AnimationState.Stopped) {
            event.getController().setAnimation(
                    new AnimationBuilder().addAnimation(this.animationProcedure, EDefaultLoopTypes.PLAY_ONCE)
            );
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
}
