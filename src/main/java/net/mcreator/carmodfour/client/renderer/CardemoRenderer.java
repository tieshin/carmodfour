package net.mcreator.carmodfour.client.renderer;

import software.bernie.geckolib3.renderers.geo.GeoEntityRenderer;
import software.bernie.geckolib3.renderers.geo.GeoLayerRenderer;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.geo.render.built.GeoModel;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;

import net.mcreator.carmodfour.client.DriveStateKeybindHandler;
import net.mcreator.carmodfour.entity.CardemoEntity;
import net.mcreator.carmodfour.entity.model.CardemoModel;

import java.util.ArrayDeque;

/**
 * CardemoRenderer
 *
 * Unified smooth visual dynamics:
 *  - Terrain tilt and wall roll
 *  - Anti-intersection tilt (front/back/side)
 *  - Spring-damped lift and recoil smoothing
 *  - Emissive overlays (blinkers, brake lights, headlights)
 */
public class CardemoRenderer extends GeoEntityRenderer<CardemoEntity> {

    // ===============================================================
    // EMISSIVE OVERLAY TEXTURES
    // ===============================================================
    private static final ResourceLocation LEFT_SIGNAL_EMISSIVE =
            new ResourceLocation("carmodfour:textures/entities/cardemo_l_blinker_overlay.png");
    private static final ResourceLocation RIGHT_SIGNAL_EMISSIVE =
            new ResourceLocation("carmodfour:textures/entities/cardemo_r_blinker_overlay.png");
    private static final ResourceLocation BRAKE_LIGHT_EMISSIVE =
            new ResourceLocation("carmodfour:textures/entities/cardemo_brake_light_overlay.png");
    private static final ResourceLocation HEADLIGHT_EMISSIVE =
            new ResourceLocation("carmodfour:textures/entities/cardemo_headlight_overlay.png");

    // ===============================================================
    // STATE VARIABLES
    // ===============================================================
    private float smoothedPitch = 0f;
    private float smoothedRoll  = 0f;

    private float smoothTiltX = 0f;
    private float smoothTiltZ = 0f;
    private float smoothLift  = 0f;

    private float tiltVelX = 0f;
    private float tiltVelZ = 0f;
    private float liftVel  = 0f;

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

    private static final float  SPRING_STIFFNESS  = 0.18f;
    private static final float  SPRING_DAMPING    = 0.22f;
    private static final float  MAX_TILT_DEG      = 10.0f;

    // ===============================================================
    // HEADLIGHT FLASH CONTROL
    // ===============================================================
    private static boolean headlightFlashActive = false;
    private static long headlightFlashStart = 0L;
    private static int headlightFlashEntity = -1;

    // ===============================================================
    // CONSTRUCTOR
    // ===============================================================
    public CardemoRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CardemoModel());
        this.shadowRadius = 1.0f;

        // ===========================================================
        // EMISSIVE OVERLAY LAYER (Blinkers + Brake lights)
        // ===========================================================
        this.addLayer(new GeoLayerRenderer<CardemoEntity>(this) {
            @Override
            public void render(PoseStack stack, MultiBufferSource bufferIn, int packedLightIn,
                               CardemoEntity entity, float limbSwing, float limbSwingAmount,
                               float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {

                boolean leftVisible  = DriveStateKeybindHandler.isLeftSignalVisible();
                boolean rightVisible = DriveStateKeybindHandler.isRightSignalVisible();
                float brakeIntensity = DriveStateKeybindHandler.getBrakeIntensity();

                if (!leftVisible && !rightVisible && brakeIntensity <= 0.01f)
                    return;

                GeoModelProvider<CardemoEntity> provider = this.getEntityModel();
                GeoModel model = provider.getModel(provider.getModelResource(entity));

                if (leftVisible)
                    renderEmissive(model, stack, bufferIn, entity, LEFT_SIGNAL_EMISSIVE, 1f);

                if (rightVisible)
                    renderEmissive(model, stack, bufferIn, entity, RIGHT_SIGNAL_EMISSIVE, 1f);

                if (brakeIntensity > 0.01f)
                    renderEmissive(model, stack, bufferIn, entity, BRAKE_LIGHT_EMISSIVE, brakeIntensity);
            }

            private void renderEmissive(GeoModel model, PoseStack stack, MultiBufferSource bufferIn,
                                        CardemoEntity entity, ResourceLocation texture, float alpha) {
                RenderType rt = RenderType.entityTranslucentEmissive(texture);
                VertexConsumer vc = bufferIn.getBuffer(rt);
                this.getRenderer().render(model, entity, 0, rt, stack, bufferIn, vc,
                        0xF000F0, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, alpha);
            }
        });

        // ===========================================================
        // HEADLIGHT FLASH OVERLAY (Lock/Unlock confirmation)
        // ===========================================================
        this.addLayer(new GeoLayerRenderer<CardemoEntity>(this) {
            @Override
            public void render(PoseStack stack, MultiBufferSource bufferIn, int packedLightIn,
                               CardemoEntity entity, float limbSwing, float limbSwingAmount,
                               float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {

                if (!entity.level.isClientSide) return;

                // Check if this entity triggered a door sound recently
                if (entity.getId() == headlightFlashEntity && headlightFlashActive) {
                    long elapsed = System.currentTimeMillis() - headlightFlashStart;
                    if (elapsed > 2000) {
                        headlightFlashActive = false;
                        return;
                    }

                    // Smooth 2s flicker → double pulse
                    float phase = (elapsed / 2000f) * (float)Math.PI * 2f;
                    float intensity = (float)Math.abs(Math.sin(phase * 2f)) * 0.9f;

                    if (intensity > 0.02f) {
                        GeoModelProvider<CardemoEntity> provider = this.getEntityModel();
                        GeoModel model = provider.getModel(provider.getModelResource(entity));
                        renderEmissive(model, stack, bufferIn, entity, HEADLIGHT_EMISSIVE, intensity);
                    }
                }
            }

            private void renderEmissive(GeoModel model, PoseStack stack, MultiBufferSource bufferIn,
                                        CardemoEntity entity, ResourceLocation texture, float alpha) {
                RenderType rt = RenderType.entityTranslucentEmissive(texture);
                VertexConsumer vc = bufferIn.getBuffer(rt);
                this.getRenderer().render(model, entity, 0, rt, stack, bufferIn, vc,
                        0xF000F0, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, alpha);
            }
        });
    }

    // ===============================================================
    // EXTERNAL TRIGGER (Called from playDoorSound in CardemoEntity)
    // ===============================================================
    public static void triggerHeadlightFlash(CardemoEntity entity) {
        if (entity == null || entity.level == null || !entity.level.isClientSide) return;
        headlightFlashEntity = entity.getId();
        headlightFlashActive = true;
        headlightFlashStart = System.currentTimeMillis();
    }

    // ===============================================================
    // MAIN RENDER METHOD
    // ===============================================================
    @Override
    public void render(CardemoEntity entity, float entityYaw, float partialTicks,
                       PoseStack stack, MultiBufferSource bufferIn, int packedLightIn) {
        stack.pushPose();
        stack.scale(2.0f, 2.0f, 2.0f); // restore full car scale
        super.render(entity, entityYaw, partialTicks, stack, bufferIn, packedLightIn);
        stack.popPose();
    }

    // ===============================================================
    // ROTATIONS + TERRAIN TILT + SPRING DYNAMICS
    // ===============================================================
    @Override
    protected void applyRotations(CardemoEntity entity, PoseStack stack,
                                  float ageInTicks, float rotationYaw, float partialTicks) {

        float targetPitch = entity.getXRot();
        smoothedPitch += (targetPitch - smoothedPitch) * 0.1f;

        float targetRoll = entity.getVisualRoll();
        smoothedRoll += (targetRoll - smoothedRoll) * 0.2f;

        super.applyRotations(entity, stack, ageInTicks, rotationYaw, partialTicks);
        stack.mulPose(Vector3f.ZP.rotationDegrees(smoothedRoll * 1.5f));
        stack.mulPose(Vector3f.XP.rotationDegrees(-smoothedPitch * 1.8f));

        if (CardemoEntity.cameraLurchPitchOffset != 0f)
            stack.mulPose(Vector3f.XP.rotationDegrees(CardemoEntity.cameraLurchPitchOffset));

        if (entity.level == null) return;

        double speed = Math.min(entity.getDeltaMovement().length() * 20.0, 1.0);
        float tiltStrength   = BASE_TILT_STRENGTH * (float)(0.5 + speed * 1.5);
        float offsetStrength = BASE_OFFSET_STRENGTH * (float)(0.8 + 0.4 * speed);

        Vec3 pos = entity.position();
        BlockPos basePos = new BlockPos(pos.x, pos.y, pos.z);
        float tiltX = 0f, tiltZ = 0f, lift = 0f;

        // Wall proximity + rear bumper checks (unchanged physics)
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

        double yawRad = Math.toRadians(entity.getYRot());
        Vec3 rearDir = new Vec3(Math.sin(yawRad + Math.PI), 0, -Math.cos(yawRad + Math.PI)).normalize();
        AABB rearBox = entity.getBoundingBox()
                .move(-rearDir.x * 0.8, 0, -rearDir.z * 0.8)
                .inflate(0.1, 0.2, 0.1);

        int totalHits = 0;
        float totalDepth = 0f;
        for (BlockPos bp : BlockPos.betweenClosed(
                new BlockPos(Math.floor(rearBox.minX), Math.floor(rearBox.minY), Math.floor(rearBox.minZ)),
                new BlockPos(Math.floor(rearBox.maxX), Math.floor(rearBox.maxY), Math.floor(rearBox.maxZ))
        )) {
            BlockState bs = entity.level.getBlockState(bp);
            if (!bs.isAir() && bs.getMaterial().isSolid()) {
                double blockCenterDist = pos.distanceTo(Vec3.atCenterOf(bp));
                double depth = Math.max(0, 1.5 - blockCenterDist);
                totalDepth += depth * 0.75;
                totalHits++;
            }
        }

        if (totalHits > 0) {
            float avgDepth = totalDepth / totalHits;
            tiltZ += avgDepth * tiltStrength * 1.6f;
            lift  += avgDepth * offsetStrength * 0.8f;
        }

        addToHistory(tiltXHist, tiltX);
        addToHistory(tiltZHist, tiltZ);
        addToHistory(liftHist,  lift);

        float avgX = average(tiltXHist);
        float avgZ = average(tiltZHist);
        float avgLift = average(liftHist);

        tiltVelX += (avgX - smoothTiltX) * SPRING_STIFFNESS;
        tiltVelZ += (avgZ - smoothTiltZ) * SPRING_STIFFNESS;
        liftVel  += (avgLift - smoothLift) * SPRING_STIFFNESS;

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
    }

    // ===============================================================
    // HELPERS
    // ===============================================================
    @Override
    public RenderType getRenderType(CardemoEntity entity, float partialTicks, PoseStack stack,
                                    MultiBufferSource renderTypeBuffer, VertexConsumer vertexBuilder,
                                    int packedLightIn, ResourceLocation textureLocation) {
        return RenderType.entityTranslucent(getTextureLocation(entity));
    }

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
