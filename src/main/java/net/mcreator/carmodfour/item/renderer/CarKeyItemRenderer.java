package net.mcreator.carmodfour.item.renderer;

import software.bernie.geckolib3.renderers.geo.GeoItemRenderer;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.core.IAnimatableModel;

import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.block.model.ItemTransforms.TransformType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

import net.mcreator.carmodfour.item.CarKeyItem;
import net.mcreator.carmodfour.item.model.CarKeyItemModel;
import net.mcreator.carmodfour.interfaces.RendersPlayerArms;

import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import com.mojang.math.Vector3f;

@SuppressWarnings("deprecation")
public class CarKeyItemRenderer extends GeoItemRenderer<CarKeyItem> implements RendersPlayerArms {
    private final Set<String> hiddenBones = new HashSet<>();
    private final Set<String> suppressedBones = new HashSet<>();
    private final Map<String, Vector3f> queuedBoneSetMovements = new HashMap<>();
    private final Map<String, Vector3f> queuedBoneSetRotations = new HashMap<>();
    private final Map<String, Vector3f> queuedBoneAddRotations = new HashMap<>();
    private boolean renderArms = false;
    private TransformType transformType;

    public CarKeyItemRenderer() {
        super(new CarKeyItemModel());
    }

    @Override
    public ResourceLocation getTextureLocation(CarKeyItem instance) {
        return super.getTextureLocation(instance);
    }

    public void hideBone(String name, boolean hide) {
        if (hide) hiddenBones.add(name);
        else hiddenBones.remove(name);
    }

    public void suppressModification(String name) {
        suppressedBones.add(name);
    }

    public void allowModification(String name) {
        suppressedBones.remove(name);
    }

    public void setBonePosition(String name, float x, float y, float z) {
        queuedBoneSetMovements.put(name, new Vector3f(x, y, z));
    }

    public void setBoneRotation(String name, float x, float y, float z) {
        queuedBoneSetRotations.put(name, new Vector3f(x, y, z));
    }

    public void addToBoneRotation(String name, float x, float y, float z) {
        queuedBoneAddRotations.put(name, new Vector3f(x, y, z));
    }

    @Override
    public void setRenderArms(boolean renderArms) {
        this.renderArms = renderArms;
    }

    public TransformType getCurrentTransform() {
        return this.transformType;
    }

    // Implement the required method from RendersPlayerArms
    @Override
    public boolean shouldAllowHandRender(ItemStack mainhand, ItemStack offhand, InteractionHand renderingHand) {
        return renderingHand == InteractionHand.MAIN_HAND;
    }
}
