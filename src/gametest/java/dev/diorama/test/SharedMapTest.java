package dev.diorama.test;

//? if >=1.21.4 && fabric {
import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.SavedChunk;
import dev.diorama.render.SavedChunkImport;
import dev.diorama.share.ShareClient;
import dev.diorama.share.SharedChunks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The shared map on the test API's server (#289): land a player spent time on far from where this
 * player is shows up on this player's map without them going there, land nobody visited doesn't,
 * and the blocks that came can't be read for ores.
 */
final class SharedMapTest {
	/** Chunks east of spawn a player spent time near, and as far west, ones only generated. */
	private static final int VISITED_X = 41;
	private static final int UNVISITED_X = -41;
	private static final int RADIUS = 3;

	private SharedMapTest() {
	}

	static void run(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("shared-map"))
			.createServer(DioramaClientTest.serverProperties())) {
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				for (int cx = -RADIUS; cx <= RADIUS; cx++) {
					for (int cz = -RADIUS; cz <= RADIUS; cz++) {
						// As if a player had stood there for a minute.
						level.getChunk(VISITED_X + cx, cz).setInhabitedTime(1200);
						level.getChunk(UNVISITED_X + cx, cz);
					}
				}
				// A diamond in a box of stone, where the sections that are sent reach: no ray can get to it.
				LevelChunk chunk = level.getChunk(VISITED_X, 0);
				int minSolid = Integer.MAX_VALUE;
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						minSolid = Math.min(minSolid, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
					}
				}
				int y = Math.max(chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, 8, 8) - 3, minSolid - 24 + 3);
				BlockPos ore = new BlockPos(VISITED_X * 16 + 8, y, 8);
				for (BlockPos at : BlockPos.betweenClosed(ore.offset(-1, -1, -1), ore.offset(1, 1, 1))) {
					level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
				}
				level.setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
				s.saveEverything(true, true, true);
			});
			int ground = server.computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, VISITED_X * 16 + 8, 8));
			try (TestDedicatedServerConnection connection = server.connect()) {
				long joined = System.nanoTime();
				context.waitFor(mc -> ShareClient.sharing() && MapSession.get() != null && MapSession.get().savedChunks() != null, 1200);
				if (context.computeOnClient(mc -> MapSession.get().map().chunk(VISITED_X, 0) != null)) {
					throw new AssertionError("The client loaded the visited chunks itself: this wouldn't show sharing");
				}
				context.setScreen(MapScreen::new);
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(VISITED_X * 16 + 8, Projection.v(ground, 8), 2, TileKey.SURFACE));
				context.waitFor(mc -> job().drewChunk(VISITED_X, 0), 12000);
				Diorama.LOGGER.info("Shared land drawn {} s after joining", Math.round((System.nanoTime() - joined) / 1e9));
				context.waitTicks(40);
				context.takeScreenshot("shared-map-visited");
				if (context.computeOnClient(mc -> job().drewChunk(UNVISITED_X, 0))) {
					throw new AssertionError("Land nobody visited was drawn from the server");
				}
				TileKey tile = new TileKey(TileKey.SURFACE, Math.floorDiv(VISITED_X * 16 + 8, Projection.TILE_UNITS), (int) Math.floor(Projection.v(ground, 8) / Projection.TILE_UNITS));
				int[] pixels = context.computeOnClient(mc -> MapSession.get().map().savedMap().read(tile));
				int shown = 0;
				for (int pixel : pixels == null ? new int[0] : pixels) {
					shown += (pixel >>> 24) != 0 ? 1 : 0;
				}
				if (shown == 0) {
					throw new AssertionError("Tile " + tile + " over the shared land shows nothing");
				}
				// What came is already here: read it on the test thread.
				SavedChunk shared = context.computeOnClient(mc -> SharedChunks.current()).read(VISITED_X, 0);
				if (shared == null) {
					throw new AssertionError("The shared chunk that was drawn can't be read again");
				}
				List<Integer> sections = sent(shared);
				int hiddenOresOnServer = server.computeOnServer(s -> hiddenOres(s.overworld().getChunk(VISITED_X, 0), sections));
				int hiddenOresSent = hiddenOres(shared);
				Diorama.LOGGER.info("Sections {} came; ores no ray reaches in them: {} on the server, {} in what came", sections, hiddenOresOnServer, hiddenOresSent);
				if (hiddenOresOnServer == 0) {
					throw new AssertionError("The diamond in stone isn't in the sections that came, so this checks nothing");
				}
				if (hiddenOresSent != 0) {
					throw new AssertionError(hiddenOresSent + " ores no ray can reach came with the shared chunk");
				}

				// The same land drawn by this player's own map once they go there: shared land is
				// drawn as their own would be, so where one meets the other there's no seam.
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(VISITED_X * 16 + 8, Projection.v(ground, 8), 2, TileKey.SURFACE));
				int[] sharedPixels = context.computeOnClient(mc -> MapSession.get().map().savedMap().read(tile)).clone();
				context.setScreen(() -> null);
				server.runCommand("tp @a " + (VISITED_X * 16 + 8) + " " + (ground + 20) + " 8");
				context.waitFor(mc -> MapSession.get().map().chunk(VISITED_X, 0) != null, 2400);
				DioramaClientTest.settle(context, 200);
				context.setScreen(MapScreen::new);
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(VISITED_X * 16 + 8, Projection.v(ground, 8), 2, TileKey.SURFACE));
				context.waitTicks(40);
				context.takeScreenshot("shared-map-then-walked");
				int[] ownPixels = context.computeOnClient(mc -> MapSession.get().map().savedMap().read(tile));
				long sum = 0;
				int both = 0;
				int far = 0;
				for (int i = 0; i < sharedPixels.length; i++) {
					if ((sharedPixels[i] >>> 24) != 0xFF || (ownPixels[i] >>> 24) != 0xFF) {
						continue;
					}
					int d = 0;
					for (int shift = 0; shift < 24; shift += 8) {
						d = Math.max(d, Math.abs(((sharedPixels[i] >> shift) & 0xFF) - ((ownPixels[i] >> shift) & 0xFF)));
					}
					sum += d;
					both++;
					far += d > 16 ? 1 : 0;
				}
				double mean = both == 0 ? 0 : (double) sum / both;
				Diorama.LOGGER.info("Shared vs own drawing of tile {}: {} pixels in both, mean difference {}/255, {} pixels over 16", tile, both, String.format(java.util.Locale.ROOT, "%.2f", mean), far);
				if (both < 1000) {
					throw new AssertionError("Too few pixels drawn both ways to compare: " + both);
				}
				// The GPU baker draws a hair differently from run to run (0.24 measured); a seam would be far more.
				if (mean > 1.0 || far > both / 1000) {
					throw new AssertionError("Shared land is drawn differently from the player's own: mean difference " + mean + "/255, " + far + " pixels over 16");
				}
				context.setScreen(() -> null);
			}
		}
	}

	private static SavedChunkImport job() {
		return MapSession.get().savedChunks();
	}

	/** The indexes of the sections that came with a shared chunk. */
	private static List<Integer> sent(SavedChunk chunk) {
		List<Integer> sent = new ArrayList<>();
		for (int s = 0; s < chunk.states().length; s++) {
			if (chunk.states()[s] != null) {
				sent.add(s);
			}
		}
		return sent;
	}

	/** Ores no ray can reach, inside the given sections of the chunk as the server has it. */
	private static int hiddenOres(LevelChunk chunk, List<Integer> sections) {
		int count = 0;
		for (int s : sections) {
			count += hiddenOres(chunk.getSections()[s].getStates());
		}
		return count;
	}

	/** The same, in what came. */
	private static int hiddenOres(SavedChunk chunk) {
		int count = 0;
		for (int s : sent(chunk)) {
			count += hiddenOres(chunk.states()[s]);
		}
		return count;
	}

	/** Ores inside one section with an opaque full block on every side. */
	private static int hiddenOres(PalettedContainer<BlockState> states) {
		int count = 0;
		for (int y = 1; y < 15; y++) {
			for (int z = 1; z < 15; z++) {
				for (int x = 1; x < 15; x++) {
					if (states.get(x, y, z).getBlock().getDescriptionId().endsWith("_ore") && opaque(states, x, y, z)) {
						count++;
					}
				}
			}
		}
		return count;
	}

	private static boolean opaque(PalettedContainer<BlockState> states, int x, int y, int z) {
		int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int[] side : sides) {
			if (!states.get(x + side[0], y + side[1], z + side[2]).isSolidRender()) {
				return false;
			}
		}
		return true;
	}
}
//?}
