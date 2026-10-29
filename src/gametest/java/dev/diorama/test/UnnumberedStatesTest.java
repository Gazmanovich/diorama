package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.VoxelReader;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Block states the game never numbered, as some mods put in the world on the fly (Epic Fight's
 * cracked ground, #180): a column of them under the player is baked into the map and drawn in the
 * cave map's depth gauge, looking like the block they belong to, without an error.
 */
final class UnnumberedStatesTest {
	private UnnumberedStatesTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 40, 40));
		world.getServer().runCommand("tp @a 40.5 " + ground + " 40.5");
		context.waitFor(mc -> mc.player.blockPosition().getX() == 40 && mc.player.blockPosition().getZ() == 40, 600);
		context.waitTicks(20);
		// Gold block's states made again outside the registry, as a mod's own block states are. Gold,
		// so the map's copy of the ground can't pass for them before it has them.
		StateDefinition<Block, BlockState> definition = new StateDefinition.Builder<Block, BlockState>(Blocks.GOLD_BLOCK).create(Block::defaultBlockState, BlockState::new);
		// The shapes the game works out for registered states at startup; such mods do the same.
		definition.getPossibleStates().forEach(BlockState::initCache);
		BlockState unnumbered = definition.any();
		if (Block.BLOCK_STATE_REGISTRY.getId(unnumbered) >= 0) {
			throw new AssertionError("The made-up gold state has an id");
		}
		int top = context.computeOnClient(mc -> {
			BlockPos feet = mc.player.blockPosition();
			// Only on the client, as the mods do: the server never hears of these.
			for (int dy = 1; dy <= 24; dy++) {
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						mc.level.setBlock(feet.offset(dx, -dy, dz), unnumbered, 3);
					}
				}
			}
			return feet.getY() - 1;
		});
		context.waitTicks(60);
		// The cave map a few blocks down: its depth gauge reads the column under the player.
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(40.5, Projection.v(top - 8, 40.5), 3.0, MapSession.caveLayerFor(top - 8));
			}
		});
		settle(context, 60);
		if (context.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(40, top - 3, 40)) != unnumbered)) {
			throw new AssertionError("The client lost the unnumbered gold");
		}
		// The baker reads the column as gold, the block those states belong to, once the map has
		// taken its copy of the changed chunk.
		int gold = Block.BLOCK_STATE_REGISTRY.getId(Blocks.GOLD_BLOCK.defaultBlockState());
		int read = -2;
		for (int i = 0; i < 1200 && read != gold; i++) {
			context.waitTick();
			read = context.computeOnClient(mc -> new VoxelReader(MapSession.get().map(), BlockLooks.get()).state(40, top - 3, 40));
		}
		if (read != gold) {
			throw new AssertionError("The baker read the unnumbered gold as state " + read);
		}
		context.takeScreenshot("unnumbered-states-cave-map");
		context.setScreen(() -> null);
	}

	private static void settle(ClientGameTestContext context, int minTicks) {
		context.waitTicks(minTicks);
		int quiet = 0;
		for (int i = 0; i < 1200 && quiet < 30; i++) {
			context.waitTick();
			boolean baking = context.computeOnClient(mc -> MapSession.get() == null || MapSession.get().map().bakingCount() > 0);
			quiet = baking ? 0 : quiet + 1;
		}
	}
}
