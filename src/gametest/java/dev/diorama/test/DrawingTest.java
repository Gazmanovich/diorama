package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.WorldKey;
import dev.diorama.bake.TileKey;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.render.MapViewport;
import dev.diorama.waypoint.Waypoint;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Drawing on the map (#154), with the mouse and keyboard as a player would: a pen stroke down the
 * road, a clicked-out line, an area round the castle and a note, in their colours, then erasing,
 * undoing, measuring, leaving draw mode, the far zoom and the minimap. Opt-in ({@code drawings}): it builds
 * {@link ShowcaseScene}'s castle. {@code DIORAMA_GIF=1} adds frames of the drawing.
 */
final class DrawingTest {
	private static final boolean GIF = "1".equals(System.getenv("DIORAMA_GIF"));
	private static double[] cursor = {0, 0};
	private static int frames;

	private DrawingTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		context.getInput().resizeWindow(1920, 1080);
		world.getServer().runCommand("difficulty peaceful");
		BlockPos village = world.getServer().computeOnServer(server ->
			server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
		if (village == null) {
			throw new AssertionError("No village near spawn");
		}
		int cx = village.getX();
		int cz = village.getZ() - 110;
		world.getServer().runCommand("tp @a " + cx + " 200 " + (cz + 40));
		context.waitTicks(100);
		ShowcaseScene scene = world.getServer().computeOnServer(server -> ShowcaseScene.site(server.overworld(), cx, cz));
		int base = scene.base();
		world.getServer().runOnServer(server -> scene.castle("waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs"));
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("gamemode spectator @a");
		// On the road south of the castle, so the minimap shows the road and the gate.
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 46.5) + " 180 -4");
		context.waitTicks(60);
		context.runOnClient(mc -> {
			Settings.get().radarNeutral = false;
			Settings.get().radarPassive = false;
			Settings.get().drawings = Settings.DrawingsShown.ALWAYS;
		});
		String dimension = "minecraft:overworld";
		int before = context.computeOnClient(mc -> DrawingStore.get().in(dimension).size());

		context.setScreen(MapScreen::new);
		moveTo(context, 1300 / 4.0, 30 / 4.0);
		double v = Projection.v(base + 12, cz);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 3.2, TileKey.SURFACE));
		settle(context, 200);
		click(context, widget(context, s -> s.drawButton()));
		expect(context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawMode().active()), "the pencil turns draw mode on");

		// Pen (picked first): down the road from the gate, a hand's wobble in it.
		float[] gate = gui(context, cx, base + 1, cz + 22);
		float[] road = gui(context, cx + 1, base + 1, cz + 44);
		moveTo(context, gate[0], gate[1]);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		for (int i = 1; i <= 24; i++) {
			double t = i / 24.0;
			moveTo(context, gate[0] + (road[0] - gate[0]) * t + Math.sin(t * Math.PI * 3) * 6, gate[1] + (road[1] - gate[1]) * t);
			if (i == 12) {
				// Enter mid-stroke does nothing: the pencil still has the keyboard focus from its click,
				// and Enter mustn't press it.
				context.getInput().pressKey(InputConstants.KEY_RETURN);
			}
		}
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		Drawing pen = last(context, dimension, before + 1);
		expect(pen.kind == Drawing.Kind.STROKE && pen.pointCount() >= 4 && pen.color == Waypoint.PALETTE[2], "a pen stroke in the first colour, got " + describe(pen));

		// Red, then a line clicked out east of the castle, finished with a right-click. (A
		// double-click finishes it too, but a slow test machine spaces the test's clicks too far apart.)
		pickColour(context, 0);
		pickTool(context, 1);
		float[][] line = {gui(context, cx + 26, base + 1, cz - 4), gui(context, cx + 40, base + 1, cz - 20), gui(context, cx + 56, base + 1, cz - 8)};
		for (float[] point : line) {
			click(context, point[0], point[1]);
		}
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		frame(context);
		Drawing clicked = last(context, dimension, before + 2);
		expect(clicked.kind == Drawing.Kind.STROKE && clicked.pointCount() == 3 && clicked.color == Waypoint.PALETTE[0], "a three-point red line, got " + describe(clicked));

		// The area tool, then light blue (picking a colour mustn't add a corner where the swatch
		// was), then an area round the castle, closed with Enter.
		pickTool(context, 2);
		pickColour(context, 14);
		for (int[] corner : new int[][] {{-26, -30}, {26, -30}, {28, 24}, {-26, 24}}) {
			float[] at = gui(context, cx + corner[0], base + 1, cz + corner[1]);
			click(context, at[0], at[1]);
		}
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		Drawing area = last(context, dimension, before + 3);
		expect(area.kind == Drawing.Kind.AREA && area.pointCount() == 4 && area.color == Waypoint.PALETTE[14], "a four-corner light blue area, got " + describe(area));

		// White, then a note on the keep. Its text has a c in it, which on the map switches to caves.
		pickTool(context, 3);
		pickColour(context, 9);
		float[] keep = gui(context, cx + 2, base + 12, cz - 10);
		click(context, keep[0], keep[1]);
		context.getInput().typeChars("Home, cows in the yard");
		// A real key press of C comes with the typed c; it mustn't reach the map.
		context.getInput().pressKey(InputConstants.KEY_C);
		frame(context);
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		Drawing note = last(context, dimension, before + 4);
		expect(note.kind == Drawing.Kind.NOTE && note.text.equals("Home, cows in the yard") && note.color == Waypoint.PALETTE[9], "a white note, got " + describe(note));
		int layer = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().layer());
		expect(layer == TileKey.SURFACE, "pressing C while typing a note left the map on the surface");
		// Another, by the gate, kept by clicking the colour button half-way rather than with Enter.
		float[] gateNote = gui(context, cx + 14, base + 1, cz + 30);
		click(context, gateNote[0], gateNote[1]);
		context.getInput().typeChars("Gate");
		pickColour(context, 2);
		Drawing kept = last(context, dimension, before + 5);
		expect(kept.kind == Drawing.Kind.NOTE && kept.text.equals("Gate") && kept.color == Waypoint.PALETTE[9], "a click on the colour button keeps a half-typed note, got " + describe(kept));
		moveTo(context, 1300 / 4.0, 30 / 4.0);
		context.waitTicks(3);
		context.takeScreenshot("drawing-01-drawn");

		// Erase the line by dragging over it, and leave draw mode with Esc before letting go. Back
		// in draw mode, the Undo button still brings it back where it was.
		pickTool(context, 4);
		float[] middle = {(line[0][0] + line[1][0]) / 2, (line[0][1] + line[1][1]) / 2};
		moveTo(context, middle[0] - 2, middle[1] + 2);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		moveTo(context, middle[0], middle[1]);
		moveTo(context, middle[0] + 2, middle[1] - 2);
		int left = context.computeOnClient(mc -> DrawingStore.get().in(dimension).size());
		expect(left == before + 4, "erasing the line leaves four drawings, got " + (left - before));
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		expect(context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen map && !map.drawMode().active()), "Esc mid-erase leaves draw mode and keeps the map open");
		click(context, widget(context, s -> s.drawButton()));
		click(context, widget(context, s -> s.drawMode().otherButtons().get(1)));
		List<Drawing> after = context.computeOnClient(mc -> DrawingStore.get().in(dimension));
		expect(after.size() == before + 5 && after.get(before + 1).id.equals(clicked.id), "Undo puts the line back in its place");

		// Measure 24 blocks along the courtyard's flat cross path, east to west of the gate path.
		pickTool(context, 5);
		float[] west = gui(context, cx - 12, base + 1, cz + 8);
		float[] east = gui(context, cx + 12, base + 1, cz + 8);
		moveTo(context, west[0], west[1]);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		for (int i = 1; i <= 6; i++) {
			moveTo(context, west[0] + (east[0] - west[0]) * i / 6, west[1] + (east[1] - west[1]) * i / 6);
		}
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		int measured = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawMode().measured());
		String reading = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawMode().measuredText());
		expect(measured >= 23 && measured <= 25 && reading != null && reading.contains("east"), "about 24 blocks east across the courtyard, read " + reading);
		frame(context);
		context.takeScreenshot("drawing-01b-measure");
		// The first Esc clears the reading and stays in draw mode.
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		expect(context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawMode().active() && ((MapScreen) mc.gui.screen()).drawMode().measured() == -1), "the first Esc clears the measurement and stays in draw mode");
		// Then from the courtyard down to the road outside the walls: across the ground on a
		// slant, south, and down to wherever the server's ground there is.
		int roadY = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, cx + 1, cz + 40));
		float[] yard = gui(context, cx - 12, base + 1, cz + 8);
		float[] outside = gui(context, cx + 1, roadY, cz + 40);
		moveTo(context, yard[0], yard[1]);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		for (int i = 1; i <= 6; i++) {
			moveTo(context, yard[0] + (outside[0] - yard[0]) * i / 6, yard[1] + (outside[1] - yard[1]) * i / 6);
		}
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		int slant = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawMode().measured());
		String slantReading = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawMode().measuredText());
		int climb = roadY - (base + 1);
		String expectedClimb = climb == 0 ? null : Math.abs(climb) + (climb > 0 ? " up" : " down");
		expect(Math.abs(slant - (int) Math.round(Math.hypot(13, 32))) <= 1 && slantReading != null && slantReading.contains("13 east") && slantReading.contains("32 south")
			&& (expectedClimb == null ? !slantReading.contains(" up") && !slantReading.contains(" down") : slantReading.contains(expectedClimb)),
			"about 35 blocks, 13 east, 32 south" + (expectedClimb == null ? "" : ", " + expectedClimb) + " down to the road, read " + slantReading);
		context.takeScreenshot("drawing-01c-measure-slant");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);

		// Esc leaves draw mode but not the map.
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		boolean stillMap = context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen map && !map.drawMode().active());
		expect(stillMap, "Esc leaves draw mode and keeps the map open");
		moveTo(context, 1300 / 4.0, 30 / 4.0);
		context.waitTicks(3);
		context.takeScreenshot("drawing-02-map");
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 1.2, TileKey.SURFACE));
		settle(context, 60);
		context.takeScreenshot("drawing-03-far");

		// Saved: the file read afresh has the same drawings, in the same order, point for point.
		List<String> live = context.computeOnClient(mc -> DrawingStore.get().in(dimension).stream().map(DrawingTest::full).toList());
		List<String> saved = context.computeOnClient(mc -> DrawingStore.open(WorldKey.current()).in(dimension).stream().map(DrawingTest::full).toList());
		expect(live.size() == before + 5 && saved.equals(live), "the five drawings read back from the file as drawn: " + saved + " vs " + live);

		// The minimap: lines and the area, not the note; then the setting turns them off there.
		context.setScreen(() -> null);
		settle(context, 40);
		context.takeScreenshot("drawing-04-minimap");
		context.runOnClient(mc -> Settings.get().drawings = Settings.DrawingsShown.FULLSCREEN);
		context.waitTicks(3);
		context.takeScreenshot("drawing-05-minimap-off");
		context.runOnClient(mc -> Settings.get().drawings = Settings.DrawingsShown.ALWAYS);
	}

	private interface WidgetOf {
		AbstractWidget get(MapScreen screen);
	}

	private static float[] widget(ClientGameTestContext context, WidgetOf of) {
		return context.computeOnClient(mc -> {
			AbstractWidget w = of.get((MapScreen) mc.gui.screen());
			return new float[] {w.getX() + w.getWidth() / 2.0F, w.getY() + w.getHeight() / 2.0F};
		});
	}

	private static void pickTool(ClientGameTestContext context, int tool) {
		click(context, widget(context, s -> s.drawMode().toolButtons().get(tool)));
	}

	private static void pickColour(ClientGameTestContext context, int k) {
		click(context, widget(context, s -> s.drawMode().otherButtons().get(0)));
		float[] swatch = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawMode().swatch(k));
		click(context, swatch[0], swatch[1]);
	}

	/** Where a world point is on the map screen, in GUI pixels. */
	private static float[] gui(ClientGameTestContext context, double x, double y, double z) {
		return context.computeOnClient(mc -> {
			MapViewport.View view = ((MapScreen) mc.gui.screen()).view();
			return new float[] {view.screenX(x + 0.5), view.screenY(Projection.v(y, z + 0.5))};
		});
	}

	private static Drawing last(ClientGameTestContext context, String dimension, int expected) {
		List<Drawing> all = context.computeOnClient(mc -> DrawingStore.get().in(dimension));
		if (all.size() != expected) {
			throw new AssertionError("Expected " + expected + " drawings, found " + all.size());
		}
		return all.get(all.size() - 1);
	}

	private static String full(Drawing d) {
		return d.id + " " + d.kind + " " + Integer.toHexString(d.color) + " " + java.util.Arrays.toString(d.points) + " " + d.text;
	}

	private static String describe(Drawing d) {
		return d.kind + " with " + d.pointCount() + " points in " + Integer.toHexString(d.color) + (d.text.isEmpty() ? "" : " \"" + d.text + "\"");
	}

	private static void expect(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Drawing: expected " + what);
		}
	}

	private static void click(ClientGameTestContext context, float[] at) {
		click(context, at[0], at[1]);
	}

	/** Moves to a point in GUI pixels and clicks the left button there. */
	private static void click(ClientGameTestContext context, double x, double y) {
		moveTo(context, x, y);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		frame(context);
	}

	private static void moveTo(ClientGameTestContext context, double x, double y) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		cursor = new double[] {x * scale, y * scale};
		context.getInput().setCursorPos(cursor[0], cursor[1]);
		context.waitTick();
		frame(context);
	}

	/** A GIF frame, the cursor's position in its name: the game's screenshots leave the cursor out. */
	private static void frame(ClientGameTestContext context) {
		if (GIF) {
			context.takeScreenshot(String.format("drawing-gif-%03d-%d-%d", frames++, Math.round(cursor[0]), Math.round(cursor[1])));
		}
	}

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
