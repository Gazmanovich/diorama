package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//? if >=1.21.4 && fabric {
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A house built on a server shows on the map even when the map's rays down to its roof cross
 * chunks the server never sends while the player stands by it ({@code unsent-chunks}, #272).
 *
 * <p>Rays start at the top of the tallest chunk around a tile, so a tall pillar beside the house
 * sends the rays down to its roof through chunks five or six south of it. With the server's view
 * distance at 4, those are only sent while the player stands south of the house. The ground there
 * gets mapped from the south first, then the player comes back in a new session north of it and
 * the house goes up. The rays' path through the unsent chunks is air in the saved map, and the
 * map has to say so instead of keeping the ground it saved before.
 */
final class UnsentChunksTest {
	private static final int SIZE = 7;
	/** Above the trees around, so none shades the roof. */
	private static final int WALL = 10;

	private UnsentChunksTest() {
	}

	//? if >=1.21.4 && fabric {
	static void run(ClientGameTestContext context) {
		java.util.Properties properties = DioramaClientTest.serverProperties();
		properties.setProperty("view-distance", "4");
		properties.setProperty("simulation-distance", "4");
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama-unsent"))
			.createServer(properties)) {
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			int x0 = 8;
			int z0 = 8;
			int ground = Integer.MIN_VALUE;
			for (int dx = -1; dx <= SIZE; dx++) {
				for (int dz = -1; dz <= SIZE; dz++) {
					ground = Math.max(ground, surface(server, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + dx, z0 + dz));
				}
			}
			int roofY = ground + WALL;
			int midX = x0 + SIZE / 2;
			int midZ = z0 + SIZE / 2;
			// Beside the house in the same tile, out of its light, and far above it: every ray of the
			// tile starts up there.
			int pillarX = midX + 20;
			int pillarZ = midZ - 40;
			int pillarFoot = surface(server, Heightmap.Types.WORLD_SURFACE, pillarX, pillarZ);
			int siteFloor = ground;
			// Set here rather than with fill, which refuses chunks nobody is near.
			server.runOnServer(s -> {
				var level = s.overworld();
				for (int y = pillarFoot; y <= roofY + 100; y++) {
					for (int i = 0; i < 4; i++) {
						level.setBlockAndUpdate(new BlockPos(pillarX + (i & 1), y, pillarZ + (i >> 1)), Blocks.STONE.defaultBlockState());
					}
				}
				for (int x = x0 - 1; x <= x0 + SIZE; x++) {
					for (int z = z0 - 1; z <= z0 + SIZE; z++) {
						for (int y = siteFloor; y <= roofY + 12; y++) {
							level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
						}
					}
				}
			});
			int before;
			try (TestDedicatedServerConnection connection = server.connect()) {
				// South of the site, with every chunk the rays cross sent: the ground gets mapped.
				teleport(server, midX, midZ + 48);
				waitForMap(context);
				before = roofPixel(context, midX, roofY, midZ);
				// North of it, where the player leaves, so the next session starts there.
				teleport(server, midX, midZ - 32);
				waitForMap(context);
			}
			try (TestDedicatedServerConnection connection = server.connect()) {
				// A new session north of the site: the chunks south of it never arrive.
				waitForMap(context);
				// Where the rays down to the roof start, at the pillar's height.
				int startChunkZ = (int) Math.floor(midZ + 100 * Projection.cos() / Projection.sin()) >> 4;
				boolean sent = context.computeOnClient(mc -> mc.level.getChunkSource().getChunk(midX >> 4, startChunkZ, ChunkStatus.FULL, false) != null);
				if (sent) {
					throw new AssertionError("The chunk the roof's rays start in was sent: the test no longer stands for #272");
				}
				server.runCommand("fill " + x0 + " " + ground + " " + z0 + " " + (x0 + SIZE - 1) + " " + roofY + " " + (z0 + SIZE - 1) + " red_concrete");
				context.waitTicks(100);
				DioramaClientTest.settle(context, 20);
				int after = roofPixel(context, midX, roofY, midZ);
				for (int i = 0; i < 60 && !red(after); i++) {
					context.waitTicks(20);
					after = roofPixel(context, midX, roofY, midZ);
				}
				Diorama.LOGGER.info("unsent-chunks: roof spot {} before the house, {} after", String.format("%08x", before), String.format("%08x", after));
				context.setScreen(MapScreen::new);
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(midX + 0.5, midZ + 0.5),
					Projection.v(midX + 0.5, roofY + 1, midZ + 0.5), 2, TileKey.SURFACE));
				DioramaClientTest.settle(context, 40);
				context.takeScreenshot("unsent-chunks");
				context.setScreen(() -> null);
				if (!red(after)) {
					throw new AssertionError("A house built where the map's rays cross chunks the server hasn't sent didn't reach the map: the roof shows "
						+ String.format("%08x", after) + ", the ground before it " + String.format("%08x", before));
				}
			}
		}
	}

	private static void teleport(TestDedicatedServerContext server, int x, int z) {
		server.runCommand("tp @a " + x + " " + surface(server, Heightmap.Types.MOTION_BLOCKING, x, z) + " " + z);
	}

	/** The first open y above a column, generating its chunk if it has to. */
	private static int surface(TestDedicatedServerContext server, Heightmap.Types type, int x, int z) {
		return server.computeOnServer(s -> s.overworld().getChunk(x >> 4, z >> 4).getHeight(type, x & 15, z & 15) + 1);
	}

	//?}

	private static void waitForMap(ClientGameTestContext context) {
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 60, 2400);
		context.waitTicks(100);
		DioramaClientTest.settle(context, 20);
	}

	private static int roofPixel(ClientGameTestContext context, int x, int y, int z) {
		return context.computeOnClient(mc -> pixelAt(x, y, z));
	}

	/** The saved map's pixel on the middle of a roof's top. Render thread. */
	private static int pixelAt(int x, int y, int z) {
		if (MapSession.get() == null || MapSession.get().map().savedMap() == null) {
			return 0;
		}
		double u = Projection.u(x + 0.5, z + 0.5);
		double v = Projection.v(x + 0.5, y + 1, z + 0.5);
		TileKey tile = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		int px = Math.floorMod((int) Math.floor(u * Projection.PIXELS_PER_UNIT), Projection.TILE_PIXELS);
		int py = Math.floorMod((int) Math.floor(v * Projection.PIXELS_PER_UNIT), Projection.TILE_PIXELS);
		int[] pixels = MapSession.get().map().savedMap().read(tile);
		return pixels == null ? 0 : pixels[py * Projection.TILE_PIXELS + px];
	}

	/** Red concrete, which no ground nearby is. */
	private static boolean red(int abgr) {
		if ((abgr >>> 24) == 0) {
			return false;
		}
		int r = abgr & 0xFF;
		int g = abgr >> 8 & 0xFF;
		int b = abgr >> 16 & 0xFF;
		return r >= 90 && r > 2 * g && r > 2 * b;
	}
}
