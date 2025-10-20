package net.mcreator.carmodfour.entity;

/*
 * =============================================================================
 *  CardemoEntity.java  —  Vehicle Entity with Wall-Aware Roll, Terrain Tilt,
 *                         Silent Damage, and Robust Bump Recoil (Forge 1.19.2)
 * =============================================================================
 *
 *  PURPOSE OF THIS REVISION
 *  ------------------------
 *  The user requested a "bumping" mechanic and verified that earlier attempts
 *  did not trigger consistently. This revision strengthens bump detection and
 *  fixes entity removal on death while preserving all existing systems:
 *
 *   ✓ Manual drive states (PARK/DRIVE/REVERSE) with steering inertia
 *   ✓ Terrain pitch + wall-aware roll (client-synced)
 *   ✓ Kinetic collision damage to others (armor-bypassing)
 *   ✓ Silent incoming damage (no red flash for this vehicle)
 *   ✓ NEW: Bump recoil that triggers reliably on walls or steep rises
 *   ✓ NEW: Proper removal on death (entity vanishes as expected)
 *
 * =============================================================================
 */

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
import net.minecraft.core.BlockPos;

import net.mcreator.carmodfour.init.CarmodfourModEntities;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public class CardemoEntity extends Mob implements IAnimatable {

    // ==========================================================================
    // SYNCHRONIZED / RUNTIME STATE
    // ==========================================================================

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
    private static final EntityDataAccessor<Float> ROLL_SYNC =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.FLOAT);

    public enum VehicleState { LOCKED, UNLOCKED, ENGINE_OFF, ENGINE_ON }
    public enum DriveState   { PARK, DRIVE, REVERSE }

    private final AnimationFactory factory = GeckoLibUtil.createFactory(this);
    private String animationProcedure = "empty";
    private Player owner = null;
    private static final Vec3 DRIVER_OFFSET = new Vec3(0.25, 0.45, 0.3);

    private boolean accelerating = false;
    private boolean braking      = false;
    private boolean turningLeft  = false;
    private boolean turningRight = false;

    private double currentSpeed    = 0.0;
    private float  currentTurnRate = 0.0f;

    private static final double MAX_SPEED     = 0.35;
    private static final double ACCEL_FACTOR  = 0.08;
    private static final double BRAKE_FACTOR  = 0.25;
    private static final double DRAG          = 0.01;
    private static final double IDLE_SPEED    = 0.02;
    private static final float  MAX_TURN_RATE = 4.0f;
    private static final float  TURN_ACCEL    = 0.25f;

    private static final float HITBOX_WIDTH  = 2.5f;
    private static final float HITBOX_HEIGHT = 1.0f;
    private static final float HITBOX_LENGTH = 2.5f;

    @OnlyIn(Dist.CLIENT) private Vec3   clientPrevPos   = null;
    @OnlyIn(Dist.CLIENT) private double clientSpeedBps  = 0.0;
    private float visualRoll = 0.0f;

    private static final float TERRAIN_ROLL_CLAMP_DEG = 15.0f;
    private static final float PROX_ROLL_CLAMP_DEG    = 22.5f;
    private static final float TOTAL_ROLL_CLAMP_DEG   = 22.5f;
    private static final float ROLL_SMOOTH            = 0.15f;
    private static final float PROX_DEADZONE          = 0.03f;

    private static final double SIDE_CHECK_DIST      = 1.35;
    private static final double SIDE_CHECK_HALF_LEN  = 0.90;
    private static final double SIDE_CHECK_STEP_LEN  = 0.30;
    private static final double SIDE_CHECK_Y_START   = 0.10;
    private static final double SIDE_CHECK_Y_END     = 1.20;
    private static final double SIDE_CHECK_Y_STEP    = 0.30;

    private static final boolean DBG_COLLISION_EVENTS = false;
    private static final boolean DBG_BUMP_LOGS        = false;
    private static void dlog(boolean on, String msg) { if (on) System.out.println("[Cardemo] " + msg); }

    // ==========================================================================
    // BUMP / RECOIL SYSTEM (ROBUST)
    // ==========================================================================

    private static final double BUMP_PROBE_DIST   = 1.5;
    private static final double BUMP_SLOPE_THRESH = 2.0;
    private static final double RECOIL_TOTAL_DIST = 1.10;
    private static final int    RECOIL_DURATION   = 12;
    private static final int    RECOIL_COOLDOWN   = 8;
    private static final float  BUMP_VOL          = 0.90f;
    private static final float  BUMP_PITCH        = 0.85f;

    private int   recoilTicks      = 0;
    private int   recoilCooldown   = 0;
    private Vec3  recoilDirection  = Vec3.ZERO;
    private double recoilProgress  = 0.0;

    public CardemoEntity(PlayMessages.SpawnEntity packet, Level world) {
        this(CarmodfourModEntities.CARDEMO.get(), world);
    }

    public CardemoEntity(EntityType<CardemoEntity> type, Level world) {
        super(type, world);
        setNoAi(false);
        setNoGravity(false);
        this.maxUpStep = 1.1f;
    }

    public void setOwner(Player player) { if (owner == null) owner = player; }
    public Player getOwner() { return owner; }
    public boolean isOwner(Player player) { return owner != null && owner.getUUID().equals(player.getUUID()); }

    public VehicleState getState() {
        try { return VehicleState.valueOf(this.entityData.get(VEHICLE_STATE)); }
        catch (IllegalArgumentException e) { return VehicleState.LOCKED; }
    }
    public void setState(VehicleState state) { this.entityData.set(VEHICLE_STATE, state.name()); }

    public boolean isLocked()   { return getState() == VehicleState.LOCKED; }
    public boolean isEngineOn() { return getState() == VehicleState.ENGINE_ON; }
    public void setLocked(boolean value)   { setState(value ? VehicleState.LOCKED    : VehicleState.UNLOCKED); }
    public void setEngineOn(boolean value) { setState(value ? VehicleState.ENGINE_ON : VehicleState.ENGINE_OFF); }

    public DriveState getDriveState() {
        try { return DriveState.valueOf(this.entityData.get(DRIVE_MODE)); }
        catch (IllegalArgumentException e) { return DriveState.PARK; }
    }
    public void setDriveState(DriveState state) { this.entityData.set(DRIVE_MODE, state.name()); }

    public boolean isDoorOpen() { return this.entityData.get(DOOR_OPEN); }
    public void setDoorOpen(boolean open) { this.entityData.set(DOOR_OPEN, open); }

    public String getTexture()         { return this.entityData.get(TEXTURE); }
    public String getSyncedAnimation() { return this.entityData.get(ANIMATION); }
    public void   setAnimation(String name) { this.entityData.set(ANIMATION, name); }
    public void   setAnimationProcedure(String animation) { setAnimation(animation); }

    public void setAccelerating(boolean b) { this.accelerating = b; }
    public void setBraking(boolean b)      { this.braking = b; }
    public void setTurningLeft(boolean b)  { this.turningLeft = b; }
    public void setTurningRight(boolean b) { this.turningRight = b; }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(SHOOT, false);
        this.entityData.define(ANIMATION, "undefined");
        this.entityData.define(TEXTURE, "cardemo");
        this.entityData.define(VEHICLE_STATE, VehicleState.LOCKED.name());
        this.entityData.define(DOOR_OPEN, false);
        this.entityData.define(DRIVE_MODE, DriveState.PARK.name());
        this.entityData.define(ROLL_SYNC, 0.0f);
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

    @Override
    public void tick() {
        super.tick();
        if (recoilCooldown > 0) recoilCooldown--;
        if (!level.isClientSide) {
            if (isEngineOn()) {
                switch (getDriveState()) {
                    case DRIVE   -> handleDriveMode();
                    case REVERSE -> handleReverseMode();
                    case PARK    -> handleParkMode();
                }
                handleEntityCollisions();
            }
        } else updateClientSpeedOverlay();
    }

    // --------------------------------------------------------------------------
    // CAMERA LURCH VISUAL (shared to renderer)
    // --------------------------------------------------------------------------
    @OnlyIn(Dist.CLIENT)
    public static float cameraLurchPitchOffset = 0f;

    // --------------------------------------------------------------------------
    // CAMERA LURCH (forward/back impact jolt)
    // --------------------------------------------------------------------------
    @OnlyIn(Dist.CLIENT)
    private static float lurchStrength = 0f;
    @OnlyIn(Dist.CLIENT)
    private static int lurchTicks = 0;
    @OnlyIn(Dist.CLIENT)
    private static int lurchDuration = 0;


    // --------------------------------------------------------------------------
// CLIENT-SIDE HOTBAR OVERLAY (Speed / Drive / HP / Horn / Turn Signals)
// --------------------------------------------------------------------------
    @OnlyIn(Dist.CLIENT)
    private void updateClientSpeedOverlay() {
        Vec3 now = this.position();
        if (clientPrevPos == null) clientPrevPos = now;
        else {
            Vec3 delta = now.subtract(clientPrevPos);
            double instSpeed = new Vec3(delta.x, 0, delta.z).length() * 20.0;
            clientSpeedBps = clientSpeedBps * 0.8 + instSpeed * 0.2;
            clientPrevPos = now;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.isPassengerOfSameVehicle(this) && isEngineOn()) {
            // --- Drive mode indicators ---
            String p = getDriveState() == DriveState.PARK    ? "( P )" : "P";
            String d = getDriveState() == DriveState.DRIVE   ? "( D )" : "D";
            String r = getDriveState() == DriveState.REVERSE ? "( R )" : "R";

            // --- Speed and health stats ---
            float speed = (float) clientSpeedBps;
            float hp    = this.getHealth();
            float maxHp = this.getMaxHealth();
            int hpInt   = (int) Math.ceil(hp);
            int maxInt  = (int) Math.ceil(maxHp);

            // --- Determine HP color ---
            float ratio = hp / maxHp;
            String colorCode;
            if (ratio > 0.6f) colorCode = "§a";       // Green (safe)
            else if (ratio > 0.25f) colorCode = "§e"; // Yellow (moderate)
            else colorCode = "§c";                    // Red (critical)

            // --- Horn indicator ---
            boolean hornDown = net.mcreator.carmodfour.client.DriveStateKeybindHandler.HORN_KEY.isDown();
            String horn = hornDown ? "§cH§r" : "H";

            // --- Turn signal indicators (updated) ---
            boolean leftActive  = net.mcreator.carmodfour.client.DriveStateKeybindHandler.isLeftSignalOn();
            boolean rightActive = net.mcreator.carmodfour.client.DriveStateKeybindHandler.isRightSignalOn();
            boolean leftVisible  = net.mcreator.carmodfour.client.DriveStateKeybindHandler.isLeftSignalVisible();
            boolean rightVisible = net.mcreator.carmodfour.client.DriveStateKeybindHandler.isRightSignalVisible();

            String leftArrow;
            if (leftActive) {
                leftArrow = leftVisible ? "§e<§r" : "";  // blink yellow → vanish fully
            } else {
                leftArrow = "<"; // steady white when inactive
            }

            String rightArrow;
            if (rightActive) {
                rightArrow = rightVisible ? "§e>§r" : ""; // blink yellow → vanish fully
            } else {
                rightArrow = ">"; // steady white when inactive
            }

            // --- Build final overlay line ---
            String text = String.format(
                    "%s || %s || | %s | %s | %s |  ||  %.1f b/s  ||  HP : %s%d§r / %d || %s",
                    leftArrow, horn, p, d, r, speed, colorCode, hpInt, maxInt, rightArrow
            );

            // --- Display overlay (above hotbar) ---
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(text), false);
        }
    }

    private void handleEntityCollisions() {
        if (currentSpeed <= 0.01) return;
        double yawRad = Math.toRadians(this.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        AABB hitbox = this.getBoundingBox().move(forward.scale(0.6)).inflate(0.6);
        java.util.List<Entity> entities = level.getEntities(this, hitbox,
                e -> e instanceof LivingEntity && e != this && !e.isPassengerOfSameVehicle(this));
        if (entities.isEmpty()) return;
        double speedBps = currentSpeed * 20.0;
        currentSpeed = 0.0;
        for (Entity e : entities) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            float damageHp = (float)(speedBps * 4.0);
            living.hurt(DamageSource.OUT_OF_WORLD, damageHp);
            double kb = Math.min(0.5 + speedBps / 3.0, 10.0);
            double up = Math.min(0.20 + speedBps / 50.0, 1.0);
            Vec3 fling = new Vec3(forward.x, up, forward.z).normalize().scale(kb);
            living.push(fling.x, fling.y, fling.z);
            living.hasImpulse = true;
        }
    }

    private void handleDriveMode()  { accelerateAndMove(false); }
    private void handleReverseMode(){ accelerateAndMove(true);  }
    private void handleParkMode()   { currentSpeed = 0.0; currentTurnRate *= 0.5f; }

    private void accelerateAndMove(boolean reverse) {
        if (recoilTicks > 0) {
            applyRecoilStep();
            double yawRadRecoil = Math.toRadians(this.getYRot());
            applyTerrainAndProximityTilt(yawRadRecoil);
            return;
        }

        double targetMax = reverse ? MAX_SPEED * 0.5 : MAX_SPEED;
        double accel     = reverse ? ACCEL_FACTOR * 0.75 : ACCEL_FACTOR;

        if (accelerating) currentSpeed += accel * (targetMax - currentSpeed);
        else if (braking) currentSpeed -= BRAKE_FACTOR * currentSpeed;
        else {
            currentSpeed -= DRAG;
            if (currentSpeed < IDLE_SPEED) currentSpeed = IDLE_SPEED;
        }

        if (currentSpeed < 0) currentSpeed = 0;
        if (currentSpeed > targetMax) currentSpeed = targetMax;

        float desiredTurn = 0f;
        if (turningLeft && !turningRight) desiredTurn = -MAX_TURN_RATE;
        if (turningRight && !turningLeft) desiredTurn = MAX_TURN_RATE;
        if (reverse) desiredTurn *= -1f;

        float deltaTurn = (desiredTurn - currentTurnRate) * TURN_ACCEL;
        currentTurnRate += deltaTurn;

        if (!turningLeft && !turningRight) {
            float speedRatio = (float)(currentSpeed / MAX_SPEED);
            float centerFactor = 0.85f + 0.15f * (1 - speedRatio);
            currentTurnRate *= centerFactor;
            if (Math.abs(currentTurnRate) < 0.01f) currentTurnRate = 0f;
        }

        this.setYRot(this.getYRot() + currentTurnRate);
        double yawRad = Math.toRadians(this.getYRot());

        // ---------------------------------------------------------------------
        // 🧩 BUMP DETECTION (velocity-scaled HP loss + anvil sound intensity)
        // ---------------------------------------------------------------------
        if (currentSpeed > 0.05 && recoilCooldown == 0) {
            boolean frontHit = !reverse && isBlockedAheadOrTooSteep(yawRad);
            boolean rearHit  =  reverse && isBlockedBehindOrTooSteep(yawRad);

            if (frontHit || rearHit) {
                double impactSpeedBps = Math.abs(currentSpeed * 20.0);

                // Apply HP loss scaled by impact velocity
                if (impactSpeedBps > 1.5) {
                    double damage = Math.pow(impactSpeedBps, 1.25) * 0.35;
                    if (damage < 1.0) damage = 1.0;
                    this.hurt(DamageSource.GENERIC, (float) damage);
                }

                // 🔊 Play anvil sound scaled by impact velocity
                if (!this.level.isClientSide) {
                    float vol = (float) Math.min(0.2 + (impactSpeedBps / 10.0f) * 0.8f, 1.0f);
                    float pitch = 0.75f + (float) Math.min(impactSpeedBps / 20.0f, 0.3f);
                    this.level.playSound(null, this.blockPosition(),
                            SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, vol, pitch);
                }

                triggerRecoil(yawRad, rearHit);
                applyTerrainAndProximityTilt(yawRad);
                return;
            }
        }

        // ---------------------------------------------------------------------
        // 🧱 Preemptive bump clamp — stop forward motion if wall detected right before move
        // ---------------------------------------------------------------------
        if (recoilCooldown == 0 && currentSpeed > 0.01) {
            boolean frontHit = !reverse && isBlockedAheadOrTooSteep(yawRad);
            boolean rearHit  =  reverse && isBlockedBehindOrTooSteep(yawRad);

            if (frontHit || rearHit) {
                double impactSpeedBps = Math.abs(currentSpeed * 20.0);
                currentSpeed = 0;

                // Apply scaled HP loss
                if (impactSpeedBps > 1.5) {
                    double damage = Math.pow(impactSpeedBps, 1.25) * 0.35;
                    if (damage < 1.0) damage = 1.0;
                    this.hurt(DamageSource.GENERIC, (float) damage);
                }

                // 🔊 Velocity-scaled anvil sound
                if (!this.level.isClientSide) {
                    float vol = (float) Math.min(0.2 + (impactSpeedBps / 10.0f) * 0.8f, 1.0f);
                    float pitch = 0.75f + (float) Math.min(impactSpeedBps / 20.0f, 0.3f);
                    this.level.playSound(null, this.blockPosition(),
                            SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, vol, pitch);
                }

                triggerRecoil(yawRad, rearHit);
                applyTerrainAndProximityTilt(yawRad);
                return;
            }
        }

        double motionX = reverse ?  Math.sin(yawRad) * currentSpeed : -Math.sin(yawRad) * currentSpeed;
        double motionZ = reverse ? -Math.cos(yawRad) * currentSpeed :  Math.cos(yawRad) * currentSpeed;

        double verticalBoost = 0.0;
        if (this.onGround && currentSpeed > 0.05)
            verticalBoost = 0.1 * Math.min(1.0, currentSpeed / MAX_SPEED);

        // ---------------------------------------------------------------------
        // 🎥 APPLY CAMERA LURCH (client-side forward/recoil motion)
        // ---------------------------------------------------------------------
        if (level.isClientSide) {
            applyCameraLurch(Minecraft.getInstance());
        }

        // ---------------------------------------------------------------------
        // 🚗 MOTION + MOVEMENT
        // ---------------------------------------------------------------------
        Vec3 motion = new Vec3(motionX, getDeltaMovement().y + verticalBoost, motionZ);
        setDeltaMovement(motion);
        hasImpulse = true;
        move(MoverType.SELF, motion);

        // ---------------------------------------------------------------------
        // 🧭 TERRAIN TILT + WALL PROXIMITY
        // ---------------------------------------------------------------------
        applyTerrainAndProximityTilt(yawRad);

        // ---------------------------------------------------------------------
        // 🕐 FORWARD GRACE WINDOW — prevent false bumps when coasting
        // ---------------------------------------------------------------------
        if (!reverse) {
            if (currentSpeed > 0.05) {
                forwardGraceTicks = FORWARD_GRACE_MAX;
            } else if (forwardGraceTicks > 0) {
                forwardGraceTicks--;
            }
        }
    }


// ==========================================================================
// BUMP / RECOIL IMPLEMENTATION — full-block climbable (air-above aware)
// ==========================================================================

    // --- Motion grace buffer (prevents false bumps when slowing or coasting) ---
    private int forwardGraceTicks = 0;
    private static final int FORWARD_GRACE_MAX = 6; // ~0.3 s grace at 20 TPS

    // --- Probe sampling parameters ---
    private static final double PROBE_STEP_BASE = 0.25; // fixed sample spacing (m)
    private static final int    GROUND_SMOOTH_WINDOW = 3; // rolling average window
    private static final double MAX_CONTINUOUS_SLOPE_DEG = 55.0; // tolerance for hill angle

    // --- Prevents multiple bump sounds firing at once (e.g., double anvil overlap) ---
    private int recentBumpSoundTicks = 0;

    // ==========================================================================
    // DAMAGE + DEATH HANDLING — silent, death-safe despawn
    // ==========================================================================
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) return false;

        // Use vanilla reduction so attributes & events stay correct
        boolean took = super.hurt(source, amount);

        // Suppress red flash / invulnerability blink
        this.hurtTime = 0;
        this.invulnerableTime = 0;

        // Server-side immediate cleanup if dead
        if (!this.level.isClientSide && !this.isAlive()) {
            this.discard();
        }
        return took;
    }

    // --------------------------------------------------------------------------
    // die() override — guarantees despawn for any lethal event
    // --------------------------------------------------------------------------
    @Override
    public void die(DamageSource cause) {
        super.die(cause); // run vanilla event chain first

        if (!this.level.isClientSide && !this.isRemoved()) {
            // Small visual & audio flourish
            if (this.level instanceof net.minecraft.server.level.ServerLevel server) {
                for (int i = 0; i < 12; i++) {
                    double ox = (this.random.nextDouble() - 0.5) * 1.5;
                    double oy = this.random.nextDouble() * 0.8;
                    double oz = (this.random.nextDouble() - 0.5) * 1.5;
                    server.sendParticles(ParticleTypes.SMOKE,
                            this.getX() + ox, this.getY() + oy, this.getZ() + oz,
                            1, 0, 0, 0, 0.01);
                }
            }
            this.level.playSound(null, this.blockPosition(),
                    SoundEvents.ANVIL_BREAK, SoundSource.BLOCKS, 0.8f, 0.85f);

            // 🔹 Final guaranteed removal
            this.discard();
        }
    }

    /**
     * Detects a true, unclimbable rise (or solid wall) directly ahead or behind.
     * Handles stairs, slabs, voxel hills, and solid-with-air-above full-block slopes.
     */
    private boolean isBlockedAheadOrTooSteep(double yawRad) {
        return detectBumpDirection(yawRad, false);
    }

    private boolean isBlockedBehindOrTooSteep(double yawRad) {
        return detectBumpDirection(yawRad, true);
    }

    /**
     * Generic directional bump detection — continuous-slope and air-above aware.
     * (Restored robust version so recoil triggers reliably.)
     */
    private boolean detectBumpDirection(double yawRad, boolean reverse) {
        if (recoilTicks > 0) return false;

        // Grace period
        if (!reverse && forwardGraceTicks > 0 && currentSpeed < 0.05)
            return false;

        // Fixed lookahead (front = 2 m, rear = 3 m)
        final double lookahead = reverse ? 3.0 : 2.0;
        final double tinyNoise = 0.12;
        final double climbCap  = this.maxUpStep + 0.05;

        // Lag compensation (TPS scaling) — left as-is if you want
        double tickScale = 1.0;
        if (level != null && level.getServer() != null) {
            double tps = Math.max(10.0, level.getServer().getAverageTickTime() > 0
                    ? 1000.0 / level.getServer().getAverageTickTime()
                    : 20.0);
            tickScale = 20.0 / tps;
        }

        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
        if (reverse) forward = forward.scale(-1);

        Vec3 here = this.position();
        double hereY = getGroundY(here);

        // Offset to bumper
        Vec3 originOffset = forward.scale(reverse ? -1.0 : 0.4);
        Vec3 originPos = here.add(originOffset);

        // ----------------------------------------------------------------------
        // (A) Continuous ascent-aware probe check — full-block and voxel-slope friendly
        // ----------------------------------------------------------------------
        double probeStep = PROBE_STEP_BASE * tickScale;
        double lastY = hereY;
        double totalRise = 0.0;
        double totalRun  = 0.0;
        int consecutiveSmallRises = 0;

        java.util.ArrayDeque<Double> smoothHeights = new java.util.ArrayDeque<>();

        for (double dist = probeStep; dist <= lookahead + 1e-6; dist += probeStep) {
            Vec3 p = originPos.add(forward.scale(dist));
            double y = getGroundY(p);

            smoothHeights.add(y);
            if (smoothHeights.size() > GROUND_SMOOTH_WINDOW)
                smoothHeights.removeFirst();
            double avgY = smoothHeights.stream().mapToDouble(Double::doubleValue).average().orElse(y);

            double rise = avgY - lastY;
            totalRun += probeStep;

            // --- Solid-with-air-above logic (voxel hills, 1-block steps) ---
            BlockPos basePos = new BlockPos(p.x, Math.floor(y - 0.5), p.z);
            BlockPos abovePos = basePos.above();
            var baseState = level.getBlockState(basePos);
            var aboveState = level.getBlockState(abovePos);
            boolean solid = !baseState.isAir() && baseState.getMaterial().isSolid();
            boolean airAbove = aboveState.isAir();

            if (solid && airAbove && rise <= (1.0 + 0.15)) {
                // treat this as a step we can mount
                lastY = basePos.getY() + 1.0;
                consecutiveSmallRises++;
                continue;
            }

            if (rise > 0) {
                totalRise += rise;

                // Allow chained 1-block ascents (continuous voxel ramps)
                if (rise <= (this.maxUpStep + 0.15)) {
                    consecutiveSmallRises++;
                    lastY = avgY;
                    continue;
                }

                // if multiple small steps in sequence → still climbable
                double avgSlope = Math.toDegrees(Math.atan2(totalRise, totalRun));
                if (consecutiveSmallRises >= 2 && avgSlope <= MAX_CONTINUOUS_SLOPE_DEG) {
                    lastY = avgY;
                    continue;
                }
            }

            if (rise <= tinyNoise) { lastY = avgY; continue; }

            // Skip climbable blocks (stairs/slabs)
            if (isClimbableBlock(basePos)) { lastY = avgY; continue; }

            // check steep or tall rise
            double slopeDeg = Math.toDegrees(Math.atan2(rise, probeStep));
            if (slopeDeg > 70.0 && rise > climbCap) {
                dlog(DBG_BUMP_LOGS, String.format(
                        "BUMP (%s): slope=%.1f° rise=%.2f > climbCap=%.2f at dist=%.1f",
                        reverse ? "rear" : "front", slopeDeg, rise, climbCap, dist));

                // if bump impact reduces health to 0, ensure despawn
                if (!this.level.isClientSide && !this.isAlive()) this.discard();
                return true;
            }

            // reset if descent or flat
            if (rise <= 0) consecutiveSmallRises = 0;
            lastY = avgY;
        }

        // ----------------------------------------------------------------------
        // (B) Wall/solid check (same as before)
        // ----------------------------------------------------------------------
        final double[] lateral = { 0.0, +0.35, -0.35 };
        final double[] heights = { 0.6, 1.2 };
        Vec3 right = new Vec3(Math.cos(yawRad), 0, Math.sin(yawRad));

        for (double h : heights) {
            for (double lat : lateral) {
                Vec3 origin = originPos.add(right.scale(lat)).add(0.0, h, 0.0);
                Vec3 end = origin.add(forward.scale(lookahead + 0.1));

                var ctx = new net.minecraft.world.level.ClipContext(
                        origin, end,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,
                        this
                );
                var hit = this.level.clip(ctx);
                if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    var bhr = (net.minecraft.world.phys.BlockHitResult) hit;
                    BlockPos pos = bhr.getBlockPos();
                    var state = level.getBlockState(pos);

                    boolean solidWall = !state.isAir() &&
                            (state.getMaterial().isSolid() || !state.getCollisionShape(level, pos).isEmpty());
                    if (!solidWall) continue;
                    if (isClimbableBlock(pos)) continue;

                    double hitY = hit.getLocation().y;
                    double riseFromGround = hitY - hereY;
                    if (riseFromGround > climbCap) {
                        dlog(DBG_BUMP_LOGS, String.format(
                                "BUMP (%s): wall rise=%.2f > climbCap=%.2f at %s",
                                reverse ? "rear" : "front", riseFromGround, climbCap, pos));

                        if (!this.level.isClientSide && !this.isAlive()) this.discard();
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /** Recognizes stairs, slabs, gentle terrain, and solid-with-air-above blocks as climbable. */
    private boolean isClimbableBlock(BlockPos pos) {
        if (level == null) return false;
        var state = level.getBlockState(pos);
        if (state.isAir()) return false;

        // Full solid block with air above = climbable
        if (state.getMaterial().isSolid() && level.isEmptyBlock(pos.above()))
            return true;

        var block = state.getBlock();
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
        if (key == null) return false;

        String name = key.getPath();
        return name.contains("stairs") || name.contains("slab")
                || name.contains("path") || name.contains("carpet")
                || name.contains("grass_block") || name.contains("gravel");
    }

    /** Initiates recoil motion, cancels movement, plays thunk (single/cooldowned sound). */
    private void triggerRecoil(double yawRad, boolean reverseHit) {
        // 🎥 (Camera lurch left intact; harmless if it does nothing on your client)
        if (this.level.isClientSide) {
            double impactSpeedBps = Math.abs(currentSpeed * 20.0);
            if (impactSpeedBps > 2.0) {
                lurchStrength = (float)Math.min(impactSpeedBps / 10.0, 0.5f);
                lurchDuration = (int)Math.min(30 + (impactSpeedBps * 4.0), 30);
                lurchTicks = lurchDuration;
            }
        }

        if (recoilTicks > 0 || recoilCooldown > 0) return;

        double dirSign = reverseHit ? -1.0 : 1.0;
        this.recoilDirection = new Vec3(Math.sin(yawRad) * dirSign, 0, -Math.cos(yawRad) * dirSign).normalize();
        this.recoilTicks = RECOIL_DURATION;
        this.recoilProgress = 0.0;
        this.currentSpeed = 0.0;
        setDeltaMovement(new Vec3(0, getDeltaMovement().y, 0));

        // 🔊 Single anvil sound with short cooldown to prevent overlaps
        if (!this.level.isClientSide && recentBumpSoundTicks == 0) {
            this.level.playSound(null, this.blockPosition(),
                    SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS,
                    BUMP_VOL, BUMP_PITCH);
            recentBumpSoundTicks = 5; // ~0.25s at 20 TPS
        }

        // Handle death in case the bump itself is lethal
        if (!this.level.isClientSide && !this.isAlive()) this.discard();
    }

    /** Applies one step of the recoil using a quadratic ease-out curve. */
    private void applyRecoilStep() {
        if (recoilTicks <= 0) return;

        double tPrev = recoilProgress;
        double tNext = (RECOIL_DURATION - recoilTicks + 1) / (double) RECOIL_DURATION;
        recoilProgress = tNext;

        double easePrev = 1.0 - Math.pow(1.0 - tPrev, 2.0);
        double easeNext = 1.0 - Math.pow(1.0 - tNext, 2.0);
        double distThisTick = (easeNext - easePrev) * RECOIL_TOTAL_DIST;

        Vec3 step = recoilDirection.scale(distThisTick);
        move(MoverType.SELF, step);

        recoilTicks--;
        if (recoilTicks <= 0) {
            recoilCooldown = RECOIL_COOLDOWN;
            recoilDirection = Vec3.ZERO;
            recoilProgress = 0.0;
        }

        // Final safety: despawn if dead after recoil finishes
        if (!this.level.isClientSide && !this.isAlive()) this.discard();
    }

// ==========================================================================
// END BUMP / RECOIL IMPLEMENTATION — death-safe
// ==========================================================================




    // ==========================================================================
    // TILT CALCULATION (PITCH + ROLL COMBINED)
    // ==========================================================================

    private void applyTerrainAndProximityTilt(double yawRad) {
        final double sampleDistance = 0.8;

        // Basis
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 right   = new Vec3(Math.cos(yawRad),  0, Math.sin(yawRad));

        // Sample points around the car
        Vec3 frontPos = position().add(forward.scale(sampleDistance));
        Vec3 backPos  = position().add(forward.scale(-sampleDistance));
        Vec3 rightPos = position().add(right.scale(sampleDistance));
        Vec3 leftPos  = position().add(right.scale(-sampleDistance));

        // Ground heights (ceiling-safe)
        double frontY = getGroundY(frontPos);
        double backY  = getGroundY(backPos);
        double rightY = getGroundY(rightPos);
        double leftY  = getGroundY(leftPos);

        // Pitch from front/back
        float pitchTarget = (float) Math.toDegrees(Math.atan2(frontY - backY, sampleDistance * 2.0)) * -1F;
        if (pitchTarget > 60f) pitchTarget = 60f;
        if (pitchTarget < -60f) pitchTarget = -60f;
        float newPitch = lerp(getXRot(), pitchTarget, 0.25f);
        setXRot(newPitch);

        // Roll: terrain + proximity
        double dyRoll = leftY - rightY;
        float rawTerrainRoll = (float) Math.toDegrees(Math.atan2(dyRoll, sampleDistance * 2.0));
        if (rawTerrainRoll >  TERRAIN_ROLL_CLAMP_DEG) rawTerrainRoll =  TERRAIN_ROLL_CLAMP_DEG;
        if (rawTerrainRoll < -TERRAIN_ROLL_CLAMP_DEG) rawTerrainRoll = -TERRAIN_ROLL_CLAMP_DEG;
        float terrainRoll = rawTerrainRoll;

        float leftClose  = computeSideCloseness(true,  yawRad);
        float rightClose = computeSideCloseness(false, yawRad);
        float diff = (leftClose - rightClose);
        if (Math.abs(diff) < PROX_DEADZONE) diff = 0f;
        float proximityRoll = diff * PROX_ROLL_CLAMP_DEG;

        float targetRoll = terrainRoll + proximityRoll;
        if (targetRoll >  TOTAL_ROLL_CLAMP_DEG) targetRoll =  TOTAL_ROLL_CLAMP_DEG;
        if (targetRoll < -TOTAL_ROLL_CLAMP_DEG) targetRoll = -TOTAL_ROLL_CLAMP_DEG;

        visualRoll = lerp(visualRoll, targetRoll, ROLL_SMOOTH);

        // Sync to clients
        if (!level.isClientSide) this.entityData.set(ROLL_SYNC, visualRoll);
    }

    /**
     * Side closeness scanner for wall-aware roll. Returns value in [0,1].
     */
    private float computeSideCloseness(boolean leftSide, double yawRad) {
        if (level == null) return 0f;

        // Basis
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 right   = new Vec3(Math.cos(yawRad),  0, Math.sin(yawRad));
        Vec3 sideDir = leftSide ? right.scale(-1) : right;

        float strongest = 0f;

        // Along the car length
        for (double t = -SIDE_CHECK_HALF_LEN; t <= SIDE_CHECK_HALF_LEN + 1e-6; t += SIDE_CHECK_STEP_LEN) {
            Vec3 along = forward.scale(t);

            // Outward rays
            for (double s = 0.0; s <= SIDE_CHECK_DIST + 1e-6; s += 0.15) {
                Vec3 base = position().add(along).add(sideDir.scale(s));

                // Vertical samples
                for (double yoff = SIDE_CHECK_Y_START; yoff <= SIDE_CHECK_Y_END + 1e-6; yoff += SIDE_CHECK_Y_STEP) {
                    BlockPos bp = new BlockPos(
                            (int) Math.floor(base.x),
                            (int) Math.floor(this.getY() + yoff - 0.2),
                            (int) Math.floor(base.z)
                    );
                    var state = level.getBlockState(bp);

                    // Solid or any collision shape counts
                    if (!state.isAir() && (state.getMaterial().isSolid() || !state.getCollisionShape(level, bp).isEmpty())) {
                        float frac = (float) (1.0 - (s / SIDE_CHECK_DIST));
                        if (frac > strongest) strongest = frac;
                        // Stop this outward slice once obstacle is found
                        break;
                    }
                }
            }
        }

        if (strongest < 0f) strongest = 0f;
        if (strongest > 1f) strongest = 1f;
        return strongest;
    }

    /**
     * Ceiling-safe ground height finder to avoid sampling tunnel/bridge ceilings.
     */
    private double getGroundY(Vec3 pos) {
        int startY = (int) Math.floor(pos.y);
        int minY   = level.getMinBuildHeight();

        for (int y = startY; y >= startY - 8 && y >= minY; y--) {
            BlockPos check = new BlockPos((int) Math.floor(pos.x), y, (int) Math.floor(pos.z));
            if (!level.getBlockState(check).isAir()) {
                // First solid block found; ground is its top face
                return y + 1.0;
            }
        }

        // Heightmap fallback
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(pos.x), (int) Math.floor(pos.z));
    }

    // ==========================================================================
    // CAMERA LURCH SYSTEM (forward/back head impulse)
    // ==========================================================================
    @OnlyIn(Dist.CLIENT)
    private void applyCameraLurch(Minecraft mc) {
        if (mc.player == null || !mc.player.isPassengerOfSameVehicle(this)) return;
        if (lurchTicks <= 0) return;

        // Normalized time progression (0 → 1)
        float t = (float)(lurchDuration - lurchTicks) / (float)lurchDuration;

        // Ease curve: quick push forward, then gentle rebound
        float intensity;
        if (t < 0.4f) {
            // Rapid initial pitch forward
            intensity = (float)(Math.sin(t * Math.PI * 1.2) * lurchStrength);
            mc.player.turn(0f, intensity * 4.0f);
        } else {
            // Recoil recovery
            float recoverT = (t - 0.4f) / 0.6f; // normalize [0.4..1.0]
            if (recoverT > 1f) recoverT = 1f;
            intensity = (float)(Math.cos(recoverT * Math.PI) * lurchStrength * 0.5f);
            mc.player.turn(0f, -intensity * 2.0f);
        }

        lurchTicks--;
        if (lurchTicks <= 0) {
            lurchStrength = 0f;
            lurchDuration = 0;
        }
    }

    // ==========================================================================
    // DIMENSIONS / BOUNDING BOX OVERRIDES
    // ==========================================================================

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

    // ==========================================================================
    // RIDING / PASSENGER POSITIONING
    // ==========================================================================

    @Override
    public void positionRider(Entity passenger) {
        if (this.hasPassenger(passenger)) {
            Vec3 rotatedOffset = DRIVER_OFFSET.yRot((float) Math.toRadians(-this.getYRot()));
            Vec3 targetPos = this.position().add(rotatedOffset);
            passenger.setPos(targetPos.x, targetPos.y, targetPos.z);
        }
    }

    // ==========================================================================
    // PLAYER INTERACTION (LOCK/DOOR/ENTER)
    // ==========================================================================

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        boolean sneaking = player.isShiftKeyDown();
        boolean client   = this.level.isClientSide;

        if (sneaking) {
            if (!client) {
                if (isLocked()) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("Car is locked."), true);
                    return InteractionResult.FAIL;
                }
                if (isDoorOpen()) {
                    playDoorSound(false, player);
                    setAnimation("r_door_close");
                    setDoorOpen(false);
                } else {
                    playDoorSound(true, player);
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

    private void playDoorSound(boolean opening, Player source) {
        if (this.level.isClientSide) return;
        float dist   = (source != null) ? (float) source.distanceTo(this) : 0f;
        float volume = Math.max(0.35f, 1.0f - (dist / 24.0f));
        float pitch  = Math.max(0.75f, 1.0f - (dist / 48.0f));

        this.level.playSound(
                null,
                this.blockPosition(),
                opening ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE,
                SoundSource.BLOCKS,
                volume,
                pitch
        );
    }

    // ==========================================================================
    // ATTRIBUTES / SPAWN RULES
    // ==========================================================================

    public static void init() {
        SpawnPlacements.register(
                CarmodfourModEntities.CARDEMO.get(),
                SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (entityType, world, reason, pos, random) ->
                        world.getDifficulty() != Difficulty.PEACEFUL &&
                                Mob.checkMobSpawnRules(entityType, world, reason, pos, random)
        );
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.MAX_HEALTH, 200)
                .add(Attributes.ARMOR, 10)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.ATTACK_DAMAGE, 0)
                .add(Attributes.FOLLOW_RANGE, 16);
    }

    // ==========================================================================
    // PUSH / KNOCKBACK BEHAVIOR
    // ==========================================================================

    @Override
    public void knockback(double strength, double x, double z) { }

    @Override
    public boolean isPushable() { return false; }

    // ==========================================================================
    // ANIMATION HOOKS (GECKOLIB)
    // ==========================================================================

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

    // ==========================================================================
    // RENDERER-FACING ACCESSORS
    // ==========================================================================

    @OnlyIn(Dist.CLIENT)
    public float getVisualRoll() {
        if (this.level != null && this.level.isClientSide) return this.entityData.get(ROLL_SYNC);
        return visualRoll;
    }

    // ==========================================================================
    // TURN SIGNAL RESET HANDLING (on spawn / despawn)
    // ==========================================================================
    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        resetSignalState();
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        resetSignalState();
    }

    /** Resets all client + server turn signal flags when spawned/despawned */
    private void resetSignalState() {
        if (level.isClientSide) {
            // Reset the client-visible indicators
            net.mcreator.carmodfour.client.DriveStateKeybindHandler.resetSignalsClient();
        }
    }

    // ==========================================================================
    // MATH UTILS
    // ==========================================================================

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
