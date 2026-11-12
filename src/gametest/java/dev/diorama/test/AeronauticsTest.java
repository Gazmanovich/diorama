package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.CraftBlocks;
import dev.diorama.bake.TileKey;
import dev.diorama.craft.Body;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Create Aeronautics ships on the map (opt-in: {@code aeronautics}, with Create Aeronautics and
 * Sable installed, the physics mod Aeronautics moves its builds with). A small lopsided wool boat with a mast is built
 * in the air over a stone floor and made into a ship by Sable's own assemble command, with physics
 * paused so it hangs there. It is photographed on the map and the minimap where it was built,
 * after it's moved, after it's turned and tilted, after a block is placed on its deck, and after
 * it drops to the floor with physics running, and after its blocks are broken. Each time the map must hold a picture of the ship's blocks as
 * they are. Everything Sable is reached by reflection, so the section builds without it.
 */
final class AeronauticsTest {
	private static final String CONTAINER = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";

	private AeronauticsTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		if (!context.computeOnClient(mc -> type(CONTAINER) != null)) {
			throw new AssertionError("DIORAMA_TESTS=aeronautics needs Create Aeronautics and Sable installed");
		}
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition()).offset(24, 0, -24);
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()));
		int x = at.getX();
		int z = at.getZ();
		int floor = ground + 1;
		for (int y = floor + 24; y >= floor; y -= 8) {
			world.getServer().runCommand("fill " + (x - 24) + " " + y + " " + (z - 20) + " " + (x + 24) + " " + Math.min(floor + 24, y + 7) + " " + (z + 20) + " air");
		}
		world.getServer().runCommand("fill " + (x - 24) + " " + floor + " " + (z - 20) + " " + (x + 24) + " " + floor + " " + (z + 20) + " smooth_stone");
		// The boat, six blocks up: white deck, red bow to the east, blue stern, a mast nearer the bow
		// with a sail of Aeronautics' yellow balloon cloth to the south of it, and a steering wheel aft.
		// Lopsided, so a mirrored or turned picture shows.
		int deck = floor + 6;
		world.getServer().runCommand("fill " + (x - 4) + " " + deck + " " + (z - 1) + " " + (x + 4) + " " + deck + " " + (z + 2) + " white_wool");
		world.getServer().runCommand("fill " + (x + 4) + " " + deck + " " + (z - 1) + " " + (x + 4) + " " + (deck + 1) + " " + (z + 2) + " red_wool");
		world.getServer().runCommand("fill " + (x - 4) + " " + deck + " " + (z - 1) + " " + (x - 4) + " " + deck + " " + (z + 2) + " blue_wool");
		world.getServer().runCommand("fill " + (x + 1) + " " + (deck + 1) + " " + z + " " + (x + 1) + " " + (deck + 5) + " " + z + " oak_log");
		world.getServer().runCommand("fill " + (x + 1) + " " + (deck + 3) + " " + (z + 1) + " " + (x + 1) + " " + (deck + 5) + " " + (z + 2) + " aeronautics:yellow_envelope");
		world.getServer().runCommand("setblock " + (x - 2) + " " + (deck + 1) + " " + z + " simulated:steering_wheel");
		world.getServer().runCommand("tp @a " + (x - 10) + " " + (floor + 1) + " " + (z + 12) + " facing " + x + " " + deck + " " + z);
		context.waitTicks(100);
		int savedZoom = context.computeOnClient(mc -> Settings.get().zoomStep());
		Facing savedFacing = context.computeOnClient(mc -> Projection.facing());
		context.runOnClient(mc -> Settings.get().zoomStep(8));
		try {
			photos(context, "01-built", new double[] {x + 0.5, deck, z + 0.5});

			world.getServer().runCommand("sable paused true");
			world.getServer().runCommand("sable assemble area " + (x - 4) + " " + deck + " " + (z - 1) + " " + (x + 4) + " " + (deck + 5) + " " + (z + 2));
			context.waitFor(mc -> ships(mc).size() == 1, 400);
			// The commands below say @e, Sable's word for every sub-level: this is the only one.
			UUID id = context.computeOnClient(mc -> (UUID) call(ships(mc).get(0), "getUniqueId"));
			context.waitTicks(140);
			log(context, "assembled");
			double[] built = where(context);
			expectUnmapped(context);
			photos(context, "02-ship", built);
			expectShown(context, id, 9, 6, 4, 0);

			// Paused physics moves nothing, so it runs for the moment the ship is told to move.
			world.getServer().runCommand("sable paused false");
			world.getServer().runCommand("sable physics translation @e add 6 3 -3");
			world.getServer().runCommand("sable paused true");
			context.waitTicks(140);
			log(context, "moved");
			double[] moved = where(context);
			// Sable's command has moved a ship twice as far as told, so this asks only that it went that way.
			if (moved[0] - built[0] < 5 || moved[0] - built[0] > 13 || built[2] - moved[2] < 2 || built[2] - moved[2] > 7) {
				throw new AssertionError("The ship didn't move the way it was told: from " + built[0] + " " + built[2] + " to " + moved[0] + " " + moved[2]);
			}
			// Beside it, so the minimap has it too.
			world.getServer().runCommand("tp @a " + (int) Math.floor(moved[0] - 10) + " " + (floor + 1) + " " + (int) Math.floor(moved[2] + 10) + " facing " + moved[0] + " " + moved[1] + " " + moved[2]);
			context.waitTicks(40);
			photos(context, "03-moved", moved);

			world.getServer().runCommand("sable paused false");
			world.getServer().runCommand("sable physics rotation @e add axis 0 1 0 50");
			world.getServer().runCommand("sable physics rotation @e add axis 1 0 0 25");
			world.getServer().runCommand("sable paused true");
			context.waitTicks(140);
			log(context, "turned");
			photos(context, "04-turned", where(context));

			// A block placed on the deck of the ship as it hangs there: in the ship's own far-off chunks.
			int[] plot = context.computeOnClient(AeronauticsTest::plotMin);
			world.getServer().runCommand("setblock " + (plot[0] + 2) + " " + (plot[1] + 1) + " " + (plot[2] + 1) + " lime_wool");
			world.getServer().runCommand("setblock " + (plot[0] + 2) + " " + (plot[1] + 2) + " " + (plot[2] + 1) + " lime_wool");
			context.waitTicks(140);
			log(context, "built on");
			photos(context, "05-built-on", where(context));
			expectShown(context, id, 9, 6, 4, 2);

			// Physics let go: the ship drops to the floor, and the map follows it down.
			double[] hung = where(context);
			world.getServer().runCommand("sable paused false");
			context.waitFor(mc -> where(mc)[1] < hung[1] - 3, 400);
			context.waitTicks(100);
			log(context, "landed");
			photos(context, "06-landed", where(context));

			// Every block of the ship broken: Sable drops a ship with nothing left in it.
			double[] last = where(context);
			world.getServer().runCommand("fill " + plot[0] + " " + plot[1] + " " + plot[2] + " " + (plot[0] + 8) + " " + (plot[1] + 5) + " " + (plot[2] + 3) + " air");
			context.waitFor(mc -> ships(mc).isEmpty(), 400);
			context.waitTicks(200);
			log(context, "broken up");
			photos(context, "07-gone", last);
			if (context.computeOnClient(mc -> MapSession.get().crafts().has(id))) {
				throw new AssertionError("The ship is still on the map after its blocks were broken");
			}
		} finally {
			world.getServer().runCommand("sable paused false");
			context.runOnClient(mc -> Settings.get().zoomStep(savedZoom));
			FacingTest.turnTo(context, savedFacing);
		}
	}

	/** The map close on a spot facing north and south, then the minimap. */
	private static void photos(ClientGameTestContext context, String name, double[] at) {
		for (Facing facing : new Facing[] {Facing.NORTH, Facing.SOUTH}) {
			FacingTest.turnTo(context, facing);
			context.setScreen(MapScreen::new);
			// The cursor's tooltip off the ship.
			context.getInput().setCursorPos(1595, 250);
			look(context, at);
			waitForMap(context);
			look(context, at);
			context.waitTicks(40);
			context.takeScreenshot("aeronautics-" + name + "-map-" + facing.id());
			context.setScreen(() -> null);
			if (facing == Facing.NORTH) {
				context.waitTicks(40);
				context.takeScreenshot("aeronautics-" + name + "-minimap");
			}
		}
	}

	private static void look(ClientGameTestContext context, double[] at) {
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(Projection.u(at[0], at[2]), Projection.v(at[0], at[1], at[2]), 8, TileKey.SURFACE);
			}
		});
	}

	private static void waitForMap(ClientGameTestContext context) {
		context.waitTicks(40);
		for (int i = 0; i < 60; i++) {
			if (context.computeOnClient(mc -> MapSession.get().map().bakingCount() == 0)) {
				break;
			}
			context.waitTicks(20);
		}
		context.waitTicks(60);
	}

	/** Where the one ship is: the world place of the point it turns about. */
	private static double[] where(ClientGameTestContext context) {
		return context.computeOnClient(AeronauticsTest::where);
	}

	private static double[] where(Minecraft mc) {
		Object position = call(call(ships(mc).get(0), "logicalPose"), "position");
		return new double[] {(double) call(position, "x"), (double) call(position, "y"), (double) call(position, "z")};
	}

	/** The least corner of the one ship's blocks, in its own chunks. */
	private static int[] plotMin(Minecraft mc) {
		Object box = call(call(ships(mc).get(0), "getPlot"), "getBoundingBox");
		return new int[] {(int) call(box, "minX"), (int) call(box, "minY"), (int) call(box, "minZ")};
	}

	/** The ships Sable has on the client. */
	private static List<Object> ships(Minecraft mc) {
		try {
			Object container = type(CONTAINER).getMethod("getContainer", ClientLevel.class).invoke(null, mc.level);
			return container == null ? List.of() : new ArrayList<>((List<?>) call(container, "getAllSubLevels"));
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * The map must be drawing the ship, from a picture of a grid this size with this many blocks of
	 * lime wool in it. Asked after the map has been looked at, so the picture has been made.
	 */
	private static void expectShown(ClientGameTestContext context, UUID id, int sizeX, int sizeY, int sizeZ, int lime) {
		String[] problem = {null};
		for (int i = 0; i < 20; i++) {
			problem[0] = context.computeOnClient(mc -> {
				Body.Blocks shown = MapSession.get().crafts().shown(id);
				if (shown == null) {
					return MapSession.get().crafts().has(id) ? "The map has the ship but no picture of its blocks as they are" : "The map doesn't have the ship";
				}
				CraftBlocks blocks = shown.blocks();
				int found = 0;
				for (int cell = 0; cell < blocks.volume(); cell++) {
					// By name: the field for lime wool isn't the same on every version.
					if (String.valueOf(Block.BLOCK_STATE_REGISTRY.byId(blocks.state(cell))).contains("lime_wool")) {
						found++;
					}
				}
				String got = blocks.sizeX + " " + blocks.sizeY + " " + blocks.sizeZ + ", " + found + " lime wool";
				return got.equals(sizeX + " " + sizeY + " " + sizeZ + ", " + lime + " lime wool") ? null : "The ship on the map is " + got;
			});
			if (problem[0] == null) {
				return;
			}
			context.waitTicks(20);
		}
		throw new AssertionError(problem[0]);
	}

	/** The far-off chunks the ship keeps its blocks in aren't land, and mustn't be mapped as land. */
	private static void expectUnmapped(ClientGameTestContext context) {
		boolean mapped = context.computeOnClient(mc -> {
			int[] plot = plotMin(mc);
			return MapSession.get().map().worldChunk(plot[0] >> 4, plot[2] >> 4) != null;
		});
		Diorama.LOGGER.info("Aeronautics test: the map {} the chunks the ship's blocks are kept in", mapped ? "holds" : "doesn't hold");
		if (mapped) {
			throw new AssertionError("The map took the chunks the ship's blocks are kept in for land");
		}
	}

	private static void log(ClientGameTestContext context, String what) {
		String line = context.computeOnClient(mc -> {
			StringBuilder out = new StringBuilder("player " + mc.player.blockPosition().toShortString());
			for (Object ship : ships(mc)) {
				Object pose = call(ship, "logicalPose");
				out.append("; ship ").append(call(ship, "getUniqueId")).append(" at ").append(call(pose, "position"))
					.append(" turned ").append(call(pose, "orientation")).append(" about ").append(call(pose, "rotationPoint"))
					.append(" blocks ").append(call(call(ship, "getPlot"), "getBoundingBox"));
			}
			return out.toString();
		});
		Diorama.LOGGER.info("Aeronautics test: {}: {}", what, line);
	}

	private static Class<?> type(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	private static Object call(Object owner, String name) {
		try {
			Method method = owner.getClass().getMethod(name);
			method.setAccessible(true);
			return method.invoke(owner);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(name + " on " + owner.getClass().getName(), e);
		}
	}
}
