package dev.diorama.drawing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.diorama.Diorama;
import dev.diorama.Maps;
import dev.diorama.WorldKey;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The drawings of one world, saved as JSON in {@code .minecraft/diorama/<world>/drawings.json},
 * beside its waypoints. Like waypoints, each was drawn on one of its dimension's maps
 * ({@link Maps}), and only those of the maps in use are listed; the rest are kept aside and saved
 * with them. Every change is saved straight away. Client thread only.
 */
public final class DrawingStore {
	private static final Gson GSON = new GsonBuilder()
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

	private static @Nullable DrawingStore current;

	private final String worldKey;
	private final Path file;
	/** The drawings of the maps in use, in drawing order. */
	private final List<Drawing> drawings = new ArrayList<>();
	/** The drawings of the dimensions' other maps, in drawing order, saved with the rest but not shown. */
	private final List<Drawing> elsewhere = new ArrayList<>();
	/** Inside {@link #batch}: changes wait to be saved together. */
	private boolean batching;

	private DrawingStore(String worldKey) {
		this.worldKey = worldKey;
		this.file = WorldKey.folder(worldKey).resolve("drawings.json");
		this.load();
	}

	/**
	 * The store for the world the client is in, loading it when the world changes; null while the
	 * world isn't known.
	 */
	public static @Nullable DrawingStore get() {
		String key = WorldKey.current();
		if (key == null) {
			return null;
		}
		if (current == null || !current.worldKey.equals(key)) {
			current = new DrawingStore(key);
		}
		return current;
	}

	/**
	 * Called on leaving a world. The drawings are all on disk, but which ones show depends on the
	 * world's maps in use, so the next visit reads them again.
	 */
	public static void onLeave() {
		current = null;
	}

	/** Opens the store saved under a world key, outside the usual current-world lookup. For tests. */
	public static DrawingStore open(String worldKey) {
		return new DrawingStore(worldKey);
	}

	/** A dimension switched to another of its maps: its drawings become that map's. */
	public static void mapChanged(String dimension) {
		DrawingStore store = current;
		if (store != null) {
			store.split(dimension);
		}
	}

	/** A map was deleted: the drawings made on it go too. */
	public static void mapDeleted(String worldKey, String dimension, String map) {
		DrawingStore store = current != null && current.worldKey.equals(worldKey) ? current : new DrawingStore(worldKey);
		if (store.elsewhere.removeIf(d -> d.dimension.equals(dimension) && Maps.id(d.map).equals(map))) {
			store.save();
		}
	}

	/**
	 * Moves the dimension's drawings between the shown and kept-aside lists, each list keeping the
	 * order they were drawn in, which is the order they draw in.
	 */
	private void split(String dimension) {
		String active = Maps.of(this.worldKey).active(dimension);
		List<Drawing> leaving = this.drawings.stream().filter(d -> d.dimension.equals(dimension) && !Maps.id(d.map).equals(active)).toList();
		List<Drawing> coming = this.elsewhere.stream().filter(d -> d.dimension.equals(dimension) && Maps.id(d.map).equals(active)).toList();
		this.drawings.removeAll(leaving);
		this.elsewhere.removeAll(coming);
		this.elsewhere.addAll(leaving);
		this.drawings.addAll(coming);
	}

	/** The drawings in a dimension, oldest first, which is the order they draw in. */
	public List<Drawing> in(String dimension) {
		List<Drawing> out = new ArrayList<>();
		for (Drawing drawing : this.drawings) {
			if (drawing.dimension.equals(dimension)) {
				out.add(drawing);
			}
		}
		return out;
	}

	public List<Drawing> all() {
		return this.drawings;
	}

	/** Adds a drawing to the map in use in its dimension. */
	public void add(Drawing drawing) {
		drawing.map = Maps.noted(Maps.of(this.worldKey).active(drawing.dimension));
		this.drawings.add(drawing);
		this.save();
	}

	/** Puts a removed drawing back where it was in the order, so it draws under the same things again. */
	public void restore(Drawing drawing, int index) {
		if (!Maps.id(drawing.map).equals(Maps.of(this.worldKey).active(drawing.dimension))) {
			// Undone after switching maps: it goes back to the map it was drawn on.
			this.elsewhere.add(drawing);
			this.save();
			return;
		}
		this.drawings.add(Math.max(0, Math.min(index, this.drawings.size())), drawing);
		this.save();
	}

	/** Removes a drawing; returns where it was in the order, or -1 if it wasn't here. */
	public int remove(Drawing drawing) {
		int index = this.drawings.indexOf(drawing);
		if (index >= 0) {
			this.drawings.remove(index);
			this.save();
		}
		return index;
	}

	/** Makes several changes and saves once, after them all. */
	public void batch(Runnable changes) {
		this.batching = true;
		try {
			changes.run();
		} finally {
			this.batching = false;
			this.save();
		}
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
						Drawing drawing = GSON.fromJson(element, Drawing.class);
						if (valid(drawing)) {
							if (drawing.map != null && drawing.map.isEmpty()) {
								drawing.map = null;
							}
							(Maps.id(drawing.map).equals(Maps.of(this.worldKey).active(drawing.dimension)) ? this.drawings : this.elsewhere).add(drawing);
						} else {
							damaged = true;
						}
					} catch (RuntimeException e) {
						damaged = true;
						Diorama.LOGGER.warn("Skipping a drawing in {} that couldn't be read: {}", this.file, element, e);
					}
				}
			}
		} catch (Exception e) {
			damaged = true;
			Diorama.LOGGER.error("Couldn't read drawings from {}", this.file, e);
		}
		if (damaged) {
			Path backup = this.file.resolveSibling("drawings.json.unreadable-" + System.currentTimeMillis());
			try {
				Files.copy(this.file, backup);
				Diorama.LOGGER.warn("Kept a copy of the unreadable drawings file at {}", backup);
			} catch (IOException e) {
				Diorama.LOGGER.error("Couldn't back up {}", this.file, e);
			}
		}
	}

	private static boolean valid(@Nullable Drawing drawing) {
		if (drawing == null || drawing.id == null || drawing.kind == null || drawing.dimension == null || drawing.points == null || drawing.points.length % 2 != 0) {
			return false;
		}
		if (drawing.text == null) {
			drawing.text = "";
		}
		for (double value : drawing.points) {
			if (!Double.isFinite(value)) {
				return false;
			}
		}
		return drawing.complete();
	}

	private void save() {
		if (this.batching) {
			return;
		}
		try {
			Files.createDirectories(this.file.getParent());
			Path temp = this.file.resolveSibling("drawings.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				List<Drawing> all = new ArrayList<>(this.drawings);
				all.addAll(this.elsewhere);
				GSON.toJson(all, writer);
			}
			Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			// Gson wraps writer failures (a full disk, say) in unchecked exceptions.
			Diorama.LOGGER.error("Couldn't save drawings to {}", this.file, e);
		}
	}
}
