package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.WorldBorderOverlay;
import dev.diorama.bake.TileKey;
import dev.diorama.drawing.Drawing;
import dev.diorama.render.MapViewport;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The world border on the map (opt-in: {@code world-border}): nothing with the border the game
 * starts with, then a 64-block border around the player as a blue line on the fullscreen map and
 * both minimaps, following the setting, climbing a block of stone built across it, green while it
 * grows, and level in a cave slice.
 */
final class WorldBorderTest {
	/** The colours the game gives the border's wall: standing still, and growing. */
	private static final int BLUE = 0x20A0FF;
	private static final int GREEN = 0x40FF80;
	private static final int SIZE = 64;

	private WorldBorderTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("difficulty peaceful");
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, -2, -2));
		// Looking straight down, so the game's own blue wall is in none of the pictures that count blue.
		world.getServer().runCommand("tp @a -1.5 " + ground + " -1.5 180 90");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().getX() == -2 && mc.player.blockPosition().getZ() == -2, 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Settings.WorldBorderShown before = context.computeOnClient(mc -> Settings.get().worldBorder);
		if (before != Settings.WorldBorderShown.ALWAYS) {
			throw new AssertionError("The world border should show everywhere by default, not " + before);
		}
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);

		// The border a world starts with is 30 million blocks away: nothing to draw.
		if (count(map(context, at, 2, TileKey.SURFACE, "border-none-map"), BLUE) != 0) {
			throw new AssertionError("Blue on the map before any border was set");
		}
		context.setScreen(() -> null);
		int far = context.computeOnClient(mc -> new WorldBorderOverlay().sides(around(at.getX(), at.getY(), at.getZ(), 0.1875), mc.level, mc.player).size());
		if (far != 0) {
			throw new AssertionError("The unset border made " + far + " lines");
		}

		double west = at.getX() - SIZE / 2.0;
		double north = at.getZ() - SIZE / 2.0;
		// With the decimals: a whole number means the middle of that block.
		world.getServer().runCommand("worldborder center " + at.getX() + ".0 " + at.getZ() + ".0");
		world.getServer().runCommand("worldborder set " + SIZE);
		context.waitFor(mc -> mc.level.getWorldBorder().getSize() == SIZE && mc.level.getWorldBorder().getMinX() == west, 200);
		context.waitTicks(220);

		// A block of stone across the north side, higher than anything around: the line climbs it.
		int x0 = at.getX() + 6;
		int x1 = at.getX() + 12;
		int z0 = (int) north;
		int z1 = z0 + 5;
		int tallest = world.getServer().computeOnServer(server -> {
			int top = Integer.MIN_VALUE;
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					top = Math.max(top, server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
				}
			}
			return top;
		});
		int roof = tallest + 5;
		world.getServer().runCommand("fill " + x0 + " " + tallest + " " + z0 + " " + x1 + " " + roof + " " + z1 + " stone");
		context.waitFor(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x0 + 3, z0) == roof + 1, 200);
		DioramaClientTest.settle(context, 40);

		String wrong = context.computeOnClient(mc -> {
			List<Drawing> sides = new WorldBorderOverlay().sides(around(at.getX(), at.getY(), at.getZ(), 2), mc.level, mc.player);
			if (sides.size() != 4) {
				return "Expected the border's four sides in a view around it, got " + sides.size();
			}
			double[] northSide = sides.get(0).points;
			double onStone = vAt(northSide, x0 + 3.5);
			double expected = Projection.v(roof + 1, north);
			// Not NaN, which is what a line that doesn't reach the stone gives.
			if (!(Math.abs(onStone - expected) <= 1e-6)) {
				return "The north side crosses the stone at map row " + onStone + ", not on top of it at " + expected;
			}
			double[] westSide = sides.get(2).points;
			if (westSide[0] != west || westSide[2] != west) {
				return "The west side is at map column " + westSide[0] + ", not " + west;
			}
			// In a cave slice there's no one ground to follow: level, at the player's height.
			int layer = MapSession.caveLayerFor(at.getY() - 12);
			MapViewport.View caves = new MapViewport.View(0, 0, 200, 200, Projection.u(at.getX(), at.getZ()), Projection.v(at.getX(), at.getY(), at.getZ()), 2, layer);
			double[] inCaves = new WorldBorderOverlay().sides(caves, mc.level, mc.player).get(0).points;
			double level = Projection.v(Math.min(mc.player.getY(), layer), north);
			if (inCaves.length != 4 || inCaves[1] != level || inCaves[3] != level) {
				return "The north side in a cave slice should be level at map row " + level + ": " + java.util.Arrays.toString(inCaves);
			}
			return null;
		});
		if (wrong != null) {
			throw new AssertionError(wrong);
		}

		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.SQUARE);
		context.waitTicks(4);
		int square = count(read(context.takeScreenshot("border-minimap-square")), BLUE);
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
		context.waitTicks(4);
		int round = count(read(context.takeScreenshot("border-minimap-round")), BLUE);
		context.runOnClient(mc -> {
			Settings.get().minimapShape = Settings.Shape.SQUARE;
			Settings.get().worldBorder = Settings.WorldBorderShown.FULLSCREEN;
		});
		context.waitTicks(4);
		int fullscreenOnly = count(read(context.takeScreenshot("border-minimap-fullscreen-only")), BLUE);
		if (square < 40 || round < 20 || fullscreenOnly != 0) {
			throw new AssertionError("Blue pixels on the minimap: " + square + " square, " + round + " round, " + fullscreenOnly + " when set to the fullscreen map only");
		}

		int shown = count(map(context, at, 4, TileKey.SURFACE, "border-map"), BLUE);
		map(context, new BlockPos(x0 + 3, roof, z0 + 6), 10, TileKey.SURFACE, "border-map-stone");
		int cave = count(map(context, at, 4, MapSession.caveLayerFor(at.getY() - 12), "border-map-cave"), BLUE);
		context.runOnClient(mc -> Settings.get().worldBorder = Settings.WorldBorderShown.OFF);
		int off = count(map(context, at, 4, TileKey.SURFACE, "border-map-off"), BLUE);
		if (shown < 400 || cave < 400 || off != 0) {
			throw new AssertionError("Blue pixels on the map: " + shown + " shown, " + cave + " in a cave slice, " + off + " switched off");
		}
		context.runOnClient(mc -> Settings.get().worldBorder = Settings.WorldBorderShown.ALWAYS);

		// A border on the move is the colour the game draws its wall in, and moves on the map.
		// The time is in seconds on older versions and ticks on newer ones: either way it's growing for a minute or more.
		world.getServer().runCommand("worldborder add 320 1200");
		context.waitFor(mc -> mc.level.getWorldBorder().getSize() > SIZE + 2, 400);
		double grownWest = context.computeOnClient(mc -> new WorldBorderOverlay().sides(around(at.getX(), at.getY(), at.getZ(), 2), mc.level, mc.player).get(2).points[0]);
		if (!(grownWest < west - 0.25)) {
			throw new AssertionError("The west side of a growing border should have moved out from " + west + ", not be at " + grownWest);
		}
		BufferedImage growing = map(context, at, 4, TileKey.SURFACE, "border-map-growing");
		if (count(growing, GREEN) < 400 || count(growing, BLUE) != 0) {
			throw new AssertionError("A growing border should be green: " + count(growing, GREEN) + " green pixels, " + count(growing, BLUE) + " blue");
		}
		context.setScreen(() -> null);

		// For the picture: the game's wall ahead and the line on the minimap.
		world.getServer().runCommand("worldborder set " + SIZE);
		world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() - 14.5) + " 180 5");
		context.waitTicks(220);
		context.takeScreenshot("border-world");

		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(3);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(2));
		context.waitTicks(5);
		context.takeScreenshot("border-settings");
		context.setScreen(() -> null);

		world.getServer().runCommand("fill " + x0 + " " + tallest + " " + z0 + " " + x1 + " " + roof + " " + z1 + " air");
		world.getServer().runCommand("worldborder center 0 0");
		world.getServer().runCommand("worldborder set 59999968");
		world.getServer().runCommand("tp @a -1.5 " + ground + " -1.5 180 15");
		context.runOnClient(mc -> Settings.get().worldBorder = before);
		context.waitTicks(20);
	}

	/** A view the size of a minimap around a place, at {@code zoom}. */
	private static MapViewport.View around(int x, int y, int z, double zoom) {
		return new MapViewport.View(0, 0, 200, 200, Projection.u(x, z), Projection.v(x, y, z), zoom, TileKey.SURFACE);
	}

	/** The map row of a line through {@code points} (u, v pairs, u rising) at column {@code u}. */
	private static double vAt(double[] points, double u) {
		for (int i = 0; i + 3 < points.length; i += 2) {
			if (u >= points[i] && u <= points[i + 2]) {
				double t = points[i + 2] == points[i] ? 0 : (u - points[i]) / (points[i + 2] - points[i]);
				return points[i + 1] + t * (points[i + 3] - points[i + 1]);
			}
		}
		return Double.NaN;
	}

	/** Opens the map on a place and takes its picture; the map stays open. */
	private static BufferedImage map(ClientGameTestContext context, BlockPos at, double zoom, int layer, String name) {
		context.setScreen(MapScreen::new);
		// Over the hint bar at the bottom, so no hover box covers the map.
		context.getInput().setCursorPos(1500, 890);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(at.getX(), at.getZ()), Projection.v(at.getX(), at.getY(), at.getZ()), zoom, layer));
		DioramaClientTest.settle(context, 30);
		return read(context.takeScreenshot(name));
	}

	/** How many pixels are the colour, give or take the last bit or two. */
	private static int count(BufferedImage shot, int colour) {
		int found = 0;
		for (int y = 0; y < shot.getHeight(); y++) {
			for (int x = 0; x < shot.getWidth(); x++) {
				int rgb = shot.getRGB(x, y);
				if (Math.abs((rgb >> 16 & 0xFF) - (colour >> 16 & 0xFF)) <= 2 && Math.abs((rgb >> 8 & 0xFF) - (colour >> 8 & 0xFF)) <= 2 && Math.abs((rgb & 0xFF) - (colour & 0xFF)) <= 2) {
					found++;
				}
			}
		}
		return found;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
