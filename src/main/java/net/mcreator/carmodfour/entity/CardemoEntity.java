package net.mcreator.carmodfour.entity;

import software.bernie.geckolib3.util.GeckoLibUtil;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.IAnimatable;

import net.minecraftforge.network.PlayMessages;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;

import net.mcreator.carmodfour.init.CarmodfourModEntities;

public class CardemoEntity extends Mob implements IAnimatable {

    public static final EntityDataAccessor<Boolean> SHOOT = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<String> TEXTURE = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> VEHICLE_STATE = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DOOR_OPEN = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DRIVE_MODE = SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.STRING);

    public enum VehicleState { LOCKED, UNLOCKED, ENGINE_OFF, ENGINE_ON }
    public enum DriveState { PARK, DRIVE, REVERSE }

    private final AnimationFactory factory = GeckoLibUtil.createFactory(this);
    private Player owner = null;

    private static final Vec3 DRIVER_OFFSET = new Vec3(0.25, 0.65, 0.0);

    private boolean accelerating = false;
    private boolean braking = false;
    private boolean turningLeft = false;
    private boolean turningRight = false;

    private double currentSpeed = 0.0;
    private float currentTurnRate = 0.0f;
    private int crashCooldown = 0;
    private int exitGraceTimer = 0; // 🟢 prevents immediate crash after exiting

    private static final double MAX_SPEED = 1.0;
    private static final double ACCEL_FACTOR = 0.02;
    private static final double BRAKE_FACTOR = 0.25;
    private static final double DRAG = 0.008;
    private static final double IDLE_SPEED = 0.05;
    private static final float MAX_TURN_RATE = 5.5f;
    private static final float TURN_ACCEL = 0.35f;

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
    }

    // ====== Ownership & States ======
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

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(SHOOT, false);
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
        if (crashCooldown > 0) crashCooldown--;
        if (exitGraceTimer > 0) exitGraceTimer--;

        // 🟢 start grace period when exiting
        if (this.getPassengers().isEmpty() && exitGraceTimer == 0 && currentSpeed > 0.25) {
            exitGraceTimer = 20; // 1 second grace
        }

        if (!level.isClientSide && this.tickCount > 1200) {
            this.hurt(DamageSource.GENERIC, Float.MAX_VALUE);
            return;
        }

        if (!level.isClientSide) {
            if (isEngineOn()) {
                DriveState mode = getDriveState();
                switch (mode) {
                    case DRIVE -> handleDriveMode(tickDelta);
                    case REVERSE -> handleReverseMode(tickDelta);
                    case PARK -> handleParkMode();
                }
            }
            checkBlockCollision();
        } else {
            Minecraft mc = Minecraft.getInstance();
            renderDriveStateHotbar(mc);
        }
    }

    private void handleDriveMode(float tickDelta) {
        double targetMax = MAX_SPEED;
        if (!this.getPassengers().isEmpty()) {
            if (accelerating) {
                double accelRate = ACCEL_FACTOR * Math.pow(1.0 - (currentSpeed / targetMax), 3.0);
                currentSpeed += accelRate * tickDelta;
            } else if (braking) {
                currentSpeed -= BRAKE_FACTOR * currentSpeed * tickDelta;
            } else {
                currentSpeed -= DRAG * tickDelta;
                if (currentSpeed < IDLE_SPEED) currentSpeed = IDLE_SPEED;
            }
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
        } else currentSpeed -= (currentSpeed - IDLE_SPEED) / (5.0 / tickDelta);

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
        float newPitch = this.getXRot() * 0.8F;
        if (Math.abs(newPitch) < 0.5F) newPitch = 0F;
        this.setXRot(newPitch);
    }

    private void updateTurnAndMotion(float desiredTurn, float tickDelta, boolean reverse) {
        float turnDiff = desiredTurn - currentTurnRate;
        float turnStep = TURN_ACCEL * tickDelta;
        if (Math.abs(turnDiff) < turnStep) currentTurnRate = desiredTurn;
        else currentTurnRate += Math.signum(turnDiff) * turnStep;

        if (!turningLeft && !turningRight) {
            currentTurnRate *= 0.9f;
            if (Math.abs(currentTurnRate) < 0.002f) currentTurnRate = 0f;
        }

        this.setYRot(this.getYRot() + currentTurnRate * tickDelta);
        double yawRad = Math.toRadians(this.getYRot());
        double motionX = reverse ? Math.sin(yawRad) * currentSpeed : -Math.sin(yawRad) * currentSpeed;
        double motionZ = reverse ? -Math.cos(yawRad) * currentSpeed : Math.cos(yawRad) * currentSpeed;

        Vec3 motion = new Vec3(motionX, this.getDeltaMovement().y, motionZ);
        this.setDeltaMovement(motion);
        this.hasImpulse = true;
        this.move(MoverType.SELF, motion);
        applyTerrainTilt(yawRad, tickDelta);
    }

    private void applyTerrainTilt(double yawRad, float tickDelta) {
        double sampleDistance = 0.6;
        Vec3 forwardVec = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 frontPos = this.position().add(forwardVec.scale(sampleDistance));
        Vec3 backPos = this.position().add(forwardVec.scale(-sampleDistance));

        double frontY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) frontPos.x, (int) frontPos.z);
        double backY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) backPos.x, (int) backPos.z);

        double dy = frontY - backY;
        double dx = sampleDistance * 2;
        float targetPitch = (float) Math.toDegrees(Math.atan2(dy, dx)) * -1F;
        targetPitch = Math.max(-25F, Math.min(25F, targetPitch));

        float smoothing = 0.25F;
        float newPitch = this.getXRot() + (targetPitch - this.getXRot()) * smoothing;
        this.setXRot(newPitch);
    }

    private void checkBlockCollision() {
        if (crashCooldown > 0 || exitGraceTimer > 0) return; // 🟢 skip while in grace period

        double speedBps = currentSpeed * 20.0;
        Vec3 forward = new Vec3(-Math.sin(Math.toRadians(getYRot())), 0, Math.cos(Math.toRadians(getYRot())));
        Vec3 from = this.position().add(0, 0.4, 0);
        Vec3 to = from.add(forward.scale(1.2));
        HitResult result = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));

        if (result.getType() == HitResult.Type.BLOCK) {
            if (result.getLocation().y < this.getY() + 0.2) return;

            if (speedBps >= 13.0) {
                this.hurt(DamageSource.GENERIC, Float.MAX_VALUE);
                crashCooldown = 20;
                return;
            } else {
                currentSpeed = 0;
                setDeltaMovement(Vec3.ZERO);
                crashCooldown = 5;
                return;
            }
        }

        AABB hitbox = this.getBoundingBox().inflate(0.6);
        boolean hitSomething = false;
        for (Entity e : level.getEntities(this, hitbox)) {
            if (e == this || e == this.getFirstPassenger()) continue;
            if (!(e instanceof LivingEntity living)) continue;

            double damage = 0;
            if (speedBps >= 15.0) damage = 120.0;
            else if (speedBps >= 10.0) damage = 80.0;
            else if (speedBps >= 5.0) damage = 40.0;

            if (damage > 0) {
                living.hurt(DamageSource.mobAttack(this), (float) damage);
                double knockbackStrength = Math.min(1.5, speedBps / 10.0);
                Vec3 knockDir = forward.normalize().scale(knockbackStrength);
                living.setDeltaMovement(living.getDeltaMovement().add(knockDir.x, 0.4, knockDir.z));
                hitSomething = true;
            }
        }

        if (hitSomething) {
            currentSpeed = 0;
            setDeltaMovement(Vec3.ZERO);
            crashCooldown = 10;
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void renderDriveStateHotbar(Minecraft mc) {
        Player player = mc.player;
        if (player == null) return;
        if (player.getVehicle() != this || !isEngineOn()) return;

        double actualSpeed = this.getDeltaMovement().horizontalDistance() * 20.0;
        int speedBps = (int) Math.round(actualSpeed);

        String hotbarText = getDriveState() == DriveState.PARK ? "( 1. P )" : "1. P";
        hotbarText += " || ";
        hotbarText += getDriveState() == DriveState.DRIVE ? "( 2. D )" : "2. D";
        hotbarText += " || ";
        hotbarText += getDriveState() == DriveState.REVERSE ? "( 3. R )" : "3. R";
        hotbarText += String.format("  |  Speed: %d b/s", speedBps);

        mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(hotbarText), false);
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
        boolean sneaking = player.isShiftKeyDown();
        boolean client = this.level.isClientSide;

        if (sneaking) {
            if (!client) {
                if (isLocked()) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("Car is locked."), true);
                    return InteractionResult.FAIL;
                }
                setDoorOpen(!isDoorOpen());
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
                .add(Attributes.MAX_HEALTH, 60)
                .add(Attributes.ARMOR, 10)
                .add(Attributes.ATTACK_DAMAGE, 0)
                .add(Attributes.FOLLOW_RANGE, 16);
    }

    @Override
    public void registerControllers(AnimationData data) {
        data.addAnimationController(new AnimationController<>(this, "door_controller", 0, this::doorPredicate));
    }

    private <E extends IAnimatable> PlayState doorPredicate(AnimationEvent<E> event) {
        AnimationController<?> controller = event.getController();
        if (this.isDoorOpen()) {
            controller.setAnimation(new AnimationBuilder().addAnimation("r_door_open", EDefaultLoopTypes.PLAY_ONCE));
        } else {
            controller.setAnimation(new AnimationBuilder().addAnimation("r_door_close", EDefaultLoopTypes.PLAY_ONCE));
        }
        return PlayState.CONTINUE;
    }

    @Override
    public AnimationFactory getFactory() { return this.factory; }
}
