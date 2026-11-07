package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.render.SavedChunkImport;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.server.MinecraftServer;

/**
 * Leaving a world while the map is filling in from its save ({@code leave-during-fill}): the world
 * closes with the fill's work unfinished, opens again at once, and the old fill's threads end
 * while the new one starts, round after round. Each round the server makes ground the fill hasn't
 * drawn, out of the player's sight as a pre-generation tool would, the player goes somewhere new,
 * and the world is left once the fill has drawn a tile since.
 */
final class LeaveDuringFillTest {
	private static final int ROUNDS = 6;
	/** Chunks a side of the ground made each round: as many as one forceload takes, many tiles' worth, so the fill has work left when the world is left. */
	private static final int CHUNKS = 16;

	private LeaveDuringFillTest() {
	}

	static void run(ClientGameTestContext context) {
		TestWorldSave save = null;
		SavedChunkImport left = null;
		int withWorkLeft = 0;
		for (int round = 0; round < ROUNDS; round++) {
			SavedChunkImport job;
			long leaving;
			try (TestSingleplayerContext world = save == null
				? context.worldBuilder().adjustSettings(s -> {
					s.setName("Diorama Leave Fill");
					s.setSeed("leave-during-fill");
					// No fall from the teleports' height to die of.
					s.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE);
				}).create()
				: save.open()) {
				save = world.getWorldSave();
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().savedChunks() != null, 2400);
				if (left != null) {
					// Opened again straight away: the fill from before ends while this one starts.
					SavedChunkImport previous = left;
					context.waitFor(mc -> previous.finished(), 1200);
				}
				int x = 4000 + round * 1500;
				int z = 4000;
				// Somewhere new, as the 0.8.0 smoke test did before leaving, so the map has new ground to
				// draw live too.
				world.getServer().runCommand("tp @a " + x + " 200 " + z);
				DioramaClientTest.waitUntilGenerated(context, world);
				// On a chunk's corner. The fill only learns of it at its next listing, below.
				makeUnseenGround(context, world, (x + 320) & ~15, z);
				// All generated before leaving, which Minecraft 1.21.1 needs.
				DioramaClientTest.waitUntilGenerated(context, world);
				// The fill lists the save, and the world is left on its first tile since, or once it
				// has nothing to do, if its listing every minute came first and drew it all.
				SavedChunkImport filling = context.computeOnClient(mc -> MapSession.get().savedChunks());
				int before = filling.drawnCount();
				context.runOnClient(mc -> filling.rescanSoon());
				context.waitFor(mc -> filling.drawnCount() > before || filling.idle(), 6000);
				job = filling;
				leaving = System.nanoTime();
			}
			long closeMs = (System.nanoTime() - leaving) / 1_000_000;
			int chunksLeft = job.chunksLeft();
			Diorama.LOGGER.info("Leave during fill, round {}: left in {} ms with {} chunks left to draw", round, closeMs, chunksLeft);
			withWorkLeft += chunksLeft > 0 ? 1 : 0;
			left = job;
		}
		if (withWorkLeft < ROUNDS / 2) {
			throw new AssertionError("Only " + withWorkLeft + " of " + ROUNDS + " rounds left the world with the fill's work unfinished");
		}
		// Leaving is noticed on the next tick, which stops the fill; its tile in progress may finish.
		SavedChunkImport last = left;
		context.waitFor(mc -> last.finished(), 1200);
	}

	/** Has the server make and save a square of chunks the client never loads, then lets them go. */
	private static void makeUnseenGround(ClientGameTestContext context, TestSingleplayerContext world, int x, int z) {
		int last = CHUNKS * 16 - 1;
		world.getServer().runCommand("forceload add " + x + " " + z + " " + (x + last) + " " + (z + last));
		for (int i = 0; !world.getServer().computeOnServer(server -> loaded(server, x, z)); i++) {
			if (i > 2400) {
				throw new AssertionError("The forced chunks never loaded");
			}
			context.waitTick();
		}
		world.getServer().runCommand("forceload remove all");
		for (int i = 0; world.getServer().computeOnServer(server -> server.overworld().getChunkSource().getChunkNow(x >> 4, z >> 4) != null); i++) {
			if (i > 2400) {
				throw new AssertionError("The forced chunks never unloaded");
			}
			context.waitTick();
		}
		world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
	}

	private static boolean loaded(MinecraftServer server, int x, int z) {
		for (int dx = 0; dx < CHUNKS; dx++) {
			for (int dz = 0; dz < CHUNKS; dz++) {
				if (server.overworld().getChunkSource().getChunkNow((x >> 4) + dx, (z >> 4) + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}
}
