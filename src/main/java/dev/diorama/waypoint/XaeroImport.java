package dev.diorama.waypoint;

import dev.diorama.Diorama;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Brings a player's waypoints over from Xaero's Minimap. Xaero's keeps them as text, one folder
 * per world and one per dimension inside it:
 * {@code .minecraft/xaero/minimap/<world>/<dimension>/<multiworld>.txt}, with a line per waypoint:
 * {@code waypoint:name:initials:x:y:z:colour:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination}.
 * A singleplayer world's folder is its save folder and its file is {@code waypoints.txt}. A
 * server's folder is {@code Multiplayer_<address>}, and since one address can lead to several
 * worlds, Xaero's keeps a file for each it told apart: {@code mw<spawn>_<name>.txt} on plain
 * servers, {@code mw$<id>_<name>.txt} when the server or Xaero's World Map names the world.
 * Read only: nothing here writes to Xaero's files.
 */
public final class XaeroImport {
	/** Xaero's colour index (its colour list starts with the 16 chat colours) to the dye that index stands for. */
	private static final DyeColor[] EXTRA_COLORS = {DyeColor.MAGENTA, DyeColor.LIGHT_BLUE, DyeColor.LIME, DyeColor.PINK, DyeColor.BROWN};
	/** Names Xaero's gives its own waypoints, stored as translation keys, in English for when Xaero's isn't installed. */
	private static final Map<String, String> XAERO_NAMES = Map.of(
		"gui.xaero_deathpoint", "Latest Death",
		"gui.xaero_deathpoint_old", "Old Death"
	);
	private static final int MAX_NAME = 32;
	private static final int MAX_INITIALS = 2;
	private static final int MAX_HORIZONTAL = 30_000_000;
	private static final int MAX_VERTICAL = 4096;

	/**
	 * A waypoint read from Xaero's. {@code heightKnown} is false when Xaero's didn't store a
	 * height ({@code ~}); the waypoint then takes the height it was imported at.
	 */
	public record Entry(Waypoint waypoint, boolean heightKnown) {
	}

	/**
	 * One of Xaero's worlds for this world folder or server: {@code id} is the file name's first
	 * part ({@code waypoints}, {@code mw$default}, {@code mw2,1,-3}), {@code name} what Xaero's
	 * shows for it, if it has one.
	 */
	public record World(String id, @Nullable String name, List<Entry> entries, int destinationsSkipped) {
		public String label() {
			return this.name != null && !this.name.isBlank() ? this.name : this.id;
		}
	}

	/**
	 * What Xaero's has for the world the client is in. {@code chosen} is the world Xaero's itself
	 * would open, or null when that can't be told and the player has to pick from {@code worlds}.
	 */
	public record Found(List<World> worlds, @Nullable World chosen) {
	}

	private XaeroImport() {
	}

	/** Xaero's waypoints for the world the client is in, or null if Xaero's has none. Reads files: call when the player asks. */
	public static @Nullable Found find() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		Path base = mc.gameDirectory.toPath().resolve("xaero").resolve("minimap");
		if (!Files.isDirectory(base)) {
			// Before 2021 or so Xaero's kept the same folders here; it moves them on its next start.
			base = mc.gameDirectory.toPath().resolve("XaeroWaypoints");
		}
		IntegratedServer server = mc.getSingleplayerServer();
		ServerData data = mc.getCurrentServer();
		List<String> roots;
		if (server != null) {
			roots = singleplayerFolders(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
		} else if (data != null && !data.isRealm()) {
			roots = serverFolders(data.ip);
		} else {
			// Xaero's names a Realm by its owner and id, which only the Realms screen knows.
			return null;
		}
		BlockPos spawn = mc.level.getRespawnData().pos();
		String spawnId = "mw" + (spawn.getX() >> 6) + "," + (spawn.getY() >> 6) + "," + (spawn.getZ() >> 6);
		int height = mc.player != null ? mc.player.getBlockY() : 64;
		try {
			return find(base, roots, server != null, spawnId, height);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read Xaero's Minimap waypoints from {}", base, e);
			return null;
		}
	}

	/**
	 * Xaero's worlds in the first of {@code roots} that exists under {@code base}, and which one
	 * Xaero's would open. {@code height} stands in for heights Xaero's didn't store.
	 */
	static @Nullable Found find(Path base, List<String> roots, boolean singleplayer, String spawnId, int height) throws IOException {
		Path root = existing(base, roots);
		if (root == null) {
			return null;
		}
		Map<String, World> worlds = new LinkedHashMap<>();
		try (Stream<Path> dimensions = Files.list(root)) {
			for (Path folder : dimensions.filter(Files::isDirectory).sorted().toList()) {
				String dimension = dimension(folder.getFileName().toString());
				if (dimension == null) {
					continue;
				}
				try (Stream<Path> files = Files.list(folder)) {
					for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
						String fileName = file.getFileName().toString();
						if (!fileName.endsWith(".txt")) {
							continue;
						}
						String stem = fileName.substring(0, fileName.length() - ".txt".length());
						String id = stem;
						String name = null;
						if (!stem.equals("waypoints")) {
							// Xaero's own rule: other files are <id>_<name>, and it skips any without a name.
							String[] parts = stem.split("_");
							if (parts.length < 2) {
								continue;
							}
							id = parts[0];
							name = parts[1].replace("%us%", "_").replace("§§", ":");
						}
						List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
						World parsed = parse(lines, dimension, height);
						World previous = worlds.get(id);
						if (previous == null) {
							worlds.put(id, new World(id, name, parsed.entries(), parsed.destinationsSkipped()));
						} else {
							List<Entry> entries = new ArrayList<>(previous.entries());
							entries.addAll(parsed.entries());
							worlds.put(id, new World(id, previous.name() != null ? previous.name() : name, entries,
								previous.destinationsSkipped() + parsed.destinationsSkipped()));
						}
					}
				}
			}
		}
		// Decided before dropping empty worlds: if Xaero's own world for here is empty, another
		// world's waypoints aren't this one's.
		World chosen = chosen(worlds, singleplayer, root.resolve("config.txt"), spawnId);
		if (chosen != null) {
			return chosen.entries().isEmpty() ? null : new Found(List.of(chosen), chosen);
		}
		List<World> candidates = worlds.values().stream().filter(world -> !world.entries().isEmpty()).toList();
		return candidates.isEmpty() ? null : new Found(candidates, null);
	}

	/**
	 * The world Xaero's would open, following its own rules: {@code waypoints} in singleplayer;
	 * on a server, the only one there is, else the one its config pins (multiworld detection off,
	 * the default) or the one for the current spawn (detection on). Worlds named by the server or
	 * Xaero's World Map ({@code mw$...}) take precedence over both, and which of those applies
	 * isn't known from here, so with any of them in the mix the player picks.
	 */
	private static @Nullable World chosen(Map<String, World> worlds, boolean singleplayer, Path config, String spawnId) throws IOException {
		if (singleplayer) {
			return worlds.get("waypoints");
		}
		if (worlds.size() == 1) {
			return worlds.values().iterator().next();
		}
		if (worlds.keySet().stream().anyMatch(id -> id.startsWith("mw$")) || !Files.isRegularFile(config)) {
			return null;
		}
		boolean detection = false;
		String pinned = null;
		for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
			String[] parts = line.split(":");
			if (parts.length >= 2 && parts[0].equals("usingMultiworldDetection")) {
				detection = parts[1].equals("true");
			} else if (parts.length >= 2 && parts[0].equals("defaultMultiworldId")) {
				pinned = parts[1];
			}
		}
		return worlds.get(detection ? spawnId : pinned);
	}

	/** The first folder in {@code names} under {@code base}, matching case the way Xaero's does. */
	private static @Nullable Path existing(Path base, List<String> names) throws IOException {
		if (!Files.isDirectory(base)) {
			return null;
		}
		List<Path> folders;
		try (Stream<Path> list = Files.list(base)) {
			folders = list.filter(Files::isDirectory).toList();
		}
		for (String name : names) {
			for (Path folder : folders) {
				if (folder.getFileName().toString().equalsIgnoreCase(name)) {
					return folder;
				}
			}
		}
		return null;
	}

	/** Xaero's folder name for a save folder, newest naming first. */
	static List<String> singleplayerFolders(String saveFolder) {
		String older = saveFolder.replace("_", "%us%").replace("/", "%fs%").replace("\\", "%bs%");
		String newer = older.replace("[", "%lb%").replace("]", "%rb%");
		return newer.equals(older) ? List.of(newer) : List.of(newer, older);
	}

	/**
	 * Xaero's folder names for a server address, newest naming first (Xaero's renames older ones
	 * when it starts, but a player who stopped updating it has those), then the shared folder
	 * Xaero's uses when told not to tell servers apart by address.
	 */
	static List<String> serverFolders(String address) {
		List<String> names = new ArrayList<>();
		for (int version = 4; version >= 0; version--) {
			String name = serverFolder(address, version);
			if (!names.contains(name)) {
				names.add(name);
			}
		}
		names.add("Multiplayer_Any Address");
		return names;
	}

	/** Xaero's {@code MinimapWorldStateUpdater.getAutoRootContainerPath}, for each of its folder-name versions. */
	private static String serverFolder(String address, int version) {
		String ip = address;
		int port = version >= 1 && ip.indexOf(':') != ip.lastIndexOf(':') ? ip.lastIndexOf("]:") + 1 : ip.indexOf(':');
		if (port > 0) {
			ip = ip.substring(0, port);
		}
		while (ip.endsWith(".")) {
			ip = ip.substring(0, ip.length() - 1);
		}
		if (version >= 2) {
			ip = ip.replace("[", "").replace("]", "");
		}
		ip = ip.replace(":", version < 3 ? "§" : ".").replace("_", "%us%").replace("/", "%fs%").replace("\\", "%bs%");
		if (version >= 4) {
			ip = ip.trim();
			int end = ip.length();
			while (end > 0 && ip.charAt(end - 1) == '.') {
				end--;
			}
			ip = ip.substring(0, end) + ",".repeat(ip.length() - end);
		}
		return "Multiplayer_" + (ip.isEmpty() ? "Empty Address" : ip);
	}

	/** Our dimension id for one of Xaero's dimension folders, or null if it isn't one we can read. */
	static @Nullable String dimension(String folder) {
		return switch (folder) {
			case "dim%0", "Overworld" -> "minecraft:overworld";
			case "dim%-1", "Nether" -> "minecraft:the_nether";
			case "dim%1", "The End" -> "minecraft:the_end";
			// dim%namespace$path, with / as % and a trailing . as ,
			default -> folder.startsWith("dim%") && folder.contains("$") ? WaypointShare.ourDimension(folder.replace(',', '.')) : null;
		};
	}

	/**
	 * The waypoints in one of Xaero's files. Lines that aren't waypoints (sets, comments, the
	 * slime chunk seed) and ones that can't be read are skipped, as Xaero's does. One-off
	 * destinations are skipped too: Xaero's removes them once reached. (Its temporary waypoints
	 * never reach the file.)
	 */
	static World parse(List<String> lines, String dimension, int height) {
		List<Entry> entries = new ArrayList<>();
		int destinations = 0;
		for (String line : lines) {
			String[] args = line.split(":");
			if (args.length < 10 || !args[0].equalsIgnoreCase("waypoint")) {
				continue;
			}
			try {
				int x = Integer.parseInt(args[3]);
				boolean heightKnown = !args[4].equals("~");
				int y = heightKnown ? Integer.parseInt(args[4]) : height;
				int z = Integer.parseInt(args[5]);
				int color = Integer.parseInt(args[6]);
				int type = Integer.parseInt(args[8]);
				if (type == 3 || (args.length > 13 && args[13].equals("true"))) {
					destinations++;
					continue;
				}
				if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL || Math.abs(y) > MAX_VERTICAL) {
					continue;
				}
				String name = WaypointShare.cut(WaypointShare.chatSafe(name(args[1].replace("§§", ":"))).trim(), MAX_NAME).trim();
				Waypoint waypoint = new Waypoint(name.isEmpty() ? "Waypoint" : name, x, y, z, dimension, WaypointShare.nearestPaletteColor(color(color)));
				String initials = WaypointShare.cut(WaypointShare.chatSafe(args[2].replace("§§", ":")).trim(), MAX_INITIALS);
				if (!initials.isEmpty()) {
					waypoint.initials = initials;
				}
				waypoint.visible = !args[7].equals("true");
				if (type == 1 || type == 2) {
					waypoint.kind = Waypoint.Kind.DEATH;
				}
				entries.add(new Entry(waypoint, heightKnown));
			} catch (NumberFormatException e) {
				// Xaero's skips a line it can't read, and so do we.
			}
		}
		return new World("", null, entries, destinations);
	}

	/** Xaero's names its death points with translation keys; the name it shows for them. */
	private static String name(String stored) {
		String fallback = XAERO_NAMES.get(stored);
		return fallback == null ? stored : Language.getInstance().getOrDefault(stored, fallback);
	}

	/** The colour Xaero's draws for one of its colour indices. */
	static int color(int index) {
		int clamped = Math.max(0, Math.min(15 + EXTRA_COLORS.length, index));
		return clamped < 16 ? WaypointShare.chatColor(clamped) : EXTRA_COLORS[clamped - 16].getTextColor();
	}

	/**
	 * The waypoints of {@code world} that aren't in the store yet. A waypoint is already there if
	 * one has its name, dimension and position; for one Xaero's stored without a height, any
	 * height counts, since it took the player's height when it was imported.
	 */
	public static List<Waypoint> missing(World world, WaypointStore store) {
		List<Waypoint> out = new ArrayList<>();
		List<Waypoint> known = new ArrayList<>(store.all());
		for (Entry entry : world.entries()) {
			Waypoint in = entry.waypoint();
			boolean present = known.stream().anyMatch(w -> w.name.equals(in.name) && w.dimension.equals(in.dimension)
				&& w.x == in.x && w.z == in.z && (!entry.heightKnown() || w.y == in.y));
			if (!present) {
				out.add(in);
				known.add(in);
			}
		}
		return out;
	}

	/** Adds the waypoints of {@code world} that aren't in the store yet and returns them. */
	public static List<Waypoint> importInto(World world, WaypointStore store) {
		// Copies, so a later edit to an imported waypoint can't reach back into the read file.
		List<Waypoint> added = missing(world, store).stream().map(XaeroImport::copy).toList();
		store.addAll(added);
		Diorama.LOGGER.info("Imported {} waypoints from Xaero's Minimap world {}", added.size(), world.id());
		return added;
	}

	private static Waypoint copy(Waypoint from) {
		Waypoint to = new Waypoint(from.name, from.x, from.y, from.z, from.dimension, from.color);
		to.initials = from.initials;
		to.visible = from.visible;
		to.kind = from.kind;
		return to;
	}
}
