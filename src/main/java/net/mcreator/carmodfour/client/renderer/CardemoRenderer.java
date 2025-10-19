package net.mcreator.carmodfour.client.renderer;

import software.bernie.geckolib3.renderers.geo.GeoEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;

import net.mcreator.carmodfour.entity.model.CardemoModel;
import net.mcreator.carmodfour.entity.CardemoEntity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;

public class CardemoRenderer extends GeoEntityRenderer<CardemoEntity> {

    private float smoothedPitch = 0f; // persistent pitch smoothing
    private float smoothedRoll = 0f;  // persistent roll smoothing

    public CardemoRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CardemoModel());
        this.shadowRadius = 1.0f;
    }

    @Override
    public void applyRotations(CardemoEntity entity, PoseStack stack, float ageInTicks, float rotationYaw, float partialTicks) {
        super.applyRotations(entity, stack, ageInTicks, rotationYaw, partialTicks);

        // --- Pitch smoothing ---
        float targetPitch = entity.getXRot();
        float pitchSmoothFactor = 0.1f;
        smoothedPitch += (targetPitch - smoothedPitch) * pitchSmoothFactor;
        float adjustedPitch = -smoothedPitch * 1.8f;

        // --- Roll smoothing ---
        float targetRoll = entity.getVisualRoll(); // from entity (already smoothed server-side)
        float rollSmoothFactor = 0.15f; // slightly faster than pitch
        smoothedRoll += (targetRoll - smoothedRoll) * rollSmoothFactor;

        // --- Apply transformations in logical order ---
        // 1. Roll (Z-axis)
        stack.mulPose(Vector3f.ZP.rotationDegrees(smoothedRoll));

        // 2. Pitch (X-axis)
        stack.mulPose(Vector3f.XP.rotationDegrees(adjustedPitch));

        // 3. Lift (suspension)
        float liftAmount = Math.abs(smoothedPitch) * 0.025f;
        stack.translate(0, liftAmount, 0);
    }

    @Override
    public void render(CardemoEntity entity, float entityYaw, float partialTicks, PoseStack stack,
                       MultiBufferSource bufferIn, int packedLightIn) {
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
}
