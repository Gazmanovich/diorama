package dev.diorama.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.diorama.Diorama;
import dev.diorama.WorldKey;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The waypoints of one world, saved as JSON in {@code .minecraft/diorama/<world>/waypoints.json}.
 * Client thread only.
 */
public final class WaypointStore {
	/** How many death markers to keep; older ones are dropped as new deaths come in. */
	public static final int DEATHS_KEPT = 3;

	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.registerTypeAdapter(UUID.class, new TypeAdapter<UUID>() {
			@Override
			public void write(JsonWriter out, UUID value) throws IOException {
				out.value(value.toString());
			}

			@Override
			public UUID read(JsonReader in) throws IOException {
				return UUID.fromString(in.nextString());
			}
		})
		.create();

	private static @Nullable WaypointStore current;

	private final String worldKey;
	private final Path file;
	private final List<Waypoint> waypoints = new ArrayList<>();

	private WaypointStore(String worldKey) {
		this.worldKey = worldKey;
		this.file = WorldKey.folder(worldKey).resolve("waypoints.json");
		this.load();
	}

	/** The store for the world the client is in, loading it when the world changes. */
	public static @Nullable WaypointStore get() {
		String key = WorldKey.current();
		if (key == null) {
			current = null;
			return null;
		}
		if (current == null || !current.worldKey.equals(key)) {
			current = new WaypointStore(key);
		}
		return current;
	}

	/** Opens the store saved under a world key, outside the usual current-world lookup. For tests and tools. */
	public static WaypointStore open(String worldKey) {
		return new WaypointStore(worldKey);
	}

	public List<Waypoint> all() {
		return this.waypoints;
	}

	public List<Waypoint> in(String dimension) {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint waypoint : this.waypoints) {
			if (waypoint.dimension.equals(dimension)) {
				out.add(waypoint);
			}
		}
		return out;
	}

	public void add(Waypoint waypoint) {
		this.waypoints.add(waypoint);
		if (waypoint.kind == Waypoint.Kind.DEATH) {
			List<Waypoint> deaths = new ArrayList<>(this.waypoints.stream().filter(w -> w.kind == Waypoint.Kind.DEATH).toList());
			deaths.sort(Comparator.comparingLong(w -> w.created));
			for (int i = 0; i < deaths.size() - DEATHS_KEPT; i++) {
				this.waypoints.remove(deaths.get(i));
			}
		}
		this.save();
	}

	/**
	 * Adds several waypoints with one save, for an import. Death markers among them don't push
	 * older ones out; the next death trims them as usual.
	 */
	public void addAll(List<Waypoint> added) {
		if (!added.isEmpty()) {
			this.waypoints.addAll(added);
			this.save();
		}
	}

	public void remove(Waypoint waypoint) {
		this.waypoints.remove(waypoint);
		this.save();
	}

	/** Call after changing a waypoint in place. */
	public void changed() {
		this.save();
	}

	/**
	 * Reads the file one entry at a time, so a single bad entry costs only itself. If anything
	 * couldn't be read, the file is copied aside first: the next save rewrites it, and the copy
	 * keeps whatever was lost.
	 */
	private void load() {
		if (!Files.isRegularFile(this.file)) {
			return;
		}
		boolean damaged = false;
		try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonArray()) {
				damaged = true;
			} else {
				for (JsonElement element : root.getAsJsonArray()) {
					try {
						Waypoint waypoint = normalise(GSON.fromJson(element, Waypoint.class));
						if (waypoint != null) {
							this.waypoints.add(waypoint);
						} else {
							damaged = true;
						}
					} catch (RuntimeException e) {
						damaged = true;
						Diorama.LOGGER.warn("Skipping a waypoint in {} that couldn't be read: {}", this.file, element, e);
					}
				}
			}
		} catch (Exception e) {
			damaged = true;
			Diorama.LOGGER.error("Couldn't read waypoints from {}", this.file, e);
		}
		if (damaged) {
			Path backup = this.file.resolveSibling("waypoints.json.unreadable-" + System.currentTimeMillis());
			try {
				Files.copy(this.file, backup);
				Diorama.LOGGER.warn("Kept a copy of the unreadable waypoints file at {}", backup);
			} catch (IOException e) {
				Diorama.LOGGER.error("Couldn't back up {}", this.file, e);
			}
		}
	}

	/** Fills in anything a hand-edited or older file left out; null if the entry is unusable. */
	private static @Nullable Waypoint normalise(@Nullable Waypoint waypoint) {
		if (waypoint == null || waypoint.dimension == null) {
			return null;
		}
		if (waypoint.id == null) {
			waypoint.id = UUID.randomUUID();
		}
		if (waypoint.name == null || waypoint.name.isBlank()) {
			waypoint.name = "Waypoint";
		}
		if (waypoint.initials == null) {
			waypoint.initials = "";
		}
		if (waypoint.kind == null) {
			waypoint.kind = Waypoint.Kind.MANUAL;
		}
		return waypoint;
	}

	/** Writes to a temporary file and moves it over the old one, so a crash can't leave half a file. */
	private void save() {
		try {
			Files.createDirectories(this.file.getParent());
			Path temp = this.file.resolveSibling("waypoints.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(this.waypoints, writer);
			}
			Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			// Gson wraps writer failures (a full disk, say) in unchecked exceptions.
			Diorama.LOGGER.error("Couldn't save waypoints to {}", this.file, e);
		}
	}
}
