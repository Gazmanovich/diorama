package dev.diorama.test;

import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointBeams;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.WorldProjection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Beams on waypoints in the world (opt-in: {@code waypoint-beams}): which waypoints get one under
 * each choice of the setting, read off the screen where each beam stands, and pictures by day and
 * night, from close by, looking up one, and with a hundred and twenty waypoints.
 */
final class WaypointBeamsTest {
	private static final String OVERWORLD = "minecraft:overworld";
	/** A pixel this near a beam's colour is in a beam. */
	private static final int IN_BEAM = 70;

	private WaypointBeamsTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("difficulty peaceful");
		if (context.computeOnClient(mc -> mc.player.isSpectator())) {
			throw new AssertionError("Run waypoint-beams without showcase or icon-waypoints: they leave the player spectating");
		}
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos spawn = context.computeOnClient(mc -> mc.player.blockPosition());
		// A lookout over the trees, facing north and a little down, as from a hilltop: the beams ahead
		// stand against the land and the sky both.
		BlockPos at = new BlockPos(spawn.getX(), surface(context, spawn.getX(), spawn.getZ()) + 16, spawn.getZ());
		stand(world, at.getX(), at.getY(), at.getZ(), 4);
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);

		Settings.Beams before = context.computeOnClient(mc -> Settings.get().waypointBeams);
		Waypoint camp = on(context, "Camp", at.getX(), at.getZ() - 45, 0xFFF76B15);
		camp.kind = Waypoint.Kind.TEMPORARY;
		Waypoint death = on(context, "Death", at.getX() - 40, at.getZ() - 140, 0xFFE5484D);
		death.kind = Waypoint.Kind.DEATH;
		Waypoint home = on(context, "Home", at.getX() + 25, at.getZ() - 80, 0xFFD6409F);
		// Past where the land is loaded, at the lookout's own height.
		Waypoint farm = new Waypoint("Iron Farm", at.getX() - 150, at.getY() - 16, at.getZ() - 600, OVERWORLD, 0xFFFFFFFF);
		Waypoint village = new Waypoint("Village", at.getX() + 420, at.getY() - 16, at.getZ() - 1400, OVERWORLD, 0xFFFFC53D);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
			for (Waypoint waypoint : new Waypoint[] {camp, death, home, farm, village}) {
				store.add(waypoint);
			}
		});

		BufferedImage off = shot(context, Settings.Beams.OFF, "beams-day-off");
		BufferedImage temporary = shot(context, Settings.Beams.TEMPORARY, "beams-day-temporary");
		BufferedImage all = shot(context, Settings.Beams.ALL, "beams-day-all");
		// Points up each beam, above its pin.
		int[][] onCamp = points(context, camp, off);
		int[][] onDeath = points(context, death, off);
		int[][] onHome = points(context, home, off);
		expect(off, onCamp, camp, false, "off, the temporary waypoint");
		expect(off, onDeath, death, false, "off, the death marker");
		expect(off, onHome, home, false, "off, the ordinary waypoint");
		expect(temporary, onCamp, camp, true, "temporary and deaths, the temporary waypoint");
		expect(temporary, onDeath, death, true, "temporary and deaths, the death marker");
		expect(temporary, onHome, home, false, "temporary and deaths, the ordinary waypoint");
		expect(all, onCamp, camp, true, "all, the temporary waypoint");
		expect(all, onDeath, death, true, "all, the death marker");
		expect(all, onHome, home, true, "all, the ordinary waypoint");

		// A waypoint past the distance limit has no beam, one inside it has, and a temporary one keeps
		// its beam at any distance, as it keeps its pin. Home is about 85 blocks off.
		context.runOnClient(mc -> Settings.get().waypointDistance = 64);
		BufferedImage limited = shot(context, Settings.Beams.ALL, "beams-day-limited");
		expect(limited, onHome, home, false, "all and a distance of 64, the ordinary waypoint past it");
		expect(limited, onDeath, death, true, "all and a distance of 64, the death marker");
		context.runOnClient(mc -> Settings.get().waypointDistance = 128);
		expect(shot(context, Settings.Beams.ALL, "beams-day-limit-128"), onHome, home, true, "all and a distance of 128, the ordinary waypoint inside it");
		// A hidden waypoint has none.
		context.runOnClient(mc -> {
			Settings.get().waypointDistance = 0;
			home.visible = false;
		});
		expect(shot(context, Settings.Beams.ALL, "beams-day-hidden"), onHome, home, false, "all, a hidden waypoint");
		context.runOnClient(mc -> home.visible = true);

		// From close by a beam fades, and is gone at the waypoint; looking up one, it narrows into the sky.
		for (int away : new int[] {20, 8, 5, 2}) {
			// On whatever is there, a tree's top if it comes to that: never inside one.
			String spot = "tp @a " + (home.x + 0.5) + " " + (surface(context, home.x, home.z + away) + 1) + " " + (home.z + away + 0.5) + " 180 ";
			world.getServer().runCommand(spot + "0");
			context.waitTicks(220);
			context.takeScreenshot("beams-near-" + away);
			if (away == 20) {
				// Looking nearly straight up it: the beam still shows, right to where it leaves the screen.
				world.getServer().runCommand(spot + "-80");
				context.waitTicks(220);
				BufferedImage up = shot(context, Settings.Beams.ALL, "beams-look-up");
				expect(up, points(context, home, up, 160), home, true, "looking up, the ordinary waypoint");
			}
		}

		stand(world, at.getX(), at.getY(), at.getZ(), 4);
		world.getServer().runCommand("time set midnight");
		context.waitTicks(220);
		shot(context, Settings.Beams.TEMPORARY, "beams-night-temporary");
		BufferedImage night = shot(context, Settings.Beams.ALL, "beams-night-all");
		expect(night, onHome, home, true, "at night, the ordinary waypoint");
		world.getServer().runCommand("time set noon");
		context.waitTicks(220);

		// A world full of waypoints, every one with a beam.
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			Random random = new Random(151);
			for (int i = 0; i < 120; i++) {
				double distance = i < 12 ? 30 + random.nextDouble() * 200 : 260 + random.nextDouble() * 1500;
				double angle = random.nextDouble() * Math.PI * 2;
				int x = at.getX() + (int) Math.round(Math.cos(angle) * distance);
				int z = at.getZ() + (int) Math.round(Math.sin(angle) * distance);
				store.add(new Waypoint("W" + i, x, at.getY() + random.nextInt(40) - 20, z, OVERWORLD, Waypoint.PALETTE[random.nextInt(Waypoint.PALETTE.length)]));
			}
		});
		shot(context, Settings.Beams.ALL, "beams-clutter-all");
		shot(context, Settings.Beams.TEMPORARY, "beams-clutter-temporary");

		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(3);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(3));
		// Off the buttons, so no tooltip covers them.
		context.getInput().setCursorPos(1500, 880);
		context.waitTicks(5);
		context.takeScreenshot("beams-settings");
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			Settings.get().waypointBeams = before;
			Settings.get().save();
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
		});
	}

	private static BufferedImage shot(ClientGameTestContext context, Settings.Beams beams, String name) {
		context.runOnClient(mc -> Settings.get().waypointBeams = beams);
		context.waitTicks(10);
		Path file = context.takeScreenshot(name);
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A waypoint on top of whatever stands at (x, z). */
	private static Waypoint on(ClientGameTestContext context, String name, int x, int z, int color) {
		return new Waypoint(name, x, surface(context, x, z), z, OVERWORLD, color);
	}

	/** The height of the highest block at (x, z), which has to be loaded. */
	private static int surface(ClientGameTestContext context, int x, int z) {
		return context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)) - 1;
	}

	/** Stands the player in the block (x, y, z) on a block nobody can see, facing north at {@code pitch}. */
	private static void stand(TestSingleplayerContext world, int x, int y, int z, int pitch) {
		world.getServer().runCommand("setblock " + x + " " + (y - 1) + " " + z + " minecraft:barrier");
		world.getServer().runCommand("tp @a " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 180 " + pitch);
	}

	/** Where on a screenshot the points 8 to 40 blocks above a waypoint's spot are, those that are on it. */
	private static int[][] points(ClientGameTestContext context, Waypoint waypoint, BufferedImage shot) {
		return points(context, waypoint, shot, 40);
	}

	/** The same up to {@code highest} blocks above it, for a view up the beam. */
	private static int[][] points(ClientGameTestContext context, Waypoint waypoint, BufferedImage shot, int highest) {
		List<int[]> points = new ArrayList<>();
		for (int up = 8; up <= highest; up += 4) {
			double y = waypoint.y + 1.0 + up;
			float[] screen = context.computeOnClient(mc -> WorldProjection.project(waypoint.x + 0.5, y, waypoint.z + 0.5, shot.getWidth(), shot.getHeight()));
			if (screen != null && screen[0] >= 0 && screen[1] >= 0 && screen[0] < shot.getWidth() && screen[1] < shot.getHeight()) {
				points.add(new int[] {Math.round(screen[0]), Math.round(screen[1])});
			}
		}
		if (points.size() < 4) {
			throw new AssertionError("Too little of the beam of " + waypoint.name + " would be on screen: " + points.size() + " points");
		}
		return points.toArray(int[][]::new);
	}

	/**
	 * A beam shows at most of its points: another waypoint's pin or label may cover a few. Without one,
	 * none of them is the beam's colour.
	 */
	private static void expect(BufferedImage shot, int[][] points, Waypoint waypoint, boolean beam, String what) {
		int colour = WaypointBeams.coreColor(waypoint.color & 0xFFFFFF);
		int found = 0;
		StringBuilder seen = new StringBuilder();
		for (int[] at : points) {
			// A far beam is a few pixels wide, and the point is rounded to one: the nearest match close by counts.
			double away = Double.MAX_VALUE;
			int nearest = 0;
			for (int x = Math.max(0, at[0] - 4); x <= Math.min(shot.getWidth() - 1, at[0] + 4); x++) {
				int rgb = shot.getRGB(x, at[1]);
				double d = Math.sqrt(square((rgb >> 16 & 0xFF) - (colour >> 16 & 0xFF)) + square((rgb >> 8 & 0xFF) - (colour >> 8 & 0xFF)) + square((rgb & 0xFF) - (colour & 0xFF)));
				if (d < away) {
					away = d;
					nearest = rgb;
				}
			}
			if (away < IN_BEAM) {
				found++;
			}
			seen.append(" #").append(Integer.toHexString(nearest & 0xFFFFFF)).append(" at ").append(at[0]).append(',').append(at[1]);
		}
		if (beam ? found * 3 < points.length * 2 : found > 0) {
			throw new AssertionError("With beams " + what + (beam ? " should stand in a beam" : " should have no beam") + ": " + found + " of " + points.length
				+ " points up it are the beam's colour #" + Integer.toHexString(colour) + ":" + seen);
		}
	}

	private static double square(double v) {
		return v * v;
	}
}
