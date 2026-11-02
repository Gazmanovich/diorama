package dev.diorama.test;

import dev.diorama.Settings;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointStore;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Automatic pins in the player's language (opt-in: {@code pin-names}): a Bed pin, a death pin and a
 * portal pin made in English, then the waypoint list and the Bed pin's editor in Russian. The
 * Russian for the death pin's name comes from a test pack, standing in for the translators' file.
 */
final class PinNamesTest {
	private static final String PACK = "diorama-test-pin-names";

	private PinNamesTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		russianPack(context);
		context.runOnClient(mc -> Settings.get().portalMarkers = true);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		IconWaypointsTest.sleep(context, world, at.offset(3, 0, 0));

		world.getServer().runCommand("gamerule show_death_messages false");
		world.getServer().runCommand("gamerule immediate_respawn true");
		world.getServer().runCommand("kill @a");
		context.waitFor(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.DEATH), 200);
		context.waitFor(mc -> mc.player != null && mc.player.isAlive(), 200);
		world.getServer().runCommand("gamerule immediate_respawn false");

		int y = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ())) + 20;
		int x = at.getX() + 12;
		int z = at.getZ() - 12;
		world.getServer().runCommand("fill " + x + " " + y + " " + z + " " + (x + 3) + " " + (y + 4) + " " + z + " obsidian");
		world.getServer().runCommand("fill " + (x + 1) + " " + (y + 1) + " " + z + " " + (x + 2) + " " + (y + 3) + " " + z + " nether_portal[axis=x]");
		context.waitFor(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.NETHER_PORTAL), 400);

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("pin-names-1-list-en_us");

		DioramaClientTest.switchLanguage(context, "ru_ru");
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("pin-names-2-list-ru_ru");
		Waypoint bed = pin(context, Waypoint.Kind.BED);
		Waypoint death = pin(context, Waypoint.Kind.DEATH);
		Waypoint portal = pin(context, Waypoint.Kind.NETHER_PORTAL);
		expectShown(context, bed, "Кровать");
		String time = context.computeOnClient(mc -> death.name.substring("Death ".length()));
		expectShown(context, death, "Смерть " + time);
		// Made in English, the portal's pin now reads the game's Russian name for the block.
		String russianPortal = context.computeOnClient(mc -> net.minecraft.world.level.block.Blocks.NETHER_PORTAL.getName().getString());
		if (russianPortal.equals("Nether Portal")) {
			throw new AssertionError("The game has no Russian name for the Nether portal");
		}
		expectShown(context, portal, russianPortal);

		// Saving the editor without renaming keeps the pin's automatic name; a rename is the player's.
		context.setScreen(() -> new WaypointEditScreen(null, bed, false));
		context.waitTicks(5);
		context.takeScreenshot("pin-names-3-bed-editor-ru_ru");
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.setScreen(() -> new WaypointEditScreen(null, death, false));
		context.waitTicks(5);
		DioramaClientTest.typeInto(context, 0, "Lava lake");
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.setScreen(() -> null);

		DioramaClientTest.switchLanguage(context, "en_us");
		expectShown(context, bed, "Bed");
		expectShown(context, death, "Lava lake");
		expectShown(context, portal, "Nether Portal");
		DioramaClientTest.reloadPacks(context, mc -> mc.getResourcePackRepository().removePack("file/" + PACK));
		context.runOnClient(mc -> Settings.get().portalMarkers = false);
	}

	private static Waypoint pin(ClientGameTestContext context, Waypoint.Kind kind) {
		return context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == kind).findFirst().orElseThrow());
	}

	private static void expectShown(ClientGameTestContext context, Waypoint waypoint, String expected) {
		String shown = context.computeOnClient(mc -> waypoint.shownName());
		if (!shown.equals(expected)) {
			throw new AssertionError(waypoint.kind + " pin saved as " + waypoint.name + " shows " + shown + ", expected " + expected);
		}
	}

	/** Russian for the death pin's name, as the translators would add it to ru_ru.json. */
	private static void russianPack(ClientGameTestContext context) {
		java.nio.file.Path pack = context.computeOnClient(mc -> mc.getResourcePackDirectory().resolve(PACK));
		context.runOnClient(mc -> {
			try {
				java.nio.file.Files.createDirectories(pack.resolve("assets/diorama/lang"));
				//? if >=1.21.9 {
				int format = net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES).major();
				String formats = "\"pack_format\": " + format + ", \"min_format\": " + format + ", \"max_format\": " + format;
				//?} else if >=1.21.6 {
				/*int format = net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
				String formats = "\"pack_format\": " + format;
				*///?} else {
				/*int format = net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
				String formats = "\"pack_format\": " + format;
				*///?}
				java.nio.file.Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\": {" + formats + ", \"description\": \"Diorama test: pin names in Russian\"}}");
				java.nio.file.Files.writeString(pack.resolve("assets/diorama/lang/ru_ru.json"),
					"{\"diorama.waypoint.death\": \"Смерть %s\"}",
					java.nio.charset.StandardCharsets.UTF_8);
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		DioramaClientTest.reloadPacks(context, mc -> {
			mc.getResourcePackRepository().reload();
			if (!mc.getResourcePackRepository().addPack("file/" + PACK)) {
				throw new AssertionError("The pin names test pack didn't load");
			}
		});
	}
}
