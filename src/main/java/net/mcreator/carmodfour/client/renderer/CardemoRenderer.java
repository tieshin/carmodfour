package net.mcreator.carmodfour.client.renderer;

import software.bernie.geckolib3.renderers.geo.GeoEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;

import net.mcreator.carmodfour.entity.CardemoEntity;
import net.mcreator.carmodfour.entity.model.CardemoModel;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;

/**
 * CardemoRenderer
 *
 * Handles rendering, pitch/roll smoothing, and rotation order for the car entity.
 * Ensures realistic body roll and slope alignment without inverting yaw.
 */
public class CardemoRenderer extends GeoEntityRenderer<CardemoEntity> {

    private float smoothedPitch = 0f;
    private float smoothedRoll = 0f;

    public CardemoRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CardemoModel());
        this.shadowRadius = 1.0f;
    }

    @Override
    protected void applyRotations(CardemoEntity entity, PoseStack stack,
                                  float ageInTicks, float rotationYaw, float partialTicks) {
        // Smooth pitch and roll for stable transitions
        float targetPitch = entity.getXRot();
        smoothedPitch += (targetPitch - smoothedPitch) * 0.1f;

        float targetRoll = entity.getVisualRoll();
        smoothedRoll += (targetRoll - smoothedRoll) * 0.2f;

        // --- Correct rotation order: Yaw → Roll → Pitch ---

        // Step 1: Apply yaw (base class handles world facing)
        super.applyRotations(entity, stack, ageInTicks, rotationYaw, partialTicks);

        // Step 2: Apply roll (Z axis, after yaw so it’s local to vehicle orientation)
        stack.mulPose(Vector3f.ZP.rotationDegrees(smoothedRoll * 1.5f));

        // Step 3: Apply pitch (X axis)
        stack.mulPose(Vector3f.XP.rotationDegrees(-smoothedPitch * 1.8f));

        // Step 4: Optional lift for realism (simulates suspension movement)
        float liftAmount = Math.abs(smoothedPitch) * 0.025f + Math.abs(smoothedRoll) * 0.01f;
        stack.translate(0, liftAmount, 0);
    }

    @Override
    public void render(CardemoEntity entity, float entityYaw, float partialTicks,
                       PoseStack stack, MultiBufferSource bufferIn, int packedLightIn) {
        stack.pushPose();

        // Uniform scale (vehicle size)
        stack.scale(2.0f, 2.0f, 2.0f);

        // Render the model with all applied transforms
        super.render(entity, entityYaw, partialTicks, stack, bufferIn, packedLightIn);

        stack.popPose();
    }

    @Override
    public RenderType getRenderType(CardemoEntity entity, float partialTicks, PoseStack stack,
                                    MultiBufferSource renderTypeBuffer, VertexConsumer vertexBuilder,
                                    int packedLightIn, ResourceLocation textureLocation) {
        return RenderType.entityTranslucent(getTextureLocation(entity));
    }
}
