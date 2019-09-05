package com.simibubi.create.modules.contraptions;

import java.util.LinkedList;
import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.modules.contraptions.base.IRotate;
import com.simibubi.create.modules.contraptions.base.KineticTileEntity;
import com.simibubi.create.modules.contraptions.relays.EncasedBeltBlock;
import com.simibubi.create.modules.contraptions.relays.GearboxTileEntity;
import com.simibubi.create.modules.contraptions.relays.SplitShaftTileEntity;
import com.simibubi.create.modules.contraptions.relays.belt.BeltTileEntity;

import net.minecraft.block.BlockState;
import net.minecraft.state.IProperty;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.util.Direction;
import net.minecraft.util.Direction.Axis;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class RotationPropagator {

	/**
	 * Determines the change in rotation between two attached kinetic entities. For
	 * instance, an axis connection returns 1 while a 1-to-1 gear connection
	 * reverses the rotation and therefore returns -1.
	 * 
	 * @param from
	 * @param to
	 * @return
	 */
	private static float getRotationSpeedModifier(KineticTileEntity from, KineticTileEntity to) {
		final BlockState stateFrom = from.getBlockState();
		final BlockState stateTo = to.getBlockState();
		final IRotate definitionFrom = (IRotate) stateFrom.getBlock();
		final IRotate definitionTo = (IRotate) stateTo.getBlock();
		final BlockPos diff = to.getPos().subtract(from.getPos());
		final Direction direction = Direction.getFacingFromVector(diff.getX(), diff.getY(), diff.getZ());
		final World world = from.getWorld();

		IProperty<Axis> axisProperty = BlockStateProperties.AXIS;
		boolean connectedByAxis = definitionFrom.hasShaftTowards(world, from.getPos(), stateFrom, direction)
				&& definitionTo.hasShaftTowards(world, to.getPos(), stateTo, direction.getOpposite());
		boolean connectedByGears = definitionFrom.hasCogsTowards(world, from.getPos(), stateFrom, direction)
				&& definitionTo.hasCogsTowards(world, to.getPos(), stateTo, direction.getOpposite());

		// Belt <-> Belt
		if (from instanceof BeltTileEntity && to instanceof BeltTileEntity && !connectedByAxis) {
			return ((BeltTileEntity) from).getController().equals(((BeltTileEntity) to).getController()) ? 1 : 0;
		}

		// Gearbox <-> Gearbox
		if (from instanceof GearboxTileEntity && to instanceof GearboxTileEntity)
			return 0;

		// Axis <-> Axis
		if (connectedByAxis) {
			return getAxisModifier(from, direction) * getAxisModifier(to, direction.getOpposite());
		}

		// Attached Encased Belts
		if (AllBlocks.ENCASED_BELT.typeOf(stateFrom) && AllBlocks.ENCASED_BELT.typeOf(stateTo)) {
			boolean connected = stateFrom.get(EncasedBeltBlock.CONNECTED) && stateTo.get(EncasedBeltBlock.CONNECTED)
					&& stateFrom.get(EncasedBeltBlock.CONNECTED_FACE) == direction
					&& stateTo.get(EncasedBeltBlock.CONNECTED_FACE) == direction.getOpposite();
			return connected ? 1 : 0;
		}
		
		// Attached Fans
		if (AllBlocks.ENCASED_FAN.typeOf(stateFrom) && AllBlocks.ENCASED_FAN.typeOf(stateTo)) {
			if (stateFrom.get(BlockStateProperties.AXIS) == stateTo.get(BlockStateProperties.AXIS))
				return 1;
		}

		// Gear <-> Large Gear
		if (isLargeToSmallGear(stateFrom, stateTo, diff))
			return -2f;
		if (isLargeToSmallGear(stateTo, stateFrom, diff))
			return -.5f;

		// Gear <-> Gear
		if (connectedByGears) {
			if (diff.manhattanDistance(BlockPos.ZERO) != 1)
				return 0;
			if (AllBlocks.LARGE_COGWHEEL.typeOf(stateTo))
				return 0;
			if (stateFrom.get(axisProperty) == stateTo.get(axisProperty))
				return -1;
		}

		return 0;
	}

	private static float getAxisModifier(KineticTileEntity te, Direction direction) {
		if (!te.hasSource())
			return 1;
		Direction source = te.getSourceFacing();

		if (te instanceof GearboxTileEntity)
			return direction.getAxis() == source.getAxis() ? direction == source ? 1 : -1
					: direction.getAxisDirection() == source.getAxisDirection() ? -1 : 1;

		if (te instanceof SplitShaftTileEntity)
			return ((SplitShaftTileEntity) te).getRotationSpeedModifier(direction);

		return 1;
	}

	private static boolean isLargeToSmallGear(BlockState from, BlockState to, final BlockPos diff) {
		if (!AllBlocks.LARGE_COGWHEEL.typeOf(from) || !AllBlocks.COGWHEEL.typeOf(to))
			return false;
		Axis axisFrom = from.get(BlockStateProperties.AXIS);
		if (axisFrom != to.get(BlockStateProperties.AXIS))
			return false;
		if (axisFrom.getCoordinate(diff.getX(), diff.getY(), diff.getZ()) != 0)
			return false;
		for (Axis axis : Axis.values()) {
			if (axis == axisFrom)
				continue;
			if (Math.abs(axis.getCoordinate(diff.getX(), diff.getY(), diff.getZ())) != 1)
				return false;
		}
		return true;
	}

	/**
	 * Insert the added position to the kinetic network.
	 * 
	 * @param worldIn
	 * @param pos
	 */
	public static void handleAdded(World worldIn, BlockPos pos, KineticTileEntity addedTE) {
		if (worldIn.isRemote)
			return;
		if (!worldIn.isAreaLoaded(pos, 1))
			return;

		if (addedTE.getSpeed() != 0) {
			propagateNewSource(addedTE);
			return;
		}

		for (KineticTileEntity neighbourTE : getConnectedNeighbours(addedTE)) {
			final float speedModifier = getRotationSpeedModifier(neighbourTE, addedTE);

			if (neighbourTE.getSpeed() == 0)
				continue;
			if (neighbourTE.hasSource() && neighbourTE.getSource().equals(addedTE.getPos())) {
				addedTE.setSpeed(neighbourTE.getSpeed() * speedModifier);
				addedTE.onSpeedChanged();
				addedTE.sendData();
				continue;
			}

			addedTE.setSpeed(neighbourTE.getSpeed() * speedModifier);
			addedTE.setSource(neighbourTE.getPos());
			addedTE.onSpeedChanged();
			addedTE.sendData();
			propagateNewSource(addedTE);
			return;
		}
	}

	/**
	 * Search for sourceless networks attached to the given entity and update them.
	 * 
	 * @param updateTE
	 */
	private static void propagateNewSource(KineticTileEntity updateTE) {
		BlockPos pos = updateTE.getPos();
		World world = updateTE.getWorld();

		for (KineticTileEntity neighbourTE : getConnectedNeighbours(updateTE)) {
			final float newSpeed = updateTE.getSpeed() * getRotationSpeedModifier(updateTE, neighbourTE);

			if ((neighbourTE.isSource())
					|| neighbourTE.hasSource() && !neighbourTE.getSource().equals(updateTE.getPos())) {
				if (neighbourTE.getSpeed() != newSpeed) {
					world.destroyBlock(pos, true);
					return;
				}
				continue;
			}

			if (neighbourTE.getSpeed() == newSpeed)
				continue;

			neighbourTE.setSpeed(newSpeed);
			neighbourTE.setSource(updateTE.getPos());
			neighbourTE.onSpeedChanged();
			neighbourTE.sendData();
			propagateNewSource(neighbourTE);

		}
	}

	/**
	 * Remove the given entity from the network.
	 * 
	 * @param worldIn
	 * @param pos
	 * @param removedTE
	 */
	public static void handleRemoved(World worldIn, BlockPos pos, KineticTileEntity removedTE) {
		if (worldIn.isRemote)
			return;
		if (removedTE == null)
			return;
		if (removedTE.getSpeed() == 0)
			return;

		for (BlockPos neighbourPos : getPotentialNeighbourLocations(removedTE)) {
			BlockState neighbourState = worldIn.getBlockState(neighbourPos);
			if (!(neighbourState.getBlock() instanceof IRotate))
				continue;

			final KineticTileEntity neighbourTE = (KineticTileEntity) worldIn.getTileEntity(neighbourPos);
			if (!neighbourTE.hasSource() || !neighbourTE.getSource().equals(pos) || neighbourTE.isSource())
				continue;

			propagateMissingSource(neighbourTE);
		}

	}

	/**
	 * Clear the entire subnetwork depending on the given entity and find a new
	 * source
	 * 
	 * @param updateTE
	 */
	private static void propagateMissingSource(KineticTileEntity updateTE) {
		final World world = updateTE.getWorld();

		List<KineticTileEntity> potentialNewSources = new LinkedList<>();
		List<BlockPos> frontier = new LinkedList<>();
		frontier.add(updateTE.getPos());

		while (!frontier.isEmpty()) {
			final BlockPos pos = frontier.remove(0);
			final KineticTileEntity currentTE = (KineticTileEntity) world.getTileEntity(pos);

			currentTE.removeSource();
			currentTE.sendData();

			for (KineticTileEntity neighbourTE : getConnectedNeighbours(currentTE)) {
				if (neighbourTE.isSource()) {
					potentialNewSources.add(neighbourTE);
					continue;
				}

				if (!neighbourTE.hasSource())
					continue;

				if (!neighbourTE.getSource().equals(pos)) {
					potentialNewSources.add(neighbourTE);
					continue;
				}

				frontier.add(neighbourTE.getPos());
			}
		}

		for (KineticTileEntity newSource : potentialNewSources) {
			if (newSource.hasSource() || newSource.isSource()) {
				propagateNewSource(newSource);
				return;
			}
		}
	}

	private static KineticTileEntity findConnectedNeighbour(KineticTileEntity te, BlockPos neighbourPos) {
		BlockState neighbourState = te.getWorld().getBlockState(neighbourPos);
		if (!(neighbourState.getBlock() instanceof IRotate))
			return null;
		if (!neighbourState.hasTileEntity())
			return null;

		KineticTileEntity neighbour = (KineticTileEntity) te.getWorld().getTileEntity(neighbourPos);
		if (getRotationSpeedModifier(te, neighbour) == 0)
			return null;
		return neighbour;
	}

	private static List<KineticTileEntity> getConnectedNeighbours(KineticTileEntity te) {
		List<KineticTileEntity> neighbours = new LinkedList<>();
		for (BlockPos neighbourPos : getPotentialNeighbourLocations(te)) {
			final KineticTileEntity neighbourTE = findConnectedNeighbour(te, neighbourPos);
			if (neighbourTE == null)
				continue;

			neighbours.add(neighbourTE);
		}
		return neighbours;
	}

	private static List<BlockPos> getPotentialNeighbourLocations(KineticTileEntity te) {
		List<BlockPos> neighbours = new LinkedList<>();

		if (!te.getWorld().isAreaLoaded(te.getPos(), 1))
			return neighbours;

		for (Direction facing : Direction.values())
			neighbours.add(te.getPos().offset(facing));

		// Some Blocks can interface diagonally
		BlockState blockState = te.getBlockState();
		if (AllBlocks.COGWHEEL.typeOf(blockState) || AllBlocks.LARGE_COGWHEEL.typeOf(blockState)
				|| AllBlocks.BELT.typeOf(blockState)) {
			Axis axis = ((IRotate) blockState.getBlock()).getRotationAxis(blockState);

			BlockPos.getAllInBox(new BlockPos(-1, -1, -1), new BlockPos(1, 1, 1)).forEach(offset -> {
				if (axis.getCoordinate(offset.getX(), offset.getY(), offset.getZ()) != 0)
					return;
				if (offset.distanceSq(0, 0, 0, false) != BlockPos.ZERO.distanceSq(1, 1, 0, false))
					return;
				neighbours.add(te.getPos().add(offset));
			});
		}

		return neighbours;
	}

}