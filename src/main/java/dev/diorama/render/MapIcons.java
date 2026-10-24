package dev.diorama.render;

import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import org.jspecify.annotations.Nullable;

/** Small white shapes drawn tinted on the map: dots for mobs, carets for "above" and "below". */
public final class MapIcons {
	private static final int SIZE = 32;
	private static @Nullable MapTexture dot;
	private static @Nullable MapTexture caret;

	private MapIcons() {
	}

	/** A round dot of diameter {@code size} centred on the point. */
	public static void dot(IconBatch icons, float x, float y, float size, int argb) {
		if (dot == null) {
			dot = create("diorama dot", MapIcons::circle);
		}
		draw(icons, dot, x - size / 2, y - size / 2, size, argb, false);
	}

	/** A small caret centred on the point, pointing up, or down when {@code down}. */
	public static void caret(IconBatch icons, float x, float y, float size, int argb, boolean down) {
		if (caret == null) {
			caret = create("diorama caret", MapIcons::triangle);
		}
		draw(icons, caret, x - size / 2, y - size / 2, size, argb, down);
	}

	private static void draw(IconBatch icons, MapTexture texture, float x, float y, float size, int argb, boolean flip) {
		icons.rect(texture, Sampling.CLAMP_LINEAR, x, y, x + size, y + size, 0, flip ? 1 : 0, 1, flip ? 0 : 1, argb);
	}

	private interface Shape {
		/** Coverage 0..1 of the pixel centred at (px, py), in -1..1 coordinates. */
		float coverage(float px, float py);
	}

	private static float circle(float px, float py) {
		float d = (float) Math.sqrt(px * px + py * py);
		// One pixel of soft edge.
		return Math.max(0, Math.min(1, (0.94F - d) * SIZE / 2));
	}

	private static float triangle(float px, float py) {
		// Tip at the top, base at the bottom.
		float halfWidth = (py + 0.8F) * 0.62F;
		if (py < -0.8F || py > 0.75F) {
			return 0;
		}
		return Math.max(0, Math.min(1, (halfWidth - Math.abs(px)) * SIZE / 2));
	}

	private static MapTexture create(String label, Shape shape) {
		int[] pixels = new int[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				float px = (x + 0.5F) / SIZE * 2 - 1;
				float py = (y + 0.5F) / SIZE * 2 - 1;
				int a = Math.round(shape.coverage(px, py) * 255);
				pixels[y * SIZE + x] = (a << 24) | 0xFFFFFF;
			}
		}
		MapTexture texture = MapTexture.create(label, SIZE, 1);
		texture.write(0, 0, 0, SIZE, pixels);
		return texture;
	}
}
