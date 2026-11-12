package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Another dimension's waypoints shown where the player is (opt-in: {@code cross-dimension}, issue
 * #123): an Overworld waypoint, switched on in the Nether from the waypoint list's Overworld
 * folder, stands at an eighth of its x and z on the minimap, the map and in the world, and its
 * menu's Teleport takes the player to it in the Overworld. Then the other way: a Nether waypoint
 * at eight times its x and z in the Overworld.
 */
final class CrossDimensionTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	/** Blue, so the pin stands out on the Nether's red and the grass. */
	private static final int BLUE = 0xFF3355FF;
	private static final int YELLOW = 0xFFFFC53D;

	private CrossDimensionTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos home = context.computeOnClient(mc -> mc.player.blockPosition());
		// 160 and 96 blocks off in the Overworld: 20 and 12 in the Nether.
		Waypoint base = add(context, new Waypoint("Base", home.getX() + 160, home.getY(), home.getZ() + 96, OVERWORLD, BLUE));
		// A Nether waypoint whose Overworld spot is a few dozen blocks from home.
		Waypoint hub = add(context, new Waypoint("Hub", Math.floorDiv(home.getX(), 8) + 4, 70, Math.floorDiv(home.getZ(), 8) - 3, NETHER, YELLOW));

		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0");
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == Level.NETHER && MapSession.get().map().chunkCount() > 200, 2400);
		DioramaClientTest.settle(context, 100);
		int netherX = Math.floorDiv(base.x, 8);
		int netherZ = Math.floorDiv(base.z, 8);
		if (copyIn(context, NETHER, base) != null) {
			throw new AssertionError("The Overworld's Base shows in the Nether before it was switched on");
		}

		// The waypoint list's Overworld folder, Show here clicked with the mouse.
		context.setScreen(() -> {
			WaypointListScreen list = new WaypointListScreen(null);
			list.showDimension(OVERWORLD);
			return list;
		});
		context.waitTicks(5);
		context.takeScreenshot("cross-dimension-1-list-off");
		DioramaClientTest.clickWidget(context, context.computeOnClient(mc -> showHere(mc.gui.screen(), false)));
		context.waitTicks(5);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot("cross-dimension-2-list-on");
		if (!context.computeOnClient(mc -> WaypointStore.get().showsIn(NETHER, OVERWORLD))) {
			throw new AssertionError("Show here in the Overworld folder didn't switch the Overworld's waypoints on in the Nether");
		}
		context.setScreen(() -> null);

		Waypoint copy = copyIn(context, NETHER, base);
		if (copy == null || copy.x != netherX || copy.z != netherZ || copy.y != base.y) {
			throw new AssertionError("Base should stand at " + netherX + ", " + base.y + ", " + netherZ + " in the Nether, not "
				+ (copy == null ? "nowhere" : copy.x + ", " + copy.y + ", " + copy.z));
		}
		// Facing it, so the in-world pin is on screen with the minimap.
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0 facing " + netherX + " " + base.y + " " + netherZ);
		context.waitTicks(10);
		DioramaClientTest.settle(context, 20);
		context.takeScreenshot("cross-dimension-3-nether-minimap-and-world");

		DioramaClientTest.openMapOnPlayer(context);
		pinAt(context, netherX, base.y, netherZ, base);
		hoverPin(context, netherX, base.y, netherZ);
		context.takeScreenshot("cross-dimension-4-nether-map");
		DioramaClientTest.rightClickPin(context, new int[] {netherX, base.y, netherZ});
		context.takeScreenshot("cross-dimension-5-nether-menu");
		List<String> labels = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu().labels());
		if (labels.isEmpty() || !labels.get(0).equals("Base (Overworld)")) {
			throw new AssertionError("The menu of the Overworld's pin should be titled Base (Overworld): " + labels);
		}
		// Teleport goes to the waypoint itself, in the Overworld. Across the ground only: the player
		// may fall from there, as spawn, and so the waypoint's height, can be in the air.
		DioramaClientTest.clickMenu(context, "Teleport");
		context.waitFor(mc -> mc.level.dimension() == Level.OVERWORLD && Math.hypot(mc.player.getX() - (base.x + 0.5), mc.player.getZ() - (base.z + 0.5)) < 2, 600);

		// The other way: back home in the Overworld, the Nether's Hub at eight times its x and z.
		world.getServer().runCommand("tp @a " + home.getX() + " " + home.getY() + " " + home.getZ());
		context.waitFor(mc -> mc.gui.screen() == null && MapSession.get() != null && mc.player.blockPosition().closerThan(home, 4)
			&& MapSession.get().map().chunkCount() > 400, 2400);
		DioramaClientTest.settle(context, 60);
		context.setScreen(() -> {
			WaypointListScreen list = new WaypointListScreen(null);
			list.showDimension(NETHER);
			return list;
		});
		context.waitTicks(5);
		DioramaClientTest.clickWidget(context, context.computeOnClient(mc -> showHere(mc.gui.screen(), false)));
		context.waitTicks(5);
		context.setScreen(() -> null);
		int overworldX = hub.x * 8;
		int overworldZ = hub.z * 8;
		Waypoint hubCopy = copyIn(context, OVERWORLD, hub);
		if (hubCopy == null || hubCopy.x != overworldX || hubCopy.z != overworldZ) {
			throw new AssertionError("Hub should stand at " + overworldX + ", " + overworldZ + " in the Overworld, not "
				+ (hubCopy == null ? "nowhere" : hubCopy.x + ", " + hubCopy.z));
		}
		world.getServer().runCommand("tp @a " + home.getX() + " " + home.getY() + " " + home.getZ() + " facing " + overworldX + " " + hub.y + " " + overworldZ);
		context.waitTicks(10);
		DioramaClientTest.settle(context, 20);
		context.takeScreenshot("cross-dimension-6-overworld-minimap-and-world");
		DioramaClientTest.openMapOnPlayer(context);
		pinAt(context, overworldX, hub.y, overworldZ, hub);
		hoverPin(context, overworldX, hub.y, overworldZ);
		context.takeScreenshot("cross-dimension-7-overworld-map");
		context.setScreen(() -> null);

		// Switched off, the copy goes.
		context.runOnClient(mc -> WaypointStore.get().setShownIn(OVERWORLD, NETHER, false));
		if (copyIn(context, OVERWORLD, hub) != null) {
			throw new AssertionError("Hub still shows in the Overworld once switched off");
		}
		context.runOnClient(mc -> {
			WaypointStore.get().setShownIn(NETHER, OVERWORLD, false);
			WaypointStore.get().remove(base);
			WaypointStore.get().remove(hub);
		});
	}

	private static Waypoint add(ClientGameTestContext context, Waypoint waypoint) {
		context.runOnClient(mc -> WaypointStore.get().add(waypoint));
		return waypoint;
	}

	/** The copy of {@code original} that {@code dimension}'s maps and world show, or null. */
	private static Waypoint copyIn(ClientGameTestContext context, String dimension, Waypoint original) {
		return context.computeOnClient(mc -> WaypointStore.get().shownIn(dimension).stream()
			.filter(w -> w != original && w.source() == original).findFirst().orElse(null));
	}

	/** The folder bar's Show here button, which must read on or off. */
	private static Button showHere(net.minecraft.client.gui.screens.Screen screen, boolean on) {
		String label = net.minecraft.network.chat.CommonComponents.optionStatus(net.minecraft.network.chat.Component.translatable("diorama.folder.show_here"), on).getString();
		return DioramaClientTest.buttonLabelled(screen, label);
	}

	/** Checks the open map draws a pin at a spot that stands in for {@code original}. */
	private static void pinAt(ClientGameTestContext context, int x, int y, int z, Waypoint original) {
		boolean found = context.computeOnClient(mc -> {
			for (WaypointOverlay.Pin pin : ((MapScreen) mc.gui.screen()).pins()) {
				Waypoint w = pin.waypoint();
				if (w.x == x && w.y == y && w.z == z && w.source() == original && !pin.clamped()) {
					return true;
				}
			}
			return false;
		});
		if (!found) {
			throw new AssertionError("The map has no pin for " + original.name + " at " + x + ", " + y + ", " + z);
		}
	}

	/** Puts the cursor on a pin, so the map shows its name and distance. */
	private static void hoverPin(ClientGameTestContext context, int x, int y, int z) {
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			for (WaypointOverlay.Pin pin : ((MapScreen) mc.gui.screen()).pins()) {
				if (pin.waypoint().x == x && pin.waypoint().y == y && pin.waypoint().z == z) {
					return new double[] {pin.x() * scale, pin.headY() * scale};
				}
			}
			throw new AssertionError("No pin at " + x + "," + y + "," + z);
		});
		DioramaClientTest.moveCursor(context, at[0], at[1]);
		context.waitTicks(3);
	}
}
