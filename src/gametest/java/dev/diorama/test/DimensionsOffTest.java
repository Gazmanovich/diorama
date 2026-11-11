package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.DimensionsScreen;
import dev.diorama.MapSession;
import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.world.level.Level;

/**
 * The map turned off in chosen dimensions (opt-in: {@code dimensions-off}, issue #323). The player
 * ticks the Nether in Settings: there, no minimap, no waypoint in the world, nothing mapped, and
 * the map keys say why; unticked, the map is back. Then the same from the file a modpack ships
 * ({@code dimensions-off-file}) and from a real server's ({@code dimensions-off-server}).
 */
final class DimensionsOffTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final int LOOK_TAB = 4;

	private DimensionsOffTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		// A waypoint ahead of where the player will stand in the Nether.
		Waypoint exit = new Waypoint("Exit", 12, 70, 0, NETHER, 0xFF3355FF);
		context.runOnClient(mc -> {
			Settings.get().mapOffDimensions.clear();
			WaypointStore.get().add(exit);
		});
		context.takeScreenshot("dimensions-off-1-overworld");

		// Settings, Look: the button, then the list with the Nether ticked by a click.
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(LOOK_TAB));
		context.waitTicks(5);
		context.takeScreenshot("dimensions-off-2-settings-none");
		DioramaClientTest.clickWidget(context, widget(context, "Map off in dimensions: None"));
		context.waitTicks(5);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof DimensionsScreen)) {
			throw new AssertionError("The Map off in dimensions button didn't open the list");
		}
		for (String name : new String[] {"Overworld", "Nether", "End"}) {
			widget(context, name);
		}
		DioramaClientTest.clickWidget(context, widget(context, "Nether"));
		context.waitTicks(5);
		context.takeScreenshot("dimensions-off-3-list-nether-ticked");
		if (!context.computeOnClient(mc -> Settings.get().mapOffDimensions.equals(Set.of(NETHER)))) {
			throw new AssertionError("Ticking Nether should turn the map off in the Nether only: " + context.computeOnClient(mc -> Settings.get().mapOffDimensions));
		}
		String saved = context.computeOnClient(mc -> read(mc.gameDirectory.toPath().resolve("diorama").resolve("config.json")));
		if (!saved.contains(NETHER)) {
			throw new AssertionError("The ticked dimension wasn't saved:\n" + saved);
		}
		context.getInput().setCursorPos(0, 0);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(5);
		context.takeScreenshot("dimensions-off-4-settings-nether");
		widget(context, "Map off in dimensions: Nether");
		context.setScreen(() -> null);
		// Still on in the Overworld.
		if (!context.computeOnClient(mc -> MapSession.get() != null && ServerRules.minimapAllowed())) {
			throw new AssertionError("Turning the map off in the Nether turned it off in the Overworld");
		}

		// Into the Nether: no map there, and nothing mapped.
		int started = context.computeOnClient(mc -> MapSession.started());
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0 facing 12 70 0");
		context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER && mc.player != null && Math.abs(mc.player.getX()) < 2, 1200);
		context.waitTicks(200);
		off(context, "in the Nether");
		if (context.computeOnClient(mc -> MapSession.started()) != started) {
			throw new AssertionError("The map started in the Nether, where it's off");
		}
		context.takeScreenshot("dimensions-off-5-nether-off");
		keys(context, "dimensions-off-6-nether-map-key", "Diorama is off in this dimension. Change it in Map settings: Look, Map off in dimensions.");

		// Unticked while standing there: mapped and shown again, the waypoint with it.
		context.runOnClient(mc -> {
			Settings.get().mapOffDimensions.remove(NETHER);
			Settings.get().save();
		});
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		DioramaClientTest.settle(context, 100);
		context.takeScreenshot("dimensions-off-7-nether-on-again");

		// Ticked while standing there: off at once.
		context.runOnClient(mc -> Settings.get().mapOffDimensions.add(NETHER));
		context.waitTicks(10);
		off(context, "in the Nether, ticked while there");
		context.takeScreenshot("dimensions-off-8-nether-off-again");

		// Back in the Overworld the map is as it was, and the dimension button doesn't offer the Nether's.
		world.getServer().runCommand("execute in minecraft:overworld run tp @a 0 120 0");
		context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.OVERWORLD && MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		DioramaClientTest.settle(context, 60);
		DioramaClientTest.openMapOnPlayer(context);
		context.takeScreenshot("dimensions-off-9-overworld-map");
		if (mapDimensions(context).contains(NETHER)) {
			throw new AssertionError("The map offers the Nether's saved map, where the map is off: " + mapDimensions(context));
		}
		// On again, its saved map is one the dimension button steps to.
		context.runOnClient(mc -> Settings.get().mapOffDimensions.clear());
		if (!mapDimensions(context).contains(NETHER)) {
			throw new AssertionError("The Nether was mapped while on, but the map doesn't offer it: " + mapDimensions(context));
		}
		// Looking at the Nether's saved map from here, then turning it off (Settings opens over the
		// map): the map goes back to the dimension the player is in.
		otherDimension(context, NETHER);
		DioramaClientTest.settle(context, 20);
		context.takeScreenshot("dimensions-off-10-overworld-map-shows-nether");
		context.runOnClient(mc -> Settings.get().mapOffDimensions.add(NETHER));
		context.waitTicks(5);
		if (otherDimension(context, null) != null) {
			throw new AssertionError("The map still shows the Nether's saved map after the Nether was turned off");
		}
		context.takeScreenshot("dimensions-off-11-overworld-map-back");
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			Settings.get().mapOffDimensions.clear();
			Settings.get().save();
			WaypointStore.get().remove(exit);
		});
	}

	/**
	 * The file a modpack ships, in singleplayer: the Overworld and the Nether off (one ID without
	 * its namespace, with spaces around it), greyed out in the list, and the End still mapped.
	 */
	static void file(ClientGameTestContext context) {
		Path config = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("config").resolve("diorama-server.properties"));
		write(config, "map-off-dimensions= overworld , minecraft:the_nether\n");
		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
			context.waitFor(mc -> mc.level != null && mc.player != null && ServerRules.mapOffByServer(OVERWORLD), 1200);
			context.waitTicks(100);
			off(context, "in the Overworld, by the file");
			context.takeScreenshot("dimensions-off-file-1-overworld-off");
			keys(context, "dimensions-off-file-2-map-key", "Diorama is off in this dimension, set in config/diorama-server.properties.");
			context.setScreen(() -> new DimensionsScreen(new SettingsScreen(null)));
			context.waitTicks(5);
			for (String name : new String[] {"Overworld", "Nether"}) {
				AbstractWidget box = widget(context, name);
				if (box.active) {
					throw new AssertionError(name + " is off by the file, but its checkbox can be changed");
				}
			}
			AbstractWidget end = widget(context, "End");
			if (!end.active) {
				throw new AssertionError("The file leaves the End alone, but its checkbox is greyed out");
			}
			double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
			AbstractWidget overworld = widget(context, "Overworld");
			context.getInput().setCursorPos((overworld.getX() + 8) * scale, (overworld.getY() + 10) * scale);
			context.waitTicks(14);
			context.takeScreenshot("dimensions-off-file-3-list");
			context.getInput().setCursorPos(0, 0);
			context.setScreen(() -> null);

			world.getServer().runCommand("execute in minecraft:the_end run tp @a 0 80 0");
			context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.END && MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			DioramaClientTest.settle(context, 100);
			if (!context.computeOnClient(mc -> ServerRules.minimapAllowed())) {
				throw new AssertionError("The file turns the map off in the Overworld and the Nether, and it's off in the End");
			}
			context.takeScreenshot("dimensions-off-file-4-end-on");
		}
		if (context.computeOnClient(mc -> ServerRules.mapOffByServer(OVERWORLD))) {
			throw new AssertionError("The file's dimensions are still off after leaving the world");
		}
		try {
			Files.delete(config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A real server whose file turns the map off in the Overworld: off for the player who joins, and it says the server did it. */
	static void server(ClientGameTestContext context) {
		ServerLocksTest.onServer(context, "map-off", "map-off-dimensions=minecraft:overworld\n", () -> {
			off(context, "in the Overworld, by the server");
			context.takeScreenshot("dimensions-off-server-1-overworld-off");
			keys(context, "dimensions-off-server-2-map-key", "This server turns Diorama off in this dimension.");
		}, server -> {
			server.command("execute in minecraft:the_nether run tp @a 0 70 0");
			context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER && MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			DioramaClientTest.settle(context, 100);
			context.takeScreenshot("dimensions-off-server-3-nether-on");
		});
	}

	/** No session, so nothing mapped, and nothing that draws the map or waypoints may. */
	private static void off(ClientGameTestContext context, String where) {
		String state = context.computeOnClient(mc -> MapSession.get() == null && !ServerRules.minimapAllowed() ? null
			: "session " + (MapSession.get() != null) + ", shown " + ServerRules.minimapAllowed());
		if (state != null) {
			throw new AssertionError("The map should be off " + where + ": " + state);
		}
	}

	/** The map, new waypoint and waypoint list keys open nothing, and say why over the hotbar. */
	private static void keys(ClientGameTestContext context, String screenshot, String message) {
		for (int key : new int[] {InputConstants.KEY_B, InputConstants.KEY_U, InputConstants.KEY_M}) {
			String before = context.computeOnClient(mc -> ServerRules.lastMapOffMessage());
			int count = Integer.parseInt(before.substring(0, before.indexOf(':')));
			context.getInput().pressKey(key);
			context.waitTicks(3);
			String screen = context.computeOnClient(mc -> mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
			if (screen != null) {
				throw new AssertionError("A map key opened " + screen + " where the map is off");
			}
			// Each key says so itself.
			String said = context.computeOnClient(mc -> ServerRules.lastMapOffMessage());
			if (!said.equals((count + 1) + ": " + message)) {
				throw new AssertionError("Key " + key + " should say \"" + message + "\" once: was \"" + before + "\", now \"" + said + "\"");
			}
		}
		context.takeScreenshot(screenshot);
	}

	/** The dimensions the open map's dimension button steps through. */
	@SuppressWarnings("unchecked")
	private static java.util.List<String> mapDimensions(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			try {
				java.lang.reflect.Method method = dev.diorama.MapScreen.class.getDeclaredMethod("dimensions");
				method.setAccessible(true);
				return (java.util.List<String>) method.invoke(mc.gui.screen());
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	/** The other dimension the open map shows, or null for the player's own; sets it first if {@code show} isn't null. */
	private static String otherDimension(ClientGameTestContext context, String show) {
		return context.computeOnClient(mc -> {
			try {
				java.lang.reflect.Field field = dev.diorama.MapScreen.class.getDeclaredField("otherDimension");
				field.setAccessible(true);
				if (show != null) {
					field.set(mc.gui.screen(), show);
				}
				return (String) field.get(mc.gui.screen());
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	/** The visible widget of the open screen with this label. */
	private static AbstractWidget widget(ClientGameTestContext context, String label) {
		return context.computeOnClient(mc -> {
			StringBuilder seen = new StringBuilder();
			for (GuiEventListener child : mc.gui.screen().children()) {
				if (child instanceof AbstractWidget widget && widget.visible) {
					if (widget.getMessage().getString().equals(label)) {
						return widget;
					}
					seen.append(widget.getMessage().getString()).append(" | ");
				}
			}
			throw new AssertionError("No \"" + label + "\" on " + mc.gui.screen().getClass().getSimpleName() + ": " + seen);
		});
	}

	private static void write(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
