package dev.diorama;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.diorama.bake.TileBaker;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;

/**
 * Player settings, saved to {@code .minecraft/diorama/config.json}. Fields are public so Gson
 * can read and write them; call {@link #save()} after changing one.
 */
public final class Settings {
	public enum Corner {
		TOP_RIGHT,
		TOP_LEFT,
		BOTTOM_RIGHT,
		BOTTOM_LEFT
	}

	public enum Size {
		SMALL(96),
		MEDIUM(128),
		LARGE(176);

		public final int pixels;

		Size(int pixels) {
			this.pixels = pixels;
		}
	}

	/** Minimap zoom steps, in GUI pixels per block. */
	public static final double[] ZOOMS = {0.75, 1, 1.5, 2, 3, 4};
	/** Radar height ranges, in blocks. */
	public static final int[] RANGES = {16, 32, 64, 128};

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Settings current;

	public boolean minimap = true;
	public Size minimapSize = Size.MEDIUM;
	public Corner minimapCorner = Corner.TOP_RIGHT;
	public int minimapZoom = 3;
	public boolean compass = true;
	public boolean coordinates = true;
	public boolean biome = true;
	public boolean autoCaves = true;

	public boolean radarPlayers = true;
	public boolean radarHostile = true;
	public boolean radarNeutral = true;
	public boolean radarPassive = true;
	public int radarRange = 32;

	public boolean waypointsOnMap = true;
	public boolean waypointsInWorld = true;
	public boolean deathMarkers = true;

	public boolean outlines = true;
	public boolean roundedFog = true;
	/** The look with sky occlusion, deep shadows and a lower camera; off is the flatter look from before. */
	public boolean depthShading = true;
	public boolean alwaysFogOfWar = false;
	/**
	 * Fast is for slower computers: the map takes one sample per pixel instead of four, so it
	 * fills in about four times sooner, and the fog draws two layers instead of eight, without
	 * lighting them from the sun.
	 */
	public Quality quality = Quality.FANCY;
	public boolean chunkGrid = false;
	public boolean slimeChunks = false;
	public SpawnSpots spawnSpots = SpawnSpots.OFF;

	public enum Quality {
		FANCY,
		FAST
	}

	/** Which spawn spots the map marks: none, those dark even by day, or also those dark only at night. */
	public enum SpawnSpots {
		OFF,
		ALWAYS_DARK,
		AT_NIGHT_TOO
	}

	public static Settings get() {
		if (current == null) {
			current = load();
		}
		return current;
	}

	/** Reads the file again. For tests and tools. */
	public static Settings reload() {
		current = load();
		return current;
	}

	public double zoom() {
		return ZOOMS[Math.max(0, Math.min(ZOOMS.length - 1, this.minimapZoom))];
	}

	/** The map look these settings ask for. */
	public MapStyle style() {
		MapStyle base = this.depthShading ? MapStyle.DEFAULT : MapStyle.FLAT;
		return base.withName("Settings")
			.withOutline(this.outlines ? MapStyle.DEFAULT.outline() : 0)
			.withRoundedFog(this.roundedFog);
	}

	public void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling("config.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Diorama.LOGGER.error("Couldn't save settings to {}", file, e);
		}
		this.apply();
	}

	/** Pushes the settings that live elsewhere: the map look and fog of war. */
	public void apply() {
		// The map session re-bakes only if the tiles would look different.
		MapStyle.set(this.style());
		boolean fast = this.quality == Quality.FAST;
		if (fast != TileBaker.fast()) {
			TileBaker.setFast(fast);
			// Bake what's on screen again at the new quality.
			MapSession session = MapSession.get();
			if (session != null) {
				session.map().colorsChanged();
			}
		}
		ServerRules.setAlwaysFogOfWar(this.alwaysFogOfWar);
	}

	private static Settings load() {
		Path file = file();
		Settings settings = null;
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				settings = GSON.fromJson(reader, Settings.class);
			} catch (Exception e) {
				Diorama.LOGGER.error("Couldn't read settings from {}; using defaults", file, e);
			}
		}
		if (settings == null) {
			settings = new Settings();
		}
		if (settings.minimapSize == null) {
			settings.minimapSize = Size.MEDIUM;
		}
		if (settings.spawnSpots == null) {
			settings.spawnSpots = SpawnSpots.OFF;
		}
		if (settings.minimapCorner == null) {
			settings.minimapCorner = Corner.TOP_RIGHT;
		}
		if (settings.quality == null) {
			settings.quality = Quality.FANCY;
		}
		// Hand-edited numbers: snap to a value the settings screen can show.
		settings.minimapZoom = Math.max(0, Math.min(ZOOMS.length - 1, settings.minimapZoom));
		int nearest = RANGES[0];
		for (int range : RANGES) {
			if (Math.abs(range - settings.radarRange) < Math.abs(nearest - settings.radarRange)) {
				nearest = range;
			}
		}
		settings.radarRange = nearest;
		settings.apply();
		return settings;
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve("config.json");
	}
}
