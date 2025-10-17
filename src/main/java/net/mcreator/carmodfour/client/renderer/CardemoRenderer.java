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

    private float smoothedPitch = 0f; // persistent smoothed angle

    public CardemoRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CardemoModel());
        this.shadowRadius = 1.0f;
    }

    @Override
    public void applyRotations(CardemoEntity entity, PoseStack stack, float ageInTicks, float rotationYaw, float partialTicks) {
        super.applyRotations(entity, stack, ageInTicks, rotationYaw, partialTicks);

        // 1️⃣ Raw input from entity
        float targetPitch = entity.getXRot();

        // 2️⃣ Smooth it with exponential damping
        //    The closer smoothingFactor is to 1, the slower & smoother the motion.
        float smoothingFactor = 0.1f; // try 0.05f for even slower smoothing
        smoothedPitch += (targetPitch - smoothedPitch) * smoothingFactor;

        // 3️⃣ Convert to visual tilt (flip + amplify)
        float adjustedPitch = -smoothedPitch * 1.8f;

        // 4️⃣ Apply tilt
        stack.mulPose(Vector3f.XP.rotationDegrees(adjustedPitch));

        // 5️⃣ Apply lift proportional to tilt (suspension rise)
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
