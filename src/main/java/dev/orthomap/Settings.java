package dev.orthomap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;

/**
 * Player settings, saved to {@code .minecraft/orthomap/config.json}. Fields are public so Gson
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
	public boolean alwaysFogOfWar = false;

	public static Settings get() {
		if (current == null) {
			current = load();
		}
		return current;
	}

	public double zoom() {
		return ZOOMS[Math.max(0, Math.min(ZOOMS.length - 1, this.minimapZoom))];
	}

	/** The map look these settings ask for. */
	public MapStyle style() {
		return MapStyle.BASELINE.withName("Settings")
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
			Orthomap.LOGGER.error("Couldn't save settings to {}", file, e);
		}
		this.apply();
	}

	/** Pushes the settings that live elsewhere: the map look and fog of war. */
	public void apply() {
		// Only a real change of look re-bakes the map; moving the minimap shouldn't.
		MapStyle style = this.style();
		if (!style.looksLike(MapStyle.get())) {
			MapStyle.set(style);
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
				Orthomap.LOGGER.error("Couldn't read settings from {}; using defaults", file, e);
			}
		}
		if (settings == null) {
			settings = new Settings();
		}
		if (settings.minimapSize == null) {
			settings.minimapSize = Size.MEDIUM;
		}
		if (settings.minimapCorner == null) {
			settings.minimapCorner = Corner.TOP_RIGHT;
		}
		settings.apply();
		return settings;
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(Orthomap.MOD_ID).resolve("config.json");
	}
}
