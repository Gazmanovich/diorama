package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.bake.TileKey;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.util.Random;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A player with a hundred and twenty waypoints, as after importing years of Xaero's Minimap
 * (opt-in: {@code waypoint-clutter}): the world, the square and round minimap and the map, with
 * the waypoint distance setting off and at 256, and the setting in the settings screen.
 */
final class WaypointClutterTest {
	private static final String[] NAMES = {
		"Home", "Iron Farm", "Village", "Spawner", "Mineshaft", "Base", "Desert Temple", "Witch Hut",
		"Ocean Monument", "Portal", "Shipwreck", "Ruined Portal", "Gold Farm", "Trading Hall", "Outpost",
		"Ancient City", "Igloo", "Mansion", "Cave", "Lava Lake", "Diamonds", "Slime Chunk", "Stronghold",
		"Camp", "Bridge", "Tower", "Farm", "Storage", "Beacon", "Mesa"
	};

	private WaypointClutterTest() {
	}

	static void photos(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("difficulty peaceful");
		// The showcase and icon-waypoints leave the player spectating in the air, where this would wait for the ground forever.
		if (context.computeOnClient(mc -> mc.player.isSpectator())) {
			throw new AssertionError("Run waypoint-clutter without showcase or icon-waypoints: they leave the player spectating");
		}
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		// Facing north, level, so the pins ahead spread across the view.
		world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 5");
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			String overworld = "minecraft:overworld";
			Random random = new Random(151);
			for (int i = 0; i < 120; i++) {
				// A few close by, most a long way off, the way a world fills up over the years.
				double distance = i < 12 ? 30 + random.nextDouble() * 200 : 260 + random.nextDouble() * 1500;
				double angle = random.nextDouble() * Math.PI * 2;
				int x = at.getX() + (int) Math.round(Math.cos(angle) * distance);
				int z = at.getZ() + (int) Math.round(Math.sin(angle) * distance);
				int y = distance < 200 ? mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : at.getY() + random.nextInt(40) - 20;
				String name = NAMES[i % NAMES.length] + (i < NAMES.length ? "" : " " + (i / NAMES.length + 1));
				store.add(new Waypoint(name, x, y, z, overworld, Waypoint.PALETTE[random.nextInt(Waypoint.PALETTE.length)]));
			}
			Waypoint death = new Waypoint("Death", at.getX() - 300, at.getY(), at.getZ() - 700, overworld, Waypoint.PALETTE[0]);
			death.kind = Waypoint.Kind.DEATH;
			store.add(death);
		});

		for (int distance : new int[] {0, 256}) {
			String suffix = distance == 0 ? "any" : Integer.toString(distance);
			context.runOnClient(mc -> {
				Settings.get().waypointDistance = distance;
				Settings.get().minimapShape = Settings.Shape.SQUARE;
			});
			context.waitTicks(10);
			context.takeScreenshot("clutter-world-" + suffix);
			context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
			context.waitTicks(10);
			context.takeScreenshot("clutter-round-" + suffix);
			context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.SQUARE);
		}

		// The map shows every waypoint whatever the setting.
		context.setScreen(MapScreen::new);
		// Over the hint bar at the bottom, so no hover box covers the map.
		context.getInput().setCursorPos(1500, 880);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(at.getX(), Projection.v(at.getY(), at.getZ()), 0.3, TileKey.SURFACE));
		settle(context, 100);
		context.takeScreenshot("clutter-map-256");
		context.setScreen(() -> null);

		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(3);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(3));
		context.waitTicks(5);
		context.takeScreenshot("clutter-settings");
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			Settings.get().waypointDistance = 0;
			Settings.get().save();
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
		});
	}

	/** Waits until no tile has been baking for a while. */
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
