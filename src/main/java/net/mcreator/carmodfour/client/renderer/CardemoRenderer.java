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
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;

import net.mcreator.carmodfour.client.DriveStateKeybindHandler;
import net.mcreator.carmodfour.entity.CardemoEntity;
import net.mcreator.carmodfour.entity.model.CardemoModel;

import java.util.ArrayDeque;

/**
 * CardemoRenderer — terrain-adaptive vehicle renderer
 * ---------------------------------------------------
 *  ✓ Geometry-based tilt & lift (no speed scaling)
 *  ✓ Smooth spring interpolation
 *  ✓ Emissive layers (signals, brakes, headlights, flash)
 *  ✓ Stronger uphill extension & downhill compression
 */
public class CardemoRenderer extends GeoEntityRenderer<CardemoEntity> {

    private static final ResourceLocation LEFT_SIGNAL_EMISSIVE =
            new ResourceLocation("carmodfour:textures/entities/cardemo_l_blinker_overlay.png");
    private static final ResourceLocation RIGHT_SIGNAL_EMISSIVE =
            new ResourceLocation("carmodfour:textures/entities/cardemo_r_blinker_overlay.png");
    private static final ResourceLocation BRAKE_LIGHT_EMISSIVE =
            new ResourceLocation("carmodfour:textures/entities/cardemo_brake_light_overlay.png");
    private static final ResourceLocation HEADLIGHT_EMISSIVE =
            new ResourceLocation("carmodfour:textures/entities/cardemo_headlight_overlay.png");

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

    private static final double VISUAL_CLEARANCE = 0.35;
    private static final float  BASE_TILT_STRENGTH   = 3.0f;
    private static final float  BASE_OFFSET_STRENGTH = 0.2f;
    private static final float  SPRING_STIFFNESS  = 0.18f;
    private static final float  SPRING_DAMPING    = 0.22f;
    private static final float  MAX_TILT_DEG      = 10.0f;

    private static boolean headlightFlashActive = false;
    private static long headlightFlashStart = 0L;
    private static int headlightFlashEntity = -1;

    public CardemoRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new CardemoModel());
        this.shadowRadius = 1.0f;

        // ──────────────────────────────────────────────
        // SIGNAL + BRAKE LAYER
        // ──────────────────────────────────────────────
        this.addLayer(new GeoLayerRenderer<CardemoEntity>(this) {
            @Override
            public void render(PoseStack stack, MultiBufferSource buf, int light,
                               CardemoEntity e, float a, float b, float c, float d, float eYaw, float ePitch) {
                if (e == null || e.level == null) return;

                boolean left  = DriveStateKeybindHandler.isLeftSignalVisible();
                boolean right = DriveStateKeybindHandler.isRightSignalVisible();
                float brake   = DriveStateKeybindHandler.getBrakeIntensity();
                if (!left && !right && brake <= 0.01f) return;

                GeoModel m = this.getEntityModel().getModel(this.getEntityModel().getModelResource(e));
                if (left)  renderEmissive(m, stack, buf, e, LEFT_SIGNAL_EMISSIVE, 1f);
                if (right) renderEmissive(m, stack, buf, e, RIGHT_SIGNAL_EMISSIVE, 1f);
                if (brake > 0.01f) renderEmissive(m, stack, buf, e, BRAKE_LIGHT_EMISSIVE, brake);
            }
        });

        // ──────────────────────────────────────────────
        // HEADLIGHT MODE LAYER
        // ──────────────────────────────────────────────
        this.addLayer(new GeoLayerRenderer<CardemoEntity>(this) {
            @Override
            public void render(PoseStack stack, MultiBufferSource buf, int light,
                               CardemoEntity e, float a, float b, float c, float d, float eYaw, float ePitch) {
                if (e == null || e.level == null || !e.isEngineOn()) return;
                int hl = e.getHeadlightMode();
                float alpha = switch (hl) {
                    case 1 -> 0.2f; case 2 -> 0.5f; case 3 -> 1f; default -> 0f;
                };
                if (alpha <= 0.01f) return;
                GeoModel m = this.getEntityModel().getModel(this.getEntityModel().getModelResource(e));
                renderEmissive(m, stack, buf, e, HEADLIGHT_EMISSIVE, alpha);
            }
        });

        // ──────────────────────────────────────────────
        // LOCK / UNLOCK FLASH LAYER
        // ──────────────────────────────────────────────
        this.addLayer(new GeoLayerRenderer<CardemoEntity>(this) {
            @Override
            public void render(PoseStack stack, MultiBufferSource buf, int light,
                               CardemoEntity e, float a, float b, float c, float d, float eYaw, float ePitch) {
                if (!headlightFlashActive || e == null || e.level == null) return;
                if (e.getId() != headlightFlashEntity) return;
                long elapsed = System.currentTimeMillis() - headlightFlashStart;
                if (elapsed > 2000) { headlightFlashActive = false; return; }
                float total = 850f;
                float phase = (elapsed / total) * (float)Math.PI;
                float raw = Math.abs((float)Math.sin(phase * 1.8f));
                float fade = (elapsed > total * 0.5f)
                        ? 1f - (float)Math.pow((elapsed - total * 0.5f) / (total * 0.5f), 1.6)
                        : 1f;
                float intensity = raw * fade * 0.9f;
                if (intensity > 0.02f) {
                    GeoModel m = this.getEntityModel().getModel(this.getEntityModel().getModelResource(e));
                    renderEmissive(m, stack, buf, e, HEADLIGHT_EMISSIVE, intensity);
                }
            }
        });
    }

    public static void triggerHeadlightFlash(CardemoEntity e) {
        if (e == null || e.level == null || !e.level.isClientSide) return;
        headlightFlashEntity = e.getId();
        headlightFlashActive = true;
        headlightFlashStart = System.currentTimeMillis();
    }

    @Override
    public void render(CardemoEntity e, float yaw, float pt, PoseStack stack,
                       MultiBufferSource buf, int light) {
        Mth.lerp(pt, e.yBodyRot, e.getYRot());
        stack.pushPose();
        stack.scale(2f, 2f, 2f);
        super.render(e, yaw, pt, stack, buf, light);
        stack.popPose();
    }

    // ──────────────────────────────────────────────
    // ROTATION & LIFT DYNAMICS
    // ──────────────────────────────────────────────
    @Override
    protected void applyRotations(CardemoEntity e, PoseStack stack,
                                  float age, float yaw, float pt) {

        float targetPitch = e.getXRot();
        smoothedPitch += (targetPitch - smoothedPitch) * 0.1f;
        float targetRoll = e.getVisualRoll();
        smoothedRoll += (targetRoll - smoothedRoll) * 0.2f;

        super.applyRotations(e, stack, age, yaw, pt);
        stack.mulPose(Vector3f.ZP.rotationDegrees(smoothedRoll * 1.5f));
        stack.mulPose(Vector3f.XP.rotationDegrees(-smoothedPitch * 1.8f));

        if (CardemoEntity.cameraLurchPitchOffset != 0f)
            stack.mulPose(Vector3f.XP.rotationDegrees(CardemoEntity.cameraLurchPitchOffset));

        if (e.level == null) return;

        float tiltX = 0f, tiltZ = 0f, lift = 0f;
        Vec3 pos = e.position();
        BlockPos base = new BlockPos(pos.x, pos.y, pos.z);

        for (int dx=-1; dx<=1; dx++)
            for (int dy=-1; dy<=1; dy++)
                for (int dz=-1; dz<=1; dz++) {
                    if (Math.abs(dx)+Math.abs(dy)+Math.abs(dz)!=1) continue;
                    BlockState st = e.level.getBlockState(base.offset(dx,dy,dz));
                    if (!st.isAir() && st.getMaterial().isSolid()) {
                        double dist = e.position().distanceTo(Vec3.atCenterOf(base.offset(dx,dy,dz)));
                        if (dist < VISUAL_CLEARANCE) {
                            double depth = VISUAL_CLEARANCE - dist;
                            if (dx>0) tiltZ -= depth*BASE_TILT_STRENGTH;
                            if (dx<0) tiltZ += depth*BASE_TILT_STRENGTH;
                            if (dz>0) tiltX += depth*BASE_TILT_STRENGTH;
                            if (dz<0) tiltX -= depth*BASE_TILT_STRENGTH;
                            if (dy<0) lift  += depth*BASE_OFFSET_STRENGTH;
                        }
                    }
                }

        addToHistory(tiltXHist,tiltX); addToHistory(tiltZHist,tiltZ); addToHistory(liftHist,lift);
        float avgX=average(tiltXHist), avgZ=average(tiltZHist), avgLift=average(liftHist);

        tiltVelX+=(avgX-smoothTiltX)*SPRING_STIFFNESS;
        tiltVelZ+=(avgZ-smoothTiltZ)*SPRING_STIFFNESS;
        liftVel +=(avgLift-smoothLift)*SPRING_STIFFNESS;

        tiltVelX*=(1f-SPRING_DAMPING);
        tiltVelZ*=(1f-SPRING_DAMPING);
        liftVel *=(1f-SPRING_DAMPING);

        smoothTiltX+=tiltVelX;
        smoothTiltZ+=tiltVelZ;
        smoothLift +=liftVel;

        smoothTiltX=clamp(smoothTiltX,-MAX_TILT_DEG,MAX_TILT_DEG);
        smoothTiltZ=clamp(smoothTiltZ,-MAX_TILT_DEG,MAX_TILT_DEG);

        // ─── STRONGER UPHILL EXTENSION ───
        if (smoothTiltZ < -0.05f) {
            float f = clamp(Math.abs(smoothTiltZ)/MAX_TILT_DEG,0f,1f);
            smoothLift += f * 0.85f;      // lift
            smoothTiltZ -= f * 1.65f;     // nose-up exaggeration
            smoothTiltX -= f * 0.45f;     // slight rear roll
        }

// ─── STRONGER DOWNHILL COMPRESSION (enhanced) ───
        if (smoothTiltZ > 0.05f) {
            float f = clamp(smoothTiltZ / MAX_TILT_DEG, 0f, 1f);

            // stronger nose dive angle
            smoothTiltZ += f * 6.25f;      // was 4.10f

            // heavier front-end compression
            smoothLift  -= f * 0.95f;      // was 0.70f

            // increased forward body roll
            smoothTiltX += f * 1.45f;      // was 1.10f

            // additional drop to simulate suspension unloading in rear
            smoothLift  -= f * 0.35f;      // was 0.20f

            // slight nose-forward push to ground
            smoothTiltZ += f * 1.10f;      // new
        }

        stack.mulPose(Vector3f.ZP.rotationDegrees(smoothTiltX));
        stack.mulPose(Vector3f.XP.rotationDegrees(smoothTiltZ));
        stack.translate(0.0, smoothLift, 0.0);
    }

    @Override
    public RenderType getRenderType(CardemoEntity e, float pt, PoseStack s,
                                    MultiBufferSource buf, VertexConsumer v,
                                    int light, ResourceLocation tex) {
        return RenderType.entityTranslucent(getTextureLocation(e));
    }

    private void addToHistory(ArrayDeque<Float> b,float v){if(b.size()>=INTERP_WINDOW)b.removeFirst();b.addLast(v);}
    private float average(ArrayDeque<Float> b){if(b.isEmpty())return 0f;float s=0f;for(float v:b)s+=v;return s/b.size();}
    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}

    private void renderEmissive(GeoModel m, PoseStack s, MultiBufferSource buf,
                                CardemoEntity e, ResourceLocation tex, float a) {
        if (e==null||e.level==null) return;
        RenderType rt = RenderType.entityTranslucentEmissive(tex);
        VertexConsumer vc = buf.getBuffer(rt);
        CardemoRenderer.this.render(m,e,0,rt,s,buf,vc,
                0xF000F0,OverlayTexture.NO_OVERLAY,1f,1f,1f,a);
    }
}
