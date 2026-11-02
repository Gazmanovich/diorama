package dev.diorama.test;

import dev.diorama.Facing;
import dev.diorama.MapExport;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Maps;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ImageExport;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.render.MapLevel;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Which way the map faces ({@code facing}): a house with a different colour on each wall shows the
 * wall the map looks at, on the right side of the picture, the map's right-click finds the right
 * ground in every direction, and the world remembers its direction.
 */
final class FacingTest {
	private static final int SIZE = 9;
	private static final int HEIGHT = 7;
	/** The height a drawing is taken to be on, to carry it across a turn. */
	private static final int DRAWN_Y = 64;
	/** Which wall each facing sees, by turns: it looks at the side facing it. */
	private static final BlockState[] WALLS = {
		Blocks.LAPIS_BLOCK.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState(),
		Blocks.REDSTONE_BLOCK.defaultBlockState(), Blocks.EMERALD_BLOCK.defaultBlockState()
	};

	private FacingTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition()).offset(16, 0, 0);
		int y0 = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ())) + 6;
		int x0 = at.getX();
		int z0 = at.getZ();
		int x1 = x0 + SIZE - 1;
		int z1 = z0 + SIZE - 1;
		world.getServer().runOnServer(server -> {
			for (int y = y0; y < y0 + HEIGHT; y++) {
				for (int i = 0; i < SIZE; i++) {
					server.overworld().setBlockAndUpdate(new BlockPos(x0 + i, y, z1), WALLS[Facing.NORTH.turns]);
					server.overworld().setBlockAndUpdate(new BlockPos(x0, y, z0 + i), WALLS[Facing.EAST.turns]);
					server.overworld().setBlockAndUpdate(new BlockPos(x0 + i, y, z0), WALLS[Facing.SOUTH.turns]);
					server.overworld().setBlockAndUpdate(new BlockPos(x1, y, z0 + i), WALLS[Facing.WEST.turns]);
				}
			}
		});
		context.waitTicks(60);
		// The middle of the wall each facing looks at, as points on its outer face.
		double midX = x0 + SIZE / 2.0;
		double midZ = z0 + SIZE / 2.0;
		double midY = y0 + HEIGHT / 2.0;
		double[][] faces = {{midX, z1 + 1}, {x0, midZ}, {midX, z0}, {x1 + 1, midZ}};

		// On the roof's middle: a waypoint keeps the world's place, a drawing the ground under it.
		String dimension = "minecraft:overworld";
		Drawing note = context.computeOnClient(mc -> {
			Waypoint pin = new Waypoint("House", (int) midX, y0 + HEIGHT, (int) midZ, dimension, Waypoint.PALETTE[1]);
			WaypointStore.get().add(pin);
			double u = Projection.u(midX, midZ);
			double v = Projection.v(midX, DRAWN_Y, midZ);
			Drawing made = new Drawing(Drawing.Kind.NOTE, dimension, 0xFFFFFFFF, new double[] {u, v}, "here");
			DrawingStore.get().add(made);
			return made;
		});
		context.setScreen(MapScreen::new);
		for (Facing facing : Facing.values()) {
			if (facing != Facing.NORTH) {
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(1));
			}
			double[] face = faces[facing.turns];
			double[] shown = context.computeOnClient(mc -> note.shown());
			double wantU = context.computeOnClient(mc -> Projection.u(midX, midZ));
			double wantV = context.computeOnClient(mc -> Projection.v(midX, DRAWN_Y, midZ));
			if (Math.abs(shown[0] - wantU) > 0.01 || Math.abs(shown[1] - wantV) > 0.01) {
				throw new AssertionError("Facing " + facing + ", a note on the ground is at " + shown[0] + ", " + shown[1] + ", not " + wantU + ", " + wantV);
			}
			double u = context.computeOnClient(mc -> Projection.u(face[0], face[1]));
			double v = context.computeOnClient(mc -> Projection.v(face[0], midY, face[1]));
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u, v, 4, TileKey.SURFACE));
			TileKey tile = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
			int px = Math.floorMod((int) Math.floor(u * Projection.PIXELS_PER_UNIT), Projection.TILE_PIXELS);
			int py = Math.floorMod((int) Math.floor(v * Projection.PIXELS_PER_UNIT), Projection.TILE_PIXELS);
			int[] expected = rgb(WALLS[facing.turns]);
			context.waitFor(mc -> {
				int[] pixels = MapSession.get().map().savedMap().read(tile);
				return pixels != null && close(pixels[py * Projection.TILE_PIXELS + px], expected);
			}, 2400);
			context.takeScreenshot("facing-" + facing.id());

			// What the right-click reads off a saved tile: the wall, wherever the map looks from.
			BlockPos spot = savedSpot(context, u, v);
			if (spot == null || Math.abs(spot.getX() + 0.5 - face[0]) > 2.5 || Math.abs(spot.getZ() + 0.5 - face[1]) > 2.5) {
				throw new AssertionError("Facing " + facing + ", the map finds " + spot + " at the wall by " + face[0] + ", " + face[1]);
			}
		}
		Path folder = context.computeOnClient(mc -> MapSession.get().map().importFolder());
		if (!folder.getFileName().toString().endsWith("-west")) {
			throw new AssertionError("The west map is saved in " + folder);
		}
		if (!Files.isRegularFile(folder.resolve("redraw-from"))) {
			throw new AssertionError("The west map remembers nothing");
		}
		if (context.computeOnClient(mc -> Maps.get().facing()) != Facing.WEST) {
			throw new AssertionError("The world didn't keep the way its map faces");
		}
		// An image of the map as it faces, with the note where the map has it.
		context.runOnClient(mc -> {
			double u = Projection.u(midX, midZ);
			double v = Projection.v(midX, DRAWN_Y, midZ);
			if (!MapExport.start(MapSession.get().map().savedMap(), ImageExport.Area.ofUnits(1, u - 96, v - 64, u + 96, v + 64), false, List.of(note))) {
				throw new AssertionError("Another export was still running");
			}
		});
		context.waitFor(mc -> MapExport.running() == null, 2400);
		context.setScreen(() -> null);
		context.waitTicks(40);
		context.takeScreenshot("facing-minimap-west");
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(1));
		context.waitTicks(10);
		context.takeScreenshot("facing-north-again");

		// Under a roof too: the view's own folder, turned, with smoke over what's left to draw.
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 64 0");
		context.setScreen(() -> null);
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().roofless() && MapSession.get().map().chunkCount() > 100, 2400);
		context.waitFor(mc -> MapSession.get().map().savedMap().saved(0).stream().anyMatch(key -> Math.abs(key.tileU()) < 3 && Math.abs(key.tileV()) < 3), 2400);
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(2));
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(mc.player.getX(), mc.player.getZ()), Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()), 2, TileKey.SURFACE));
		context.waitFor(mc -> MapSession.get().map().importFolder().getFileName().toString().equals("under-roof-50-south"), 200);
		context.waitTicks(200);
		context.takeScreenshot("facing-nether-south");
		// East is what the world is left on: see reopened.
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(-1));
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			DrawingStore.get().remove(note);
		});
		world.getServer().runCommand("execute in minecraft:overworld run tp @a " + x0 + " " + (y0 + HEIGHT + 2) + " " + z0);
		context.waitFor(mc -> MapSession.get() != null && !MapSession.get().map().roofless(), 2400);
	}

	/** After the world is reopened: it opens the way the player left it, and goes back to north for what runs next. */
	static void reopened(ClientGameTestContext context) {
		context.waitFor(mc -> MapSession.get() != null, 2400);
		if (Projection.facing() != Facing.EAST) {
			throw new AssertionError("The world reopened facing " + Projection.facing());
		}
		if (!context.computeOnClient(mc -> MapSession.get().map().importFolder()).getFileName().toString().endsWith("-east")) {
			throw new AssertionError("The reopened world's map isn't its east one");
		}
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(-1));
		context.setScreen(() -> null);
	}

	private static BlockPos savedSpot(ClientGameTestContext context, double u, double v) {
		try {
			return context.computeOnClient(mc -> {
				MapLevel map = MapSession.get().map();
				return map.savedSpot(TileKey.SURFACE, u, v, BlockLooks.get(), false);
			}).get(20, TimeUnit.SECONDS).pos();
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static int[] rgb(BlockState state) {
		if (state.is(Blocks.LAPIS_BLOCK)) {
			return new int[] {0, 0, 1};
		}
		if (state.is(Blocks.REDSTONE_BLOCK)) {
			return new int[] {1, 0, 0};
		}
		if (state.is(Blocks.EMERALD_BLOCK)) {
			return new int[] {0, 1, 0};
		}
		return new int[] {1, 1, 0};
	}

	/** Whether a tile's ABGR pixel leans to these channels and away from the others. */
	private static boolean close(int abgr, int[] expected) {
		if ((abgr >>> 24) == 0) {
			return false;
		}
		int[] channels = {abgr & 0xFF, abgr >> 8 & 0xFF, abgr >> 16 & 0xFF};
		int on = 255;
		int off = 0;
		for (int i = 0; i < 3; i++) {
			if (expected[i] == 1) {
				on = Math.min(on, channels[i]);
			} else {
				off = Math.max(off, channels[i]);
			}
		}
		return on > off + 20;
	}
}
