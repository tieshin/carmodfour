package net.mcreator.carmodfour.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * ============================================================================
 *  InvisibleHeadlightBlock — emits invisible, dynamic light for vehicles.
 * ============================================================================
 *
 *  • Invisible and non-collidable
 *  • Light intensity controlled by blockstate property "level" (0–15)
 *  • Can be placed/removed dynamically by CardemoEntity
 *  • No ticking, no loops, no render geometry
 * ============================================================================
 */
public class InvisibleHeadlightBlock extends Block {

    // Light brightness level (0–15)
    public static final IntegerProperty LIGHT_LEVEL = IntegerProperty.create("level", 0, 15);

    public InvisibleHeadlightBlock() {
        super(BlockBehaviour.Properties
                .of(Material.AIR)
                .sound(SoundType.WOOL)
                .noCollission()
                .noOcclusion()
                .lightLevel(state -> state.getValue(LIGHT_LEVEL))
                .strength(-1.0F, 3600000.0F)
                .noLootTable()
        );
        this.registerDefaultState(this.stateDefinition.any().setValue(LIGHT_LEVEL, 15));
    }

    // --- Blockstate definition
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIGHT_LEVEL);
    }

    // --- No visible or collidable shape
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    // --- Behaves like air for transparency/pathfinding
    @Override
    public boolean isAir(BlockState state) {
        return true;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(LIGHT_LEVEL, 15);
    }

    // --- Safe placement (no recursion)
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moving) {
        if (!level.isClientSide) {
            // passive block: no scheduled ticks
        }
    }

    // --- Auto-remove when overwritten by solid block
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        if (!level.isClientSide) {
            if (!level.isEmptyBlock(pos) && !(level.getBlockState(pos).getBlock() instanceof InvisibleHeadlightBlock)) {
                level.removeBlock(pos, false);
            }
        }
    }
}