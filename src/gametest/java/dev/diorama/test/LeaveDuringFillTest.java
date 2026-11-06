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
	/** Chunks a side of the ground made each round: more than one tile's worth, so the fill has work left when the world is left. */
	private static final int CHUNKS = 8;

	private LeaveDuringFillTest() {
	}

	static void run(ClientGameTestContext context) {
		TestWorldSave save = null;
		SavedChunkImport left = null;
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
				// On a chunk's corner, so the square forced is the square checked.
				makeUnseenGround(context, world, (x + 320) & ~15, z);
				int before = context.computeOnClient(mc -> MapSession.get().savedChunks().drawnCount());
				context.runOnClient(mc -> MapSession.get().savedChunks().rescanSoon());
				// Somewhere new, as the 0.8.0 smoke test did before leaving, so the map has new ground to
				// draw live too.
				world.getServer().runCommand("tp @a " + x + " 200 " + z);
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().savedChunks() != null
					&& MapSession.get().savedChunks().drawnCount() > before, 6000);
				// Generated before leaving, which Minecraft 1.21.1 needs (see waitUntilGenerated). The
				// fill has thousands of chunks left by then.
				DioramaClientTest.waitUntilGenerated(context, world);
				job = context.computeOnClient(mc -> MapSession.get().savedChunks());
				if (job.chunksLeft() == 0) {
					throw new AssertionError("Round " + round + ": the fill had nothing left to draw when the world was left");
				}
				leaving = System.nanoTime();
			}
			long closeMs = (System.nanoTime() - leaving) / 1_000_000;
			Diorama.LOGGER.info("Leave during fill, round {}: left in {} ms with {} chunks left to draw", round, closeMs, job.chunksLeft());
			left = job;
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
		for (int i = 0; world.getServer().computeOnServer(server -> anyLoaded(server, x, z)); i++) {
			if (i > 2400) {
				throw new AssertionError("The forced chunks never unloaded");
			}
			context.waitTick();
		}
		world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
	}

	private static boolean anyLoaded(MinecraftServer server, int x, int z) {
		for (int dx = 0; dx < CHUNKS; dx++) {
			for (int dz = 0; dz < CHUNKS; dz++) {
				if (server.overworld().getChunkSource().getChunkNow((x >> 4) + dx, (z >> 4) + dz) != null) {
					return true;
				}
			}
		}
		return false;
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
