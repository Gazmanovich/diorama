package dev.diorama.render;

import dev.diorama.Projection;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

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
	private static final Map<Long, CompletableFuture<int[]>> DRAWING = new HashMap<>();
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
		if (texture == null) {
			// For the first frames, while the voxel arrow for this heading is still being drawn.
			flat(graphics, x, y, yawDegrees, size);
			return;
		}
		float half = ArrowArt.GUI_SIZE / 2.0F;
		MapCanvas.of(graphics).rect(texture, Sampling.CLAMP_LINEAR, x - half, y - half, x + half, y + half, 0, 0, 1, 1, 0xFFFFFFFF);
	}

	/**
	 * The arrow's texture for one heading, or null while it is still being drawn. The first time a
	 * size is needed, every heading is drawn on a worker thread, the current one first, so the render
	 * thread never waits; finished ones are uploaded as they come in.
	 */
	private static @Nullable MapTexture arrow(int heading, float size) {
		double sin = Projection.sin();
		double cos = Projection.cos();
		long style = (long) Math.round(size * 4) << 40 | Math.round(sin * 10000) << 20 | Math.round(cos * 10000);
		if (STARTED.add(style)) {
			for (int i = 0; i < HEADINGS; i++) {
				int h = (heading + i) % HEADINGS;
				double yaw = h * 360.0 / HEADINGS;
				DRAWING.put(style * HEADINGS + h, CompletableFuture.supplyAsync(() -> {
					PinArt.Image image = ArrowArt.voxel(yaw, size, sin, cos);
					return image.abgr(image.width);
				}, WORKER));
			}
		}
		Iterator<Map.Entry<Long, CompletableFuture<int[]>>> pending = DRAWING.entrySet().iterator();
		while (pending.hasNext()) {
			Map.Entry<Long, CompletableFuture<int[]>> entry = pending.next();
			if (entry.getValue().isDone()) {
				int[] pixels = entry.getValue().join();
				int edge = ArrowArt.GUI_SIZE * ArrowArt.SCALE;
				MapTexture texture = MapTexture.create("diorama arrow", edge, 1);
				texture.write(0, 0, 0, edge, pixels);
				ARROWS.put(entry.getKey(), texture);
				pending.remove();
			}
		}
		return ARROWS.get(style * HEADINGS + heading);
	}

	/** The flat arrow, drawn while the voxel one isn't ready. North-south distances look shorter under the tilted camera. */
	private static void flat(GuiGraphicsExtractor graphics, float x, float y, float yawDegrees, float size) {
		double yaw = Math.toRadians(yawDegrees);
		double dx = -Math.sin(yaw);
		double dy = Math.cos(yaw) * Projection.sin();
		double len = Math.sqrt(dx * dx + dy * dy);
		dx /= len;
		dy /= len;
		quad(graphics, x, y, dx, dy, size + 1.6F, 0xE0101418);
		quad(graphics, x, y, dx, dy, size, 0xFFFFFFFF);
		quad(graphics, x, y, dx, dy, size * 0.55F, 0xFF3FA9F5);
	}

	private static void quad(GuiGraphicsExtractor graphics, float x, float y, double dx, double dy, float size, int color) {
		double px = -dy;
		double py = dx;
		float tipX = (float) (x + dx * size);
		float tipY = (float) (y + dy * size);
		float leftX = (float) (x - dx * size * 0.75 + px * size * 0.7);
		float leftY = (float) (y - dy * size * 0.75 + py * size * 0.7);
		float rightX = (float) (x - dx * size * 0.75 - px * size * 0.7);
		float rightY = (float) (y - dy * size * 0.75 - py * size * 0.7);
		float notchX = (float) (x - dx * size * 0.35);
		float notchY = (float) (y - dy * size * 0.35);
		// One quad: tip, right wing, notch, left wing (the GUI culls the other winding).
		MapCanvas.of(graphics).solid(new float[] {tipX, tipY, rightX, rightY, notchX, notchY, leftX, leftY}, color);
	}
}
