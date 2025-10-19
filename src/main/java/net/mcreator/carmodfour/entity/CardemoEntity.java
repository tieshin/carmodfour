package net.mcreator.carmodfour.entity;

/*
 * =============================================================================
 *  CardemoEntity.java  —  Vehicle Entity with Wall-Aware Roll, Terrain Tilt,
 *                         and Kinetic Collision Damage
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
 *    blocks (e.g., hugging a wall), clamped to ±22.5° and blended with terrain
 *    roll (±15°). The result is smoothed for stable visuals. Roll is **synced**
 *    to clients via entity data so visuals are consistent in SP/MP.
 *
 *  • KINETIC COLLISION DAMAGE:
 *      Hearts = 2 × speed_bps  →  HP = 4 × speed_bps
 *      - 10 b/s  ->  20 hearts (40 HP)
 *      - 14 b/s  ->  28 hearts (56 HP)
 *      - 20 b/s  ->  40 hearts (80 HP)
 *    Damage is applied with an armor-bypassing damage source so it cannot be
 *    mitigated. Knockback scales with impact energy.
 *
 *  • Slope lift (handled via a small vertical boost on motion while grounded)
 *    and visual lift (handled in renderer from pitch) — no server Y-forcing.
 *
 *  • Simple interaction flow: unlock/open door/enter; distance-aware door
 *    sounds; optional owner tracking.
 *
 * =============================================================================
 *  TUNING CHEATSHEET
 * =============================================================================
 *  Roll clamp (overall) ............... TOTAL_ROLL_CLAMP_DEG = 22.5f
 *  Terrain-only roll clamp ............ TERRAIN_ROLL_CLAMP_DEG = 15.0f
 *  Wall proximity roll clamp .......... PROX_ROLL_CLAMP_DEG = 22.5f
 *  Roll smoothing ..................... ROLL_SMOOTH = 0.15f
 *  Side scan (outward distance) ....... SIDE_CHECK_DIST = 1.35
 *  Side scan (front/back half-length) . SIDE_CHECK_HALF_LEN = 0.9
 *  Side scan steps (length) ........... SIDE_CHECK_STEP_LEN = 0.3
 *  Vertical scan range ................ Y: 0.1 -> 1.2 (step 0.3)
 *  Ground probe (downwards) ........... up to 8 blocks
 *
 * =============================================================================
 *  NOTES
 * =============================================================================
 *  • Damage units passed to LivingEntity#hurt are in **health points (HP)**,
 *    where 1 heart = 2 HP. The kinetic formula above already returns HP.
 *
 *  • The renderer should read:
 *      - entity.getXRot() for pitch
 *      - entity.getVisualRoll() for Z-roll (client reads synced float)
 *
 *  • If you want to soften the lean even more, reduce PROX_ROLL_CLAMP_DEG and/or
 *    TOTAL_ROLL_CLAMP_DEG a bit; if you want snappier/floatier lean, tweak
 *    ROLL_SMOOTH down/up respectively.
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

    // Synced visual roll value so clients render exactly what server computes
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

    // Input/motion booleans (wired from controls elsewhere)
    private boolean accelerating = false;
    private boolean braking      = false;
    private boolean turningLeft  = false;
    private boolean turningRight = false;

    // Speed + steering
    private double currentSpeed    = 0.0;   // blocks per tick
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

    // Server-computed visual roll (synced to ROLL_SYNC)
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

    // Smoothing toward target roll (0..1). Lower = heavier, higher = lighter.
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

    // Debug logging toggle
    private static final boolean DBG_COLLISION_EVENTS = false;

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
        this.entityData.define(ROLL_SYNC, 0.0f); // client-facing synced roll
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("DriveState", getDriveState().name());
        tag.putString("VehicleState", getState().name());
        tag.putBoolean("DoorOpen", isDoorOpen());
        // visualRoll and ROLL_SYNC are visual only; no persistence needed
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("DriveState")) setDriveState(DriveState.valueOf(tag.getString("DriveState")));
        if (tag.contains("VehicleState")) setState(VehicleState.valueOf(tag.getString("VehicleState")));
        if (tag.contains("DoorOpen")) setDoorOpen(tag.getBoolean("DoorOpen"));
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
     *                         KINETIC COLLISION DAMAGE
     * ------------------------------------------------------------------------- */

    /**
     * Handles entity collisions ahead of the car — applying damage & knockback
     * from current speed. **Uses blocks/second** for damage and knockback.
     * Damage: hearts = 2 × speed_bps  ⇒  HP = 4 × speed_bps (armor-bypassing).
     */
    private void handleEntityCollisions() {
        // Skip if barely moving (per-tick threshold)
        if (currentSpeed <= 0.01) return;

        // Compute forward and ahead AABB
        double yawRad = Math.toRadians(this.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        AABB hitbox = this.getBoundingBox().move(forward.scale(0.6)).inflate(0.6);

        // Collect targets
        java.util.List<Entity> entities = level.getEntities(this, hitbox,
                e -> e instanceof LivingEntity && e != this && !e.isPassengerOfSameVehicle(this));

        if (entities.isEmpty()) return;

        // Convert speed to blocks/second for correct damage scaling
        double speedBps = currentSpeed * 20.0;

        // Brake immediately on impact to prevent tunneling
        currentSpeed = 0.0;

        for (Entity e : entities) {
            if (!(e instanceof LivingEntity living)) continue;

            // Skip creative/spectator players
            if (living instanceof Player p && (p.isCreative() || p.isSpectator())) continue;

            // Hearts = 2 × bps  → HP = 4 × bps (exactly as specified)
            float damageHp = (float)(speedBps * 4.0);

            // Apply unmitigated damage so armor/resistance can't reduce it
            living.hurt(DamageSource.OUT_OF_WORLD, damageHp);

            // Knockback scaled by real bps; sensible caps
            double kb = Math.min(0.5 + speedBps / 3.0, 10.0);
            double up = Math.min(0.20 + speedBps / 50.0, 1.0);
            Vec3 fling = new Vec3(forward.x, up, forward.z).normalize().scale(kb);
            living.push(fling.x, fling.y, fling.z);
            living.hasImpulse = true;

            dlog(DBG_COLLISION_EVENTS, String.format(
                    "Hit %s | speed=%.2f b/s | dmg=%.1f HP | kb=%.2f",
                    living.getName().getString(), speedBps, damageHp, kb));
        }
    }

    /* -------------------------------------------------------------------------
     *                             DRIVE / REVERSE / PARK
     * ------------------------------------------------------------------------- */

    private void handleDriveMode()  { accelerateAndMove(false); }
    private void handleReverseMode(){ accelerateAndMove(true);  }
    private void handleParkMode()   { currentSpeed = 0.0; currentTurnRate *= 0.5f; }

    private void accelerateAndMove(boolean reverse) {
        // Speed integration (per-tick)
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

        if (currentSpeed < 0) currentSpeed = 0;
        if (currentSpeed > targetMax) currentSpeed = targetMax;

        // Steering integration with inertia toward desired
        float desiredTurn = 0f;
        if (turningLeft && !turningRight)  desiredTurn = -MAX_TURN_RATE;
        if (turningRight && !turningLeft)  desiredTurn =  MAX_TURN_RATE;
        if (reverse) desiredTurn *= -1f;

        float deltaTurn = (desiredTurn - currentTurnRate) * TURN_ACCEL;
        currentTurnRate += deltaTurn;

        // Dynamic recentering when no input (less snap at higher speed)
        if (!turningLeft && !turningRight) {
            float speedRatio = (float)(currentSpeed / MAX_SPEED);
            float centerFactor = 0.85f + 0.15f * (1 - speedRatio);
            currentTurnRate *= centerFactor;
            if (Math.abs(currentTurnRate) < 0.01f) currentTurnRate = 0f;
        }

        // Yaw integration
        this.setYRot(this.getYRot() + currentTurnRate);
        double yawRad = Math.toRadians(this.getYRot());

        // Forward vector * speed (per tick)
        double motionX = reverse ?  Math.sin(yawRad) * currentSpeed : -Math.sin(yawRad) * currentSpeed;
        double motionZ = reverse ? -Math.cos(yawRad) * currentSpeed :  Math.cos(yawRad) * currentSpeed;

        // Slope-friendly tiny boost while grounded
        double verticalBoost = 0.0;
        if (this.onGround && currentSpeed > 0.05)
            verticalBoost = 0.1 * Math.min(1.0, currentSpeed / MAX_SPEED);

        // Commit motion
        Vec3 motion = new Vec3(motionX, getDeltaMovement().y + verticalBoost, motionZ);
        setDeltaMovement(motion);
        hasImpulse = true;

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
     * proximity roll that leans the car AWAY from closer walls.
     *
     * SERVER: computes and stores visualRoll; also pushes ROLL_SYNC every tick.
     * CLIENT: does not compute; it reads ROLL_SYNC in getVisualRoll().
     */
    private void applyTerrainAndProximityTilt(double yawRad) {
        final double sampleDistance = 0.8;

        // Basis vectors
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 right   = new Vec3(Math.cos(yawRad),  0, Math.sin(yawRad));

        // Sample positions around the car
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
        if (pitchTarget > 60f) pitchTarget = 60f;
        if (pitchTarget < -60f) pitchTarget = -60f;
        float newPitch = lerp(getXRot(), pitchTarget, 0.25f);
        setXRot(newPitch);

        // ---- Terrain roll (gentle)
        double dyRoll = leftY - rightY;
        float rawTerrainRoll = (float) Math.toDegrees(Math.atan2(dyRoll, sampleDistance * 2.0));
        if (rawTerrainRoll >  TERRAIN_ROLL_CLAMP_DEG) rawTerrainRoll =  TERRAIN_ROLL_CLAMP_DEG;
        if (rawTerrainRoll < -TERRAIN_ROLL_CLAMP_DEG) rawTerrainRoll = -TERRAIN_ROLL_CLAMP_DEG;
        float terrainRoll = rawTerrainRoll;

        // ---- Proximity roll (lean away from closer side)
        float leftClose  = computeSideCloseness(true,  yawRad);
        float rightClose = computeSideCloseness(false, yawRad);
        float diff = (leftClose - rightClose); // >0 => left closer => lean right (away from left)
        if (Math.abs(diff) < PROX_DEADZONE) diff = 0f;
        float proximityRoll = diff * PROX_ROLL_CLAMP_DEG;

        // ---- Combine and smooth
        float targetRoll = terrainRoll + proximityRoll;
        if (targetRoll >  TOTAL_ROLL_CLAMP_DEG) targetRoll =  TOTAL_ROLL_CLAMP_DEG;
        if (targetRoll < -TOTAL_ROLL_CLAMP_DEG) targetRoll = -TOTAL_ROLL_CLAMP_DEG;

        visualRoll = lerp(visualRoll, targetRoll, ROLL_SMOOTH);

        // Sync for clients
        if (!level.isClientSide) {
            this.entityData.set(ROLL_SYNC, visualRoll);
        }
    }

    /**
     * Side closeness scanner: returns 0..1 where 1 = wall pressed right next
     * to that side, 0 = no obstacle within SIDE_CHECK_DIST.
     */
    private float computeSideCloseness(boolean leftSide, double yawRad) {
        if (level == null) return 0f;

        // Basis
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 right   = new Vec3(Math.cos(yawRad),  0, Math.sin(yawRad));
        Vec3 sideDir = leftSide ? right.scale(-1) : right;

        float strongest = 0f;

        // Sample along the car’s length
        for (double t = -SIDE_CHECK_HALF_LEN; t <= SIDE_CHECK_HALF_LEN + 1e-6; t += SIDE_CHECK_STEP_LEN) {
            Vec3 along = forward.scale(t);

            // Cast outward rays perpendicular to heading
            for (double s = 0.0; s <= SIDE_CHECK_DIST + 1e-6; s += 0.15) {
                Vec3 base = position().add(along).add(sideDir.scale(s));

                // Vertical sampling (to catch fences, slabs, etc.)
                for (double yoff = SIDE_CHECK_Y_START; yoff <= SIDE_CHECK_Y_END + 1e-6; yoff += SIDE_CHECK_Y_STEP) {
                    BlockPos bp = new BlockPos(
                            (int) Math.floor(base.x),
                            (int) Math.floor(this.getY() + yoff - 0.2), // slight downward bias
                            (int) Math.floor(base.z)
                    );
                    var state = level.getBlockState(bp);

                    // Count as obstacle if solid or has any collision shape
                    if (!state.isAir() && (state.getMaterial().isSolid() || !state.getCollisionShape(level, bp).isEmpty())) {
                        float frac = (float) (1.0 - (s / SIDE_CHECK_DIST));
                        if (frac > strongest) strongest = frac;
                        // stop probing outward for this slice — obstacle found
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

        // fallback to heightmap
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
        // No knockback for the vehicle itself
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
     * Client returns the synced ROLL_SYNC for SP/MP consistency.
     */
    @OnlyIn(Dist.CLIENT)
    public float getVisualRoll() {
        if (this.level != null && this.level.isClientSide) {
            return this.entityData.get(ROLL_SYNC);
        }
        return visualRoll;
    }

    /* -------------------------------------------------------------------------
     *                                  MATH UTILS
     * ------------------------------------------------------------------------- */

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
