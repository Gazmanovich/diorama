package dev.diorama.test;

import dev.diorama.DioramaClient;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.WorldKey;
import dev.diorama.bake.BiomeTints;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Radar;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.XaeroImport;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Drives the real game through the map's views and saves screenshots, so a person can look at
 * what the map actually draws, and asserts what can be measured along the way.
 */
public class DioramaClientTest implements FabricClientGameTest {
	/**
	 * The test servers' port: free when the run starts, so test runs on one machine don't collide,
	 * and the same for every server in the run, so they all share one address.
	 */
	private static final int SERVER_PORT = freePort();

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1600, 900);
		context.runOnClient(mc -> mc.options.renderDistance().set(12));
		String seed = System.getProperty("diorama.seed", "diorama");
		Set<String> tests = selectedTests();
		if (tests.contains("showcase") && tests.size() > 1) {
			// The showcase leaves the player in the Nether, spectating, on peaceful, with radar off.
			throw new AssertionError("Run the showcase on its own: DIORAMA_TESTS=showcase");
		}
		TestWorldSave save = null;
		int[] spawnCell = null;
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed(seed);
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			String tp = System.getProperty("diorama.tp");
			if (tp != null) {
				world.getServer().runCommand("tp @a " + tp);
			}

			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
			settle(context, 100);
			save = world.getWorldSave();
			spawnCell = tests.contains("saved-map") ? knownSpawnCell(context) : null;
			if (tests.contains("styles")) {
				styleTour(context, world);
			}
			if (tests.contains("showcase")) {
				showcase(context, world);
			}
			if (tests.contains("dimensions")) {
				dimensions(context, world);
			}
			if (tests.contains("bench")) {
				bench(context);
			}
			if (tests.contains("tour")) {
				tour(context, world);
			}
			if (tests.contains("radar")) {
				radar(context, world);
			}
			if (tests.contains("waypoints")) {
				waypoints(context, world);
			}
			if (tests.contains("xaero-import")) {
				xaeroImport(context, world);
			}
			if (tests.contains("fog-of-war")) {
				fogOfWar(context, world);
			}
			if (tests.contains("settings")) {
				settings(context, world);
			}
			if (tests.contains("overlays")) {
				overlays(context, world);
			}
			if (tests.contains("screens")) {
				screens(context, world);
			}
			//? if <1.21.6 {
			/*if (tests.contains("broken-fog")) {
				brokenFog(context);
			}
			*///?}
			if (spawnCell != null) {
				// Go far from spawn, so its chunks are long gone once the world is reopened.
				world.getServer().runCommand("tp @a 3000 200 3000");
				context.waitTicks(100);
			}
		}
		if (spawnCell != null && save != null) {
			try (TestSingleplayerContext reopened = save.open()) {
				savedMap(context, reopened, spawnCell, tests.contains("fog-of-war"));
			}
		}
		if (tests.contains("multiplayer")) {
			multiplayer(context);
		}
		// The server's side of Xaero's channels goes through Fabric API networking, so these run on
		// Fabric only. On NeoForge the shim can't start a dedicated server anyway.
		//? if fabric {
		if (tests.contains("server-id")) {
			serverLevelIds(context);
		}
		if (tests.contains("tracked-players")) {
			trackedPlayers(context);
		}
		//?}
	}

	/**
	 * Two worlds behind one server address, like a network's lobby and survival servers, or a
	 * server whose map was reset: each keeps its own map and waypoints, and going back to the
	 * first finds them again, even after its spawn moved.
	 */
	private static void multiplayer(ClientGameTestContext context) {
		String address = WorldKey.key("server", "localhost:" + SERVER_PORT);
		context.runOnClient(mc -> {
			// Start from nothing saved for this address.
			try (java.util.stream.Stream<java.nio.file.Path> old = java.nio.file.Files.list(WorldKey.folder("."))) {
				for (java.nio.file.Path folder : old.filter(p -> p.getFileName().toString().startsWith(address)).toList()) {
					org.apache.commons.io.FileUtils.deleteDirectory(folder.toFile());
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		String first = playOnServer(context, "diorama-first", "first", null, "Camp First", List.of(), List.of(), null, null);
		String second = playOnServer(context, "diorama-second", "second", null, "Camp Second", List.of(), List.of("Camp First"), false, null);
		String again = playOnServer(context, "diorama-first", "first-again", "setworldspawn 40 90 40", null, List.of("Camp First"), List.of("Camp Second"), true, null);
		if (first.equals(second)) {
			throw new AssertionError("Two worlds on one server address share a map folder: " + first);
		}
		if (!first.equals(again)) {
			throw new AssertionError("Coming back to a world opened another folder: " + first + " then " + again);
		}
	}

	/** Level ID messages as MapModCompanion sent them through Velocity, for two same-seed servers. */
	private static final byte[] LOBBY_ID = {0, 124, 86, -44, -107};
	private static final byte[] SURVIVAL_ID = {0, 4, -83, -79, -28};
	/** What the dedicated server sends a player a second after they join: a level ID message, or nothing. */
	private static byte @Nullable [] levelIdOnJoin;
	private static boolean joinHooked;
	private static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<dev.diorama.XaeroChannels.Message> WORLD_INFO =
		new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("worldinfo", "world_id"));

	/**
	 * A network's two servers whose worlds share a seed, each naming its world with a level ID a
	 * second after the player joins, as MapModCompanion does: they keep separate maps and
	 * waypoints. A server that names its world without declaring a channel gets the seed's folder
	 * on the first visit, and keeps it for that visit when the ID comes. From the next visit on,
	 * the map waits for the ID, since the address named a world before.
	 */
	//? if fabric {
	private static void serverLevelIds(ClientGameTestContext context) {
		String address = WorldKey.key("server", "localhost:" + SERVER_PORT);
		context.runOnClient(mc -> {
			try (java.util.stream.Stream<java.nio.file.Path> old = java.nio.file.Files.list(WorldKey.folder("."))) {
				for (java.nio.file.Path folder : old.filter(p -> p.getFileName().toString().startsWith(address)).toList()) {
					org.apache.commons.io.FileUtils.deleteDirectory(folder.toFile());
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		if (!joinHooked) {
			joinHooked = true;
			net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(WORLD_INFO, dev.diorama.XaeroChannels.codec(WORLD_INFO));
			net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
				byte[] message = levelIdOnJoin;
				if (message != null) {
					Thread later = new Thread(() -> {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							return;
						}
						server.execute(() -> sender.sendPacket(new dev.diorama.XaeroChannels.Message(dev.diorama.XaeroChannels.MINIMAP, message[0], java.util.Arrays.copyOfRange(message, 1, message.length))));
					});
					later.setDaemon(true);
					later.start();
				}
			});
		}
		String seeded = playOnServer(context, "diorama-shared", "id-late", null, "Old Camp", List.of(), List.of(), null, LOBBY_ID);
		if (!seeded.startsWith(address + "-world-")) {
			throw new AssertionError("A level ID after the map opened moved it out of the seed's folder: " + seeded);
		}
		// The ID came, so this time the map waits for it, still with no channel declared.
		String lobby = playOnServer(context, "diorama-shared", "id-lobby", null, "Lobby Camp", List.of(), List.of("Old Camp"), false, LOBBY_ID);
		// Listen where MapModCompanion does, so the server declares a channel that comes with
		// level IDs, as servers that send them do.
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(WORLD_INFO, (message, ctx) -> {
		});
		String survival = playOnServer(context, "diorama-shared", "id-survival", null, "Survival Camp", List.of(), List.of("Old Camp", "Lobby Camp"), false, SURVIVAL_ID);
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.unregisterGlobalReceiver(WORLD_INFO.id());
		String lobbyAgain = playOnServer(context, "diorama-shared", "id-lobby-again", null, null, List.of("Lobby Camp"), List.of("Old Camp", "Survival Camp"), true, LOBBY_ID);
		levelIdOnJoin = null;
		if (!lobby.equals(address + "-id-7c56d495") || !survival.equals(address + "-id-04adb1e4")) {
			throw new AssertionError("The level IDs didn't name the folders: " + lobby + ", " + survival);
		}
		if (!lobby.equals(lobbyAgain)) {
			throw new AssertionError("Coming back to the lobby opened another folder: " + lobby + " then " + lobbyAgain);
		}
		List<String> old = context.computeOnClient(mc -> WaypointStore.open(seeded).all().stream().map(w -> w.name).toList());
		if (!old.equals(List.of("Old Camp"))) {
			throw new AssertionError("The seed's folder lost its waypoint or took another world's: " + old);
		}
	}
	//?}

	/** Bob's offline-mode UUID, and his position as XaeroTracker sent it: x 640.5, y 120, z 30.5 in the overworld. */
	private static final UUID BOB = UUID.fromString("faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b");
	private static final byte[] BOB_TRACKED = {2, 10, 1, 0, 1, 114, 0, 11, 0, 1, 105, 0, 0, 0, 4, -6, -91, -36, -93, -61, -44, 53, 75, -82, 27, -35, -23, -27, -95, 75, 59,
		6, 0, 1, 120, 64, -124, 4, 0, 0, 0, 0, 0, 6, 0, 1, 121, 64, 94, 0, 0, 0, 0, 0, 0, 6, 0, 1, 122, 64, 62, -128, 0, 0, 0, 0, 0,
		8, 0, 1, 100, 0, 19, 109, 105, 110, 101, 99, 114, 97, 102, 116, 58, 111, 118, 101, 114, 119, 111, 114, 108, 100, 0};

	/**
	 * A player far beyond the loaded chunks whom the server tracks, as Xaero's player tracker
	 * reports them: once the client has answered the server's handshake, they show on the map
	 * with their face and name, move when the server says they moved, hide under fair play, and
	 * go when the server stops tracking them.
	 */
	//? if fabric {
	private static void trackedPlayers(ClientGameTestContext context) {
		java.util.Properties properties = new java.util.Properties();
		properties.setProperty("server-port", String.valueOf(SERVER_PORT));
		properties.setProperty("gamemode", "creative");
		java.util.concurrent.atomic.AtomicInteger answers = new java.util.concurrent.atomic.AtomicInteger();
		// Bob is higher up than the radar's default height range reaches, which hides players as it does mobs.
		int range = context.computeOnClient(mc -> Settings.get().radarRange);
		context.runOnClient(mc -> Settings.get().radarRange = 128);
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(dev.diorama.XaeroChannels.MINIMAP, (message, ctx) -> {
			if (message.kind() == 1 && java.util.Arrays.equals(message.body(), new byte[] {0, 0, 0, 3})) {
				answers.incrementAndGet();
			}
		});
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama-tracked"))
			.createServer(properties)) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				server.runCommand("time set noon");
				server.runCommand("weather clear");
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 150, 2400);
				server.runOnServer(s -> {
					// Bob is on the tab list, as players elsewhere on the server are, but not in the
					// client's world: he's far away.
					net.minecraft.server.level.ServerPlayer alice = s.getPlayerList().getPlayers().get(0);
					net.minecraft.server.level.ServerPlayer bob = new net.minecraft.server.level.ServerPlayer(s, s.overworld(), new GameProfile(BOB, "Bob"),
						net.minecraft.server.level.ClientInformation.createDefault());
					// The tab list entry reads the player's latency from their connection.
					bob.connection = alice.connection;
					alice.connection.send(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bob)));
					sendXaero(alice, new byte[] {1, 0, 0, 0, 3});
				});
				context.waitFor(mc -> answers.get() > 0, 200);
				sendXaero(server, BOB_TRACKED);
				context.setScreen(MapScreen::new);
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(600, Projection.v(120, 30.5), 1.0, TileKey.SURFACE));
				settle(context, 20);
				context.takeScreenshot("tracked-players-01-far");
				waitForBob(context, 640.5, "where XaeroTracker put him");

				byte[] moved = BOB_TRACKED.clone();
				java.nio.ByteBuffer.wrap(moved, indexAfter(moved, new byte[] {6, 0, 1, 'x'}), 8).putDouble(580.5);
				sendXaero(server, moved);
				waitForBob(context, 580.5, "after he moved");
				context.takeScreenshot("tracked-players-02-moved");

				server.runCommand("tellraw @a \"§f§a§i§r§x§a§e§r§o\"");
				waitForBob(context, null, "under fair play");
				context.takeScreenshot("tracked-players-03-fair-play");
				server.runCommand("tellraw @a \"§r§e§s§e§t§x§a§e§r§o\"");
				waitForBob(context, 580.5, "after fair play ended");

				// A remove names only the player, as servers send it.
				byte[] uuid = java.util.Arrays.copyOfRange(BOB_TRACKED, indexAfter(BOB_TRACKED, new byte[] {11, 0, 1, 'i'}), indexAfter(BOB_TRACKED, new byte[] {11, 0, 1, 'i'}) + 20);
				java.io.ByteArrayOutputStream removed = new java.io.ByteArrayOutputStream();
				removed.writeBytes(new byte[] {2, 10, 1, 0, 1, 'r', 1, 11, 0, 1, 'i'});
				removed.writeBytes(uuid);
				removed.write(0);
				sendXaero(server, removed.toByteArray());
				waitForBob(context, null, "after the server stopped tracking him");
				// Tracked again, then the tracker reset that comes before the server sends everyone again.
				sendXaero(server, BOB_TRACKED);
				waitForBob(context, 640.5, "when tracked again");
				sendXaero(server, new byte[] {3});
				waitForBob(context, null, "after the tracker reset");
				context.setScreen(() -> null);
			}
		} finally {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.unregisterGlobalReceiver(dev.diorama.XaeroChannels.MINIMAP.id());
			context.runOnClient(mc -> Settings.get().radarRange = range);
		}
	}

	private static void sendXaero(TestDedicatedServerContext server, byte[] message) {
		server.runOnServer(s -> sendXaero(s.getPlayerList().getPlayers().get(0), message));
	}

	private static void sendXaero(net.minecraft.server.level.ServerPlayer player, byte[] message) {
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
			new dev.diorama.XaeroChannels.Message(dev.diorama.XaeroChannels.MINIMAP, message[0], java.util.Arrays.copyOfRange(message, 1, message.length)));
	}
	//?}

	private static int indexAfter(byte[] bytes, byte[] pattern) {
		for (int i = 0; i + pattern.length <= bytes.length; i++) {
			if (java.util.Arrays.equals(bytes, i, i + pattern.length, pattern, 0, pattern.length)) {
				return i + pattern.length;
			}
		}
		throw new AssertionError("Not in the fixture: " + java.util.Arrays.toString(pattern));
	}

	/** Waits until Bob is on the fullscreen map where block x {@code x}, y 120, z 30.5 draws, or not on it at all if null. */
	private static void waitForBob(ClientGameTestContext context, @Nullable Double x, String when) {
		for (int tick = 0; tick < 400; tick++) {
			String wrong = context.computeOnClient(mc -> {
				MapScreen screen = (MapScreen) mc.gui.screen();
				Radar.Blip bob = screen.blips().stream().filter(b -> b.entity().getUUID().equals(BOB)).findFirst().orElse(null);
				if (x == null) {
					return bob == null ? null : "Bob is on the map at " + bob.x() + ", " + bob.y();
				}
				if (bob == null) {
					return "Bob isn't on the map";
				}
				float wantX = screen.view().screenX(x);
				float wantY = screen.view().screenY(Projection.v(120, 30.5));
				return Math.abs(bob.x() - wantX) <= 1 && Math.abs(bob.y() - wantY) <= 1 ? null
					: "Bob is drawn at " + bob.x() + ", " + bob.y() + ", not " + wantX + ", " + wantY;
			});
			if (wrong == null) {
				return;
			}
			if (tick == 399) {
				throw new AssertionError(wrong + " " + when);
			}
			context.waitTicks(1);
		}
	}

	/**
	 * Joins a fresh dedicated server for this seed, always on the same address, and checks its
	 * waypoints. The first visit to a world adds a waypoint and maps a strip east of spawn that
	 * the other world won't load. {@code stripKnown}, if not null, is whether that strip should
	 * show on the zoomed-out map from what's saved. {@code levelId}, if not null, is a level ID
	 * message the server sends a second after the player joins. Returns the world's folder key.
	 */
	private static String playOnServer(ClientGameTestContext context, String seed, String name, @Nullable String beforeJoin,
		@Nullable String addWaypoint, List<String> expected, List<String> unexpected, @Nullable Boolean stripKnown, byte @Nullable [] levelId) {
		levelIdOnJoin = levelId;
		java.util.Properties properties = new java.util.Properties();
		properties.setProperty("server-port", String.valueOf(SERVER_PORT));
		properties.setProperty("gamemode", "creative");
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed(seed))
			.createServer(properties)) {
			if (beforeJoin != null) {
				server.runCommand(beforeJoin);
			}
			try (TestDedicatedServerConnection connection = server.connect()) {
				server.runCommand("time set noon");
				server.runCommand("weather clear");
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 150, 2400);
				settle(context, 60);
				// The fullscreen map zoomed out, before this visit has spread far: anything
				// beyond the loaded chunks would be another world's saved map.
				context.setScreen(MapScreen::new);
				lookAtPlayer(context, 0.25, TileKey.SURFACE);
				settle(context, 40);
				context.takeScreenshot("multiplayer-" + name);
				if (stripKnown != null) {
					float known = context.computeOnClient(mc -> overviewKnown(500, Projection.v(70, 0)));
					if ((known > 0.5F) != stripKnown) {
						throw new AssertionError("The first world's strip east of spawn is " + known + " known on the " + name + " world");
					}
				}
				context.setScreen(() -> null);
				List<String> names = context.computeOnClient(mc -> WaypointStore.get().all().stream().map(w -> w.name).toList());
				dev.diorama.Diorama.LOGGER.info("Server world {}: waypoints {}", name, names);
				for (String want : expected) {
					if (!names.contains(want)) {
						throw new AssertionError("The " + name + " world is missing waypoint " + want + ": " + names);
					}
				}
				for (String other : unexpected) {
					if (names.contains(other)) {
						throw new AssertionError("Another world's waypoint shows on the " + name + " world: " + names);
					}
				}
				if (addWaypoint != null) {
					context.runOnClient(mc -> {
						BlockPos at = mc.player.blockPosition();
						WaypointStore.get().add(new Waypoint(addWaypoint, at.getX(), at.getY(), at.getZ(), mc.level.dimension().identifier().toString(), Waypoint.PALETTE[3]));
					});
					for (int x = 200; x <= 600; x += 200) {
						server.runCommand("tp @a " + x + " 200 0");
						context.waitTicks(20);
						settle(context, 60);
					}
				}
				return context.computeOnClient(mc -> WorldKey.current());
			}
		}
	}

	/**
	 * A spawn tile with a fully known coverage cell: {tileU, tileV, cellX, cellY}. The map was
	 * baked there in this session, so it should come back from disk after a restart.
	 */
	private static int[] knownSpawnCell(ClientGameTestContext context) {
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 2.0, TileKey.SURFACE);
		settle(context, 60);
		int[] cell = context.computeOnClient(mc -> {
			double u = mc.player.getX();
			double v = Projection.v(mc.player.getY(), mc.player.getZ());
			int tu = (int) Math.floor(u / Projection.TILE_UNITS);
			int tv = (int) Math.floor(v / Projection.TILE_UNITS);
			MapTile tile = MapSession.get().map().peek(new TileKey(TileKey.SURFACE, tu, tv));
			if (tile == null || !tile.hasCoverage()) {
				throw new AssertionError("The spawn tile wasn't baked");
			}
			double cellUnits = (double) Projection.TILE_UNITS / Projection.COVERAGE_CELLS;
			int cx = (int) ((u - tu * Projection.TILE_UNITS) / cellUnits);
			int cy = (int) ((v - tv * Projection.TILE_UNITS) / cellUnits);
			if (tile.coverage(cx, cy) < 0.9F) {
				throw new AssertionError("The spawn tile isn't known under the player");
			}
			return new int[] {tu, tv, cx, cy};
		});
		context.setScreen(() -> null);
		return cell;
	}

	/**
	 * After leaving and reopening the world 3000 blocks from spawn: the spawn tile shows from disk
	 * alone, and the fog-of-war corridor is still seen.
	 */
	private static void savedMap(ClientGameTestContext context, TestSingleplayerContext world, int[] spawnCell, boolean checkFogOfWar) {
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		settle(context, 60);
		// Map u is block x, so a tile this far from the player has no loaded chunks under it.
		double distance = context.computeOnClient(mc -> Math.abs(mc.player.getX() - (spawnCell[0] + 0.5) * Projection.TILE_UNITS));
		if (distance < 1000) {
			throw new AssertionError("The spawn tile is " + distance + " blocks away; the test can't tell disk from memory");
		}
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			double u = (spawnCell[0] + (spawnCell[2] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
			double v = (spawnCell[1] + (spawnCell[3] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
			((MapScreen) mc.gui.screen()).lookAt(u, v, 1.0, TileKey.SURFACE);
		});
		settle(context, 60);
		context.takeScreenshot("saved-map-01-spawn-after-restart");
		float known = context.computeOnClient(mc -> {
			MapTile tile = MapSession.get().map().peek(new TileKey(TileKey.SURFACE, spawnCell[0], spawnCell[1]));
			return tile == null || !tile.hasCoverage() ? 0 : tile.coverage(spawnCell[2], spawnCell[3]);
		});
		if (known < 0.9F) {
			throw new AssertionError("The spawn tile didn't come back from disk (known share " + known + ")");
		}

		// Zoom far out: overview tiles shrunk from the saved ones show both spawn and here.
		// Travel back toward spawn, so there's a trail of explored land to zoom out over. The
		// minimap is off, so no map draws the trail: only the background bakes can save it.
		double[] far = context.computeOnClient(mc -> new double[] {mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ())});
		double[] away = new double[2];
		context.setScreen(() -> null);
		boolean minimapWasOn = context.computeOnClient(mc -> Settings.get().minimap);
		context.runOnClient(mc -> Settings.get().minimap = false);
		try {
			for (int stop = 2750; stop >= 750; stop -= 250) {
				world.getServer().runCommand("tp @a " + stop + " 200 " + stop);
				// New terrain generates around the player a few chunks at a time.
				context.waitTicks(200);
				if (stop == 1750) {
					context.runOnClient(mc -> {
						away[0] = mc.player.getX();
						away[1] = Projection.v(mc.player.getY(), mc.player.getZ());
					});
				}
			}
		} finally {
			context.runOnClient(mc -> Settings.get().minimap = minimapWasOn);
		}
		double spawnU = (spawnCell[0] + (spawnCell[2] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
		double spawnV = (spawnCell[1] + (spawnCell[3] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt((spawnU + far[0]) / 2, (spawnV + far[1]) / 2, 0.06, TileKey.SURFACE));
		context.waitFor(mc -> overviewKnown(spawnU, spawnV) >= 0.9F && overviewKnown(away[0], away[1]) >= 0.9F, 1200);
		settle(context, 20);
		context.takeScreenshot("saved-map-02-zoomed-out");
		if ("1".equals(System.getenv("DIORAMA_GIF"))) {
			// Frames for a GIF: a steady zoom out from spawn until both spawn and the player show.
			for (int frame = 0; frame < 70; frame++) {
				double t = frame / 69.0;
				context.runOnClient(mc -> {
					double su = (spawnCell[0] + 0.5) * Projection.TILE_UNITS;
					double sv = (spawnCell[1] + 0.5) * Projection.TILE_UNITS;
					double pu = (spawnU + far[0]) / 2;
					double pv = (spawnV + far[1]) / 2;
					double ease = t * t * (3 - 2 * t);
					((MapScreen) mc.gui.screen()).lookAt(su + (pu - su) * ease, sv + (pv - sv) * ease, Math.pow(0.06, t), TileKey.SURFACE);
				});
				context.waitTicks(2);
				context.takeScreenshot("saved-map-zoom-" + String.format("%02d", frame));
			}
		}
		context.setScreen(() -> null);
		if (checkFogOfWar) {
			boolean seen = context.computeOnClient(mc -> MapSession.get().map().isSeen(115, -39, 116));
			if (!seen) {
				throw new AssertionError("Fog-of-war progress was lost across the restart");
			}
		}
	}

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** Known share of the overview tile drawn at a map point, or 0 if none is loaded. */
	private static float overviewKnown(double u, double v) {
		for (int lod = 1; lod <= TileKey.MAX_LOD; lod++) {
			double units = (double) Projection.TILE_UNITS * (1 << lod);
			int tu = (int) Math.floor(u / units);
			int tv = (int) Math.floor(v / units);
			MapTile tile = MapSession.get().map().peek(new TileKey(TileKey.SURFACE, tu, tv, lod));
			if (tile != null && tile.hasCoverage()) {
				double cell = units / Projection.COVERAGE_CELLS;
				return tile.coverage((int) ((u - tu * units) / cell), (int) ((v - tv * units) / cell));
			}
		}
		return 0F;
	}

	/** The Nether and the End: the minimap, and the fullscreen map near and far. */
	private static void dimensions(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0");
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER && MapSession.get().map().chunkCount() > 200, 2400);
		settle(context, 100);
		context.takeScreenshot("dimensions-01-nether-minimap");
		context.setScreen(MapScreen::new);
		settle(context, 60);
		context.takeScreenshot("dimensions-02-nether-map");
		// The sky view under the roof: the whole Nether from above, roof left out.
		lookAtPlayer(context, 3.0, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("dimensions-03-nether-under-roof");
		lookAtPlayer(context, 0.8, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("dimensions-03b-nether-under-roof-far");
		// Zoomed out past 0.5 the map draws overview tiles, built from the saved under-roof tiles.
		context.waitTicks(200);
		lookAtPlayer(context, 0.2, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("dimensions-03c-nether-under-roof-overview");
		context.setScreen(() -> null);

		world.getServer().runCommand("execute in minecraft:the_end run tp @a 0 70 0");
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.END && MapSession.get().map().chunkCount() > 100, 2400);
		settle(context, 100);
		context.takeScreenshot("dimensions-04-end-minimap");
		context.setScreen(MapScreen::new);
		settle(context, 60);
		context.takeScreenshot("dimensions-05-end-map");
		lookAtPlayer(context, 0.8, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("dimensions-06-end-far");
		context.setScreen(() -> null);
		world.getServer().runCommand("execute in minecraft:overworld run tp @a 0 100 0");
		// Back home the level switches before the loading screen closes; the next scenario starts
		// with a minimap screenshot, so wait for the screen to go and the map to fill in again.
		context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && mc.gui.screen() == null
			&& MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		settle(context, 100);
	}

	/**
	 * Times baking the same nine tiles in each look, in rotation, keeping each one's best: the
	 * first passes include warming up, and the map's own bakes run alongside.
	 */
	private static void bench(ClientGameTestContext context) {
		String[] modes = {"default", "flat at 50 degrees", "flat at 60 degrees"};
		java.util.Map<String, Long> best = new java.util.LinkedHashMap<>();
		context.waitTicks(200);
		for (int round = 0; round < 4; round++) {
			for (String mode : modes) {
				MapStyle style = mode.equals("default") ? MapStyle.DEFAULT : MapStyle.FLAT.withPitch(mode.contains("50") ? 50 : 60);
				context.runOnClient(mc -> {
					MapStyle.set(style);
					double u = mc.player.getX();
					double v = Projection.v(mc.player.getY(), mc.player.getZ());
					int tu = (int) Math.floor(u / Projection.TILE_UNITS);
					int tv = (int) Math.floor(v / Projection.TILE_UNITS);
					long start = System.nanoTime();
					for (int du = -1; du <= 1; du++) {
						for (int dv = -1; dv <= 1; dv++) {
							dev.diorama.bake.TileBaker.bake(new TileKey(TileKey.SURFACE, tu + du, tv + dv), MapSession.get().map(), dev.diorama.bake.BlockLooks.get(), null, false);
						}
					}
					best.merge(mode, System.nanoTime() - start, Math::min);
				});
			}
		}
		best.forEach((mode, nanos) -> dev.diorama.Diorama.LOGGER.info("BENCH {}: {} ms per tile", mode, nanos / 9 / 1_000_000));
		context.runOnClient(mc -> MapStyle.set(Settings.get().style()));
	}

	/**
	 * Builds {@link ShowcaseScene}'s castle beside the nearest village and photographs it for the
	 * store page: in game with the minimap, the fullscreen map from close up to far out, the caves
	 * under the castle, waypoints on the map and in the world, and the Nether over a fortress.
	 * Opt-in with {@code DIORAMA_TESTS=showcase}; {@code DIORAMA_GIF=1} adds the demo video's frames.
	 */
	private static void showcase(ClientGameTestContext context, TestSingleplayerContext world) {
		context.getInput().resizeWindow(1920, 1080);
		world.getServer().runCommand("difficulty peaceful");
		BlockPos village = world.getServer().computeOnServer(server ->
			server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
		if (village == null) {
			throw new AssertionError("No village near spawn");
		}
		int cx = village.getX() + Integer.getInteger("diorama.castleDx", 0);
		int cz = village.getZ() + Integer.getInteger("diorama.castleDz", -110);
		world.getServer().runCommand("tp @a " + cx + " 200 " + (cz + 40));
		context.waitTicks(100);
		ShowcaseScene scene = world.getServer().computeOnServer(server -> ShowcaseScene.site(server.overworld(), cx, cz));
		int base = scene.base();
		world.getServer().runCommand("time set noon");
		dev.diorama.Diorama.LOGGER.info("Showcase castle at {} {} {}, village at {}", cx, base, cz, village);
		context.runOnClient(mc -> {
			Settings.get().radarNeutral = false;
			Settings.get().radarPassive = false;
		});
		context.getInput().setCursorPos(0, 0);

		// Copper roofs by default; DIORAMA_ROOFS=1 photographs the other roofs that were tried.
		String[][] roofs = "1".equals(System.getenv("DIORAMA_ROOFS"))
			? new String[][] {
				{"deepslate_tiles", "deepslate_tile_stairs"},
				{"dark_prismarine", "dark_prismarine_stairs"},
				{"red_nether_bricks", "red_nether_brick_stairs"},
				{"bricks", "brick_stairs"},
				{"waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs"}}
			: new String[][] {{"waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs"}};
		double v = Projection.v(base + 12, cz);
		for (String[] roof : roofs) {
			world.getServer().runOnServer(server -> scene.castle(roof[0], roof[1]));
			if (roofs.length > 1) {
				context.setScreen(MapScreen::new);
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 3.2, TileKey.SURFACE));
				settle(context, 100);
				context.takeScreenshot("showcase-roof-" + roof[0]);
				context.setScreen(() -> null);
			}
		}

		// In game: past the wheat fields, looking up the road to the gate.
		world.getServer().runCommand("gamemode spectator @a");
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 92.5) + " 180 -4");
		context.waitTicks(60);
		settle(context, 60);
		context.takeScreenshot("showcase-01-in-game");

		context.setScreen(MapScreen::new);
		for (double zoom : new double[] {6, 3.2, 2.2, 1.2, 0.8}) {
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, zoom, TileKey.SURFACE));
			settle(context, 60);
			context.takeScreenshot("showcase-map-zoom-" + zoom);
		}
		if ("1".equals(System.getenv("DIORAMA_GIF"))) {
			context.setScreen(() -> null);
			demo(context, world, cx, base, cz);
		}

		// The cave view under the castle.
		int caveY = Integer.getInteger("diorama.showcaseCaveY", 30);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, Projection.v(caveY, cz), 2.4, caveY));
		settle(context, 100);
		context.takeScreenshot("showcase-cave");
		context.setScreen(() -> null);

		// Waypoints: on the map, then as labels in the world, seen from the village.
		int villageY = world.getServer().computeOnServer(server ->
			server.overworld().getChunk(village).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, village.getX() & 15, village.getZ() & 15));
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			String overworld = "minecraft:overworld";
			store.add(new Waypoint("Castle", cx, base + 1, cz - 10, overworld, Waypoint.PALETTE[3]));
			store.add(new Waypoint("Village", village.getX(), villageY, village.getZ(), overworld, Waypoint.PALETTE[1]));
			store.add(new Waypoint("Wheat", cx + 12, base - 4, cz + 66, overworld, Waypoint.PALETTE[2]));
			store.add(new Waypoint("Iron mine", cx + 150, base - 30, cz - 40, overworld, Waypoint.PALETTE[6]));
		});
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, Projection.v(base, cz + 50), 1.3, TileKey.SURFACE));
		settle(context, 60);
		context.takeScreenshot("showcase-waypoints-map");
		context.setScreen(() -> null);
		world.getServer().runCommand("tp @a " + (cx + 14.5) + " " + (base + 3) + " " + (cz + 120.5) + " 172 -6");
		context.waitTicks(60);
		settle(context, 40);
		context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
		context.takeScreenshot("showcase-waypoints-in-world");

		// The Nether from above, over the nearest fortress.
		BlockPos fortress = world.getServer().computeOnServer(server -> {
			ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
			HolderSet<Structure> wanted = HolderSet.direct(server.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.FORTRESS));
			var found = nether.getChunkSource().getGenerator().findNearestMapStructure(nether, wanted, BlockPos.ZERO, 100, false);
			return found == null ? new BlockPos(48, 70, 48) : found.getFirst();
		});
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a " + fortress.getX() + " 75 " + fortress.getZ());
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER
			&& MapSession.get().map().chunkCount() > 200, 2400);
		context.runOnClient(mc -> Settings.get().radarHostile = false);
		settle(context, 100);
		context.setScreen(MapScreen::new);
		for (double zoom : new double[] {2.6, 1.0}) {
			lookAtPlayer(context, zoom, TileKey.SURFACE);
			settle(context, 80);
			context.takeScreenshot("showcase-nether-" + zoom);
		}
		context.setScreen(() -> null);
	}

	/**
	 * Frames for the store page's demo video, one screenshot per frame. It looks around the village
	 * and settles on the castle, then cranes up and back to the map's camera angle while the field
	 * of view narrows to near-flat, then opens the fullscreen map at the same framing and zooms out.
	 * The last in-game frame and the first map frame line up, so the video can cross-fade between
	 * them. Leaves the map open.
	 */
	private static void demo(ClientGameTestContext context, TestSingleplayerContext world, int cx, int base, int cz) {
		double[] keep = {cx + 0.5, base + 20, cz + 0.5};
		double[] centre = {cx + 0.5, base + 12, cz + 0.5};
		// A narrow field of view seen from far away looks nearly like the map's flat projection.
		double narrowFov = 17;
		double frameBlocks = 64;
		double distance = frameBlocks / (2 * Math.tan(Math.toRadians(narrowFov) / 2));
		double[] above = {centre[0], centre[1] + distance * Projection.sin(), centre[2] + distance * Projection.cos()};
		double[] start = {cx + 14.5, base + 4.6, cz + 120.5};
		double[] end = {cx + 12.5, base + 7.6, cz + 110.5};

		// The game's field of view stops at 30 degrees. Its speed effect narrows it further: with no
		// movement speed a flying player sees 0.55 of it, and the effect scale blends that in smoothly.
		world.getServer().runCommand("attribute @p minecraft:movement_speed base set 0");
		CloudStatus clouds = context.computeOnClient(mc -> mc.options.cloudStatus().get());
		context.runOnClient(mc -> {
			mc.options.cloudStatus().set(CloudStatus.OFF);
			mc.options.renderDistance().set(16);
			mc.gui.hud.getChat().clearMessages(false);
		});
		// Load and build everything both ends of the shot can see before recording.
		for (double[] at : new double[][] {above, start}) {
			camera(context, at, 180, 45, 70, 1);
			context.waitTicks(400);
		}

		int frame = 0;
		// From the village houses, turn to the castle while drifting towards it.
		for (int i = 0; i < 110; i++) {
			double s = ease(i / 109.0);
			double[] at = lerp(start, end, s);
			float[] facing = facing(at, keep);
			camera(context, at, (float) Mth.lerp(s, 115, facing[0]), (float) Mth.lerp(s, 10, facing[1]), 70, 1);
			context.takeScreenshot(String.format("showcase-demo-world-%03d", frame++));
		}
		// Crane up and back to where the map's own camera would be, looking at the castle throughout.
		for (int i = 0; i < 140; i++) {
			double s = ease(i / 139.0);
			double[] at = lerp(end, above, s);
			float[] facing = facing(at, lerp(keep, centre, s));
			camera(context, at, facing[0], facing[1], 70 * Math.pow(narrowFov / 70, s), 3);
			context.takeScreenshot(String.format("showcase-demo-world-%03d", frame++));
		}
		//? if >=26.1 {
		double fov = context.computeOnClient(mc -> (double) mc.gameRenderer.mainCamera().getFov());
		//?} else {
		/*// The camera keeps its field of view private here; the demo is recorded on the newest version anyway.
		double fov = narrowFov;
		*///?}
		dev.diorama.Diorama.LOGGER.info("Demo ends {} blocks from the castle at a {} degree field of view", Math.round(distance), fov);

		// The map, framed like the last frame: the same blocks per screen height at the castle.
		context.setScreen(MapScreen::new);
		double screenBlocks = 2 * distance * Math.tan(Math.toRadians(fov) / 2);
		double matched = context.computeOnClient(mc -> mc.getWindow().getHeight() / (mc.getWindow().getGuiScale() * screenBlocks));
		double v = Projection.v(centre[1], centre[2]);
		for (int i = 0; i < 150; i++) {
			double zoom = matched * Math.pow(0.8 / matched, ease(Math.clamp((i - 12) / 117.0, 0, 1)));
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(centre[0], v, zoom, TileKey.SURFACE));
			if (i == 0) {
				settle(context, 80);
			} else {
				context.waitTicks(2);
			}
			context.takeScreenshot(String.format("showcase-demo-map-%03d", i));
		}

		world.getServer().runCommand("attribute @p minecraft:movement_speed base reset");
		context.runOnClient(mc -> {
			mc.options.fov().set(70);
			mc.options.fovEffectScale().set(1.0);
			mc.options.cloudStatus().set(clouds);
			mc.options.renderDistance().set(12);
		});
	}

	/**
	 * Puts the camera (the player's eyes) at a point, facing a way, with a field of view in
	 * degrees, then waits for the field of view to ease in.
	 */
	private static void camera(ClientGameTestContext context, double[] eye, float yaw, float pitch, double fov, int ticks) {
		context.runOnClient(mc -> {
			int option = Math.clamp((int) Math.ceil(fov), 30, 110);
			//? if >=1.21.2 {
			double narrowest = mc.player.getFieldOfViewModifier(true, 1.0F);
			//?} else {
			/*// The modifier took the effect scale from the options.
			mc.options.fovEffectScale().set(1.0);
			double narrowest = mc.player.getFieldOfViewModifier();
			*///?}
			mc.options.fov().set(option);
			mc.options.fovEffectScale().set(Math.clamp((1 - fov / option) / (1 - narrowest), 0.0, 1.0));
			mc.player.setDeltaMovement(Vec3.ZERO);
			mc.player.snapTo(eye[0], eye[1] - mc.player.getEyeHeight(), eye[2], yaw, pitch);
		});
		context.waitTicks(ticks);
	}

	/** The yaw and pitch that look from one point at another. */
	private static float[] facing(double[] from, double[] to) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		double dz = to[2] - from[2];
		float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		return new float[] {yaw, pitch};
	}

	private static double[] lerp(double[] a, double[] b, double s) {
		return new double[] {Mth.lerp(s, a[0], b[0]), Mth.lerp(s, a[1], b[1]), Mth.lerp(s, a[2], b[2])};
	}

	/** Smoothstep: starts and stops gently. */
	private static double ease(double s) {
		return s * s * (3 - 2 * s);
	}

	/**
	 * The map blends biome tints on bake threads from its own copies of the chunks. They must come
	 * out the same as the game's blend, for every column whose neighbours the map also has.
	 */
	private static void assertTintsMatchGame(ClientGameTestContext context) {
		String result = context.computeOnClient(mc -> {
			MapLevel map = MapSession.get().map();
			int pcx = mc.player.blockPosition().getX() >> 4;
			int pcz = mc.player.blockPosition().getZ() >> 4;
			int columns = 0;
			int wrong = 0;
			String example = null;
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int cx = pcx - 8; cx <= pcx + 8; cx++) {
				for (int cz = pcz - 8; cz <= pcz + 8; cz++) {
					boolean surrounded = true;
					for (int dx = -1; dx <= 1; dx++) {
						for (int dz = -1; dz <= 1; dz++) {
							surrounded &= map.chunk(cx + dx, cz + dz) != null;
						}
					}
					if (!surrounded) {
						continue;
					}
					ChunkSnapshot snapshot = map.chunk(cx, cz);
					// What the last bake used, as it stands.
					ChunkSnapshot.Tints tints = snapshot.tints() != null ? snapshot.tints() : BiomeTints.blend(snapshot);
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							pos.set((cx << 4) + x, snapshot.surface(x, z), (cz << 4) + z);
							int i = (z << 4) | x;
							int[] ours = {tints.grass()[i], tints.foliage()[i], tints.dryFoliage()[i], tints.water()[i]};
							int[] game = {
								BiomeColors.getAverageGrassColor(mc.level, pos), BiomeColors.getAverageFoliageColor(mc.level, pos),
								//? if >=1.21.5 {
								BiomeColors.getAverageDryFoliageColor(mc.level, pos), BiomeColors.getAverageWaterColor(mc.level, pos)
								//?} else {
								/*// No dry foliage before 1.21.5; the mod fills that slot with foliage.
								BiomeColors.getAverageFoliageColor(mc.level, pos), BiomeColors.getAverageWaterColor(mc.level, pos)
								*///?}
							};
							columns++;
							for (int k = 0; k < 4; k++) {
								if ((ours[k] & 0xFFFFFF) != (game[k] & 0xFFFFFF)) {
									wrong++;
									if (example == null) {
										example = String.format("%s tint %d: ours %06X, game %06X", pos.toShortString(), k, ours[k] & 0xFFFFFF, game[k] & 0xFFFFFF);
									}
								}
							}
						}
					}
				}
			}
			return columns + " " + wrong + " " + example;
		});
		String[] parts = result.split(" ", 3);
		dev.diorama.Diorama.LOGGER.info("Biome tints checked against the game's for {} columns: {} differ", parts[0], parts[1]);
		if (Integer.parseInt(parts[0]) < 1000) {
			throw new AssertionError("Too few columns to check biome tints: " + parts[0]);
		}
		if (!parts[1].equals("0")) {
			throw new AssertionError(parts[1] + " biome tints differ from the game's, for example " + parts[2]);
		}
	}

	/** The minimap, the fullscreen map at three zooms, a resource reload and a cave slice. */
	private static void tour(ClientGameTestContext context, TestSingleplayerContext world) {
		// Fancy for the shots the Fast ones further down are compared with.
		setQuality(context, Settings.Quality.FANCY);
		context.takeScreenshot("diorama-01-minimap");

		context.setScreen(MapScreen::new);
		settle(context, 60);
		context.takeScreenshot("diorama-02-map");
		assertTintsMatchGame(context);

		lookAtPlayer(context, 5.0, TileKey.SURFACE);
		settle(context, 40);
		context.takeScreenshot("diorama-03-map-close");

		lookAtPlayer(context, 0.75, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("diorama-04-map-far");

		// Quality Fast: one sample per pixel and the cheap fog. Switching re-bakes what's on screen.
		setQuality(context, Settings.Quality.FAST);
		settle(context, 80);
		context.takeScreenshot("diorama-04c-map-far-fast");
		lookAtPlayer(context, 5.0, TileKey.SURFACE);
		settle(context, 60);
		context.takeScreenshot("diorama-04d-map-close-fast");
		setQuality(context, Settings.Quality.FANCY);
		lookAtPlayer(context, 0.75, TileKey.SURFACE);
		settle(context, 80);

		if ("1".equals(System.getenv("DIORAMA_GIF"))) {
			// Frames for a GIF: a slow pan east across the edge of the loaded area, so the fog's
			// drift and the zoomed-out map show in motion.
			for (int frame = 0; frame < 60; frame++) {
				double offset = 90 + frame * 1.5;
				context.runOnClient(mc -> {
					if (mc.gui.screen() instanceof MapScreen screen && mc.player != null) {
						screen.lookAt(mc.player.getX() + offset, Projection.v(mc.player.getY(), mc.player.getZ()), 1.4, TileKey.SURFACE);
					}
				});
				context.waitTicks(3);
				context.takeScreenshot(String.format("diorama-gif-%03d", frame));
			}
		}

		// A resource reload (F3+T) must rebuild block colours and re-bake what is on screen.
		context.runOnClient(Minecraft::reloadResourcePacks);
		context.waitTicks(60);
		settle(context, 40);
		context.takeScreenshot("diorama-04b-after-reload");

		int caveY = Integer.getInteger("diorama.caveY", 24);
		lookAtPlayer(context, 2.0, caveY);
		settle(context, 80);
		context.takeScreenshot("diorama-05-cave");

		lookAtPlayer(context, 5.0, caveY);
		settle(context, 40);
		context.takeScreenshot("diorama-06-cave-close");
		context.setScreen(() -> null);

		// Open map rebound to C, which the map also uses for caves: the player's binding wins, so
		// pressing it again closes the map.
		// Before 1.21.9 a key reaches only one binding, and vanilla's Save Toolbar Activator is on C,
		// so a player moving Open map to C clears that one too; the test does the same.
		context.runOnClient(mc -> {
			mc.options.keySaveHotbarActivator.setKey(InputConstants.UNKNOWN);
			DioramaClient.OPEN_MAP.setKey(InputConstants.getKey("key.keyboard.c"));
			net.minecraft.client.KeyMapping.resetMapping();
		});
		context.getInput().pressKey(DioramaClient.OPEN_MAP);
		context.waitTicks(5);
		boolean opened = context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen);
		context.getInput().pressKey(DioramaClient.OPEN_MAP);
		context.waitTicks(5);
		boolean closed = context.computeOnClient(mc -> mc.gui.screen() == null);
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			mc.options.keySaveHotbarActivator.setKey(mc.options.keySaveHotbarActivator.getDefaultKey());
			DioramaClient.OPEN_MAP.setKey(DioramaClient.OPEN_MAP.getDefaultKey());
			net.minecraft.client.KeyMapping.resetMapping();
		});
		if (!opened || !closed) {
			throw new AssertionError("With Open map on C, the key " + (opened ? "didn't close the map" : "didn't open the map"));
		}
	}

	/** Fog of war after the server's fair-play code, in a scene built for it. */
	private static void fogOfWar(ClientGameTestContext context, TestSingleplayerContext world) {
		// Fog of war. Build a known scene deep underground: a straight corridor through solid
		// stone that turns north at its far end into a hidden room. From the corridor's start,
		// the corridor must be revealed and the room must not, because it is around a corner.
		world.getServer().runCommand("fill 100 -42 98 131 -34 120 minecraft:stone");
		world.getServer().runCommand("fill 102 -40 115 128 -38 117 minecraft:air");
		world.getServer().runCommand("fill 126 -40 110 128 -38 114 minecraft:air");
		world.getServer().runCommand("fill 119 -40 100 128 -38 109 minecraft:air");
		world.getServer().runCommand("tellraw @a \"\u00a7f\u00a7a\u00a7i\u00a7r\u00a7x\u00a7a\u00a7e\u00a7r\u00a7o\"");
		world.getServer().runCommand("tp @a 103.5 -40 116.5 -90 0");
		settle(context, 100);
		context.takeScreenshot("diorama-07-fog-of-war-minimap");
		assertSeen(context, 115, -39, 116, true, "corridor in front of the player");
		assertSeen(context, 123, -39, 104, false, "room around the corner");
		showScene(context);
		context.takeScreenshot("diorama-08-fog-of-war-corridor");
		context.setScreen(() -> null);

		// Walk to the bend: now the room is in sight.
		world.getServer().runCommand("tp @a 127.5 -40 112.5 0 0");
		settle(context, 100);
		assertSeen(context, 123, -39, 104, true, "room once the player reaches the bend");
		showScene(context);
		context.takeScreenshot("diorama-09-fog-of-war-room");
		context.setScreen(() -> null);
			world.getServer().runCommand("tellraw @a \"\u00a7r\u00a7e\u00a7s\u00a7e\u00a7t\u00a7x\u00a7a\u00a7e\u00a7r\u00a7o\"");
	}

	/**
	 * Radar: mobs of each kind and another player around the viewer, one mob high above and one
	 * underground. Then the server's fair-play code must hide the radar, and its no-minimap code
	 * must hide the minimap.
	 */
	private static void radar(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set midnight");
		// Summon relative to where the player actually stands, not mid-fall after spawning, on a
		// cleared flat patch so no mob spawns inside a block and suffocates.
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX();
		int y = at.getY();
		int z = at.getZ();
		world.getServer().runCommand("fill " + (x - 12) + " " + y + " " + (z - 12) + " " + (x + 12) + " " + (y + 4) + " " + (z + 12) + " air");
		world.getServer().runCommand("fill " + (x - 12) + " " + (y - 1) + " " + (z - 12) + " " + (x + 12) + " " + (y - 1) + " " + (z + 12) + " grass_block");
		String still = "{NoAI:1b,PersistenceRequired:1b,Silent:1b}";
		world.getServer().runCommand("summon husk " + (x + 6) + " " + y + " " + (z - 4) + " " + still);
		world.getServer().runCommand("summon creeper " + (x - 7) + " " + y + " " + (z + 3) + " " + still);
		world.getServer().runCommand("summon wolf " + (x + 4) + " " + y + " " + (z + 6) + " " + still);
		world.getServer().runCommand("summon cow " + (x - 5) + " " + y + " " + (z - 7) + " " + still);
		world.getServer().runCommand("summon villager " + (x + 9) + " " + y + " " + (z + 1) + " " + still);
		world.getServer().runCommand("summon bat " + (x - 2) + " " + (y + 18) + " " + (z + 9) + " " + still);
		world.getServer().runCommand("fill " + (x + 2) + " " + (y - 14) + " " + (z - 9) + " " + (x + 4) + " " + (y - 12) + " " + (z - 7) + " air");
		world.getServer().runCommand("summon zombie " + (x + 3) + " " + (y - 14) + " " + (z - 8) + " " + still);
		// Tests run one real client, so the other player is a stand-in added on the client.
		context.runOnClient(mc -> {
			RemotePlayer other = new RemotePlayer(mc.level, new GameProfile(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch"));
			other.setId(-424242);
			other.snapTo(x - 3.5, y, z + 8.5);
			mc.level.addEntity(other);
		});
		settle(context, 60);
		context.takeScreenshot("radar-01-minimap");

		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 6.0, TileKey.SURFACE);
		settle(context, 40);
		// Hover the husk so its tooltip shows.
		float[] husk = context.computeOnClient(mc -> {
			float scale = (float) mc.getWindow().getGuiScale();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				if (blip.entity().getType() == EntityTypes.HUSK) {
					return new float[] {blip.x() * scale, blip.y() * scale};
				}
			}
			StringBuilder found = new StringBuilder();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				found.append(' ').append(blip.entity().getType().toShortString()).append('@').append(blip.entity().blockPosition().toShortString());
			}
			StringBuilder husks = new StringBuilder();
			for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
				if (entity.getType() == EntityTypes.HUSK) {
					husks.append(' ').append(entity.blockPosition().toShortString()).append(" alive=").append(entity.isAlive())
						.append(" dy=").append(Math.round(entity.getY() - mc.player.getY()));
				}
			}
			throw new AssertionError("The husk is not on the radar. Player at " + mc.player.blockPosition().toShortString()
				+ ", husks in the level:" + husks + ", radar has:" + found);
		});
		context.getInput().setCursorPos(husk[0], husk[1]);
		context.waitTicks(10);
		context.takeScreenshot("radar-02-map");
		context.setScreen(() -> null);

		// A lineup of mobs, hostile in one row and the rest in another, babies at the ends: each
		// shows its own face on the radar.
		String[] hostile = {"zombie", "skeleton", "creeper", "spider", "enderman", "witch", "pillager", "blaze", "drowned", "stray", "zombie_villager"};
		String[] others = {"cow", "pig", "sheep", "chicken", "cat", "fox", "rabbit", "villager", "iron_golem", "panda", "bee"};
		String immune = "{NoAI:1b,PersistenceRequired:1b,Silent:1b,IsImmuneToZombification:1b}";
		for (int i = 0; i < hostile.length; i++) {
			world.getServer().runCommand("summon " + hostile[i] + " " + (x - 11 + 2 * i) + " " + y + " " + (z - 11) + " " + still);
		}
		world.getServer().runCommand("summon zombie " + (x + 11) + " " + y + " " + (z - 11) + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,IsBaby:1b}");
		world.getServer().runCommand("summon piglin " + (x + 11) + " " + y + " " + (z - 9) + " " + immune);
		for (int i = 0; i < others.length; i++) {
			world.getServer().runCommand("summon " + others[i] + " " + (x - 11 + 2 * i) + " " + y + " " + (z + 11) + " " + still);
		}
		world.getServer().runCommand("summon cow " + (x + 11) + " " + y + " " + (z + 11) + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,Age:-24000}");
		context.waitTicks(20);
		context.runOnClient(mc -> {
			// Every summoned kind is there with a face that draws, a baby zombie and a calf included.
			java.util.List<String> expected = new java.util.ArrayList<>(java.util.List.of(hostile));
			expected.addAll(java.util.List.of(others));
			expected.add("piglin");
			StringBuilder wrong = new StringBuilder();
			for (String name : expected) {
				boolean found = false;
				for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
					if (entity.getType().toShortString().equals(name) && entity.distanceToSqr(mc.player) < 30 * 30) {
						found |= dev.diorama.render.MobFaces.canDraw(entity);
					}
				}
				if (!found) {
					wrong.append(' ').append(name);
				}
			}
			for (net.minecraft.world.entity.EntityType<?> type : new net.minecraft.world.entity.EntityType<?>[] {EntityTypes.ZOMBIE, EntityTypes.COW}) {
				boolean baby = false;
				for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
					if (entity.getType() == type && entity instanceof net.minecraft.world.entity.LivingEntity living && living.isBaby()
						&& entity.distanceToSqr(mc.player) < 30 * 30) {
						baby |= dev.diorama.render.MobFaces.canDraw(entity);
					}
				}
				if (!baby) {
					wrong.append(" baby ").append(type.toShortString());
				}
			}
			if (!wrong.isEmpty()) {
				throw new AssertionError("No drawable face for:" + wrong);
			}
		});
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x + 0.5, Projection.v(y, z - 11), 9.0, TileKey.SURFACE));
		settle(context, 40);
		context.takeScreenshot("radar-04-faces-hostile");
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x + 0.5, Projection.v(y, z + 11), 9.0, TileKey.SURFACE));
		settle(context, 20);
		context.takeScreenshot("radar-05-faces-others");
		context.setScreen(() -> null);

		world.getServer().runCommand("tellraw @a \"§f§a§i§r§x§a§e§r§o\"");
		context.waitTicks(20);
		context.takeScreenshot("radar-03-fair-play-hides-radar");

		world.getServer().runCommand("tellraw @a \"§n§o§m§i§n§i§m§a§p\"");
		context.waitTicks(20);
		context.takeScreenshot("radar-04-no-minimap");
		world.getServer().runCommand("tellraw @a \"§r§e§s§e§t§x§a§e§r§o\"");
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("kill @e[type=!player]");
		context.waitTicks(10);
	}

	/**
	 * Waypoints: one nearby and one far off, the in-world label, the map tooltip, adding one by
	 * right-clicking the map, the list, a death marker, and the file on disk.
	 */
	private static void waypoints(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.onGround(), 600);
		// The empty list says to press the add-waypoint key, so pressing it there opens the editor.
		context.getInput().pressKey(DioramaClient.WAYPOINT_LIST);
		context.waitTicks(3);
		context.takeScreenshot("waypoints-00-empty-list");
		context.getInput().pressKey(DioramaClient.NEW_WAYPOINT);
		context.waitTicks(3);
		boolean keyOpenedEditor = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		context.setScreen(() -> null);
		if (!keyOpenedEditor) {
			throw new AssertionError("The add-waypoint key did nothing on the waypoint list");
		}
		// Bound to a mouse button, the hint names that button, so it has to work too.
		InputConstants.Key boundKey = context.computeOnClient(mc -> InputConstants.getKey(DioramaClient.NEW_WAYPOINT.saveString()));
		context.runOnClient(mc -> {
			DioramaClient.NEW_WAYPOINT.setKey(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT + 3));
			net.minecraft.client.KeyMapping.resetMapping();
		});
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(3);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT + 3);
		context.waitTicks(3);
		boolean mouseOpenedEditor = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			DioramaClient.NEW_WAYPOINT.setKey(boundKey);
			net.minecraft.client.KeyMapping.resetMapping();
		});
		if (!mouseOpenedEditor) {
			throw new AssertionError("A mouse button bound to add-waypoint did nothing on the waypoint list");
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			BlockPos at = mc.player.blockPosition();
			String dimension = mc.level.dimension().identifier().toString();
			int hx = at.getX() + 24;
			int hz = at.getZ() - 14;
			int hy = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, hx, hz);
			store.add(new Waypoint("Home", hx, hy, hz, dimension, Waypoint.PALETTE[5]));
			store.add(new Waypoint("Iron Mine", at.getX() + 400, at.getY(), at.getZ() + 120, dimension, Waypoint.PALETTE[1]));
		});
		context.waitTicks(10);
		context.takeScreenshot("waypoints-01-minimap");

		// Face Home: its label shows in the world with its name and distance.
		BlockPos home = context.computeOnClient(mc -> {
			for (Waypoint w : WaypointStore.get().all()) {
				if (w.name.equals("Home")) {
					return new BlockPos(w.x, w.y, w.z);
				}
			}
			throw new AssertionError("Home is missing");
		});
		//? if >=26.2 {
		context.getInput().lookAt(home);
		//?} else {
		/*context.runOnClient(mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, net.minecraft.world.phys.Vec3.atCenterOf(home)));
		*///?}
		context.waitTicks(10);
		context.takeScreenshot("waypoints-02-in-world");

		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 4.0, TileKey.SURFACE);
		settle(context, 30);
		float[] pin = context.computeOnClient(mc -> {
			float scale = (float) mc.getWindow().getGuiScale();
			for (WaypointOverlay.Pin p : ((MapScreen) mc.gui.screen()).pins()) {
				if (p.waypoint().name.equals("Home")) {
					return new float[] {p.x() * scale, p.y() * scale};
				}
			}
			throw new AssertionError("Home is not on the map");
		});
		context.getInput().setCursorPos(pin[0], pin[1]);
		context.waitTicks(5);
		context.takeScreenshot("waypoints-03-map");

		// A quick drag that starts on Home must pan, not open its editor, even when it's released
		// before the game dispatches any drag event.
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.getInput().moveCursor(80, 40);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		boolean dragOpenedEditor = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		if (dragOpenedEditor) {
			throw new AssertionError("Dragging from a waypoint opened its editor");
		}
		lookAtPlayer(context, 4.0, TileKey.SURFACE);
		context.waitTicks(3);

		// Right-click a spot left of the player: the editor opens on the block shown there, which
		// is the first solid block on the camera ray through that point (a canopy can hide the
		// ground behind it).
		double[] click = context.computeOnClient(mc -> {
			MapScreen screen = (MapScreen) mc.gui.screen();
			MapViewport.View view = screen.view();
			double u = mc.player.getBlockX() - 15 + 0.5;
			double v = Projection.v(mc.player.getY(), mc.player.getZ());
			float scale = (float) mc.getWindow().getGuiScale();
			return new double[] {u, v, view.screenX(u) * scale, view.screenY(v) * scale};
		});
		context.getInput().setCursorPos(click[2], click[3]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		boolean editorOpen = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		if (!editorOpen) {
			throw new AssertionError("Right-clicking the map didn't open the waypoint editor");
		}
		context.waitTicks(5);
		context.takeScreenshot("waypoints-04-editor");

		// A coordinate that isn't a number turns red and Save stays off, instead of saving the old
		// coordinate. What's typed survives a GUI scale change, which rebuilds the screen.
		String pickedX = context.computeOnClient(mc -> editBoxes(mc.gui.screen()).get(2).getValue());
		typeInto(context, 2, "abc");
		clickButton(context, "diorama.waypoint.save");
		boolean savedBadX = context.computeOnClient(mc -> !(mc.gui.screen() instanceof WaypointEditScreen) || WaypointStore.get().all().size() != 2);
		if (savedBadX) {
			throw new AssertionError("Save went through with X = abc");
		}
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		setGuiScale(context, 4);
		context.takeScreenshot("waypoints-04b-editor-bad-x");
		String keptX = context.computeOnClient(mc -> editBoxes(mc.gui.screen()).get(2).getValue());
		setGuiScale(context, guiScale);
		if (!keptX.equals("abc")) {
			throw new AssertionError("Changing GUI scale replaced the typed X with " + keptX);
		}
		typeInto(context, 2, pickedX);
		clickButton(context, "diorama.waypoint.save");
		context.waitTicks(5);
		int count = context.computeOnClient(mc -> WaypointStore.get().all().size());
		if (count != 3) {
			throw new AssertionError("Expected 3 waypoints after saving the picked one, found " + count);
		}
		Waypoint picked = context.computeOnClient(mc -> WaypointStore.get().all().get(2));
		// The hit block sits under the waypoint; the ray crosses its top or south face.
		int hitY = picked.y - 1;
		double vTop = Projection.v(hitY + 1, picked.z);
		double vBottom = Projection.v(hitY, picked.z + 1);
		boolean onRay = picked.x == (int) Math.floor(click[0]) && click[1] >= vTop - 1e-6 && click[1] <= vBottom + 1e-6;
		boolean solid = context.computeOnClient(mc -> !mc.level.getBlockState(new BlockPos(picked.x, hitY, picked.z)).isAir());
		if (!onRay || !solid) {
			throw new AssertionError("Picked " + picked.x + "," + picked.y + "," + picked.z + " is not the block shown at the click");
		}
		context.setScreen(() -> null);

		// A name as long as the editor allows, and coordinates near the world border, stop before
		// the row's buttons.
		context.runOnClient(mc -> WaypointStore.get().add(new Waypoint("Long waypoint name 1234567890123",
			-29999000, -64, 29999000, mc.level.dimension().identifier().toString(), Waypoint.PALETTE[3])));
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("waypoints-05-list");
		context.setScreen(() -> null);

		// Dying leaves a death marker.
		world.getServer().runCommand("gamerule show_death_messages false");
		world.getServer().runCommand("kill @a");
		context.waitTicks(20);
		boolean death = context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.DEATH));
		if (!death) {
			throw new AssertionError("No death marker after dying");
		}
		context.clickScreenButton("deathScreen.respawn");
		context.waitFor(mc -> mc.player != null && mc.player.isAlive(), 200);
		context.waitTicks(40);
		context.takeScreenshot("waypoints-06-after-death");

		// With instant respawn there is no death screen, and the death marker must still appear.
		world.getServer().runCommand("gamerule immediate_respawn true");
		long deathsBefore = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.DEATH).count());
		world.getServer().runCommand("kill @a");
		context.waitTicks(40);
		long deathsAfter = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.DEATH).count());
		world.getServer().runCommand("gamerule immediate_respawn false");
		if (deathsAfter != deathsBefore + 1) {
			throw new AssertionError("Instant respawn left " + (deathsAfter - deathsBefore) + " new death markers instead of 1");
		}

		// Worlds whose names clean up the same must not share a folder.
		if (WorldKey.key("singleplayer", "My World").equals(WorldKey.key("singleplayer", "My_World"))) {
			throw new AssertionError("Two different worlds got the same key");
		}

		// A damaged file: one bad entry is skipped, a null field is filled in, a copy of the
		// original is kept, and saving doesn't drop the good entries.
		context.runOnClient(mc -> {
			String key = "test-damaged-file";
			java.nio.file.Path folder = WorldKey.folder(key);
			try {
				java.nio.file.Files.createDirectories(folder);
				java.nio.file.Files.writeString(folder.resolve("waypoints.json"), """
					[
					  {"id": "00000000-0000-0000-0000-000000000001", "name": "Home", "initials": null, "x": 1, "y": 2, "z": 3, "dimension": "minecraft:overworld"},
					  {"id": "not-a-uuid", "name": "Broken", "x": 4, "y": 5, "z": 6, "dimension": "minecraft:overworld"}
					]
					""");
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			WaypointStore damaged = WaypointStore.open(key);
			if (damaged.all().size() != 1 || !damaged.all().get(0).label().equals("H")) {
				throw new AssertionError("Expected only Home, with initials H, from the damaged file; got " + damaged.all().size());
			}
			damaged.add(new Waypoint("New", 0, 0, 0, "minecraft:overworld", Waypoint.PALETTE[0]));
			WaypointStore reopened = WaypointStore.open(key);
			if (reopened.all().size() != 2) {
				throw new AssertionError("Saving after a damaged load kept " + reopened.all().size() + " waypoints instead of 2");
			}
			try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(folder)) {
				if (files.noneMatch(f -> f.getFileName().toString().startsWith("waypoints.json.unreadable-"))) {
					throw new AssertionError("No copy of the damaged file was kept");
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});

		String saved = context.computeOnClient(mc -> {
			try {
				return java.nio.file.Files.readString(WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
			} catch (java.io.IOException e) {
				throw new AssertionError("waypoints.json is missing", e);
			}
		});
		if (!saved.contains("\"Home\"") || !saved.contains("\"Iron Mine\"") || !saved.contains("\"DEATH\"")) {
			throw new AssertionError("waypoints.json doesn't have every waypoint:\n" + saved);
		}
		shareAndTeleport(context, world);
	}

	/** Sharing a waypoint through chat, picking one up from chat, and teleporting to one. */
	private static void shareAndTeleport(ClientGameTestContext context, TestSingleplayerContext world) {
		// The chat line is one a client may send, with names escaped the way Xaero's Minimap does,
		// and it survives a round trip.
		context.runOnClient(mc -> {
			Waypoint sent = new Waypoint("Mine: east-2_b", 120, 40, -80, "minecraft:the_nether", Waypoint.PALETTE[3]);
			String text = WaypointShare.format(sent).orElseThrow(() -> new AssertionError("Couldn't format a short waypoint"));
			//? if >=1.21.9 {
			if (text.length() > 256 || !text.codePoints().allMatch(net.minecraft.util.StringUtil::isAllowedChatCharacter)) {
			//?} else {
			/*if (text.length() > 256 || !text.chars().allMatch(c -> net.minecraft.util.StringUtil.isAllowedChatCharacter((char) c))) {
			*///?}
				throw new AssertionError("Chat would refuse " + text);
			}
			WaypointShare.Shared back = WaypointShare.parse("<Alex> " + text).orElseThrow(() -> new AssertionError("Couldn't read back " + text));
			if (!text.startsWith("xaero-waypoint:Mine^col^ east^min^2-b:")) {
				throw new AssertionError("Not escaped like Xaero's Minimap: " + text);
			}
			if (!back.name().equals("Mine: east-2_b") || back.x() != 120 || back.y() != 40 || back.z() != -80 || !back.dimension().equals("minecraft:the_nether")) {
				throw new AssertionError("Round trip of " + text + " gave " + back);
			}
			// A name cut to the editor's 32 characters never ends in half an emoji.
			String emoji = "a".repeat(31) + "\uD83D\uDE00";
			WaypointShare.Shared cut = WaypointShare.parse("xaero-waypoint:" + emoji + ":E:1:64:2:6:false:0:Internal-overworld-waypoints").orElseThrow();
			if (Character.isHighSurrogate(cut.name().charAt(cut.name().length() - 1))) {
				throw new AssertionError("Cutting the name split a character: " + cut.name());
			}
			for (String bad : new String[] {
				"xaero-waypoint:N:N:2147483648:64:0:6:false:0:Internal-overworld-waypoints",
				"xaero-waypoint:N:N:1:64:0:99:false:0:Internal-overworld-waypoints",
				"xaero-waypoint:N:N:1:64:0:6:false:0:Internal-dim%bad-waypoints",
				"xaero-waypoint: :N:1:64:0:6:false:0:Internal-overworld-waypoints"
			}) {
				if (WaypointShare.parse(bad).isPresent()) {
					throw new AssertionError("Accepted " + bad);
				}
			}
		});

		// A share with a number out of range arrives: nothing breaks, the player stays connected.
		world.getServer().runCommand("tellraw @a \"xaero-waypoint:N:N:2147483648:64:0:6:false:0:Internal-overworld-waypoints\"");
		context.waitTicks(10);
		assertConnected(context, "a share with an out-of-range number");

		// Someone else's share arrives: its line gets an [Add] link.
		world.getServer().runCommand("tellraw @a \"xaero-waypoint:Old Mine:O:120:40:-80:6:false:0:Internal-overworld-waypoints\"");
		context.waitTicks(10);
		context.takeScreenshot("waypoints-07-shared-in-chat");
		// Take the command from the [Add] link on the line chat actually received, and run it the
		// way a chat click does: from the open chat screen, which Minecraft puts back after
		// running the command. The malformed share's line has no link.
		//? if >=1.21.9 {
		context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("", false));
		//?} else {
		/*context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen(""));
		*///?}
		context.waitTicks(2);
		context.runOnClient(mc -> {
			String command = null;
			for (net.minecraft.client.multiplayer.chat.GuiMessage message : chatMessages(mc)) {
				String text = message.content().getString();
				String link = linkIn(message.content());
				if (text.contains("2147483648") && link != null) {
					throw new AssertionError("The malformed share got a link: " + text);
				}
				if (text.contains("xaero-waypoint:Old Mine:")) {
					command = link;
				}
			}
			if (command == null) {
				throw new AssertionError("The shared line in chat has no [Add] link");
			}
			//? if >=1.21.6 {
			mc.player.connection.sendUnattendedCommand(net.minecraft.commands.Commands.trimOptionalPrefix(command), mc.gui.screen());
			//?} else {
			/*// What a chat click ran before 1.21.6.
			mc.player.connection.sendUnsignedCommand(command.startsWith("/") ? command.substring(1) : command);
			*///?}
		});
		context.waitFor(mc -> mc.gui.screen() instanceof WaypointEditScreen, 100);
		context.waitTicks(2);
		context.runOnClient(mc -> {
			// What the editor shows: name, initials, x, y, z.
			java.util.List<String> shown = new java.util.ArrayList<>();
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.EditBox box) {
					shown.add(box.getValue());
				}
			}
			if (!shown.equals(java.util.List.of("Old Mine", "O", "120", "40", "-80"))) {
				throw new AssertionError("The editor shows " + shown);
			}
		});
		context.takeScreenshot("waypoints-08-add-shared");
		context.setScreen(() -> null);

		// Teleport from an existing waypoint's editor, after changing its x in the editor: the
		// player goes where the editor says.
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Waypoint target = new Waypoint("Lookout: north", at.getX() + 40, at.getY() + 30, at.getZ(), "minecraft:overworld", Waypoint.PALETTE[5]);
		int editedX = target.x + 5;
		context.setScreen(() -> new WaypointEditScreen(null, target, false));
		context.waitTicks(2);
		context.runOnClient(mc -> {
			java.util.List<net.minecraft.client.gui.components.EditBox> boxes = new java.util.ArrayList<>();
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.EditBox box) {
					boxes.add(box);
				}
			}
			// Name, initials, x, y, z.
			boxes.get(2).setValue(Integer.toString(editedX));
			net.minecraft.client.gui.components.Button teleport = buttonLabelled(mc.gui.screen(), "Teleport");
			if (!teleport.active) {
				throw new AssertionError("Teleport is off in a world where the player may use /tp");
			}
			//? if >=1.21.9 {
			teleport.onPress(null);
			//?} else {
			/*teleport.onPress();
			*///?}
		});
		context.waitFor(mc -> mc.player.distanceToSqr(editedX + 0.5, target.y, target.z + 0.5) < 4, 100);

		// Share it: a name with a colon goes out without upsetting the server.
		context.setScreen(() -> new WaypointEditScreen(null, target, false));
		context.waitTicks(2);
		//? if >=1.21.9 {
		context.runOnClient(mc -> buttonLabelled(mc.gui.screen(), "Share in chat").onPress(null));
		//?} else {
		/*context.runOnClient(mc -> buttonLabelled(mc.gui.screen(), "Share in chat").onPress());
		*///?}
		context.waitTicks(20);
		assertConnected(context, "sharing a name with a colon");
		context.takeScreenshot("waypoints-09-shared-own");
	}

	/**
	 * Every line in chat, newest first. Read through reflection: chat keeps them private. Found by
	 * type, not name, so it works where the game's names are obfuscated (1.21.11 in production).
	 */
	@SuppressWarnings("unchecked")
	private static java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage> chatMessages(Minecraft mc) {
		try {
			for (java.lang.reflect.Field field : net.minecraft.client.gui.components.ChatComponent.class.getDeclaredFields()) {
				if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType type && type.getRawType() == java.util.List.class
					&& type.getActualTypeArguments()[0] == net.minecraft.client.multiplayer.chat.GuiMessage.class) {
					field.setAccessible(true);
					return (java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage>) field.get(mc.gui.hud.getChat());
				}
			}
			throw new AssertionError("Chat has no List<GuiMessage> field");
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** The command a line's click-to-run link carries, or null. */
	private static @Nullable String linkIn(net.minecraft.network.chat.Component line) {
		String[] command = new String[1];
		line.visit((style, text) -> {
			//? if >=1.21.5 {
			if (style.getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand run) {
				command[0] = run.command();
			}
			//?} else {
			/*net.minecraft.network.chat.ClickEvent click = style.getClickEvent();
			if (click != null && click.getAction() == net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND) {
				command[0] = click.getValue();
			}
			*///?}
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return command[0];
	}

	private static void assertConnected(ClientGameTestContext context, String after) {
		boolean connected = context.computeOnClient(mc -> mc.getConnection() != null && mc.level != null && mc.player != null);
		if (!connected) {
			throw new AssertionError("Disconnected after " + after);
		}
	}

	/**
	 * Importing from Xaero's Minimap, with files Xaero's Minimap 26.6.0 wrote in a real game
	 * (src/gametest/resources/xaero/): put where Xaero's keeps them for this world, the waypoint
	 * list offers them, and importing brings their names (a colon, a death point Xaero's names
	 * with a translation key), colours, hidden state, death marker and the Nether one across.
	 * A second look offers nothing, even after the player moved, so the one Xaero's stored
	 * without a height isn't taken for a new one. With several of Xaero's worlds and no telling
	 * which, the player picks. Then how a server's worlds are offered.
	 */
	private static void xaeroImport(ClientGameTestContext context, TestSingleplayerContext world) {
		// The folder names Xaero's gave a save called "Xaero_Test [1]" and a server at localhost:25580.
		if (!XaeroImport.singleplayerFolders("Xaero_Test [1]").get(0).equals("Xaero%us%Test %lb%1%rb%")
			|| !XaeroImport.serverFolders("localhost:25580", true).get(0).equals("Multiplayer_localhost")) {
			throw new AssertionError("Xaero's folder names come out as " + XaeroImport.singleplayerFolders("Xaero_Test [1]") + " and "
				+ XaeroImport.serverFolders("localhost:25580", true));
		}
		java.nio.file.Path root = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("xaero").resolve("minimap").resolve(XaeroImport.singleplayerFolders(
			mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString()).get(0)));
		xaeroFixture("singleplayer/overworld.txt", root.resolve("dim%0").resolve("waypoints.txt"));
		xaeroFixture("singleplayer/nether.txt", root.resolve("dim%-1").resolve("waypoints.txt"));
		// Next to them, an End file that isn't UTF-8 (a name in Latin-1): it's skipped, and the
		// rest is still offered.
		try {
			java.nio.file.Files.createDirectories(root.resolve("dim%1"));
			java.nio.file.Files.write(root.resolve("dim%1").resolve("waypoints.txt"),
				"waypoint:Caf\u00e9:C:10:64:10:0:false:0:gui.xaero_default:false:0:0:false\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		Set<UUID> before = context.computeOnClient(mc -> new java.util.HashSet<>(WaypointStore.get().all().stream().map(w -> w.id).toList()));

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("xaero-01-offered");
		String offer = context.computeOnClient(mc -> importButtonLabel(mc.gui.screen()));
		if (!"Import from Xaero's Minimap (8)".equals(offer)) {
			throw new AssertionError("The waypoint list offers " + offer);
		}
		context.runOnClient(mc -> press(buttonLabelled(mc.gui.screen(), "Import from Xaero's Minimap (8)")));
		context.waitTicks(5);
		context.takeScreenshot("xaero-02-imported");

		List<Waypoint> imported = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> !before.contains(w.id)).toList());
		java.util.Map<String, Waypoint> byName = new java.util.HashMap<>();
		imported.forEach(w -> byName.put(w.name, w));
		if (imported.size() != 8 || !byName.keySet().equals(Set.of("Latest Death", "No Height", "Pink Tree", "Hidden Cave", "Base_2 - East", "Iron: Farm", "Home", "Nether Hub"))) {
			throw new AssertionError("Imported " + byName.keySet());
		}
		Waypoint home = byName.get("Home");
		if (home.x != -232 || home.y != 78 || home.z != -118 || !home.dimension.equals("minecraft:overworld") || home.color != Waypoint.PALETTE[0]) {
			throw new AssertionError("Home came in at " + home.x + "," + home.y + "," + home.z + " in " + home.dimension + ", colour " + Integer.toHexString(home.color));
		}
		if (byName.get("Latest Death").kind != Waypoint.Kind.DEATH || byName.get("Home").kind != Waypoint.Kind.MANUAL) {
			throw new AssertionError("Xaero's death point didn't come in as a death marker, or another waypoint did");
		}
		if (byName.get("Hidden Cave").visible || !byName.get("Home").visible) {
			throw new AssertionError("The waypoint hidden in Xaero's isn't hidden, or a shown one is");
		}
		if (!byName.get("Nether Hub").dimension.equals("minecraft:the_nether") || byName.get("Nether Hub").y != 128) {
			throw new AssertionError("Nether Hub came in as " + byName.get("Nether Hub").dimension + " at y " + byName.get("Nether Hub").y);
		}
		// Xaero's colour 16 is its magenta; dark blue is index 1.
		if (byName.get("Pink Tree").color != Waypoint.PALETTE[8] || byName.get("No Height").color != Waypoint.PALETTE[6]) {
			throw new AssertionError("Pink Tree is " + Integer.toHexString(byName.get("Pink Tree").color) + ", No Height " + Integer.toHexString(byName.get("No Height").color));
		}

		// Higher up, and with the list opened afresh, there's nothing left to import.
		context.setScreen(() -> null);
		world.getServer().runCommand("tp @a ~ ~20 ~");
		context.waitTicks(10);
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		String again = context.computeOnClient(mc -> importButtonLabel(mc.gui.screen()));
		if (again != null) {
			throw new AssertionError("After importing, the list still offers " + again);
		}
		context.setScreen(() -> null);
		// The death point's name came from the game's language. Under another language, or with
		// Xaero's (which has its own translation) removed, it reads differently, and it's still
		// the same death point.
		context.runOnClient(mc -> byName.get("Latest Death").name = "Derni\u00e8re mort");
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		String otherLanguage = context.computeOnClient(mc -> importButtonLabel(mc.gui.screen()));
		if (otherLanguage != null) {
			throw new AssertionError("With the death point named in another language, the list offers " + otherLanguage);
		}
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			imported.forEach(store::remove);
			try {
				org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});

		// Two of Xaero's other worlds and no "waypoints" one: which is this world can't be told,
		// so the list asks, and the player takes one.
		xaeroFixture("singleplayer/overworld.txt", root.resolve("dim%0").resolve("mw$1_Survival.txt"));
		xaeroFixture("singleplayer/nether.txt", root.resolve("dim%0").resolve("mw$2_Old Base.txt"));
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.runOnClient(mc -> press(buttonLabelled(mc.gui.screen(), "Import from Xaero's Minimap...")));
		context.waitTicks(5);
		context.takeScreenshot("xaero-03-pick");
		context.runOnClient(mc -> press(buttonLabelled(mc.gui.screen(), "Survival, Overworld: 7 new")));
		context.waitTicks(5);
		context.takeScreenshot("xaero-04-picked");
		List<Waypoint> picked = context.computeOnClient(mc -> {
			buttonLabelled(mc.gui.screen(), "Survival, Overworld: imported");
			buttonLabelled(mc.gui.screen(), "Old Base, Overworld: 1 new");
			return WaypointStore.get().all().stream().filter(w -> !before.contains(w.id)).toList();
		});
		if (picked.size() != 7 || picked.stream().noneMatch(w -> w.name.equals("Iron: Farm"))) {
			throw new AssertionError("Picking Survival imported " + picked.stream().map(w -> w.name).toList());
		}
		// Back in the list, Old Base is still on offer.
		context.runOnClient(mc -> press(buttonLabelled(mc.gui.screen(), "Done")));
		context.waitTicks(5);
		String still = context.computeOnClient(mc -> importButtonLabel(mc.gui.screen()));
		if (!"Import from Xaero's Minimap...".equals(still)) {
			throw new AssertionError("Back from the picker, the list offers " + still);
		}
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			picked.forEach(store::remove);
			try {
				org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});

		// A server's folder as Xaero's wrote it on a vanilla server, plus a copy under another
		// spawn's name, as a map reset leaves behind. On a server the player always confirms, with
		// the world Xaero's would open listed first: the one its config pins with multiworld
		// detection off, the current spawn's with it on. With Xaero's World Map's world in the
		// mix too, each dimension's file is offered on its own and none is marked.
		context.runOnClient(mc -> {
			java.nio.file.Path base = mc.gameDirectory.toPath().resolve("xaero-import-test");
			java.nio.file.Path server = base.resolve("Multiplayer_localhost");
			java.nio.file.Path config = server.resolve("config.txt");
			xaeroFixture("server/config.txt", config);
			xaeroFixture("server/overworld.txt", server.resolve("dim%0").resolve("mw-1,1,0_1.txt"));
			xaeroFixture("server/nether.txt", server.resolve("dim%-1").resolve("mw-1,1,0_1.txt"));
			xaeroFixture("server/overworld.txt", server.resolve("dim%0").resolve("mw7,1,3_2.txt"));
			List<String> folders = XaeroImport.serverFolders("localhost:25580", true);
			try {
				XaeroImport.Found pinned = XaeroImport.find(base, folders, false, "mw7,1,3", 64);
				if (pinned == null || pinned.chosen() != null || pinned.worlds().size() != 2
					|| !pinned.worlds().get(0).id().equals("mw-1,1,0") || !pinned.worlds().get(0).likely() || pinned.worlds().get(1).likely()) {
					throw new AssertionError("With detection off, the server's worlds aren't offered with the pinned one first and marked");
				}
				Set<String> names = Set.copyOf(pinned.worlds().get(0).entries().stream().map(e -> e.waypoint().name).toList());
				if (!names.equals(Set.of("Latest Death", "Mine: Deep", "Spawn Shop", "Fortress"))) {
					throw new AssertionError("The pinned server world brought " + names);
				}
				java.nio.file.Files.writeString(config, java.nio.file.Files.readString(config).replace("usingMultiworldDetection:false", "usingMultiworldDetection:true"));
				XaeroImport.Found bySpawn = XaeroImport.find(base, folders, false, "mw7,1,3", 64);
				if (!bySpawn.worlds().get(0).id().equals("mw7,1,3") || !bySpawn.worlds().get(0).likely()) {
					throw new AssertionError("With detection on, the current spawn's world isn't offered first");
				}
				xaeroFixture("server/overworld.txt", server.resolve("dim%0").resolve("mw$default_Default.txt"));
				XaeroImport.Found perDimension = XaeroImport.find(base, folders, false, "mw7,1,3", 64);
				// Two worlds' Overworld files, one's Nether file, World Map's.
				if (perDimension.worlds().size() != 4 || perDimension.worlds().stream().anyMatch(w -> w.likely() || w.dimension() == null)) {
					throw new AssertionError("With World Map's world in the mix, the server import offers " + perDimension.worlds());
				}
				// With Xaero's told not to tell servers apart, one folder holds every server's files
				// under the same ids: each dimension stays its own row, and none is marked.
				java.nio.file.Path shared = base.resolve("Multiplayer_Any Address");
				xaeroFixture("server/config.txt", shared.resolve("config.txt"));
				xaeroFixture("server/overworld.txt", shared.resolve("dim%0").resolve("mw-1,1,0_1.txt"));
				xaeroFixture("server/nether.txt", shared.resolve("dim%-1").resolve("mw-1,1,0_1.txt"));
				XaeroImport.Found anyServer = XaeroImport.find(base, XaeroImport.serverFolders("localhost:25580", false), false, "mw7,1,3", 64);
				if (anyServer.worlds().size() != 2 || anyServer.worlds().stream().anyMatch(w -> w.likely() || w.dimension() == null)) {
					throw new AssertionError("The shared server folder offers " + anyServer.worlds());
				}
				org.apache.commons.io.FileUtils.deleteDirectory(base.toFile());
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
	}

	private static void xaeroFixture(String name, java.nio.file.Path to) {
		try (java.io.InputStream in = DioramaClientTest.class.getResourceAsStream("/xaero/" + name)) {
			if (in == null) {
				throw new AssertionError("Missing fixture " + name);
			}
			java.nio.file.Files.createDirectories(to.getParent());
			java.nio.file.Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	/** Presses a button the way a click does. */
	private static void press(net.minecraft.client.gui.components.Button button) {
		//? if >=1.21.9 {
		button.onPress(null);
		//?} else {
		/*button.onPress();
		*///?}
	}

	/** The label of the waypoint list's import button, or null if it has none. */
	private static @Nullable String importButtonLabel(net.minecraft.client.gui.screens.Screen screen) {
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.Button button && button.getMessage().getString().startsWith("Import from Xaero's")) {
				return button.getMessage().getString();
			}
		}
		return null;
	}

	/** The screen's text fields, top to bottom and left to right. */
	private static List<net.minecraft.client.gui.components.EditBox> editBoxes(net.minecraft.client.gui.screens.Screen screen) {
		java.util.List<net.minecraft.client.gui.components.EditBox> boxes = new java.util.ArrayList<>();
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.EditBox box) {
				boxes.add(box);
			}
		}
		return boxes;
	}

	/** Clicks the middle of a widget with the mouse, the way a player does. */
	private static void clickAt(ClientGameTestContext context, net.minecraft.client.gui.components.AbstractWidget widget) {
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	/** Clicks a button with the mouse, which does nothing while it's off (unlike clickScreenButton). */
	private static void clickButton(ClientGameTestContext context, String translationKey) {
		String label = net.minecraft.network.chat.Component.translatable(translationKey).getString();
		clickAt(context, context.computeOnClient(mc -> buttonLabelled(mc.gui.screen(), label)));
	}

	/** Clicks a text field, empties it and types into it. */
	private static void typeInto(ClientGameTestContext context, int field, String text) {
		clickAt(context, context.computeOnClient(mc -> editBoxes(mc.gui.screen()).get(field)));
		// The test input's Ctrl+A doesn't select, so the field is emptied directly; the typing is real.
		context.runOnClient(mc -> editBoxes(mc.gui.screen()).get(field).setValue(""));
		context.getInput().typeChars(text);
		context.waitTicks(2);
	}

	private static void setGuiScale(ClientGameTestContext context, int scale) {
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			//? if >=26.1 {
			mc.resizeGui();
			//?} else {
			/*mc.resizeDisplay();
			*///?}
		});
		context.waitTicks(3);
	}

	private static net.minecraft.client.gui.components.Button buttonLabelled(net.minecraft.client.gui.screens.Screen screen, String label) {
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.Button button && button.getMessage().getString().equals(label)) {
				return button;
			}
		}
		throw new AssertionError("No " + label + " button on " + screen.getClass().getSimpleName());
	}

	/**
	 * Settings: the default minimap with its compass and info lines, the settings screen, the
	 * minimap moved to another corner at another size, and the effect icons pushing it down.
	 */
	/**
	 * Chunk grid, slime chunks and spawn spots on a built scene: a stone platform high in open sky
	 * across a chunk border, lit by one torch, with a closed stone box on one corner.
	 */
	//? if <1.21.6 {
	/*// Opt-in with DIORAMA_TESTS=broken-fog. A resource pack whose fog shader doesn't compile, the way a
	// strict graphics driver might reject ours. Before 1.21.6 the game's own shader path clears the
	// player's resource packs (or crashes) when a shader fails, so the map has to draw without fog and
	// leave the packs alone, then bring the fog back once a reload fixes the shader.
	private static void brokenFog(ClientGameTestContext context) {
		String id = "file/diorama-broken-fog";
		java.nio.file.Path pack = context.computeOnClient(mc -> mc.getResourcePackDirectory().resolve("diorama-broken-fog"));
		context.runOnClient(mc -> {
			try {
				java.nio.file.Files.createDirectories(pack.resolve("assets/diorama/shaders/core"));
				int format = net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
				java.nio.file.Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\": {\"pack_format\": " + format + ", \"description\": \"Diorama test: a fog shader that doesn't compile\"}}");
				java.nio.file.Files.writeString(pack.resolve("assets/diorama/shaders/core/fog.fsh"), "#version 150\nvoid main() { this is not glsl }\n");
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			mc.getResourcePackRepository().reload();
			if (!mc.getResourcePackRepository().addPack(id)) {
				throw new AssertionError("The broken fog test pack didn't load");
			}
			mc.reloadResourcePacks();
		});
		context.waitTicks(60);
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 0.75, TileKey.SURFACE);
		settle(context, 60);
		context.takeScreenshot("broken-fog-01-no-fog");
		boolean kept = context.computeOnClient(mc -> mc.getResourcePackRepository().getSelectedIds().contains(id));
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			mc.getResourcePackRepository().removePack(id);
			mc.reloadResourcePacks();
		});
		context.waitTicks(60);
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 0.75, TileKey.SURFACE);
		settle(context, 60);
		context.takeScreenshot("broken-fog-02-fog-back");
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(pack)) {
				files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		if (!kept) {
			throw new AssertionError("A fog shader that doesn't compile took the player's resource packs away");
		}
	}
	*///?}

	/**
	 * The minimap with a screen open. An inventory hides it, so item lists from other mods (JEI) beside
	 * the inventory don't run into it. The pause menu keeps it, behind the menu.
	 */
	private static void screens(ClientGameTestContext context, TestSingleplayerContext world) {
		// Survival, so the inventory is the plain one other mods (JEI) draw beside.
		world.getServer().runCommand("gamemode survival @a");
		context.setScreen(() -> null);
		settle(context, 40);
		context.takeScreenshot("screens-01-none");
		context.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(Minecraft.getInstance().player));
		settle(context, 20);
		context.takeScreenshot("screens-02-inventory");
		context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
		settle(context, 20);
		context.takeScreenshot("screens-03-pause");
		context.setScreen(() -> null);
		world.getServer().runCommand("gamemode creative @a");
	}

	private static void overlays(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		// No leaf decay or growth below the scene: a chunk changing for its own reasons would
		// refresh its light and hide whether light changes alone reach the map.
		world.getServer().runCommand("gamerule random_tick_speed 0");
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		// The platform's west part starts 4 blocks before a chunk border.
		int x0 = Math.floorDiv(at.getX() + 40, 16) * 16 - 4;
		int z0 = at.getZ() - 8;
		int y = 150;
		world.getServer().runCommand("fill " + x0 + " " + y + " " + z0 + " " + (x0 + 23) + " " + y + " " + (z0 + 15) + " stone");
		world.getServer().runCommand("setblock " + (x0 + 12) + " " + (y + 1) + " " + (z0 + 8) + " torch");
		// A sealed room south of the platform, across the same chunk border, split by a wall in the
		// east chunk with a torch behind it. Opening the wall lights the west half, in the other
		// chunk, without any block there changing.
		int rz = z0 + 24;
		world.getServer().runCommand("fill " + x0 + " " + y + " " + rz + " " + (x0 + 10) + " " + (y + 3) + " " + (rz + 6) + " stone hollow");
		world.getServer().runCommand("fill " + (x0 + 6) + " " + (y + 1) + " " + (rz + 1) + " " + (x0 + 6) + " " + (y + 2) + " " + (rz + 5) + " stone");
		world.getServer().runCommand("setblock " + (x0 + 8) + " " + (y + 1) + " " + (rz + 3) + " torch");
		// A bottom slab at head height over a dark floor block: a zombie doesn't fit.
		world.getServer().runCommand("setblock " + (x0 + 1) + " " + (y + 2) + " " + (z0 + 4) + " stone_slab");
		// Leaves on the dark west edge: monsters can't spawn on them or under them.
		world.getServer().runCommand("fill " + (x0 + 1) + " " + (y + 1) + " " + (z0 + 11) + " " + (x0 + 3) + " " + (y + 1) + " " + (z0 + 13) + " oak_leaves[persistent=true]");
		// A sealed room (5x2x5 inside) on the platform's south-east corner: its floor gets no sky light.
		int bx = x0 + 17;
		int bz = z0 + 9;
		world.getServer().runCommand("fill " + bx + " " + (y + 1) + " " + bz + " " + (bx + 6) + " " + (y + 4) + " " + (bz + 6) + " stone hollow");
		Settings saved = context.computeOnClient(mc -> {
			Settings s = Settings.get();
			Settings before = new Settings();
			before.chunkGrid = s.chunkGrid;
			before.slimeChunks = s.slimeChunks;
			before.spawnSpots = s.spawnSpots;
			s.chunkGrid = true;
			s.slimeChunks = true;
			s.spawnSpots = Settings.SpawnSpots.AT_NIGHT_TOO;
			return before;
		});
		try {
			context.setScreen(MapScreen::new);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x0 + 12, Projection.v(y + 1, z0 + 8), 4.0, TileKey.SURFACE));
			settle(context, 60);
			context.takeScreenshot("overlays-01-platform");

			int farX = x0 + 1;
			int farZ = z0 + 1;
			assertOverlay(context, TileKey.SURFACE, farX + 0.5, y + 1, farZ + 0.6, Overlays.SPAWN_AT_NIGHT, true, "spawn spot far from the torch");
			assertOverlay(context, TileKey.SURFACE, x0 + 13.5, y + 1, z0 + 8.6, Overlays.SPAWN_AT_NIGHT | Overlays.SPAWN_ANY_TIME, false, "spawn spot next to the torch");
			assertOverlay(context, TileKey.SURFACE, x0 + 2.5, y + 2, z0 + 12.6, Overlays.SPAWN_AT_NIGHT | Overlays.SPAWN_ANY_TIME, false, "spawn spot on leaves");
			assertOverlay(context, TileKey.SURFACE, x0 + 1.5, y + 1, z0 + 4.6, Overlays.SPAWN_AT_NIGHT | Overlays.SPAWN_ANY_TIME, false, "spawn spot under a slab at head height");
			// The chunk border is at x0 + 4: a line at its west edge, none mid-block.
			assertOverlay(context, TileKey.SURFACE, x0 + 4.1, y + 1, z0 + 2.5, Overlays.CHUNK_WEST, true, "chunk line on the border");
			assertOverlay(context, TileKey.SURFACE, x0 + 4.6, y + 1, z0 + 2.5, Overlays.CHUNK_WEST, false, "chunk line mid-block");
			long seed = context.computeOnClient(mc -> MapSession.get().slimeSeed());
			// The well-known slime chunk formula, written out independently of the mod's.
			int slimes = 0;
			for (int cx = -10; cx < 10; cx++) {
				for (int cz = -10; cz < 10; cz++) {
					boolean expected = legacySlimeChunk(seed, cx, cz);
					if (Overlays.slimeChunk(seed, cx, cz) != expected) {
						throw new AssertionError("Slime chunk " + cx + "," + cz + " doesn't match the known formula");
					}
					slimes += expected ? 1 : 0;
				}
			}
			if (slimes < 10 || slimes > 80) {
				throw new AssertionError("Slime chunks are about a tenth of all chunks, found " + slimes + " of 400");
			}
			// Where the formula's int arithmetic overflows, out to the world border.
			for (int c : new int[] {21, -21, 46341, -46341, 1874999, -1874999}) {
				for (int d : new int[] {0, 7, -46341, 1874999}) {
					if (Overlays.slimeChunk(seed, c, d) != legacySlimeChunk(seed, c, d) || Overlays.slimeChunk(seed, d, c) != legacySlimeChunk(seed, d, c)) {
						throw new AssertionError("Slime chunk " + c + "," + d + " doesn't match the known formula");
					}
				}
			}
			for (int x : new int[] {x0 + 1, x0 + 8}) {
				boolean slime = legacySlimeChunk(seed, x >> 4, (z0 + 2) >> 4);
				assertOverlay(context, TileKey.SURFACE, x + 0.5, y + 1, z0 + 2.5, Overlays.SLIME, slime, "slime chunk at x=" + x);
			}

			// A torch placed now clears the dots as far as its light reaches. It goes 12 blocks
			// east: the client lights it itself, and the spot is past the cells a block change
			// re-bakes for how it looks, so only the light refresh can clear it.
			world.getServer().runCommand("setblock " + (farX + 12) + " " + (y + 1) + " " + farZ + " torch");
			settle(context, 60);
			assertOverlay(context, TileKey.SURFACE, farX + 0.5, y + 1, farZ + 0.6, Overlays.SPAWN_AT_NIGHT | Overlays.SPAWN_ANY_TIME, false, "spawn spot after placing a torch");

			// A cave slice just under the room's roof shows its floor (the box's bottom layer, so
			// its top face is at y + 2): dark any time.
			int layer = y + 4;
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(bx + 3, Projection.v(y + 1, bz + 3), 4.0, layer));
			settle(context, 60);
			context.takeScreenshot("overlays-02-closed-room");
			assertOverlay(context, layer, bx + 3.5, y + 2, bz + 3.6, Overlays.SPAWN_ANY_TIME, true, "spawn spot in the closed room");

			// The split room: dark west of the wall until the wall opens.
			int splitLayer = y + 3;
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x0 + 5, Projection.v(y + 1, rz + 3), 4.0, splitLayer));
			settle(context, 60);
			assertOverlay(context, splitLayer, x0 + 2.5, y + 1, rz + 3.6, Overlays.SPAWN_ANY_TIME, true, "spawn spot behind the wall");
			world.getServer().runCommand("setblock " + (x0 + 6) + " " + (y + 1) + " " + (rz + 3) + " air");
			settle(context, 60);
			context.takeScreenshot("overlays-02b-wall-opened");
			assertOverlay(context, splitLayer, x0 + 2.5, y + 1, rz + 3.6, Overlays.SPAWN_ANY_TIME | Overlays.SPAWN_AT_NIGHT, false, "spawn spot once the torch shines through");

			// Spawn spots off: the tiles bake again without them, and only the grid and slime chunks show.
			context.runOnClient(mc -> Settings.get().spawnSpots = Settings.SpawnSpots.OFF);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x0 + 12, Projection.v(y + 1, z0 + 8), 4.0, TileKey.SURFACE));
			settle(context, 20);
			context.takeScreenshot("overlays-03-grid-and-slime-only");

			// What spawn spots are for: a natural cave, with only the spots that are dark by day.
			context.runOnClient(mc -> Settings.get().spawnSpots = Settings.SpawnSpots.ALWAYS_DARK);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(at.getX(), Projection.v(30, at.getZ()), 2.5, 32));
			settle(context, 80);
			context.takeScreenshot("overlays-04-cave-always-dark");
			context.setScreen(() -> null);
		} finally {
			world.getServer().runCommand("gamerule random_tick_speed 3");
			context.runOnClient(mc -> {
				Settings s = Settings.get();
				s.chunkGrid = saved.chunkGrid;
				s.slimeChunks = saved.slimeChunks;
				s.spawnSpots = saved.spawnSpots;
			});
		}
	}

	private static boolean legacySlimeChunk(long seed, int x, int z) {
		java.util.Random random = new java.util.Random(seed + (long) (x * x * 0x4c1906) + (long) (x * 0x5ac0db) + (long) (z * z) * 0x4307a7L + (long) (z * 0x5f24f) ^ 0x3ad8025fL);
		return random.nextInt(10) == 0;
	}

	/** Checks the overlay bits at a world point on the map (its top face, for a block). */
	private static void assertOverlay(ClientGameTestContext context, int layer, double x, double y, double z, int bits, boolean expected, String what) {
		int found = context.computeOnClient(mc -> {
			double u = x;
			double v = Projection.v(y, z);
			int tu = (int) Math.floor(u / Projection.TILE_UNITS);
			int tv = (int) Math.floor(v / Projection.TILE_UNITS);
			MapTile tile = MapSession.get().map().peek(new TileKey(layer, tu, tv));
			if (tile == null) {
				return -2;
			}
			int px = (int) ((u - tu * Projection.TILE_UNITS) * Projection.PIXELS_PER_UNIT);
			int py = (int) ((v - tv * Projection.TILE_UNITS) * Projection.PIXELS_PER_UNIT);
			return tile.overlayBits(px, py);
		});
		if (found < 0) {
			throw new AssertionError(what + ": " + (found == -2 ? "no tile there" : "the tile has no overlay bits"));
		}
		if (((found & bits) != 0) != expected) {
			throw new AssertionError(what + ": expected " + (expected ? "" : "no ") + "bits " + bits + ", found " + found);
		}
	}

	private static void settings(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		context.waitTicks(20);
		context.takeScreenshot("settings-01-default-minimap");

		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("settings-02-screen");
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.BOTTOM_LEFT;
			s.minimapSize = Settings.Size.LARGE;
			s.minimapZoom = 1;
			s.save();
		});
		settle(context, 20);
		context.takeScreenshot("settings-03-bottom-left-large");
		String saved = context.computeOnClient(mc -> {
			try {
				return java.nio.file.Files.readString(mc.gameDirectory.toPath().resolve("diorama").resolve("config.json"));
			} catch (java.io.IOException e) {
				throw new AssertionError("config.json is missing", e);
			}
		});
		if (!saved.contains("\"BOTTOM_LEFT\"") || !saved.contains("\"LARGE\"")) {
			throw new AssertionError("config.json doesn't have the new settings:\n" + saved);
		}

		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.TOP_RIGHT;
			s.minimapSize = Settings.Size.MEDIUM;
			s.minimapZoom = 3;
			s.save();
		});
		world.getServer().runCommand("effect give @a speed 60 0");
		world.getServer().runCommand("effect give @a weakness 60 0");
		context.waitTicks(20);
		context.takeScreenshot("settings-04-below-effects");
		world.getServer().runCommand("effect clear @a");

		// Hand-edited numbers out of range snap to valid ones, and the screen still opens.
		context.runOnClient(mc -> {
			java.nio.file.Path file = mc.gameDirectory.toPath().resolve("diorama").resolve("config.json");
			try {
				java.nio.file.Files.writeString(file, "{\"minimapZoom\": -1, \"radarRange\": -1}");
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			Settings s = Settings.reload();
			if (s.minimapZoom != 0 || s.radarRange != Settings.RANGES[0]) {
				throw new AssertionError("Bad numbers weren't fixed on load: zoom " + s.minimapZoom + ", range " + s.radarRange);
			}
			s.minimapZoom = 3;
			s.radarRange = 32;
			s.save();
		});
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(3);
		context.setScreen(() -> null);

		// A small window: 1280x720 is a 427x240 GUI. No settings button may overlap another, and a
		// bottom-corner minimap must stay clear of the hotbar.
		context.getInput().resizeWindow(1280, 720);
		context.waitTicks(10);
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("settings-05-small-screen");
		context.runOnClient(mc -> {
			java.util.List<net.minecraft.client.gui.components.AbstractWidget> widgets = new java.util.ArrayList<>();
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
					widgets.add(widget);
				}
			}
			int width = mc.gui.screen().width;
			int height = mc.gui.screen().height;
			for (var a : widgets) {
				if (a.getX() < 0 || a.getY() < 0 || a.getRight() > width || a.getBottom() > height) {
					throw new AssertionError(a.getMessage().getString() + " runs off the screen");
				}
				for (var b : widgets) {
					if (a != b && a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
						throw new AssertionError(a.getMessage().getString() + " overlaps " + b.getMessage().getString());
					}
				}
			}
		});
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.BOTTOM_LEFT;
			s.minimapSize = Settings.Size.LARGE;
			s.save();
		});
		context.waitTicks(10);
		context.takeScreenshot("settings-06-small-bottom-left");
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.TOP_RIGHT;
			s.minimapSize = Settings.Size.MEDIUM;
			s.save();
		});
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(10);

		// Rounded fog only reshapes the fog edge, so switching it keeps the baked map instead of
		// blanking it and baking every tile again.
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 1.0, TileKey.SURFACE);
		settle(context, 60);
		MapTile before = context.computeOnClient(mc -> playerTile());
		if (before == null) {
			throw new AssertionError("No baked tile under the player to check against");
		}
		context.runOnClient(mc -> {
			Settings.get().roundedFog = false;
			Settings.get().save();
		});
		context.waitTicks(5);
		context.takeScreenshot("settings-07-sharp-fog");
		boolean kept = context.computeOnClient(mc -> playerTile() == before);
		boolean applied = context.computeOnClient(mc -> !MapStyle.get().roundedFog());
		context.runOnClient(mc -> {
			Settings.get().roundedFog = true;
			Settings.get().save();
		});
		context.setScreen(() -> null);
		if (!kept) {
			throw new AssertionError("Switching rounded fog threw away the baked map");
		}
		if (!applied) {
			throw new AssertionError("Switching rounded fog didn't reach the map's fog");
		}
	}

	private static @Nullable MapTile playerTile() {
		LocalPlayer player = Minecraft.getInstance().player;
		int tu = (int) Math.floor(player.getX() / Projection.TILE_UNITS);
		int tv = (int) Math.floor(Projection.v(player.getY(), player.getZ()) / Projection.TILE_UNITS);
		return MapSession.get().map().peek(new TileKey(TileKey.SURFACE, tu, tv));
	}

	/** Which scenarios to run: DIORAMA_TESTS=tour,radar,... (default: all but styles). */
	private static Set<String> selectedTests() {
		String env = System.getenv("DIORAMA_TESTS");
		if ("1".equals(System.getenv("DIORAMA_STYLES"))) {
			return Set.of("styles");
		}
		if (env == null || env.isBlank()) {
			//? if >=1.21.4 && fabric {
			return Set.of("tour", "radar", "waypoints", "xaero-import", "fog-of-war", "settings", "overlays", "screens", "dimensions", "saved-map", "multiplayer", "server-id", "tracked-players");
			//?} else {
			/*// On NeoForge and before 1.21.4 the tests run on src/gameshim, which can't start a dedicated server.
			return Set.of("tour", "radar", "waypoints", "xaero-import", "fog-of-war", "settings", "overlays", "screens", "dimensions", "saved-map");
			*///?}
		}
		return Set.of(env.split(","));
	}

	private static void assertSeen(ClientGameTestContext context, int x, int y, int z, boolean expected, String what) {
		boolean seen = context.computeOnClient(mc -> MapSession.get().map().isSeen(x, y, z));
		if (seen != expected) {
			throw new AssertionError("Expected the " + what + " at " + x + "," + y + "," + z + (expected ? " to be seen" : " to stay hidden"));
		}
	}

	/** Opens the map on the test scene's cave slice. */
	private static void showScene(ClientGameTestContext context) {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(115, Projection.v(-40, 110), 3.5, MapSession.caveLayerFor(-40));
			}
		});
		settle(context, 60);
	}

	/**
	 * Renders the same village under every look in {@link MapStyle#tour()}, close up and zoomed
	 * out, so they can be compared side by side.
	 */
	private static void styleTour(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos village = world.getServer().computeOnServer(server ->
			server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
		if (village == null) {
			throw new AssertionError("No village near spawn");
		}
		int surface = world.getServer().computeOnServer(server ->
			server.overworld().getChunk(village).getHeight(Heightmap.Types.MOTION_BLOCKING, village.getX() & 15, village.getZ() & 15));
		world.getServer().runCommand("tp @a " + village.getX() + " " + (surface + 1) + " " + village.getZ());
		context.waitTicks(200);
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		settle(context, 100);
		for (MapStyle style : MapStyle.tour()) {
			context.runOnClient(mc -> MapStyle.set(style));
			String name = style.name().toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
			context.setScreen(MapScreen::new);
			lookAtPlayer(context, 3.0, TileKey.SURFACE);
			settle(context, 60);
			context.takeScreenshot("style-" + name + "-near");
			lookAtPlayer(context, 0.8, TileKey.SURFACE);
			settle(context, 80);
			context.takeScreenshot("style-" + name + "-far");
			context.setScreen(() -> null);
		}
		context.runOnClient(mc -> MapStyle.set(Settings.get().style()));
	}

	private static void setQuality(ClientGameTestContext context, Settings.Quality quality) {
		context.runOnClient(mc -> {
			Settings.get().quality = quality;
			Settings.get().apply();
		});
	}

	private static void lookAtPlayer(ClientGameTestContext context, double zoom, int layer) {
		context.runOnClient(mc -> {
			LocalPlayer player = mc.player;
			if (mc.gui.screen() instanceof MapScreen screen && player != null) {
				screen.lookAt(player.getX(), Projection.v(player.getY(), player.getZ()), zoom, layer);
			}
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
