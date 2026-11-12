package dev.diorama.test;

import dev.diorama.MapSession;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The waypoint editor's preview stands the pin on the waypoint's own ground (opt-in:
 * {@code waypoint-preview}, issue #258): a waypoint far off in the Overworld on a gold platform, one
 * on the Nether's floor and one on its roof, and one where the map has never been, each edited
 * while standing at spawn in the Overworld. The empty list's piece of map stays the player's.
 */
final class WaypointPreviewTest {
	/** Blue, so the pin is told apart from the Nether's red, the grass and the gold. */
	private static final int PIN = 0xFF3355FF;
	private static final int FAR = 1512;
	/** How far around the platform trees are cleared. */
	private static final int CLEARING = 12;

	private WaypointPreviewTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos home = context.computeOnClient(mc -> mc.player.blockPosition());
		emptyList(context, "waypoint-preview-0-empty-list");

		// Far off in the Overworld: a gold platform the map bakes, with a waypoint on it, in a
		// clearing so no tree hangs over it. Held loaded, as fill skips everything if a chunk isn't,
		// and generated first: an unloaded column's height reads as the bottom of the world.
		world.getServer().runCommand("forceload add " + (FAR - CLEARING) + " " + (FAR - CLEARING) + " " + (FAR + CLEARING) + " " + (FAR + CLEARING));
		int ground = world.getServer().computeOnServer(server -> {
			for (int cx = (FAR - CLEARING) >> 4; cx <= (FAR + CLEARING) >> 4; cx++) {
				for (int cz = (FAR - CLEARING) >> 4; cz <= (FAR + CLEARING) >> 4; cz++) {
					server.overworld().getChunk(cx, cz);
				}
			}
			return server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, FAR, FAR);
		});
		world.getServer().runCommand("fill " + (FAR - CLEARING) + " " + ground + " " + (FAR - CLEARING) + " " + (FAR + CLEARING) + " " + (ground + 40) + " " + (FAR + CLEARING) + " air");
		world.getServer().runCommand("fill " + (FAR - 7) + " " + (ground - 1) + " " + (FAR - 7) + " " + (FAR + 7) + " " + (ground - 1) + " " + (FAR + 7) + " gold_block");
		world.getServer().runCommand("tp @a " + FAR + " " + ground + " " + FAR);
		context.waitFor(mc -> mc.player.blockPosition().closerThan(new BlockPos(FAR, ground, FAR), 4) && MapSession.get() != null
			&& MapSession.get().map().chunkCount() > 200, 2400);
		DioramaClientTest.settle(context, 100);
		world.getServer().runCommand("forceload remove all");
		Waypoint gold = add(context, new Waypoint("Gold", FAR, ground, FAR, "minecraft:overworld", PIN));

		// The Nether: one on the floor where the player arrives, one on the roof above it.
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0");
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER
			&& MapSession.get().map().chunkCount() > 200, 2400);
		DioramaClientTest.settle(context, 100);
		BlockPos floor = context.computeOnClient(mc -> mc.player.blockPosition());
		Waypoint nether = add(context, new Waypoint("Nether", floor.getX(), floor.getY(), floor.getZ(), "minecraft:the_nether", PIN));
		Waypoint roof = add(context, new Waypoint("Roof", floor.getX(), 128, floor.getZ(), "minecraft:the_nether", PIN));
		// Standing on the roof, as in the issue's comment.
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a " + floor.getX() + " 128 " + floor.getZ());
		context.waitTicks(40);
		DioramaClientTest.settle(context, 40);
		BufferedImage roofHereShot = edit(context, roof, "waypoint-preview-1-roof-from-roof");
		// With fog of war, ground the player hasn't seen is fog on the map, and a plain face under
		// it: not the netherrack the loaded world has there.
		Waypoint unseen = add(context, new Waypoint("Unseen", floor.getX() + 120, floor.getY(), floor.getZ(), "minecraft:the_nether", PIN));
		context.runOnClient(mc -> dev.diorama.ServerRules.setAlwaysFogOfWar(true));
		DioramaClientTest.settle(context, 40);
		BufferedImage unseenShot = edit(context, unseen, "waypoint-preview-1b-unseen-fog-of-war");
		context.runOnClient(mc -> dev.diorama.ServerRules.setAlwaysFogOfWar(false));
		DioramaClientTest.settle(context, 40);

		world.getServer().runCommand("execute in minecraft:overworld run tp @a " + home.getX() + " " + home.getY() + " " + home.getZ());
		context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && mc.gui.screen() == null
			&& MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		DioramaClientTest.settle(context, 100);
		Waypoint near = add(context, new Waypoint("Near", home.getX(), home.getY(), home.getZ(), "minecraft:overworld", PIN));
		Waypoint unmapped = add(context, new Waypoint("Nowhere", 20000, 70, 20000, "minecraft:overworld", PIN));

		BufferedImage nearShot = edit(context, near, "waypoint-preview-2-near");
		BufferedImage goldShot = edit(context, gold, "waypoint-preview-3-far-gold");
		BufferedImage netherShot = edit(context, nether, "waypoint-preview-4-nether-from-overworld");
		BufferedImage roofShot = edit(context, roof, "waypoint-preview-5-roof-from-overworld");
		BufferedImage unmappedShot = edit(context, unmapped, "waypoint-preview-6-never-mapped");
		// The preview follows the position as it's typed: the waypoint at spawn, moved to the platform.
		context.setScreen(() -> new WaypointEditScreen(null, near, false));
		context.waitTicks(5);
		DioramaClientTest.typeInto(context, 2, Integer.toString(FAR));
		DioramaClientTest.typeInto(context, 3, Integer.toString(ground));
		DioramaClientTest.typeInto(context, 4, Integer.toString(FAR));
		BufferedImage typedShot = shoot(context, "waypoint-preview-6b-typed-position");
		context.setScreen(() -> null);

		// Empty coordinate boxes mean where the player stands, as in Xaero's (#255): the far-off
		// waypoint, emptied, shows the player's numbers greyed, stands on spawn's grass and saves there.
		BlockPos here = context.computeOnClient(mc -> mc.player.blockPosition());
		context.setScreen(() -> new WaypointEditScreen(null, unmapped, false));
		context.waitTicks(5);
		for (int box = 2; box <= 4; box++) {
			DioramaClientTest.typeInto(context, box, "");
		}
		context.runOnClient(mc -> mc.gui.screen().setFocused(null));
		BufferedImage emptyShot = shoot(context, "waypoint-preview-6c-empty-boxes");
		String hints = context.computeOnClient(mc -> hints(mc.gui.screen()));
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		String emptied = context.computeOnClient(mc -> mc.gui.screen() == null ? unmapped.x + " " + unmapped.y + " " + unmapped.z : "editor still open");
		// A new Nether waypoint made in the Overworld with X and Z left empty lands an eighth of
		// the way, at the player's Y left as typed.
		Waypoint netherHere = new Waypoint("Nether here", 5, 70, 5, "minecraft:the_nether", PIN);
		context.setScreen(() -> new WaypointEditScreen(null, netherHere, true));
		context.waitTicks(5);
		DioramaClientTest.typeInto(context, 2, "");
		DioramaClientTest.typeInto(context, 4, "");
		context.runOnClient(mc -> mc.gui.screen().setFocused(null));
		BufferedImage netherHereShot = shoot(context, "waypoint-preview-6d-nether-empty-x-z");
		String netherHints = context.computeOnClient(mc -> hints(mc.gui.screen()));
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		boolean netherSaved = context.computeOnClient(mc -> WaypointStore.get().all().contains(netherHere));
		context.setScreen(() -> null);

		// Only this section's own: the waypoints section checks its kept ones after rejoining.
		context.runOnClient(mc -> List.of(gold, nether, roof, unseen, near, unmapped, netherHere).forEach(WaypointStore.get()::remove));
		emptyList(context, "waypoint-preview-7-empty-list-after");

		// Checked after every picture is taken, so a failure still leaves them all.
		List<String> wrong = new ArrayList<>();
		Share nearShare = share(context, nearShot);
		Share goldShare = share(context, goldShot);
		Share netherShare = share(context, netherShot);
		Share unmappedShare = share(context, unmappedShot);
		Share typedShare = share(context, typedShot);
		Share roofHereShare = share(context, roofHereShot);
		Share roofShare = share(context, roofShot);
		Share unseenShare = share(context, unseenShot);
		if (nearShare.green < 0.1) {
			wrong.add("the waypoint at spawn doesn't stand on spawn's grass: " + nearShare);
		}
		if (goldShare.gold < 0.03) {
			wrong.add("the far waypoint doesn't stand on its gold platform: " + goldShare);
		}
		if (netherShare.red < 0.1 || netherShare.green > 0.02) {
			wrong.add("the Nether waypoint doesn't stand on the Nether: " + netherShare);
		}
		if (roofHereShare.red < 0.1 || roofHereShare.green > 0.02) {
			wrong.add("the roof waypoint, edited on the roof, doesn't stand on the Nether: " + roofHereShare);
		}
		if (roofShare.red < 0.1 || roofShare.green > 0.02) {
			wrong.add("the roof waypoint, edited from the Overworld, doesn't stand on the Nether: " + roofShare);
		}
		if (unseenShare.red > 0.02) {
			wrong.add("ground the player hasn't seen shows through fog of war: " + unseenShare);
		}
		if (unmappedShare.fog < 0.15 || unmappedShare.green > 0.02 || unmappedShare.gold > 0.01) {
			wrong.add("the never-mapped waypoint shows ground: " + unmappedShare);
		}
		if (typedShare.gold < 0.03) {
			wrong.add("the preview didn't follow the typed position to the gold platform: " + typedShare);
		}
		String spot = here.getX() + " " + here.getY() + " " + here.getZ();
		if (!hints.equals(spot)) {
			wrong.add("the empty boxes show " + hints + ", not where the player stands, " + spot);
		}
		if (!emptied.equals(spot)) {
			wrong.add("the waypoint saved with empty boxes is at " + emptied + ", not where the player stands, " + spot);
		}
		if (share(context, emptyShot).green < 0.1) {
			wrong.add("the preview of the emptied waypoint doesn't stand on spawn's grass");
		}
		String netherSpot = Math.floorDiv(here.getX(), 8) + " " + Math.floorDiv(here.getZ(), 8);
		String netherHintsXZ = netherHints.split(" ")[0] + " " + netherHints.split(" ")[2];
		if (!netherHintsXZ.equals(netherSpot)) {
			wrong.add("the Nether waypoint's empty X and Z show " + netherHintsXZ + ", not " + netherSpot);
		}
		String netherSaid = netherHere.x + " " + netherHere.z;
		if (!netherSaved || !netherSaid.equals(netherSpot) || netherHere.y != 70) {
			wrong.add("the Nether waypoint saved with empty X and Z is " + (netherSaved ? "at " + netherSaid + " y " + netherHere.y : "missing")
				+ ", not at " + netherSpot + " y 70");
		}
		if (share(context, netherHereShot).red < 0.1) {
			wrong.add("the preview of the Nether waypoint with empty X and Z doesn't stand on the Nether");
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("Waypoint editor previews: " + String.join("; ", wrong));
		}
	}

	/**
	 * The empty list's piece of map, still the ground around the player. Only when this section
	 * runs alone: the others leave waypoints behind, and some of them check theirs later.
	 */
	private static void emptyList(ClientGameTestContext context, String name) {
		if (context.computeOnClient(mc -> !WaypointStore.get().all().isEmpty())) {
			return;
		}
		context.setScreen(() -> new WaypointListScreen(null));
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(10);
		context.takeScreenshot(name);
		context.setScreen(() -> null);
	}

	/** The X, Y and Z boxes' hints, as shown while a box is empty. */
	private static String hints(net.minecraft.client.gui.screens.Screen screen) {
		List<net.minecraft.client.gui.components.EditBox> boxes = DioramaClientTest.editBoxes(screen);
		StringBuilder out = new StringBuilder();
		for (int box = 2; box <= 4; box++) {
			out.append(box > 2 ? " " : "").append(hint(boxes.get(box)));
		}
		return out.toString();
	}

	/** The box's hint: its one text field, found by type as the name differs between mappings. */
	private static String hint(net.minecraft.client.gui.components.EditBox box) {
		for (java.lang.reflect.Field field : net.minecraft.client.gui.components.EditBox.class.getDeclaredFields()) {
			if (field.getType() == net.minecraft.network.chat.Component.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
				try {
					field.setAccessible(true);
					Object hint = field.get(box);
					return hint == null ? "none" : ((net.minecraft.network.chat.Component) hint).getString();
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		throw new IllegalStateException("EditBox has no hint field");
	}

	private static Waypoint add(ClientGameTestContext context, Waypoint waypoint) {
		context.runOnClient(mc -> WaypointStore.get().add(waypoint));
		return waypoint;
	}

	/** Opens the editor on a waypoint, waits for the map's tiles to load and takes its picture. */
	private static BufferedImage edit(ClientGameTestContext context, Waypoint waypoint, String name) {
		context.setScreen(() -> new WaypointEditScreen(null, waypoint, false));
		BufferedImage shot = shoot(context, name);
		context.setScreen(() -> null);
		return shot;
	}

	private static BufferedImage shoot(ClientGameTestContext context, String name) {
		// Opening a screen leaves the cursor in the middle, over a colour swatch whose tooltip
		// would cover the preview.
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(10);
		DioramaClientTest.settle(context, 40);
		return read(context.takeScreenshot(name));
	}

	/** How much of the preview panel is grass green, netherrack red, gold and bright fog, as fractions of its pixels. */
	private record Share(double green, double red, double gold, double fog) {
	}

	/**
	 * The piece of map in the preview panel, right of the form, from the editor's own layout. The
	 * panel is 80 by 82 and see-through around the piece, so its margins take the colour of the
	 * world behind the screen: counted, grass behind the editor read as green on a Nether piece.
	 */
	private static Share share(ClientGameTestContext context, BufferedImage shot) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		int guiWidth = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
		int guiHeight = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
		int left = guiWidth / 2 - 146 + 212;
		int top = guiHeight / 2 - 92 + 16;
		int x0 = (int) ((left + 8) * scale);
		int x1 = (int) ((left + 72) * scale);
		int y0 = (int) ((top + 28) * scale);
		int y1 = (int) ((top + 78) * scale);
		int green = 0;
		int red = 0;
		int gold = 0;
		int fog = 0;
		int all = 0;
		for (int y = y0; y < Math.min(y1, shot.getHeight()); y++) {
			for (int x = x0; x < Math.min(x1, shot.getWidth()); x++) {
				int rgb = shot.getRGB(x, y);
				int r = rgb >> 16 & 0xFF;
				int g = rgb >> 8 & 0xFF;
				int b = rgb & 0xFF;
				all++;
				if (g > r * 1.1 && g > b * 1.2 && g > 50) {
					green++;
				} else if (r > 150 && g > 120 && b < g * 0.6) {
					gold++;
				} else if (r > g * 1.35 && r > b * 1.35 && r > 50) {
					red++;
				} else if (Math.min(r, Math.min(g, b)) > 180 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 40) {
					fog++;
				}
			}
		}
		return new Share(green / (double) all, red / (double) all, gold / (double) all, fog / (double) all);
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
