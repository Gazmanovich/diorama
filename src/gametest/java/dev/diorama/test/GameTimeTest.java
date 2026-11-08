package dev.diorama.test;

import dev.diorama.MapSession;
import dev.diorama.MinimapHud;
import dev.diorama.Settings;
import dev.diorama.Settings.GameTime;
import dev.diorama.SettingsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//? if >=1.21.4 && fabric {
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
//?}
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.Level;

/**
 * The game time under the minimap: its formats, the colour it turns once a bed would work, and the
 * clock it reads in the Nether and the End and with the daylight cycle off.
 */
final class GameTimeTest {
	private GameTimeTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		formats();
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		if (context.computeOnClient(mc -> Settings.get().gameTime) != GameTime.OFF) {
			throw new AssertionError("The game time should start off");
		}
		context.setScreen(() -> null);
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);
		context.takeScreenshot("game-time-01-off");

		// A real click, in the Corner row's other half.
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("game-time-01-settings-off");
		DioramaClientTest.click(context, "Game time: ");
		if (context.computeOnClient(mc -> Settings.get().gameTime) != GameTime.DAY_24H) {
			throw new AssertionError("Clicking Game time didn't pick the first format");
		}
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("game-time-01-settings-on");
		context.setScreen(() -> null);

		// Either side of when beds start and stop working. The daylight cycle stands still to stay there.
		world.getServer().runCommand("gamerule advance_time false");
		long[][] times = {{6000, 0}, {12000, 0}, {12800, 1}, {18000, 1}, {23200, 1}, {23800, 0}};
		for (long[] time : times) {
			world.getServer().runCommand("time set " + time[0]);
			boolean dark = time[1] == 1;
			// Not every test harness ticks the client in step with the server.
			context.waitFor(mc -> mc.level.getOverworldClockTime() == time[0] && mc.level.isDarkOutside() == dark, 100);
			assertBedTime(context, world, dark, "at tick " + time[0]);
			context.takeScreenshot("game-time-02-tick-" + time[0]);
		}
		world.getServer().runCommand("gamerule advance_time true");

		// A thunderstorm makes it dark enough at noon.
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather thunder");
		context.waitFor(mc -> mc.level.isThundering(), 200);
		context.waitFor(mc -> mc.level.isDarkOutside(), 400);
		assertBedTime(context, world, true, "at noon in a thunderstorm");
		context.takeScreenshot("game-time-03-thunder");
		world.getServer().runCommand("weather clear");
		context.waitFor(mc -> !mc.level.isDarkOutside(), 400);

		for (GameTime format : GameTime.values()) {
			context.runOnClient(mc -> Settings.get().gameTime = format);
			context.waitTicks(2);
			context.takeScreenshot("game-time-04-" + format.name().toLowerCase(java.util.Locale.ROOT));
		}
		context.runOnClient(mc -> Settings.get().gameTime = GameTime.DAY_24H);

		// With the daylight cycle off the clock stands at what the sky shows, and older servers send
		// that time as a negative number.
		world.getServer().runCommand("gamerule advance_time false");
		world.getServer().runCommand("time set 14000");
		context.waitTicks(40);
		long frozen = clock(context);
		context.waitTicks(40);
		if (frozen != 14000 || clock(context) != frozen) {
			throw new AssertionError("With the daylight cycle off the clock read " + frozen + ", then " + clock(context));
		}
		context.takeScreenshot("game-time-05-frozen");
		world.getServer().runCommand("gamerule advance_time true");

		// The Nether and the End have no day of their own: the Overworld's clock keeps running there.
		world.getServer().runCommand("time set 13000");
		for (String dimension : new String[] {"nether", "end"}) {
			world.getServer().runCommand("execute in minecraft:the_" + dimension + " run tp @a 0 70 0");
			context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == ("nether".equals(dimension) ? Level.NETHER : Level.END), 2400);
			long before = clock(context);
			// The client counts on its own between the server's updates, once a second. A slow
			// machine's server falls behind after changing dimension, and its next update pulls the
			// clock back a few ticks, the sky with it. So the clock only has to get a second past
			// where it was: a stopped or backward clock never does. Two seconds at least, so the
			// screenshot comes after the loading screen as before.
			context.waitTicks(40);
			long after = clock(context);
			for (int tick = 0; tick < 400 && after < before + 20; tick++) {
				context.waitTick();
				after = clock(context);
			}
			long server = world.getServer().computeOnServer(s -> s.overworld().getOverworldClockTime());
			if (after < before + 20 || Math.abs(server - after) > 200) {
				throw new AssertionError("In the " + dimension + " the clock went from " + before + " to " + after + "; the Overworld's is at " + server);
			}
			context.takeScreenshot("game-time-06-" + dimension);
		}
		world.getServer().runCommand("execute in minecraft:overworld run tp @a 0 100 0");
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == Level.OVERWORLD, 2400);
		world.getServer().runCommand("time set noon");
		context.runOnClient(mc -> {
			Settings.get().gameTime = GameTime.OFF;
			// The click saved its choice, and the next run starts from what's saved.
			Settings.get().save();
		});
	}

	/** The same clock on a dedicated server, where /skipnight lives: sent over the network, stopped and started by the server. */
	//? if >=1.21.4 && fabric {
	static void onServer(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama-game-time"))
			.createServer(DioramaClientTest.serverProperties())) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
				context.runOnClient(mc -> Settings.get().gameTime = GameTime.DAY_24H);
				server.runCommand("gamerule advance_time false");
				server.runCommand("time set 14000");
				context.waitFor(mc -> mc.level.getOverworldClockTime() == 14000 && mc.level.isDarkOutside(), 200);
				context.waitTicks(40);
				if (clock(context) != 14000) {
					throw new AssertionError("On a server with the daylight cycle off the clock moved to " + clock(context));
				}
				context.takeScreenshot("game-time-07-server-frozen-night");
				server.runCommand("time set 6000");
				context.waitFor(mc -> mc.level.getOverworldClockTime() == 6000 && !mc.level.isDarkOutside(), 200);
				context.takeScreenshot("game-time-07-server-frozen-noon");
				server.runCommand("gamerule advance_time true");
				context.waitFor(mc -> mc.level.getOverworldClockTime() > 6040, 200);
			}
		}
		context.runOnClient(mc -> Settings.get().gameTime = GameTime.OFF);
	}
	//?}

	private static void formats() {
		String[][] expected = {
			// Ticks, then Day 24h, Day 12h, 24h and 12h.
			{"0", "Day 1, 06:00", "Day 1, 06:00 AM", "06:00", "06:00 AM"},
			{"6000", "Day 1, 12:00", "Day 1, 12:00 PM", "12:00", "12:00 PM"},
			{"7000", "Day 1, 13:00", "Day 1, 01:00 PM", "13:00", "01:00 PM"},
			{"12542", "Day 1, 18:32", "Day 1, 06:32 PM", "18:32", "06:32 PM"},
			{"17999", "Day 1, 23:59", "Day 1, 11:59 PM", "23:59", "11:59 PM"},
			{"18000", "Day 2, 00:00", "Day 2, 12:00 AM", "00:00", "12:00 AM"},
			{"19000", "Day 2, 01:00", "Day 2, 01:00 AM", "01:00", "01:00 AM"},
			{"240000", "Day 11, 06:00", "Day 11, 06:00 AM", "06:00", "06:00 AM"},
		};
		GameTime[] formats = {GameTime.DAY_24H, GameTime.DAY_12H, GameTime.ONLY_24H, GameTime.ONLY_12H};
		for (String[] row : expected) {
			for (int i = 0; i < formats.length; i++) {
				String shown = MinimapHud.gameTime(formats[i], Long.parseLong(row[0]));
				if (!shown.equals(row[i + 1])) {
					throw new AssertionError("Tick " + row[0] + " in " + formats[i] + " reads " + shown + ", not " + row[i + 1]);
				}
			}
		}
	}

	/** The client turns the line by its own copy of this; a bed asks the server's. */
	private static void assertBedTime(ClientGameTestContext context, TestSingleplayerContext world, boolean expected, String when) {
		boolean client = context.computeOnClient(mc -> mc.level.isDarkOutside());
		boolean server = world.getServer().computeOnServer(s -> s.overworld().isDarkOutside());
		if (client != expected || server != expected) {
			throw new AssertionError("Dark enough for a bed " + when + ": client " + client + ", server " + server + ", expected " + expected);
		}
	}

	private static long clock(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.level.getOverworldClockTime());
	}
}
