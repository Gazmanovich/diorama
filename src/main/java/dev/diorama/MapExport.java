package dev.diorama;

import dev.diorama.bake.BlockLooks;
import dev.diorama.data.ImageExport;
import dev.diorama.data.TileStore;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.ExportDrawings;
import dev.diorama.render.CaveSliceTiles;
import dev.diorama.render.ExportFog;
import dev.diorama.render.MapLevel;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Saving part of the map as an image, in the background, into the screenshots folder, with a
 * link in chat when it's done, as screenshots get. One at a time.
 */
public final class MapExport {
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	private static volatile @Nullable MapExport running;

	private final Path file;
	private volatile double progress;

	private MapExport(Path file) {
		this.file = file;
	}

	/** The export being written, or null. */
	public static @Nullable MapExport running() {
		return running;
	}

	/** How far along it is, 0 to 1. */
	public double progress() {
		return this.progress;
	}

	/**
	 * Starts saving {@code area} of the saved sky view with the map's fog over unexplored
	 * ground, or its smoke when {@code smoke} (the sky view under a roof), and {@code drawings} over
	 * it. False if an export is already running.
	 */
	public static boolean start(TileStore store, ImageExport.Area area, boolean smoke, List<Drawing> drawings) {
		return start(store, area, smoke, 0, drawings);
	}

	/** The same, the ground drawn {@code night} into the night (0..1) as the map draws it. */
	public static boolean start(TileStore store, ImageExport.Area area, boolean smoke, float night, List<Drawing> drawings) {
		return start(() -> {
			store.catchUp();
			return ImageExport.Tiles.of(store);
		}, area, smoke ? ExportFog.Look.SMOKE : ExportFog.Look.FOG, night, drawings);
	}

	/**
	 * Starts saving {@code area} of the cave slice cut at {@code layer}, drawn from the chunks the
	 * map holds, as the map draws it, with the dark the map has around it. False if an export is
	 * already running, or the map can't draw yet.
	 */
	public static boolean startCave(MapLevel level, int layer, ImageExport.Area area, List<Drawing> drawings) {
		BlockLooks looks = BlockLooks.get();
		if (looks == null) {
			return false;
		}
		if (running != null) {
			return false;
		}
		// Made here, on the render thread: the slice as it is now.
		CaveSliceTiles tiles = new CaveSliceTiles(level, layer, looks);
		return start(() -> tiles, area, ExportFog.Look.CAVE, 0, drawings);
	}

	/** Makes an export's tiles on its own thread, where waiting is fine. */
	private interface Source {
		ImageExport.Tiles open() throws InterruptedException;
	}

	private static synchronized boolean start(Source source, ImageExport.Area area, ExportFog.Look look, float night, List<Drawing> drawings) {
		if (running != null) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		Path folder = mc.gameDirectory.toPath().resolve("screenshots");
		// One left by a game that quit partway through an export.
		ImageExport.deleteUnfinished(folder, "diorama_");
		Path file = folder.resolve("diorama_" + LocalDateTime.now().format(NAME) + ".png");
		for (int i = 1; file.toFile().exists(); i++) {
			file = folder.resolve("diorama_" + LocalDateTime.now().format(NAME) + "_" + i + ".png");
		}
		MapExport export = new MapExport(file);
		running = export;
		List<Drawing> copied = drawings.stream().map(Drawing::fixed).toList();
		// The font options notes are drawn with, read here as the game reads them for its fonts.
		Set<String> fontOptions = new HashSet<>();
		if (mc.options.forceUnicodeFont().get()) {
			fontOptions.add("uniform");
		}
		// 1.20.1 has no Japanese glyph variants.
		//? if >=1.21 {
		if (mc.options.japaneseGlyphVariants().get()) {
			fontOptions.add("jp");
		}
		//?}
		Thread thread = new Thread(() -> export.run(source, area, look, night, copied, fontOptions), "diorama-export");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		thread.start();
		return true;
	}

	private void run(Source source, ImageExport.Area area, ExportFog.Look look, float night, List<Drawing> drawings, Set<String> fontOptions) {
		Component message;
		try {
			ImageExport.Tiles tiles = source.open();
			// A view that isn't saved is drawn first, which takes most of its export's time.
			double drawing = tiles.prepareShare();
			tiles.prepare(area, p -> this.progress = p * drawing);
			// The fog reads tiles to build its mask, so it's made here rather than on the render thread.
			ImageExport.Finish finish = ExportFog.build(tiles, area, look, night);
			if (!drawings.isEmpty()) {
				finish = finish.then(ExportDrawings.build(drawings, area, Minecraft.getInstance().getResourceManager(), fontOptions));
			}
			ImageExport.write(tiles, area, this.file, finish, night, p -> this.progress = drawing + (1 - drawing) * p);
			Path absolute = this.file.toAbsolutePath();
			Component link = Component.literal(absolute.getFileName().toString())
				.withStyle(ChatFormatting.UNDERLINE)
				.withStyle(style -> style.withClickEvent(new ClickEvent.OpenFile(absolute.toString())));
			message = Component.translatable("diorama.export.done", link);
		} catch (Exception e) {
			Diorama.LOGGER.error("Couldn't export the map to {}", this.file, e);
			message = Component.translatable("diorama.export.failed", String.valueOf(e.getMessage()));
		} finally {
			// Whatever happened, the next export may start.
			running = null;
		}
		Component shown = message;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			//? if >=26.1 {
			mc.gui.hud.getChat().addClientSystemMessage(shown);
			//?} else {
			/*mc.gui.getChat().addMessage(shown);
			*///?}
		});
	}
}
