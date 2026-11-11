package dev.diorama.test;

import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointStore;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Death markers that clean up after themselves (opt-in: {@code death-pins}, issues #271 and #274):
 * a death pin shows the date and time of the death, stays while the player is further than 4
 * blocks, goes once they're back within 4, and stays put during its first 5 seconds, while it's
 * hidden, while a screen is open, and with the setting off.
 */
final class DeathPinsTest {
	private DeathPinsTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		context.runOnClient(mc -> {
			Settings.get().deathMarkers = true;
			Settings.get().removeReachedDeaths = true;
		});
		// Both names: older versions spell game rules in camel case, and a wrong name only fails the command.
		for (String rule : new String[] {"show_death_messages", "showDeathMessages", "send_command_feedback", "sendCommandFeedback"}) {
			world.getServer().runCommand("gamerule " + rule + " false");
		}
		BlockPos home = context.computeOnClient(mc -> mc.player.blockPosition());

		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(3));
		context.waitTicks(5);
		context.takeScreenshot("death-pins-0-setting");
		context.setScreen(() -> null);

		// Die 40 blocks from where the player respawns, so respawning doesn't reach the pin.
		Waypoint death = die(context, world, home.offset(40, 0, 0));
		String expected = context.computeOnClient(mc -> net.minecraft.network.chat.Component.translatable("diorama.waypoint.death", Waypoint.deathDate(death.created)).getString());
		String shown = context.computeOnClient(mc -> death.shownName());
		String day = Integer.toString(java.time.Instant.ofEpochMilli(death.created).atZone(java.time.ZoneId.systemDefault()).getDayOfMonth());
		if (!shown.equals(expected) || !shown.contains(day) || !death.name.matches("Death \\d\\d:\\d\\d")) {
			throw new AssertionError("The death pin saved as " + death.name + " shows " + shown + ", expected " + expected + " with the day " + day);
		}
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("death-pins-1-list");
		context.setScreen(() -> null);

		// 7 blocks off, looking at it, past the first 5 seconds: it stays.
		standAt(context, world, death, 7, 90);
		context.waitFor(mc -> System.currentTimeMillis() - death.created > 6000, 400);
		context.waitTicks(20);
		expectKept(context, death, "7 blocks away");
		context.takeScreenshot("death-pins-2-seven-blocks");

		// Hidden, it stays even when reached.
		context.runOnClient(mc -> {
			death.visible = false;
			WaypointStore.get().changed();
		});
		standAt(context, world, death, 2, 90);
		context.waitTicks(40);
		expectKept(context, death, "hidden, 2 blocks away");

		// Shown again, 2 blocks off, but with the waypoint list open: it waits for the list to close.
		context.setScreen(() -> new WaypointListScreen(null));
		context.runOnClient(mc -> {
			death.visible = true;
			WaypointStore.get().changed();
		});
		context.waitTicks(40);
		expectKept(context, death, "2 blocks away with the waypoint list open");
		// Then it goes.
		context.setScreen(() -> null);
		context.waitFor(mc -> !WaypointStore.get().all().contains(death), 40);
		standAt(context, world, death, 7, 90);
		context.waitTicks(20);
		context.takeScreenshot("death-pins-3-reached");

		// A new pin is left alone for 5 seconds, even with the player on it. Its clock starts again
		// here, so a slow respawn doesn't use up the 5 seconds.
		Waypoint fresh = die(context, world, home.offset(40, 0, 0));
		context.runOnClient(mc -> fresh.created = System.currentTimeMillis());
		standAt(context, world, fresh, 1, 90);
		context.waitTicks(10);
		long age = context.computeOnClient(mc -> System.currentTimeMillis() - fresh.created);
		if (age >= 5000) {
			throw new AssertionError("Standing on the pin took " + age + " ms, too long to check the first 5 seconds");
		}
		expectKept(context, fresh, "1 block away, " + age + " ms old");
		context.waitFor(mc -> !WaypointStore.get().all().contains(fresh), 200);

		// With the setting off, a reached pin stays.
		context.runOnClient(mc -> Settings.get().removeReachedDeaths = false);
		Waypoint kept = die(context, world, home.offset(40, 0, 0));
		standAt(context, world, kept, 1, 90);
		context.waitFor(mc -> System.currentTimeMillis() - kept.created > 6000, 400);
		context.waitTicks(20);
		expectKept(context, kept, "1 block away with the setting off");

		// Put back what the sections after this one expect.
		context.runOnClient(mc -> {
			Settings.get().removeReachedDeaths = true;
			WaypointStore.get().remove(kept);
		});
		world.getServer().runCommand("tp @a " + (home.getX() + 0.5) + " " + home.getY() + " " + (home.getZ() + 0.5));
		for (String rule : new String[] {"send_command_feedback", "sendCommandFeedback"}) {
			world.getServer().runCommand("gamerule " + rule + " true");
		}
	}

	/** Takes the player to a spot, kills them, respawns them, and returns the new death pin. */
	private static Waypoint die(ClientGameTestContext context, TestSingleplayerContext world, BlockPos spot) {
		int y = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, spot.getX(), spot.getZ()));
		// A flat floor east of the spot, so standing a few blocks off is at the pin's height.
		int x = spot.getX();
		int z = spot.getZ();
		world.getServer().runCommand("fill " + (x - 2) + " " + (y - 1) + " " + (z - 2) + " " + (x + 10) + " " + (y - 1) + " " + (z + 2) + " stone");
		world.getServer().runCommand("fill " + (x - 2) + " " + y + " " + (z - 2) + " " + (x + 10) + " " + (y + 3) + " " + (z + 2) + " air");
		world.getServer().runCommand("tp @a " + (x + 0.5) + " " + y + " " + (z + 0.5));
		context.waitFor(mc -> mc.player.onGround(), 600);
		Set<UUID> before = context.computeOnClient(mc -> WaypointStore.get().all().stream().map(w -> w.id).collect(Collectors.toSet()));
		world.getServer().runCommand("kill @a");
		context.waitFor(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.DEATH && !before.contains(w.id)), 200);
		Waypoint death = context.computeOnClient(mc -> WaypointStore.get().all().stream()
			.filter(w -> w.kind == Waypoint.Kind.DEATH && !before.contains(w.id)).findFirst().orElseThrow());
		// The death screen's buttons wake after a second.
		context.waitTicks(25);
		context.clickScreenButton("deathScreen.respawn");
		context.waitFor(mc -> mc.player != null && mc.player.isAlive(), 200);
		double away = context.computeOnClient(mc -> death.distanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
		if (away < 8) {
			throw new AssertionError("Respawned " + away + " blocks from the death pin");
		}
		return death;
	}

	/** Stands the player {@code blocks} east of a pin on the ground, facing it (west). */
	private static void standAt(ClientGameTestContext context, TestSingleplayerContext world, Waypoint pin, int blocks, float yaw) {
		world.getServer().runCommand("tp @a " + (pin.x + blocks + 0.5) + " " + pin.y + " " + (pin.z + 0.5) + " " + yaw + " 20");
		// The client takes a moment to hear of the teleport, longer on older versions.
		context.waitFor(mc -> Math.abs(pin.distanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ()) - blocks) <= 1.5, 100);
	}

	private static void expectKept(ClientGameTestContext context, Waypoint pin, String where) {
		if (!context.computeOnClient(mc -> WaypointStore.get().all().contains(pin))) {
			throw new AssertionError("The death pin went while the player was " + where);
		}
	}
}
