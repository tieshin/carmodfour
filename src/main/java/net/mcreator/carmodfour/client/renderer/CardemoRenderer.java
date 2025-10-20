package net.mcreator.carmodfour.client.renderer;

import software.bernie.geckolib3.renderers.geo.GeoEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import net.mcreator.carmodfour.entity.CardemoEntity;
import net.mcreator.carmodfour.entity.model.CardemoModel;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;

import java.util.ArrayDeque;

/**
 * CardemoRenderer
 *
 * Unified smooth visual dynamics:
 *  - Terrain tilt and wall roll
 *  - Anti-intersection tilt (left/right/front/back)
 *  - Bump recoil shake and spring-damped suspension rebound
 *  - Reduced bobbing on flat terrain with camera-aware smoothing
 *  - Camera lurch integration for velocity-based impacts
 */
public class CardemoRenderer extends GeoEntityRenderer<CardemoEntity> {

    // ===============================================================
    // STATE
    // ===============================================================
    private float smoothedPitch = 0f;
    private float smoothedRoll  = 0f;

    private float smoothTiltX = 0f;
    private float smoothTiltZ = 0f;
    private float smoothLift  = 0f;

    private float tiltVelX = 0f;
    private float tiltVelZ = 0f;
    private float liftVel  = 0f;

    // history buffers for smoothing
    private static final int INTERP_WINDOW = 8;
    private final ArrayDeque<Float> tiltXHist = new ArrayDeque<>();
    private final ArrayDeque<Float> tiltZHist = new ArrayDeque<>();
    private final ArrayDeque<Float> liftHist  = new ArrayDeque<>();

    // ===============================================================
    // PARAMETERS
    // ===============================================================
    private static final double VISUAL_CLEARANCE = 0.35;
    private static final float  BASE_TILT_STRENGTH   = 3.0f;
    private static final float  BASE_OFFSET_STRENGTH = 0.2f;

    private static final float  INTERP_SMOOTH     = 0.25f;
    private static final float  SPRING_STIFFNESS  = 0.18f;
    private static final float  SPRING_DAMPING    = 0.22f;
    private static final float  MAX_TILT_DEG      = 10.0f;
    private static final float  RECOIL_SHAKE_INTENSITY = 6.5f; // degrees
    private static final float  TERRAIN_SHAKE_GAIN = 0.8f;     // multiplier on terrain pitch/roll

    public CardemoRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CardemoModel());
        this.shadowRadius = 1.0f;
    }

    @Override
    protected void applyRotations(CardemoEntity entity, PoseStack stack,
                                  float ageInTicks, float rotationYaw, float partialTicks) {

        // ===============================================================
        // (1) BASE TERRAIN RESPONSE — pitch/roll smoothing
        // ===============================================================
        float targetPitch = entity.getXRot();
        smoothedPitch += (targetPitch - smoothedPitch) * 0.1f;

        float targetRoll = entity.getVisualRoll();
        smoothedRoll += (targetRoll - smoothedRoll) * 0.2f;

        super.applyRotations(entity, stack, ageInTicks, rotationYaw, partialTicks);

        // --- Apply terrain roll & pitch ---
        stack.mulPose(Vector3f.ZP.rotationDegrees(smoothedRoll * 1.5f));
        stack.mulPose(Vector3f.XP.rotationDegrees(-smoothedPitch * 1.8f));

        // --- Inject camera lurch (from impact) ---
        if (CardemoEntity.cameraLurchPitchOffset != 0f) {
            stack.mulPose(Vector3f.XP.rotationDegrees(CardemoEntity.cameraLurchPitchOffset));
        }

        if (entity.level == null) return;

        // ===============================================================
        // (2) COLLISION / WALL AVOIDANCE PROBING — enhanced rear accuracy
        // ===============================================================
        double speed = Math.min(entity.getDeltaMovement().length() * 20.0, 1.0);
        float speedScale = (float) (0.5 + speed * 1.5f);

        float tiltStrength   = BASE_TILT_STRENGTH   * speedScale;
        float offsetStrength = BASE_OFFSET_STRENGTH * (0.8f + 0.4f * speedScale);

        Vec3 pos = entity.position();
        BlockPos basePos = new BlockPos(pos.x, pos.y, pos.z);

        float tiltX = 0f;
        float tiltZ = 0f;
        float lift  = 0f;

        // --- Standard 6-face proximity check ---
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                    BlockPos check = basePos.offset(dx, dy, dz);
                    BlockState state = entity.level.getBlockState(check);
                    if (!state.isAir() && state.getMaterial().isSolid()) {
                        double dist = entity.position().distanceTo(Vec3.atCenterOf(check));
                        if (dist < VISUAL_CLEARANCE) {
                            double depth = VISUAL_CLEARANCE - dist;
                            if (dx > 0)  tiltZ -= depth * tiltStrength;
                            if (dx < 0)  tiltZ += depth * tiltStrength;
                            if (dz > 0)  tiltX += depth * tiltStrength;
                            if (dz < 0)  tiltX -= depth * tiltStrength;
                            if (dy < 0)  lift  += depth * offsetStrength;
                        }
                    }
                }
            }
        }

        // ===============================================================
        // (2B) Enhanced rear bumper detection
        // ===============================================================
        double yawRad = Math.toRadians(entity.getYRot());
        double rearYawLeft  = yawRad + Math.toRadians(155.0);
        double rearYawRight = yawRad - Math.toRadians(155.0);

        Vec3 rearDirCenter = new Vec3(Math.sin(yawRad + Math.PI), 0, -Math.cos(yawRad + Math.PI)).normalize();
        Vec3 rearDirLeft   = new Vec3(Math.sin(rearYawLeft), 0, -Math.cos(rearYawLeft)).normalize();
        Vec3 rearDirRight  = new Vec3(Math.sin(rearYawRight), 0, -Math.cos(rearYawRight)).normalize();

        Vec3[] rearDirs = new Vec3[] { rearDirCenter, rearDirLeft, rearDirRight };

        double rearProbeLength = 2.0;
        float  rearResponseGain = 1.0f;

        float totalDepth = 0f;
        int   totalHits  = 0;

        for (Vec3 dir : rearDirs) {
            Vec3 probeStart = pos.add(0, 0.7, 0);
            Vec3 probeEnd   = probeStart.add(dir.scale(rearProbeLength));

            var ctx = new net.minecraft.world.level.ClipContext(
                    probeStart, probeEnd,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    entity
            );
            var hit = entity.level.clip(ctx);
            if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                BlockPos hitPos = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
                BlockState state = entity.level.getBlockState(hitPos);
                if (!state.isAir() && state.getMaterial().isSolid()) {
                    double dist = probeStart.distanceTo(hit.getLocation());
                    double depth = rearProbeLength - dist;
                    if (depth > 0.0) {
                        totalDepth += depth;
                        totalHits++;
                    }
                }
            }
        }

        AABB rearBox = entity.getBoundingBox()
                .move(-rearDirCenter.x * 0.8, 0, -rearDirCenter.z * 0.8)
                .inflate(0.1, 0.2, 0.1);

        BlockPos min = new BlockPos(
                Math.floor(rearBox.minX),
                Math.floor(rearBox.minY),
                Math.floor(rearBox.minZ)
        );
        BlockPos max = new BlockPos(
                Math.floor(rearBox.maxX),
                Math.floor(rearBox.maxY),
                Math.floor(rearBox.maxZ)
        );

        for (BlockPos bp : BlockPos.betweenClosed(min, max)) {
            BlockState bs = entity.level.getBlockState(bp);
            if (!bs.isAir() && bs.getMaterial().isSolid()) {
                double blockCenterDist = pos.distanceTo(Vec3.atCenterOf(bp));
                double depth = Math.max(0, 1.5 - blockCenterDist);
                totalDepth += depth * 0.75;
                totalHits++;
            }
        }

        if (totalHits > 0) {
            float avgDepth = (totalDepth / totalHits) * rearResponseGain;
            tiltZ += avgDepth * tiltStrength * 1.6f;
            lift  += avgDepth * offsetStrength * 0.8f;
        }

        // ===============================================================
        // (3) RECOIL / TERRAIN REACTIONS + SPRING SMOOTHING
        // ===============================================================
        float recoilShake = 0f;
        if (entity.tickCount % 2 == 0 && entity.isAlive()) {
            if (entity.getDeltaMovement().lengthSqr() < 0.001 && entity.hurtTime > 0) {
                recoilShake = (float)(Math.sin(ageInTicks * 1.5f) * RECOIL_SHAKE_INTENSITY);
            }
        }

        float terrainDelta = Math.abs(smoothedPitch) + Math.abs(smoothedRoll);
        float terrainShake = terrainDelta * TERRAIN_SHAKE_GAIN;

        tiltZ += recoilShake + terrainShake * 0.25f;
        tiltX += (float)Math.sin(ageInTicks * 0.7f) * terrainShake * 0.1f;

        addToHistory(tiltXHist, tiltX);
        addToHistory(tiltZHist, tiltZ);
        addToHistory(liftHist,  lift);

        float avgX = average(tiltXHist);
        float avgZ = average(tiltZHist);
        float avgLift = average(liftHist);

        float targetX = smoothTiltX + (avgX - smoothTiltX) * INTERP_SMOOTH;
        float targetZ = smoothTiltZ + (avgZ - smoothTiltZ) * INTERP_SMOOTH;
        float targetLift = smoothLift + (avgLift - smoothLift) * INTERP_SMOOTH;

        tiltVelX += (targetX - smoothTiltX) * SPRING_STIFFNESS;
        tiltVelZ += (targetZ - smoothTiltZ) * SPRING_STIFFNESS;
        liftVel  += (targetLift - smoothLift) * SPRING_STIFFNESS;

        tiltVelX *= (1.0f - SPRING_DAMPING);
        tiltVelZ *= (1.0f - SPRING_DAMPING);
        liftVel  *= (1.0f - SPRING_DAMPING);

        smoothTiltX += tiltVelX;
        smoothTiltZ += tiltVelZ;
        smoothLift  += liftVel;

        smoothTiltX = clamp(smoothTiltX, -MAX_TILT_DEG, MAX_TILT_DEG);
        smoothTiltZ = clamp(smoothTiltZ, -MAX_TILT_DEG, MAX_TILT_DEG);

        stack.mulPose(Vector3f.ZP.rotationDegrees(smoothTiltX));
        stack.mulPose(Vector3f.XP.rotationDegrees(smoothTiltZ));

        float pitchMagnitude = Math.abs(smoothedPitch);
        float rollMagnitude  = Math.abs(smoothedRoll);
        float flatnessFactor = 1.0f - Math.min(1.0f, (pitchMagnitude + rollMagnitude) / 10.0f);
        float speedFactor = (float) entity.getDeltaMovement().length();
        float speedDamping = 1.0f / (1.0f + speedFactor * 15.0f);

        float baseLift = (pitchMagnitude * 0.015f + rollMagnitude * 0.008f) * flatnessFactor * speedDamping;
        smoothLift = smoothLift * 0.9f + baseLift * 0.1f;

        if (this.entityRenderDispatcher.camera != null && this.entityRenderDispatcher.camera.getEntity() == entity.getControllingPassenger()) {
            stack.translate(0, smoothLift * 0.35f, 0);
        } else {
            stack.translate(0, smoothLift, 0);
        }
    }

    @Override
    public void render(CardemoEntity entity, float entityYaw, float partialTicks,
                       PoseStack stack, MultiBufferSource bufferIn, int packedLightIn) {
        stack.pushPose();
        stack.scale(2.0f, 2.0f, 2.0f);
        super.render(entity, entityYaw, partialTicks, stack, bufferIn, packedLightIn);
        stack.popPose();
    }

    @Override
    public RenderType getRenderType(CardemoEntity entity, float partialTicks, PoseStack stack,
                                    MultiBufferSource renderTypeBuffer, VertexConsumer vertexBuilder,
                                    int packedLightIn, ResourceLocation textureLocation) {
        return RenderType.entityTranslucent(getTextureLocation(entity));
    }

    // ===============================================================
    // HELPERS
    // ===============================================================
    private void addToHistory(ArrayDeque<Float> buffer, float value) {
        if (buffer.size() >= INTERP_WINDOW) buffer.removeFirst();
        buffer.addLast(value);
    }

    private float average(ArrayDeque<Float> buffer) {
        if (buffer.isEmpty()) return 0f;
        float sum = 0f;
        for (float v : buffer) sum += v;
        return sum / buffer.size();
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
