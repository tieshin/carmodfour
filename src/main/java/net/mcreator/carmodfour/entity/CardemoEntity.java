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
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;

import net.mcreator.carmodfour.init.CarmodfourModEntities;

public class CardemoEntity extends Mob implements IAnimatable {

    // --- SYNCED ENTITY DATA ---
    public static final EntityDataAccessor<Boolean> SHOOT =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<String> ANIMATION =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<String> TEXTURE =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> VEHICLE_STATE =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DOOR_OPEN =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DRIVE_MODE =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);

    // --- ENUMS ---
    public enum VehicleState { LOCKED, UNLOCKED, ENGINE_OFF, ENGINE_ON }
    public enum DriveState { PARK, DRIVE, REVERSE }

    // --- FIELDS ---
    private final AnimationFactory factory = GeckoLibUtil.createFactory(this);
    private String animationProcedure = "empty";
    private Player owner = null;
    private static final Vec3 DRIVER_OFFSET = new Vec3(0.25, 0.45, 0.3);

    // --- Physics / control state (server-authoritative) ---
    private boolean accelerating = false;
    private boolean braking = false;
    private boolean turningLeft = false;
    private boolean turningRight = false;

    private double currentSpeed = 0.0;      // blocks/tick
    private float currentTurnRate = 0.0f;   // degrees per tick (signed)

    // Tunable constants
    private static final double MAX_SPEED = 0.35;       // roughly target top speed (~50 mph equivalent tuning)
    private static final double ACCEL_FACTOR = 0.08;    // controls exponential approach to max speed
    private static final double BRAKE_FACTOR = 0.25;    // strong braking exponential factor
    private static final double DRAG = 0.01;            // linear drag when no input
    private static final double IDLE_SPEED = 0.02;      // minimal roll
    private static final float MAX_TURN_RATE = 4.0f;    // degrees per tick at full steer
    private static final float TURN_ACCEL = 0.25f;      // how fast turn rate ramps to max

    @OnlyIn(Dist.CLIENT)
    private long entryStartTime = 0L;

    // --- CONSTRUCTORS ---
    public CardemoEntity(PlayMessages.SpawnEntity packet, Level world) {
        this(CarmodfourModEntities.CARDEMO.get(), world);
    }

    public CardemoEntity(EntityType<CardemoEntity> type, Level world) {
        super(type, world);
        xpReward = 0;
        setNoAi(false);
        setNoGravity(false);
    }

    // --- OWNER HANDLING ---
    public void setOwner(Player player) { if (owner == null) owner = player; }
    public Player getOwner() { return owner; }
    public boolean isOwner(Player player) { return owner != null && owner.getUUID().equals(player.getUUID()); }

    // --- VEHICLE & DRIVE STATE MANAGEMENT ---
    private void updateVehicleState(String type, String value) {
        switch (type) {
            case "Drive" -> this.entityData.set(DRIVE_MODE, value);
            case "Vehicle" -> this.entityData.set(VEHICLE_STATE, value);
            case "Door" -> this.entityData.set(DOOR_OPEN, Boolean.parseBoolean(value));
        }

        this.getPersistentData().putString("DriveState", getDriveState().name());
        this.getPersistentData().putString("VehicleState", getState().name());
        this.getPersistentData().putBoolean("DoorOpen", isDoorOpen());

        this.removeTag("Drive_PARK");
        this.removeTag("Drive_DRIVE");
        this.removeTag("Drive_REVERSE");
        this.addTag("Drive_" + getDriveState().name());

        this.removeTag("Vehicle_LOCKED");
        this.removeTag("Vehicle_UNLOCKED");
        this.removeTag("Vehicle_ENGINE_OFF");
        this.removeTag("Vehicle_ENGINE_ON");
        this.addTag("Vehicle_" + getState().name());

        this.removeTag("Door_OPEN");
        this.removeTag("Door_CLOSED");
        this.addTag(isDoorOpen() ? "Door_OPEN" : "Door_CLOSED");
    }

    public VehicleState getState() {
        try { return VehicleState.valueOf(this.entityData.get(VEHICLE_STATE)); }
        catch (IllegalArgumentException e) { return VehicleState.LOCKED; }
    }
    public void setState(VehicleState state) { updateVehicleState("Vehicle", state.name()); }
    public boolean isLocked() { return getState() == VehicleState.LOCKED; }
    public boolean isEngineOn() { return getState() == VehicleState.ENGINE_ON; }
    public void setLocked(boolean value) { setState(value ? VehicleState.LOCKED : VehicleState.UNLOCKED); }
    public void setEngineOn(boolean value) { setState(value ? VehicleState.ENGINE_ON : VehicleState.ENGINE_OFF); }

    public DriveState getDriveState() {
        try { return DriveState.valueOf(this.entityData.get(DRIVE_MODE)); }
        catch (IllegalArgumentException e) { return DriveState.PARK; }
    }
    public void setDriveState(DriveState state) { updateVehicleState("Drive", state.name()); }

    public boolean isDoorOpen() { return this.entityData.get(DOOR_OPEN); }
    public void setDoorOpen(boolean open) { updateVehicleState("Door", Boolean.toString(open)); }

    // --- TEXTURE & ANIM ---
    public String getTexture() { return this.entityData.get(TEXTURE); }
    public String getSyncedAnimation() { return this.entityData.get(ANIMATION); }
    public void setAnimation(String name) { this.entityData.set(ANIMATION, name); }

    // --- SYNC DATA ---
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

    // --- SAVE & LOAD NBT ---
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

    // --- INPUT FLAG SETTERS (invoked from SteeringInputPacket) ---
    public void setAccelerating(boolean accelerating) { this.accelerating = accelerating; }
    public void setBraking(boolean braking) { this.braking = braking; }
    public void setTurningLeft(boolean turningLeft) { this.turningLeft = turningLeft; }
    public void setTurningRight(boolean turningRight) { this.turningRight = turningRight; }

    // optional getters for debugging/tests
    public boolean isAccelerating() { return accelerating; }
    public boolean isBraking() { return braking; }
    public boolean isTurningLeft() { return turningLeft; }
    public boolean isTurningRight() { return turningRight; }

    // --- TICK ---
    @Override
    public void tick() {
        super.tick();

        // Gravity
        if (!this.isOnGround()) this.setDeltaMovement(this.getDeltaMovement().add(0, -0.08, 0));

        // Friction while on ground
        if (this.isOnGround()) {
            Vec3 motion = this.getDeltaMovement();
            this.setDeltaMovement(new Vec3(motion.x * 0.9, motion.y, motion.z * 0.9));
        }

        // Server-side authoritative physics
        if (!level.isClientSide) {
            // Only simulate when engine is on and drive is DRIVE or REVERSE
            if (isEngineOn() && getDriveState() != DriveState.PARK) {
                // --- Speed update ---
                if (getDriveState() == DriveState.DRIVE) {
                    // accelerating -> exponential approach to max
                    if (accelerating) {
                        currentSpeed += ACCEL_FACTOR * (1.0 - (currentSpeed / MAX_SPEED));
                    } else if (braking) {
                        // braking strong exponential drop
                        currentSpeed -= BRAKE_FACTOR * currentSpeed;
                    } else {
                        // natural linear drag toward idle speed
                        currentSpeed -= DRAG;
                        if (currentSpeed < IDLE_SPEED) currentSpeed = IDLE_SPEED;
                    }

                    // clamp
                    if (currentSpeed < 0.0) currentSpeed = 0.0;
                    if (currentSpeed > MAX_SPEED) currentSpeed = MAX_SPEED;
                } else { // REVERSE behavior (slower max and inverted direction)
                    double reverseMax = MAX_SPEED * 0.5;
                    if (accelerating) {
                        currentSpeed += ACCEL_FACTOR * (1.0 - (currentSpeed / reverseMax));
                    } else if (braking) {
                        currentSpeed -= BRAKE_FACTOR * currentSpeed;
                    } else {
                        currentSpeed -= DRAG;
                        if (currentSpeed < IDLE_SPEED * 0.5) currentSpeed = IDLE_SPEED * 0.5;
                    }
                    if (currentSpeed < 0.0) currentSpeed = 0.0;
                    if (currentSpeed > reverseMax) currentSpeed = reverseMax;
                }

                // --- Steering / Turn rate update ---
                if (turningLeft && !turningRight) {
                    // ramp turn rate toward negative max (left)
                    float desired = -MAX_TURN_RATE;
                    float delta = TURN_ACCEL * (1.0f - Math.abs(currentTurnRate) / MAX_TURN_RATE);
                    currentTurnRate = currentTurnRate - Math.min(Math.abs(desired - currentTurnRate), delta);
                    // clamp
                    if (currentTurnRate < -MAX_TURN_RATE) currentTurnRate = -MAX_TURN_RATE;
                } else if (turningRight && !turningLeft) {
                    // ramp turn rate toward positive max (right)
                    float desired = MAX_TURN_RATE;
                    float delta = TURN_ACCEL * (1.0f - Math.abs(currentTurnRate) / MAX_TURN_RATE);
                    currentTurnRate = currentTurnRate + Math.min(Math.abs(desired - currentTurnRate), delta);
                    if (currentTurnRate > MAX_TURN_RATE) currentTurnRate = MAX_TURN_RATE;
                } else {
                    // decay turn rate toward zero
                    currentTurnRate *= 0.75f;
                    if (Math.abs(currentTurnRate) < 0.01f) currentTurnRate = 0.0f;
                }

                // Apply yaw
                this.setYRot(this.getYRot() + currentTurnRate);

                // --- Apply motion based on yaw and currentSpeed ---
                double speedDir = (getDriveState() == DriveState.DRIVE) ? currentSpeed : -currentSpeed;
                double yawRad = Math.toRadians(this.getYRot());
                double motionX = -Math.sin(yawRad) * speedDir;
                double motionZ = Math.cos(yawRad) * speedDir;

                // Keep vertical motion as-is
                this.setDeltaMovement(motionX, this.getDeltaMovement().y, motionZ);
                this.hasImpulse = true;

                // Move the entity according to the deltaMovement
                this.setPos(this.getX() + getDeltaMovement().x, this.getY(), this.getZ() + getDeltaMovement().z);
            } else {
                // engine off or in PARK: gently zero speed & turn rate
                currentSpeed = 0.0;
                currentTurnRate *= 0.5f;
                if (Math.abs(currentTurnRate) < 0.01f) currentTurnRate = 0.0f;
            }
        }

        // Client-side only visuals
        if (level.isClientSide) {
            Minecraft mc = Minecraft.getInstance();
            handleSmoothCameraLock();
            renderDriveStateHotbar(mc);
        }
    }

    // --- HOTBAR DISPLAY (client only) ---
    @OnlyIn(Dist.CLIENT)
    private void renderDriveStateHotbar(Minecraft mc) {
        Player player = mc.player;
        if (player == null) return;
        if (player.getVehicle() != this || !isEngineOn()) return;

        if (entryStartTime == 0L) entryStartTime = System.currentTimeMillis();
        long elapsed = System.currentTimeMillis() - entryStartTime;
        if (elapsed < 3000L) return; // 3-second delay

        String hotbarText = "";
        hotbarText += getDriveState() == DriveState.PARK ? "( 1. P )" : "1. P";
        hotbarText += " || ";
        hotbarText += getDriveState() == DriveState.DRIVE ? "( 2. D )" : "2. D";
        hotbarText += " || ";
        hotbarText += getDriveState() == DriveState.REVERSE ? "( 3. R )" : "3. R";

        mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(hotbarText), false);
    }

    // --- FORWARD VECTOR (helper) ---
    private Vec3 getForwardVector() {
        float yawRad = (float) Math.toRadians(this.getYRot());
        return new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
    }

    // --- CAMERA LOCK ---
    @OnlyIn(Dist.CLIENT)
    private void handleSmoothCameraLock() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        if (player.getVehicle() == this) {
            long currentTime = System.currentTimeMillis();
            if (entryStartTime == 0L) entryStartTime = currentTime;

            float elapsed = (currentTime - entryStartTime) / 1000f;
            float duration = 1.25f;

            float carYaw = this.getYRot();
            float playerYaw = player.getYRot();

            if (elapsed < duration) {
                float t = elapsed / duration;
                float newYaw = playerYaw + (carYaw - playerYaw) * t;
                player.setYRot(newYaw);
                player.yRotO = newYaw;
            } else {
                player.setYRot(carYaw);
                player.yRotO = carYaw;
            }
        } else entryStartTime = 0L;
    }

    // --- RIDER POSITION ---
    @Override
    public void positionRider(Entity passenger) {
        if (this.hasPassenger(passenger)) {
            Vec3 rotatedOffset = DRIVER_OFFSET.yRot((float) Math.toRadians(-this.getYRot()));
            Vec3 targetPos = this.position().add(rotatedOffset);
            passenger.setPos(targetPos.x, targetPos.y, targetPos.z);
        }
    }

    // --- INTERACTION ---
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        boolean sneaking = player.isShiftKeyDown();
        boolean client = this.level.isClientSide;

        if (sneaking) {
            if (!client) {
                if (isLocked()) {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("Car is locked."), true
                    );
                    return InteractionResult.FAIL;
                }

                if (isDoorOpen()) {
                    setAnimationProcedure("r_door_close");
                    setDoorOpen(false);
                } else {
                    setAnimationProcedure("r_door_open");
                    setDoorOpen(true);
                }
            }
            return InteractionResult.SUCCESS;
        }

        if (!client) {
            if (isLocked()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("Car is locked."), true
                );
                return InteractionResult.FAIL;
            }

            if (!isDoorOpen()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("Door is shut."), true
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

        return InteractionResult.sidedSuccess(client);
    }

    // --- REGISTRATION ---
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

    // --- ANIMATIONS ---
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
        if (!this.animationProcedure.equals("empty")
                && event.getController().getAnimationState()
                == software.bernie.geckolib3.core.AnimationState.Stopped) {

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
    public void setAnimationProcedure(String animation) { this.animationProcedure = animation; }
    public String getAnimationProcedure() { return this.animationProcedure; }
}
