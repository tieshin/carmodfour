package net.mcreator.carmodfour.item.renderer;

import software.bernie.geckolib3.renderers.geo.GeoItemRenderer;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.IAnimatableModel;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.block.model.ItemTransforms.TransformType;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.Minecraft;

import net.mcreator.carmodfour.item.model.CARSPAWNERItemModel;
import net.mcreator.carmodfour.item.CARSPAWNERItem;
import net.mcreator.carmodfour.interfaces.RendersPlayerArms;

import java.util.Set;
import java.util.Map;
import java.util.HashSet;
import java.util.HashMap;

import com.mojang.math.Vector3f;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * GeoItemRenderer for the CARSPAWNER item.
 * Updated to remove calls to removed/deprecated GeckoLib methods.
 */
@SuppressWarnings("deprecated")
public class CARSPAWNERItemRenderer extends GeoItemRenderer<CARSPAWNERItem> implements RendersPlayerArms {
    public CARSPAWNERItemRenderer() {
        super(new CARSPAWNERItemModel());
    }

    @Override
    public RenderType getRenderType(CARSPAWNERItem animatable, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, VertexConsumer buffer, int packedLight, ResourceLocation texture) {
        return RenderType.entityTranslucent(getTextureLocation(animatable));
    }

    static {
        AnimationController.addModelFetcher(animatable -> {
            if (animatable instanceof CARSPAWNERItem) {
                Item item = (Item) animatable;
                BlockEntityWithoutLevelRenderer ister = new CARSPAWNERItemRenderer();
                if (ister instanceof GeoItemRenderer) {
                    @SuppressWarnings("unchecked")
                    IAnimatableModel<Object> model = (IAnimatableModel<Object>) ((GeoItemRenderer<?>) ister).getGeoModelProvider();
                    return model;
                }
            }
            return null;
        });
    }

    private static final float SCALE_RECIPROCAL = 1.0f / 16.0f;
    protected boolean renderArms = false;
    protected MultiBufferSource currentBuffer;
    protected RenderType renderType;
    public TransformType transformType;
    protected CARSPAWNERItem animatable;
    private float aimProgress = 0.0f;
    private final Set<String> hiddenBones = new HashSet<>();
    private final Set<String> suppressedBones = new HashSet<>();
    private final Map<String, Vector3f> queuedBoneSetMovements = new HashMap<>();
    private final Map<String, Vector3f> queuedBoneSetRotations = new HashMap<>();
    private final Map<String, Vector3f> queuedBoneAddRotations = new HashMap<>();

    @Override
    public void renderByItem(ItemStack itemStack, TransformType transformType, PoseStack matrixStack, MultiBufferSource bufferIn, int combinedLightIn, int overlay) {
        this.transformType = transformType;
        super.renderByItem(itemStack, transformType, matrixStack, bufferIn, combinedLightIn, overlay);
    }

    @Override
    public void render(GeoModel model, CARSPAWNERItem animatable, float partialTicks, RenderType type, PoseStack matrixStackIn, MultiBufferSource renderTypeBuffer, VertexConsumer vertexBuilder, int packedLightIn, int packedOverlayIn, float red,
                       float green, float blue, float alpha) {
        this.currentBuffer = renderTypeBuffer;
        this.renderType = type;
        this.animatable = animatable;
        super.render(model, animatable, partialTicks, type, matrixStackIn, renderTypeBuffer, vertexBuilder, packedLightIn, packedOverlayIn, red, green, blue, alpha);
        if (this.renderArms) {
            this.renderArms = false;
        }
    }

    @Override
    public void render(CARSPAWNERItem animatable, PoseStack stack, MultiBufferSource bufferIn, int packedLightIn, ItemStack itemStack) {
        Minecraft mc = Minecraft.getInstance();
        float sign = 1.0f;
        this.aimProgress = Mth.clamp(this.aimProgress + mc.getFrameTime() * sign * 0.1f, 0.0f, 1.0f);

        stack.pushPose();
        // NOTE: older MCreator-generated code called animatable.setupAnimationState(...) here.
        // That method no longer exists in current GeckoLib generations — remove that call.
        // Instead, perform any per-frame setup here if needed, then call super.render
        super.render(animatable, stack, bufferIn, packedLightIn, itemStack);
        stack.popPose();

        // pass transform type back to the item if it wants it (keeps compatibility with your item)
        if (this.animatable != null) {
            this.animatable.getTransformType(this.transformType);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(CARSPAWNERItem instance) {
        return super.getTextureLocation(instance);
    }

    // keep bone controls and other helper methods (unchanged)
    public void hideBone(String name, boolean hide) {
        if (hide) {
            this.hiddenBones.add(name);
        } else {
            this.hiddenBones.remove(name);
        }
    }

    @Override
    public void setRenderArms(boolean renderArms) {
        this.renderArms = renderArms;
    }

    public TransformType getCurrentTransform() {
        return this.transformType;
    }

    public void suppressModification(String name) {
        this.suppressedBones.add(name);
    }

    public void allowModification(String name) {
        this.suppressedBones.remove(name);
    }

    public void setBonePosition(String name, float x, float y, float z) {
        this.queuedBoneSetMovements.put(name, new Vector3f(x, y, z));
    }

    public void addToBoneRotation(String name, float x, float y, float z) {
        this.queuedBoneAddRotations.put(name, new Vector3f(x, y, z));
    }

    public void setBoneRotation(String name, float x, float y, float z) {
        this.queuedBoneSetRotations.put(name, new Vector3f(x, y, z));
    }

    public ItemStack getCurrentItem() {
        return this.currentItemStack;
    }

    @Override
    public boolean shouldAllowHandRender(ItemStack mainhand, ItemStack offhand, InteractionHand renderingHand) {
        return renderingHand == InteractionHand.MAIN_HAND;
    }
}
