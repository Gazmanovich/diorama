package dev.diorama.drawing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.Diorama;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The game's default font read on the CPU, for notes in exported images: its bitmap glyph sheets,
 * measured and placed as the game does, from the font file the resource packs give. Glyphs only
 * Unifont has (most of CJK) are left out.
 */
final class ExportFont {
	/** A glyph: its pixels' alpha, {@code size} GUI pixels per texel, top {@code top} GUI pixels below the text's, and the advance. */
	private record Glyph(byte[] alpha, int width, int height, float size, float top, int advance) {
	}

	private final Map<Integer, Glyph> glyphs = new HashMap<>();
	private final Map<Integer, Integer> spaces = new HashMap<>();

	private ExportFont() {
	}

	static ExportFont load(ResourceManager manager) {
		ExportFont font = new ExportFont();
		font.read(manager, Identifier.withDefaultNamespace("default"), new HashSet<>());
		return font;
	}

	/** Reads a font file's providers in order; the first to have a character keeps it, as in the game. */
	private void read(ResourceManager manager, Identifier id, Set<Identifier> seen) {
		if (!seen.add(id)) {
			return;
		}
		Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), "font/" + id.getPath() + ".json");
		Optional<Resource> resource = manager.getResource(file);
		if (resource.isEmpty()) {
			return;
		}
		JsonObject json;
		try (InputStream in = resource.get().open()) {
			json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read font {} for an export", file, e);
			return;
		}
		for (JsonElement element : json.getAsJsonArray("providers")) {
			JsonObject provider = element.getAsJsonObject();
			String type = provider.get("type").getAsString();
			try {
				switch (type) {
					case "reference" -> {
						Identifier ref = Identifier.tryParse(provider.get("id").getAsString());
						if (ref != null) {
							this.read(manager, ref, seen);
						}
					}
					case "space" -> {
						for (Map.Entry<String, JsonElement> advance : provider.getAsJsonObject("advances").entrySet()) {
							this.spaces.putIfAbsent(advance.getKey().codePointAt(0), (int) Math.ceil(advance.getValue().getAsFloat()));
						}
					}
					case "bitmap" -> this.bitmap(manager, provider);
					default -> {
						// Unifont and TrueType fonts aren't read.
					}
				}
			} catch (IOException | RuntimeException e) {
				Diorama.LOGGER.warn("Couldn't read a {} font provider in {} for an export", type, file, e);
			}
		}
	}

	private void bitmap(ResourceManager manager, JsonObject provider) throws IOException {
		Identifier sheet = Identifier.tryParse(provider.get("file").getAsString());
		if (sheet == null) {
			return;
		}
		int height = provider.has("height") ? provider.get("height").getAsInt() : 8;
		int ascent = provider.get("ascent").getAsInt();
		JsonArray rows = provider.getAsJsonArray("chars");
		Optional<Resource> resource = manager.getResource(Identifier.fromNamespaceAndPath(sheet.getNamespace(), "textures/" + sheet.getPath()));
		if (resource.isEmpty() || rows.isEmpty()) {
			return;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			int columns = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length());
			int cellW = image.getWidth() / columns;
			int cellH = image.getHeight() / rows.size();
			float scale = (float) height / cellH;
			for (int row = 0; row < rows.size(); row++) {
				int[] points = rows.get(row).getAsString().codePoints().toArray();
				for (int col = 0; col < points.length; col++) {
					int c = points[col];
					if (c == 0 || this.glyphs.containsKey(c) || this.spaces.containsKey(c)) {
						continue;
					}
					byte[] alpha = new byte[cellW * cellH];
					int width = 0;
					for (int y = 0; y < cellH; y++) {
						for (int x = 0; x < cellW; x++) {
							int a = alpha(image, col * cellW + x, row * cellH + y);
							alpha[y * cellW + x] = (byte) a;
							if (a != 0) {
								width = Math.max(width, x + 1);
							}
						}
					}
					this.glyphs.put(c, new Glyph(alpha, cellW, cellH, scale, 7 - ascent, (int) (0.5 + width * scale) + 1));
				}
			}
		}
	}

	private static int alpha(NativeImage image, int x, int y) {
		//? if >=1.21.2 {
		return image.getPixel(x, y) >>> 24;
		//?} else {
		/*return image.getPixelRGBA(x, y) >>> 24;
		*///?}
	}

	/** How wide a line of text is, in GUI pixels, as the game's font measures it. */
	int width(String text) {
		int width = 0;
		for (int c : text.codePoints().toArray()) {
			width += this.advance(c);
		}
		return width;
	}

	private int advance(int c) {
		Integer space = this.spaces.get(c);
		if (space != null) {
			return space;
		}
		Glyph glyph = this.glyphs.get(c);
		return glyph != null ? glyph.advance() : 0;
	}

	/**
	 * Draws text with its shadow, as the game does, into ARGB {@code pixels} ({@code stride} wide),
	 * its top left at ({@code x}, {@code y}) in pixels, {@code scale} pixels to a GUI pixel.
	 */
	void draw(int[] pixels, int stride, int rows, String text, float x, float y, int scale, int rgb) {
		// The shadow: a quarter of the colour, one GUI pixel right and down.
		int shadow = (rgb & 0xFCFCFC) >> 2;
		this.drawPass(pixels, stride, rows, text, x + scale, y + scale, scale, shadow);
		this.drawPass(pixels, stride, rows, text, x, y, scale, rgb);
	}

	private void drawPass(int[] pixels, int stride, int rows, String text, float x, float y, int scale, int rgb) {
		float pen = x;
		for (int c : text.codePoints().toArray()) {
			Glyph glyph = this.glyphs.get(c);
			if (glyph != null) {
				float texel = glyph.size() * scale;
				float top = y + glyph.top() * scale;
				int w = Math.round(glyph.width() * texel);
				int h = Math.round(glyph.height() * texel);
				int x0 = Math.round(pen);
				int y0 = Math.round(top);
				for (int py = 0; py < h; py++) {
					int ty = Math.min(glyph.height() - 1, (int) (py / texel));
					int oy = y0 + py;
					if (oy < 0 || oy >= rows) {
						continue;
					}
					for (int px = 0; px < w; px++) {
						int ox = x0 + px;
						int a = glyph.alpha()[ty * glyph.width() + Math.min(glyph.width() - 1, (int) (px / texel))] & 0xFF;
						if (a == 0 || ox < 0 || ox >= stride) {
							continue;
						}
						pixels[oy * stride + ox] = over(pixels[oy * stride + ox], a << 24 | rgb);
					}
				}
			}
			pen += this.advance(c) * scale;
		}
	}

	/** An ARGB colour over another, as ARGB. */
	static int over(int below, int argb) {
		int a = argb >>> 24;
		if (a == 255) {
			return argb;
		}
		int ba = below >>> 24;
		int outA = a + ba * (255 - a) / 255;
		if (outA == 0) {
			return 0;
		}
		int r = (((argb >> 16) & 0xFF) * a + ((below >> 16) & 0xFF) * ba * (255 - a) / 255) / outA;
		int g = (((argb >> 8) & 0xFF) * a + ((below >> 8) & 0xFF) * ba * (255 - a) / 255) / outA;
		int b = ((argb & 0xFF) * a + (below & 0xFF) * ba * (255 - a) / 255) / outA;
		return outA << 24 | r << 16 | g << 8 | b;
	}
}
