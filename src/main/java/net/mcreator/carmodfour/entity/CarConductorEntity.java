package net.mcreator.carmodfour.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

public class CarConductorEntity extends Mob {
    private CardemoEntity car; // removed 'final'

    // Standard constructor Forge uses
    public CarConductorEntity(EntityType<? extends CarConductorEntity> type, Level world) {
        super(type, world);
        this.noPhysics = true;
        this.noCulling = true;
        this.setInvisible(true);
    }

    // Custom constructor that links the conductor to its car
    public CarConductorEntity(EntityType<? extends CarConductorEntity> type, Level world, CardemoEntity car) {
        this(type, world); // Calls the default constructor
        this.car = car;    // Now this is legal since it's not final
    }

    @Override
    public void tick() {
        super.tick();

        // Keep yaw aligned with car and follow its position
        if (car != null) {
            this.setYRot(car.getYRot());
            this.setYHeadRot(car.getYRot());
            this.setYBodyRot(car.getYRot());

            // Position conductor slightly ahead, car follows behind
            car.setPos(this.getX(), this.getY(), this.getZ() - 0.5);
        }
    }

    @Override
    public boolean isInvisible() {
        return true;
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.2)
                .add(Attributes.MAX_HEALTH, 10.0)
                .add(Attributes.FOLLOW_RANGE, 16.0);
    }
}
