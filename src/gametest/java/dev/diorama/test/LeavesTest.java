package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TileKey;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Trees on the map (opt-in: {@code leaves}; with Passable Foliage installed, {@code --mods
 * passable-foliage}, it checks the case that broke): eight trees, four cubes of leaves and three
 * beds of small plants on a lawn in the air. Leaves must draw as blocks whether or not a mod lets
 * players walk through them, and ferns, flowers and saplings must stay thin plants.
 */
final class LeavesTest {
	private LeavesTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		int x0 = 2400;
		int z0 = 2400;
		int y = 140;
		int centerX = x0 + 26;
		int centerZ = z0 + 26;
		// Go there first: fill does nothing where chunks aren't loaded.
		world.getServer().runCommand("tp @a " + centerX + " " + (y + 2) + " " + centerZ);
		context.waitFor(mc -> mc.player.blockPosition().getX() == centerX, 200);
		context.waitTicks(60);
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("fill " + x0 + " " + (y - 1) + " " + z0 + " " + (x0 + 52) + " " + (y - 1) + " " + (z0 + 52) + " minecraft:grass_block");
		String[] trees = {"oak", "fancy_oak", "birch", "spruce", "jungle_tree_no_vine", "acacia", "cherry", "azalea_tree"};
		for (int i = 0; i < trees.length; i++) {
			world.getServer().runCommand("place feature minecraft:" + trees[i] + " " + (x0 + 6 + (i % 4) * 12) + " " + y + " " + (z0 + 8 + (i / 4) * 14));
		}
		// Plain cubes of leaves, as a tree's crown looks from above.
		String[] leaves = {"oak_leaves", "birch_leaves", "spruce_leaves", "dark_oak_leaves"};
		for (int i = 0; i < leaves.length; i++) {
			int x = x0 + 4 + i * 12;
			world.getServer().runCommand("fill " + x + " " + y + " " + (z0 + 36) + " " + (x + 4) + " " + (y + 4) + " " + (z0 + 40) + " minecraft:" + leaves[i] + "[persistent=true]");
		}
		String[] plants = {"fern", "poppy", "oak_sapling"};
		for (int i = 0; i < plants.length; i++) {
			int x = x0 + 4 + i * 12;
			world.getServer().runCommand("fill " + x + " " + y + " " + (z0 + 45) + " " + (x + 6) + " " + y + " " + (z0 + 49) + " minecraft:" + plants[i]);
		}
		context.waitTicks(40);
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(centerX, Projection.v(y, centerZ), 6.0, TileKey.SURFACE);
			}
		});
		settle(context, 80);
		context.takeScreenshot("leaves-01-map");
		context.setScreen(() -> null);

		String wrong = context.computeOnClient(mc -> {
			BlockLooks looks = BlockLooks.get();
			StringBuilder out = new StringBuilder();
			int passable = 0;
			for (Block block : BuiltInRegistries.BLOCK) {
				BlockState state = block.defaultBlockState();
				if (!state.is(BlockTags.LEAVES)) {
					continue;
				}
				if (state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
					passable++;
				}
				if (looks.kind[looks.lookId(state)] != BlockLooks.SOLID) {
					out.append(' ').append(BuiltInRegistries.BLOCK.getKey(block)).append(" is kind ").append(looks.kind[looks.lookId(state)]).append(';');
				}
				// Under water a walk-through block would otherwise count as the water itself.
				if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
					byte wet = looks.kind[looks.lookId(state.setValue(BlockStateProperties.WATERLOGGED, true))];
					if (wet != BlockLooks.SOLID) {
						out.append(" waterlogged ").append(BuiltInRegistries.BLOCK.getKey(block)).append(" is kind ").append(wet).append(';');
					}
				}
			}
			dev.diorama.Diorama.LOGGER.info("leaves: {} kinds of leaves can be walked through", passable);
			if (passable == 0 && installed("snownee.passablefoliage.PassableFoliage")) {
				out.append(" Passable Foliage is installed, but no leaves can be walked through, so its case wasn't checked;");
			}
			for (Block block : new Block[] {Blocks.FERN, Blocks.POPPY, Blocks.OAK_SAPLING}) {
				byte kind = looks.kind[looks.lookId(block.defaultBlockState())];
				if (kind != BlockLooks.PLANT) {
					out.append(' ').append(BuiltInRegistries.BLOCK.getKey(block)).append(" is kind ").append(kind).append(", no longer a thin plant;");
				}
			}
			return out.toString();
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("Leaves must draw as blocks (kind " + BlockLooks.SOLID + ") and small plants as thin plants (kind " + BlockLooks.PLANT + "):" + wrong);
		}
	}

	private static boolean installed(String className) {
		try {
			Class.forName(className, false, LeavesTest.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/** Waits until no tile has been baking for a while. */
	private static void settle(ClientGameTestContext context, int minTicks) {
		context.waitTicks(minTicks);
		try (WorldPause paused = WorldPause.pause(context)) {
			int quiet = 0;
			for (int i = 0; i < 1200 && quiet < 30; i++) {
				context.waitTick();
				boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
				quiet = baking ? 0 : quiet + 1;
			}
		}
	}
}
