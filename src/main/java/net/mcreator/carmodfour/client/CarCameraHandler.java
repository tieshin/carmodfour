package net.mcreator.carmodfour.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles smooth camera yaw snapping when a player enters a car.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class CarCameraHandler {

    private static final int SNAP_DURATION_TICKS = 40; // 2 seconds at 20 TPS

    private static final Map<Player, SnapData> activeSnaps = new HashMap<>();

    private static class SnapData {
        float startYaw;
        float targetYaw;
        float startPitch;
        float targetPitch;
        int ticksLeft;

        SnapData(float startYaw, float startPitch, float targetYaw, float targetPitch, int ticksLeft) {
            this.startYaw = startYaw;
            this.startPitch = startPitch;
            this.targetYaw = targetYaw;
            this.targetPitch = targetPitch;
            this.ticksLeft = ticksLeft;
        }
    }

    /**
     * Start snapping a player’s view to match the car.
     */
    public static void startHeadSnap(Player player, Entity car) {
        if (!(player instanceof LocalPlayer local)) return;

        float startYaw = local.getYRot();
        float startPitch = local.getXRot();
        float targetYaw = car.getYRot();
        float targetPitch = car.getXRot();

        activeSnaps.put(local, new SnapData(startYaw, startPitch, targetYaw, targetPitch, SNAP_DURATION_TICKS));
    }

    /**
     * Interpolates between angles correctly over time.
     */
    private static float lerpAngle(float start, float end, float t) {
        float delta = wrapDegrees(end - start);
        return start + delta * t;
    }

    private static float wrapDegrees(float angle) {
        angle = angle % 360.0F;
        if (angle >= 180.0F) angle -= 360.0F;
        if (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        SnapData snap = activeSnaps.get(player);
        if (snap != null && snap.ticksLeft > 0) {
            float t = 1.0f - ((float) snap.ticksLeft / SNAP_DURATION_TICKS);
            player.setYRot(lerpAngle(snap.startYaw, snap.targetYaw, t));
            player.setXRot(lerpAngle(snap.startPitch, snap.targetPitch, t));
            player.yRotO = player.getYRot();
            player.xRotO = player.getXRot();
            snap.ticksLeft--;

            if (snap.ticksLeft <= 0) {
                activeSnaps.remove(player);
            }
        }
    }
}
