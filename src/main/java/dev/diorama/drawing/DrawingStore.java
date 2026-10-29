package dev.diorama.drawing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
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
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The drawings of one world, saved as JSON in {@code .minecraft/diorama/<world>/drawings.json},
 * beside its waypoints. Every change is saved straight away. Client thread only.
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
	private final List<Drawing> drawings = new ArrayList<>();
	/** Inside {@link #batch}: changes wait to be saved together. */
	private boolean batching;

	private DrawingStore(String worldKey) {
		this.worldKey = worldKey;
		this.file = WorldKey.folder(worldKey).resolve("drawings.json");
		this.load();
	}

	/**
	 * The store for the world the client is in, loading it when the world changes; null while the
	 * world isn't known. Nothing lives only in memory, so leaving a world needs no clean-up.
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

	/** Opens the store saved under a world key, outside the usual current-world lookup. For tests. */
	public static DrawingStore open(String worldKey) {
		return new DrawingStore(worldKey);
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

	public void add(Drawing drawing) {
		this.drawings.add(drawing);
		this.save();
	}

	/** Puts a removed drawing back where it was in the order, so it draws under the same things again. */
	public void restore(Drawing drawing, int index) {
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
							this.drawings.add(drawing);
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
				GSON.toJson(this.drawings, writer);
			}
			Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			// Gson wraps writer failures (a full disk, say) in unchecked exceptions.
			Diorama.LOGGER.error("Couldn't save drawings to {}", this.file, e);
		}
	}
}
