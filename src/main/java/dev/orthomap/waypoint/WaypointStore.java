package dev.orthomap.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.orthomap.Orthomap;
import dev.orthomap.WorldKey;
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
 * The waypoints of one world, saved as JSON in {@code .minecraft/orthomap/<world>/waypoints.json}.
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

	public void remove(Waypoint waypoint) {
		this.waypoints.remove(waypoint);
		this.save();
	}

	/** Call after changing a waypoint in place. */
	public void changed() {
		this.save();
	}

	private void load() {
		if (!Files.isRegularFile(this.file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			List<Waypoint> loaded = GSON.fromJson(reader, new TypeToken<List<Waypoint>>() {}.getType());
			if (loaded != null) {
				for (Waypoint waypoint : loaded) {
					if (waypoint != null && waypoint.id != null && waypoint.name != null && waypoint.dimension != null) {
						this.waypoints.add(waypoint);
					}
				}
			}
		} catch (Exception e) {
			Orthomap.LOGGER.error("Couldn't read waypoints from {}", this.file, e);
		}
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
		} catch (IOException e) {
			Orthomap.LOGGER.error("Couldn't save waypoints to {}", this.file, e);
		}
	}
}
