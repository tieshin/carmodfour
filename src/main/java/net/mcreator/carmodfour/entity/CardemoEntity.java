package net.mcreator.carmodfour.entity;

/*
 * =============================================================================
 *  CardemoEntity.java  —  Vehicle Entity with Wall-Aware Roll & Terrain Tilt
 * =============================================================================
 *
 *  This entity implements:
 *
 *  • Manual driving states (PARK/DRIVE/REVERSE) with acceleration, braking,
 *    steering inertia and dynamic center biasing.
 *
 *  • Terrain pitch and roll using ground sampling (front/back and left/right)
 *    via downward scanning (ceiling-safe) to avoid tunnel/bridge magnetism.
 *
 *  • Proximity-based roll that makes the car lean AWAY from nearby left/right
 *    blocks (e.g., hugging a wall), clamped to ±45° and blended with terrain
 *    roll (±22.5°). The result is smoothed for stable visuals.
 *
 *  • Slope lift handled visually by renderer (based on pitch). The renderer
 *    should read getXRot() for pitch and getVisualRoll() for Z-roll.
 *
 *  • Proportional collision damage + knockback against living entities with
 *    instant braking on impact to prevent tunneling through mobs.
 *
 *  • Simple interaction flow: unlock/open door/enter; distance-aware door
 *    sounds; optional owner tracking.
 *
 *  • NEW: Visual roll is now **network-synced** using an EntityDataAccessor
 *    (ROLL_SYNC). Server computes/updates; clients render from the synced
 *    float so SP/MP are consistent and renderers don’t diverge.
 *
 *  COMPATIBILITY:
 *  - Built for 1.19.2-style signatures (e.g., Heightmap call form).
 *  - GeckoLib 3 (IAnimatable, AnimationController, etc).
 *
 * =============================================================================
 *  TUNING CHEATSHEET
 * =============================================================================
 *  Roll clamp (overall) ............... TOTAL_ROLL_CLAMP_DEG = 22.5f
 *  Terrain-only roll clamp ............ TERRAIN_ROLL_CLAMP_DEG = 22.5f
 *  Wall proximity roll clamp .......... PROX_ROLL_CLAMP_DEG = 22.5f
 *  Roll smoothing ..................... ROLL_SMOOTH = 0.35f
 *  Side scan (outward distance) ....... SIDE_CHECK_DIST = 1.35
 *  Side scan (front/back half-length) . SIDE_CHECK_HALF_LEN = 0.9
 *  Side scan steps (length) ........... SIDE_CHECK_STEP_LEN = 0.3
 *  Vertical scan range ................ Y: 0.1 -> 1.2 (step 0.3)
 *  Ground probe (downwards) ........... up to 8 blocks
 *
 * =============================================================================
 *  QUICK DEV NOTES
 * =============================================================================
 *  • If the car appears to “clip” when you approach a wall, increase
 *    SIDE_CHECK_DIST slightly (e.g., 1.55) and/or multiply the visual roll in
 *    the renderer by ~1.25–1.5 to make the lean more obvious (renderer-side).
 *
 *  • The lean direction is defined so **left wall => lean right (away)**:
 *      diff = leftCloseness - rightCloseness
 *      proximityRoll = diff * PROX_ROLL_CLAMP_DEG
 *
 *  • getVisualRoll() on the client now **returns the synced value** so you’ll
 *    always see the same angle client-side as computed server-side.
 *
 * =============================================================================
 */

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

/**
 * Primary vehicle entity.
 */
public class CardemoEntity extends Mob implements IAnimatable {

    /* -------------------------------------------------------------------------
     *                        SYNCHRONIZED / RUNTIME STATE
     * ------------------------------------------------------------------------- */

    // Synchronized data accessors
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

    // NEW: synced visual roll value so clients render exactly what server computes
    private static final EntityDataAccessor<Float> ROLL_SYNC =
            SynchedEntityData.defineId(CardemoEntity.class, EntityDataSerializers.FLOAT);

    // High-level states
    public enum VehicleState { LOCKED, UNLOCKED, ENGINE_OFF, ENGINE_ON }
    public enum DriveState   { PARK, DRIVE, REVERSE }

    // Anim/Factory
    private final AnimationFactory factory = GeckoLibUtil.createFactory(this);
    private String animationProcedure = "empty";

    // Ownership (optional)
    private Player owner = null;

    // Seat offset (driver)
    private static final Vec3 DRIVER_OFFSET = new Vec3(0.25, 0.45, 0.3);

    // Input/motion booleans (wired elsewhere in your control code)
    private boolean accelerating = false;
    private boolean braking      = false;
    private boolean turningLeft  = false;
    private boolean turningRight = false;

    // Speed + steering
    private double currentSpeed    = 0.0;
    private float  currentTurnRate = 0.0f;

    // Core motion tuning
    private static final double MAX_SPEED     = 0.35;
    private static final double ACCEL_FACTOR  = 0.08;
    private static final double BRAKE_FACTOR  = 0.25;
    private static final double DRAG          = 0.01;
    private static final double IDLE_SPEED    = 0.02;
    private static final float  MAX_TURN_RATE = 4.0f;
    private static final float  TURN_ACCEL    = 0.25f;

    // Entity physical size (broad-phase bounding shape)
    private static final float HITBOX_WIDTH  = 2.5f;
    private static final float HITBOX_HEIGHT = 1.0f;
    private static final float HITBOX_LENGTH = 2.5f;

    // Client-side HUD speed smoothing
    @OnlyIn(Dist.CLIENT) private Vec3   clientPrevPos   = null;
    @OnlyIn(Dist.CLIENT) private double clientSpeedBps  = 0.0;

    // Server-computed visual roll (what we then sync to clients)
    private float visualRoll = 0.0f;

    /* -------------------------------------------------------------------------
     *                               ROLL TUNING
     * ------------------------------------------------------------------------- */

    // Terrain roll clamp (gentle)
    private static final float TERRAIN_ROLL_CLAMP_DEG = 15.0f;

    // Proximity roll clamp (lean away from walls)
    private static final float PROX_ROLL_CLAMP_DEG = 22.5f;

    // Final safety clamp
    private static final float TOTAL_ROLL_CLAMP_DEG = 22.5f;

    // Smoothing toward target roll
    private static final float ROLL_SMOOTH = 0.15f;

    // Deadzone to prevent micro-flicker when sides equally close
    private static final float PROX_DEADZONE = 0.03f;

    // Side scanning parameters
    private static final double SIDE_CHECK_DIST      = 1.35;
    private static final double SIDE_CHECK_HALF_LEN  = 0.90;
    private static final double SIDE_CHECK_STEP_LEN  = 0.30;
    private static final double SIDE_CHECK_Y_START   = 0.10;
    private static final double SIDE_CHECK_Y_END     = 1.20;
    private static final double SIDE_CHECK_Y_STEP    = 0.30;

    /* -------------------------------------------------------------------------
     *                             DEV / DEBUG TOGGLES
     * ------------------------------------------------------------------------- */

    private static final boolean DBG_ENABLE            = false;  // master switch
    private static final boolean DBG_SIDE_PROBES       = false;  // print side closeness
    private static final boolean DBG_ROLL_VALUES       = false;  // print terrain/prox/total roll
    private static final boolean DBG_PITCH_VALUES      = false;  // print pitch values
    private static final boolean DBG_COLLISION_EVENTS  = false;  // log entity collisions
    private static final boolean DBG_MOVE              = false;  // log motion X/Z

    // Safe debug print
    private static void dlog(boolean on, String msg) { if (on) System.out.println("[Cardemo] " + msg); }

    /* -------------------------------------------------------------------------
     *                                 CTOR / INIT
     * ------------------------------------------------------------------------- */

    public CardemoEntity(PlayMessages.SpawnEntity packet, Level world) {
        this(CarmodfourModEntities.CARDEMO.get(), world);
    }

    public CardemoEntity(EntityType<CardemoEntity> type, Level world) {
        super(type, world);
        setNoAi(false);
        setNoGravity(false);
        this.maxUpStep = 1.1f; // smoother steps over slabs, stairs, etc.
    }

    /* -------------------------------------------------------------------------
     *                             ACCESSORS / HELPERS
     * ------------------------------------------------------------------------- */

    public void setOwner(Player player) {
        if (owner == null) owner = player;
    }

    public Player getOwner() { return owner; }

    public boolean isOwner(Player player) {
        return owner != null && owner.getUUID().equals(player.getUUID());
    }

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

    public String getTexture()        { return this.entityData.get(TEXTURE); }
    public String getSyncedAnimation(){ return this.entityData.get(ANIMATION); }
    public void   setAnimation(String name) { this.entityData.set(ANIMATION, name); }
    public void   setAnimationProcedure(String animation) { setAnimation(animation); }

    // Input flags (wire these from your keybinds)
    public void setAccelerating(boolean b) { this.accelerating = b; }
    public void setBraking(boolean b)      { this.braking = b; }
    public void setTurningLeft(boolean b)  { this.turningLeft = b; }
    public void setTurningRight(boolean b) { this.turningRight = b; }

    /* -------------------------------------------------------------------------
     *                            SYNCED DATA / SAVE
     * ------------------------------------------------------------------------- */

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(SHOOT, false);
        this.entityData.define(ANIMATION, "undefined");
        this.entityData.define(TEXTURE, "cardemo");
        this.entityData.define(VEHICLE_STATE, VehicleState.LOCKED.name());
        this.entityData.define(DOOR_OPEN, false);
        this.entityData.define(DRIVE_MODE, DriveState.PARK.name());

        // NEW: networked roll used by clients to render consistent Z-tilt
        this.entityData.define(ROLL_SYNC, 0.0f);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("DriveState", getDriveState().name());
        tag.putString("VehicleState", getState().name());
        tag.putBoolean("DoorOpen", isDoorOpen());
        // visualRoll/ROLL_SYNC are visual-only; no persistence needed
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("DriveState"))
            setDriveState(DriveState.valueOf(tag.getString("DriveState")));
        if (tag.contains("VehicleState"))
            setState(VehicleState.valueOf(tag.getString("VehicleState")));
        if (tag.contains("DoorOpen"))
            setDoorOpen(tag.getBoolean("DoorOpen"));
    }

    /* -------------------------------------------------------------------------
     *                                    TICK
     * ------------------------------------------------------------------------- */

    @Override
    public void tick() {
        super.tick();

        if (!level.isClientSide) {
            if (isEngineOn()) {
                switch (getDriveState()) {
                    case DRIVE   -> handleDriveMode();
                    case REVERSE -> handleReverseMode();
                    case PARK    -> handleParkMode();
                }
                handleEntityCollisions();
            }
        } else {
            updateClientSpeedOverlay();
        }
    }

    /* -------------------------------------------------------------------------
     *                             CLIENT HUD SPEED
     * ------------------------------------------------------------------------- */

    @OnlyIn(Dist.CLIENT)
    private void updateClientSpeedOverlay() {
        Vec3 now = this.position();
        if (clientPrevPos == null) {
            clientPrevPos = now;
        } else {
            Vec3 delta = now.subtract(clientPrevPos);
            double instSpeed = new Vec3(delta.x, 0, delta.z).length() * 20.0; // blocks/sec
            clientSpeedBps = clientSpeedBps * 0.8 + instSpeed * 0.2;
            clientPrevPos = now;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.isPassengerOfSameVehicle(this) && isEngineOn()) {
            String p = getDriveState() == DriveState.PARK    ? "( P )" : "P";
            String d = getDriveState() == DriveState.DRIVE   ? "( D )" : "D";
            String r = getDriveState() == DriveState.REVERSE ? "( R )" : "R";
            String text = String.format("| %s | %s | %s | || %.1f b/s", p, d, r, clientSpeedBps);
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(text), false);
        }
    }

    /* -------------------------------------------------------------------------
     *                              ENTITY COLLISIONS
     * ------------------------------------------------------------------------- */

    private void handleEntityCollisions() {
        if (currentSpeed <= 0.05) return;

        double yawRad = Math.toRadians(this.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));

        AABB hitbox = this.getBoundingBox().move(forward.scale(0.6)).inflate(0.5);

        java.util.List<Entity> entities = level.getEntities(this, hitbox,
                e -> e instanceof LivingEntity && e != this && !e.isPassengerOfSameVehicle(this));

        if (entities.isEmpty()) return;

        double speedNow = currentSpeed;
        currentSpeed = 0.0; // emergency brake

        for (Entity e : entities) {
            if (e instanceof LivingEntity living) {
                float damage = (float) Math.min(20.0, (speedNow / MAX_SPEED) * 20.0);
                living.hurt(DamageSource.mobAttack(this), damage);
                Vec3 fling = new Vec3(forward.x, 0.3, forward.z).scale(speedNow * 4.0);
                living.push(fling.x, fling.y, fling.z);
                living.hasImpulse = true;
                dlog(DBG_COLLISION_EVENTS, "Hit entity: " + e.getName().getString() + " dmg=" + damage);
            }
        }
    }

    /* -------------------------------------------------------------------------
     *                             DRIVE / REVERSE / PARK
     * ------------------------------------------------------------------------- */

    private void handleDriveMode()  { accelerateAndMove(false); }
    private void handleReverseMode(){ accelerateAndMove(true);  }
    private void handleParkMode()   { currentSpeed = 0.0; currentTurnRate *= 0.5f; }

    private void accelerateAndMove(boolean reverse) {
        // Speed integration
        double targetMax = reverse ? MAX_SPEED * 0.5 : MAX_SPEED;
        double accel     = reverse ? ACCEL_FACTOR * 0.75 : ACCEL_FACTOR;

        if (accelerating) {
            currentSpeed += accel * (targetMax - currentSpeed);
        } else if (braking) {
            currentSpeed -= BRAKE_FACTOR * currentSpeed;
        } else {
            currentSpeed -= DRAG;
            if (currentSpeed < IDLE_SPEED) currentSpeed = IDLE_SPEED;
        }

        currentSpeed = clamp(currentSpeed, 0.0, targetMax);

        // Steering integration with inertia toward desired
        float desiredTurn = 0f;
        if (turningLeft && !turningRight)  desiredTurn = -MAX_TURN_RATE;
        if (turningRight && !turningLeft)  desiredTurn =  MAX_TURN_RATE;
        if (reverse) desiredTurn *= -1f;

        float deltaTurn = (desiredTurn - currentTurnRate) * TURN_ACCEL;
        currentTurnRate += deltaTurn;

        // Dynamic recentring when no input (less snap at higher speed)
        if (!turningLeft && !turningRight) {
            float speedRatio = (float)(currentSpeed / MAX_SPEED);
            float centerFactor = 0.85f + 0.15f * (1 - speedRatio);
            currentTurnRate *= centerFactor;
            if (Math.abs(currentTurnRate) < 0.01f) currentTurnRate = 0f;
        }

        // Yaw integration
        this.setYRot(this.getYRot() + currentTurnRate);
        double yawRad = Math.toRadians(this.getYRot());

        // Forward vector * speed
        double motionX = reverse ?  Math.sin(yawRad) * currentSpeed
                : -Math.sin(yawRad) * currentSpeed;
        double motionZ = reverse ? -Math.cos(yawRad) * currentSpeed
                :  Math.cos(yawRad) * currentSpeed;

        // Slope-friendly tiny boost while grounded
        double verticalBoost = 0.0;
        if (this.onGround && currentSpeed > 0.05)
            verticalBoost = 0.1 * Math.min(1.0, currentSpeed / MAX_SPEED);

        // Commit motion
        Vec3 motion = new Vec3(motionX, getDeltaMovement().y + verticalBoost, motionZ);
        setDeltaMovement(motion);
        hasImpulse = true;

        dlog(DBG_MOVE, String.format("Move XZ: %.3f, %.3f | speed=%.3f", motionX, motionZ, currentSpeed));

        move(MoverType.SELF, motion);

        // Tilt after move based on new position
        applyTerrainAndProximityTilt(yawRad);
    }

    /* -------------------------------------------------------------------------
     *                               TILT CALCULATION
     * ------------------------------------------------------------------------- */

    /**
     * Computes pitch and roll using both terrain height deltas and proximity to
     * lateral obstacles. Roll is the sum of a gentle terrain roll and a
     * stronger proximity roll that leans the car AWAY from closer walls.
     *
     * SERVER: computes and stores visualRoll; also pushes ROLL_SYNC every tick.
     * CLIENT: does not compute; it reads ROLL_SYNC in getVisualRoll().
     */
    private void applyTerrainAndProximityTilt(double yawRad) {
        final double sampleDistance = 0.8;

        // Basis vectors
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 right   = new Vec3(Math.cos(yawRad),  0, Math.sin(yawRad));

        // Sample points
        Vec3 frontPos = position().add(forward.scale(sampleDistance));
        Vec3 backPos  = position().add(forward.scale(-sampleDistance));
        Vec3 rightPos = position().add(right.scale(sampleDistance));
        Vec3 leftPos  = position().add(right.scale(-sampleDistance));

        // Ceiling-safe ground heights
        double frontY = getGroundY(frontPos);
        double backY  = getGroundY(backPos);
        double rightY = getGroundY(rightPos);
        double leftY  = getGroundY(leftPos);

        // ---- Pitch (XRot) from front-back slope
        float pitchTarget = (float) Math.toDegrees(Math.atan2(frontY - backY, sampleDistance * 2.0)) * -1F;
        pitchTarget = clamp(pitchTarget, -60f, 60f);

        float pitchSmooth = 0.25f;
        float newPitch = lerp(getXRot(), pitchTarget, pitchSmooth);
        setXRot(newPitch);
        dlog(DBG_PITCH_VALUES, String.format("Pitch: target=%.2f, now=%.2f", pitchTarget, newPitch));

        // ---- Terrain roll (gentle)
        double dyRoll = leftY - rightY;
        float rawTerrainRoll = (float) Math.toDegrees(Math.atan2(dyRoll, sampleDistance * 2.0));
        float terrainRoll = clamp(rawTerrainRoll, -TERRAIN_ROLL_CLAMP_DEG, TERRAIN_ROLL_CLAMP_DEG);

        // ---- Proximity roll (lean away from closer side)
        float leftClose  = computeSideCloseness(true,  yawRad);
        float rightClose = computeSideCloseness(false, yawRad);

        // >0 if left closer => lean right (away from left wall)
        float diff = (leftClose - rightClose);
        if (Math.abs(diff) < PROX_DEADZONE) diff = 0f;

        float proximityRoll = diff * PROX_ROLL_CLAMP_DEG;

        // ---- Combine and smooth
        float targetRoll = clamp(terrainRoll + proximityRoll, -TOTAL_ROLL_CLAMP_DEG, TOTAL_ROLL_CLAMP_DEG);
        float newRoll    = lerp(visualRoll, targetRoll, ROLL_SMOOTH);
        visualRoll = newRoll;

        // NEW: push to clients so renderer sees consistent roll everywhere
        if (!level.isClientSide) {
            this.entityData.set(ROLL_SYNC, visualRoll);
        }

        dlog(DBG_SIDE_PROBES,  String.format("Closeness L/R: %.2f / %.2f (diff=%.2f)", leftClose, rightClose, diff));
        dlog(DBG_ROLL_VALUES,  String.format("Roll: terr=%.2f, prox=%.2f, target=%.2f, now=%.2f", terrainRoll, proximityRoll, targetRoll, newRoll));
    }

    /**
     * Returns a closeness factor 0..1 for a given side. 1 means a solid wall
     * detected right next to that side; 0 means nothing found within range.
     * We scan along the vehicle's length and several vertical samples to detect
     * fences/walls/slabs/blocks with collision shapes.
     */
    private float computeSideCloseness(boolean leftSide, double yawRad) {
        if (level == null) return 0f;

        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 right   = new Vec3(Math.cos(yawRad),  0, Math.sin(yawRad));
        Vec3 sideDir = leftSide ? right.scale(-1) : right;

        float strongest = 0f;

        for (double t = -SIDE_CHECK_HALF_LEN; t <= SIDE_CHECK_HALF_LEN + 1e-6; t += SIDE_CHECK_STEP_LEN) {
            Vec3 along = forward.scale(t);

            // Cast outward rays perpendicular to heading
            for (double s = 0.0; s <= SIDE_CHECK_DIST + 1e-6; s += 0.15) {
                Vec3 base = position().add(along).add(sideDir.scale(s));

                // vertical sampling
                for (double yoff = SIDE_CHECK_Y_START; yoff <= SIDE_CHECK_Y_END + 1e-6; yoff += SIDE_CHECK_Y_STEP) {
                    int bx = (int) Math.floor(base.x);
                    int by = (int) Math.floor(this.getY() + yoff - 0.2); // bias slightly down
                    int bz = (int) Math.floor(base.z);

                    BlockPos bp = new BlockPos(bx, by, bz);
                    var state   = level.getBlockState(bp);

                    // consider solids or anything with a collision shape
                    if (!state.isAir() && (state.getMaterial().isSolid() || !state.getCollisionShape(level, bp).isEmpty())) {
                        float frac = (float) (1.0 - (s / SIDE_CHECK_DIST));
                        if (frac > strongest) strongest = frac;
                        // stop probing outward for this slice — we found an obstacle
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
     * Finds the actual ground height below the position by scanning down a few
     * blocks to avoid sampling ceilings when under bridges/tunnels.
     */
    private double getGroundY(Vec3 pos) {
        int startY = (int) Math.floor(pos.y);
        int minY   = level.getMinBuildHeight();

        for (int y = startY; y >= startY - 8 && y >= minY; y--) {
            BlockPos check = new BlockPos((int) Math.floor(pos.x), y, (int) Math.floor(pos.z));
            if (!level.getBlockState(check).isAir()) {
                // first solid block; ground is its top face
                return y + 1.0;
            }
        }

        // fallback
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(pos.x), (int) Math.floor(pos.z));
    }

    /* -------------------------------------------------------------------------
     *                       DIMENSIONS / BOUNDING BOX OVERRIDES
     * ------------------------------------------------------------------------- */

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

    /* -------------------------------------------------------------------------
     *                                 RIDING
     * ------------------------------------------------------------------------- */

    @Override
    public void positionRider(Entity passenger) {
        if (this.hasPassenger(passenger)) {
            Vec3 rotatedOffset = DRIVER_OFFSET.yRot((float) Math.toRadians(-this.getYRot()));
            Vec3 targetPos = this.position().add(rotatedOffset);
            passenger.setPos(targetPos.x, targetPos.y, targetPos.z);
        }
    }

    /* -------------------------------------------------------------------------
     *                               INTERACTION
     * ------------------------------------------------------------------------- */

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        boolean sneaking = player.isShiftKeyDown();
        boolean client   = this.level.isClientSide;

        // Sneak: toggle door if unlocked
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

        // Normal interact: enter if unlocked + door open
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

    /* -------------------------------------------------------------------------
     *                             ATTRIBUTES / SPAWN
     * ------------------------------------------------------------------------- */

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

    /* -------------------------------------------------------------------------
     *                         PUSH / KNOCKBACK BEHAVIOR
     * ------------------------------------------------------------------------- */

    @Override
    public void knockback(double strength, double x, double z) {
        // No knockback for the vehicle
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /* -------------------------------------------------------------------------
     *                             ANIMATION HOOKS
     * ------------------------------------------------------------------------- */

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

    /* -------------------------------------------------------------------------
     *                          RENDERER-FACING ACCESSORS
     * ------------------------------------------------------------------------- */

    /**
     * The renderer reads this via entity.getVisualRoll().
     *
     * - On the **server**, this just returns the local computed visualRoll.
     * - On the **client**, this returns the **synced** value (ROLL_SYNC) so
     *   singleplayer/multiplayer are identical visually.
     */
    @OnlyIn(Dist.CLIENT)
    public float getVisualRoll() {
        // If we’re on a client world, prefer the synced value
        if (this.level != null && this.level.isClientSide) {
            return this.entityData.get(ROLL_SYNC);
        }
        // On server (or null), return the authoritative value
        return visualRoll;
    }

    /* -------------------------------------------------------------------------
     *                                  MATH UTILS
     * ------------------------------------------------------------------------- */

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /* -------------------------------------------------------------------------
     *                               END OF FILE
     * ------------------------------------------------------------------------- */
}
