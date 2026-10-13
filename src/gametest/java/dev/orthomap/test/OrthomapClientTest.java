package dev.orthomap.test;

import dev.orthomap.MapScreen;
import dev.orthomap.MapSession;
import dev.orthomap.Projection;
import dev.orthomap.bake.TileKey;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;

/**
 * Not an assertion test: it drives the real game through the map's views and saves screenshots,
 * so a person can look at what the map actually draws.
 */
public class OrthomapClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1600, 900);
		context.runOnClient(mc -> mc.options.renderDistance().set(12));
		String seed = System.getProperty("orthomap.seed", "orthomap");
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed(seed);
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			String tp = System.getProperty("orthomap.tp");
			if (tp != null) {
				world.getServer().runCommand("tp @a " + tp);
			}

			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
			settle(context, 100);
			context.takeScreenshot("orthomap-01-minimap");

			context.setScreen(MapScreen::new);
			settle(context, 60);
			context.takeScreenshot("orthomap-02-map");

			lookAtPlayer(context, 5.0, TileKey.SURFACE);
			settle(context, 40);
			context.takeScreenshot("orthomap-03-map-close");

			lookAtPlayer(context, 0.75, TileKey.SURFACE);
			settle(context, 80);
			context.takeScreenshot("orthomap-04-map-far");

			if ("1".equals(System.getenv("ORTHOMAP_GIF"))) {
				// Frames for a GIF: a slow pan east across the edge of the loaded area, so the fog's
				// drift and the zoomed-out map show in motion.
				for (int frame = 0; frame < 60; frame++) {
					double offset = 90 + frame * 1.5;
					context.runOnClient(mc -> {
						if (mc.gui.screen() instanceof MapScreen screen && mc.player != null) {
							screen.lookAt(mc.player.getX() + offset, Projection.v(mc.player.getY(), mc.player.getZ()), 1.4, TileKey.SURFACE);
						}
					});
					context.waitTicks(3);
					context.takeScreenshot(String.format("orthomap-gif-%03d", frame));
				}
			}

			// A resource reload (F3+T) must rebuild block colours and re-bake what is on screen.
			context.runOnClient(Minecraft::reloadResourcePacks);
			context.waitTicks(60);
			settle(context, 40);
			context.takeScreenshot("orthomap-04b-after-reload");

			int caveY = Integer.getInteger("orthomap.caveY", 24);
			lookAtPlayer(context, 2.0, caveY);
			settle(context, 80);
			context.takeScreenshot("orthomap-05-cave");

			lookAtPlayer(context, 5.0, caveY);
			settle(context, 40);
			context.takeScreenshot("orthomap-06-cave-close");
			context.setScreen(() -> null);

			// Fog of war: the server sends Xaero's fair-play code, then the player explores a cave.
			BlockPos cave = world.getServer().computeOnServer(server -> findCave(server.overworld(), new BlockPos(0, 0, 0)));
			if (cave == null) {
				throw new AssertionError("No cave found near spawn");
			}
			world.getServer().runCommand("tellraw @a \"§f§a§i§r§x§a§e§r§o\"");
			world.getServer().runCommand("tp @a " + cave.getX() + " " + cave.getY() + " " + cave.getZ());
			settle(context, 80);
			// Walk around a little so more of the cave gets seen.
			for (float yaw : new float[] {0, 90, 180, 270}) {
				context.getInput().lookAt(yaw, 10);
				context.getInput().holdKeyFor(options -> options.keyUp, 40);
			}
			settle(context, 40);
			context.takeScreenshot("orthomap-07-fog-of-war-minimap");

			context.setScreen(MapScreen::new);
			lookAtPlayer(context, 3.0, Integer.MIN_VALUE);
			context.runOnClient(mc -> {
				if (mc.gui.screen() instanceof MapScreen screen && mc.player != null) {
					screen.lookAt(mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ()), 3.0, MapSession.caveLayerFor(mc.player.getY()));
				}
			});
			settle(context, 40);
			context.takeScreenshot("orthomap-08-fog-of-war-map");
			context.setScreen(() -> null);
		}
	}

	/** An open spot underground near {@code near}: three blocks of air over a solid floor, no sky light. */
	private static BlockPos findCave(ServerLevel level, BlockPos near) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int r = 0; r < 64; r += 2) {
			for (int dx = -r; dx <= r; dx += 2) {
				for (int dz = -r; dz <= r; dz += 2) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					for (int y = 40; y > 0; y--) {
						pos.set(near.getX() + dx, y, near.getZ() + dz);
						if (level.getBlockState(pos.below()).isSolid()
							&& level.getBlockState(pos).isAir()
							&& level.getBlockState(pos.above()).isAir()
							&& level.getBlockState(pos.above(2)).isAir()
							&& level.getBrightness(LightLayer.SKY, pos) == 0
							&& level.getFluidState(pos.below()).isEmpty()) {
							return pos.immutable();
						}
					}
				}
			}
		}
		return null;
	}

	private static void lookAtPlayer(ClientGameTestContext context, double zoom, int layer) {
		context.runOnClient(mc -> {
			LocalPlayer player = mc.player;
			if (mc.gui.screen() instanceof MapScreen screen && player != null) {
				screen.lookAt(player.getX(), Projection.v(player.getY(), player.getZ()), zoom, layer);
			}
		});
	}

	/** Waits until no tile has been baking for a while. */
	private static void settle(ClientGameTestContext context, int minTicks) {
		context.waitTicks(minTicks);
		int quiet = 0;
		for (int i = 0; i < 1200 && quiet < 30; i++) {
			context.waitTick();
			boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
			quiet = baking ? 0 : quiet + 1;
		}
	}
}
