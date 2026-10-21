package dev.diorama.render;

import dev.diorama.Projection;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shapes drawn on top of the map. */
public final class Markers {
	/** Headings the arrow is drawn at, around the full turn: one every 5 degrees. */
	private static final int HEADINGS = 72;
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Diorama arrow");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<Long, MapTexture> ARROWS = new HashMap<>();
	private static final Map<Long, CompletableFuture<PinArt.Image>> DRAWING = new HashMap<>();
	private static final Set<Long> STARTED = new HashSet<>();

	private Markers() {
	}

	/**
	 * The player arrow at a screen point: the voxel arrow from {@link ArrowArt}, centred on the
	 * player's spot. {@code yawDegrees} is Minecraft's yaw: 0 faces south, 90 faces west.
	 */
	public static void player(GuiGraphicsExtractor graphics, float x, float y, float yawDegrees, float size) {
		int heading = Math.floorMod(Math.round(yawDegrees / (360.0F / HEADINGS)), HEADINGS);
		MapTexture texture = arrow(heading, size);
		float half = ArrowArt.GUI_SIZE / 2.0F;
		MapCanvas.of(graphics).rect(texture, Sampling.CLAMP_LINEAR, x - half, y - half, x + half, y + half, 0, 0, 1, 1, 0xFFFFFFFF);
	}

	/**
	 * The arrow's texture for one heading. The first time a size is drawn, every heading is drawn on
	 * a worker thread so turning never waits; a heading that isn't ready yet is drawn right away.
	 */
	private static MapTexture arrow(int heading, float size) {
		double sin = Projection.sin();
		double cos = Projection.cos();
		long style = (long) Math.round(size * 4) << 40 | Math.round(sin * 10000) << 20 | Math.round(cos * 10000);
		long key = style * HEADINGS + heading;
		MapTexture texture = ARROWS.get(key);
		if (texture != null) {
			return texture;
		}
		if (STARTED.add(style)) {
			for (int h = 0; h < HEADINGS; h++) {
				double yaw = h * 360.0 / HEADINGS;
				DRAWING.put(style * HEADINGS + h, CompletableFuture.supplyAsync(() -> ArrowArt.voxel(yaw, size, sin, cos), WORKER));
			}
		}
		CompletableFuture<PinArt.Image> drawing = DRAWING.remove(key);
		PinArt.Image image = drawing != null && drawing.isDone() ? drawing.join() : ArrowArt.voxel(heading * 360.0 / HEADINGS, size, sin, cos);
		int edge = image.width;
		texture = MapTexture.create("diorama arrow", edge, 1);
		texture.write(0, 0, 0, edge, image.abgr(edge));
		ARROWS.put(key, texture);
		return texture;
	}
}
