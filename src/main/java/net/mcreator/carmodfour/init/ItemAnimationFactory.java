package net.mcreator.carmodfour.init;

import software.bernie.geckolib3.item.GeoArmorItem;
import software.bernie.geckolib3.core.IAnimatable;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.mcreator.carmodfour.item.CarKeyItem;
import net.mcreator.carmodfour.item.CARSPAWNERItem;

import java.lang.reflect.Field;

@Mod.EventBusSubscriber
public class ItemAnimationFactory {

    public static void disableUseAnim() {
        try {
            ItemInHandRenderer renderer = Minecraft.getInstance().gameRenderer.itemInHandRenderer;
            float rot = 1F;
            if (renderer != null) {
                Field mainHand = ItemInHandRenderer.class.getDeclaredField("mainHandHeight");
                mainHand.setAccessible(true);
                mainHand.setFloat(renderer, rot);

                Field oMainHand = ItemInHandRenderer.class.getDeclaredField("oMainHandHeight");
                oMainHand.setAccessible(true);
                oMainHand.setFloat(renderer, rot);

                Field offHand = ItemInHandRenderer.class.getDeclaredField("offHandHeight");
                offHand.setAccessible(true);
                offHand.setFloat(renderer, rot);

                Field oOffHand = ItemInHandRenderer.class.getDeclaredField("oOffHandHeight");
                oOffHand.setAccessible(true);
                oOffHand.setFloat(renderer, rot);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public static void animatedItems(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        Player player = event.player;
        ItemStack[] hands = new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()};

        for (ItemStack stack : hands) {
            if (!(stack.getItem() instanceof IAnimatable) || stack.getItem() instanceof GeoArmorItem) continue;

            String animation = stack.getOrCreateTag().getString("geckoAnim");
            if (!animation.isEmpty()) {
                stack.getOrCreateTag().putString("geckoAnim", "");

                if ((stack.getItem() instanceof CARSPAWNERItem || stack.getItem() instanceof CarKeyItem) && player.level.isClientSide()) {
                    stack.getOrCreateTag().putString("animationProcedure", animation);
                    disableUseAnim();
                }
            }
        }
    }
}
