package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BiomeTints;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Grass at the edge of what the client has loaded is the colour of its own biome ({@code biome-edge},
 * #290). In a flat world that is savanna everywhere, with a short view distance and the chunks past
 * it filled in from the save, no column the map draws may take any other colour: the outermost
 * loaded chunks used to blend their tint against Plains for the neighbours the client never got,
 * a green strip along the edge of the loaded area.
 */
final class BiomeEdgeTest {
	private static final String DIMENSION = "diorama_test:savanna";
	/** Top of the grass: the flat layers start at the dimension's floor, y -64. */
	private static final int GRASS_Y = -60;
	private static final int VIEW = 4;

	private BiomeEdgeTest() {
	}

	static void run(ClientGameTestContext context) {
		TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
			save = world.getWorldSave();
		}
		writePack(save.getSaveDirectory());
		int distance = context.computeOnClient(mc -> mc.options.renderDistance().get());
		try (TestSingleplayerContext world = save.open()) {
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			context.runOnClient(mc -> mc.options.renderDistance().set(VIEW));
			// Every chunk east of the player saved whole, the loaded ones and those past the view.
			world.getServer().runCommand("execute in " + DIMENSION + " run forceload add 0 -112 255 127");
			world.getServer().runCommand("execute in " + DIMENSION + " run tp @a 8 -40 8");
			context.waitFor(mc -> mc.level.dimension() != Level.OVERWORLD && mc.gui.screen() == null
				&& MapSession.get() != null && MapSession.get().map().chunkCount() > 20, 2400);
			context.waitTicks(100);
			world.getServer().runCommand("execute in " + DIMENSION + " run forceload remove all");
			context.waitTicks(100);
			world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
			context.waitTicks(40);
			try (WorldPause paused = WorldPause.pause(context)) {
				context.runOnClient(mc -> MapSession.get().savedChunks().rescanSoon());
				context.waitFor(mc -> MapSession.get().savedChunks().idle(), 3600);
			}
			DioramaClientTest.settle(context, 40);

			int edge = context.computeOnClient(mc -> {
				int chunkX = 0;
				while (mc.level.getChunkSource().hasChunk(chunkX + 1, 0)) {
					chunkX++;
				}
				return chunkX;
			});
			context.setScreen(MapScreen::new);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(edge * 16 + 16, Projection.v(GRASS_Y, 8), 4, TileKey.SURFACE));
			DioramaClientTest.settle(context, 40);
			context.takeScreenshot("biome-edge-close");
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(edge * 16 + 16, Projection.v(GRASS_Y, 8), 1, TileKey.SURFACE));
			DioramaClientTest.settle(context, 40);
			context.takeScreenshot("biome-edge-wide");
			context.setScreen(() -> null);

			String wrong = context.computeOnClient(mc -> {
				var holder = mc.level.getBiome(mc.player.blockPosition());
				if (!holder.is(Biomes.SAVANNA)) {
					return "the data pack's dimension isn't savanna: " + holder;
				}
				Biome savanna = holder.value();
				int grass = BiomeColors.GRASS_COLOR_RESOLVER.getColor(savanna, 0, 0) & 0xFFFFFF;
				StringBuilder found = new StringBuilder();
				int chunks = 0;
				int off = 0;
				for (int cx = -VIEW - 2; cx <= edge + 1; cx++) {
					for (int cz = -VIEW - 2; cz <= VIEW + 2; cz++) {
						ChunkSnapshot chunk = MapSession.get().map().worldChunk(cx, cz);
						if (chunk == null) {
							continue;
						}
						chunks++;
						int[] tints = BiomeTints.of(chunk).grass();
						for (int i = 0; i < 256; i++) {
							if (tints[i] != grass) {
								if (off++ == 0) {
									found.append(String.format("chunk %d,%d column %d,%d is %06x, savanna is %06x; ", cx, cz, i & 15, i >> 4, tints[i], grass));
								}
							}
						}
					}
				}
				if (chunks < 20) {
					return "only " + chunks + " loaded chunks to check";
				}
				return off == 0 ? null : found.toString() + off + " columns of " + chunks + " chunks in all (edge chunk x " + edge + ")";
			});
			if (wrong != null) {
				throw new AssertionError("Grass at the edge of the loaded chunks isn't its biome's colour: " + wrong);
			}
		} finally {
			context.runOnClient(mc -> mc.options.renderDistance().set(distance));
		}
	}

	/** A flat savanna dimension, for the world's data packs folder. */
	private static void writePack(Path saveDirectory) {
		Path pack = saveDirectory.resolve("datapacks").resolve("savanna");
		try {
			Files.createDirectories(pack.resolve("data/diorama_test/dimension"));
			Files.writeString(pack.resolve("pack.mcmeta"),
				"{\"pack\":{\"description\":\"Savanna\",\"pack_format\":15,\"supported_formats\":[15,1000],\"min_format\":15,\"max_format\":1000}}");
			Files.writeString(pack.resolve("data/diorama_test/dimension/savanna.json"), """
				{"type": "minecraft:overworld", "generator": {"type": "minecraft:flat", "settings": {
					"biome": "minecraft:savanna", "lakes": false, "features": false, "structure_overrides": [],
					"layers": [{"block": "minecraft:bedrock", "height": 1}, {"block": "minecraft:dirt", "height": 3}, {"block": "minecraft:grass_block", "height": 1}]}}}
				""");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
