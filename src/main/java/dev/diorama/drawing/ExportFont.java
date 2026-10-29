package dev.diorama.drawing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.Diorama;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The game's default font read on the CPU, for notes in exported images. Font files are merged
 * across resource packs and their providers tried in the game's order, the first with a character
 * keeping it: bitmap sheets measured as {@code BitmapProvider} measures them, Unifont's hex glyphs
 * as {@code UnihexProvider} does (read only for the characters asked for), spaces, and the game's
 * box for a character no provider has. TrueType fonts in resource packs aren't read.
 */
final class ExportFont {
	/**
	 * A glyph: its pixels' alpha; {@code size} GUI pixels to a texel; its top {@code top} GUI pixels
	 * below the text's; its advance and shadow offset in GUI pixels.
	 */
	private record Glyph(byte[] alpha, int width, int height, float size, float top, float advance, float shadow) {
	}

	/** The game's stand-in for a character no font has: a 5 by 8 outline. */
	private static final Glyph MISSING = missing();

	private final Map<Integer, Glyph> glyphs = new HashMap<>();
	private final Map<Integer, Float> spaces = new HashMap<>();
	/** The characters to find; Unifont is only searched for these. */
	private final Set<Integer> wanted;
	/** The font options the player has on, by the names font filters use: "uniform", "jp". */
	private final Set<String> options;

	private ExportFont(Set<Integer> wanted, Set<String> options) {
		this.wanted = wanted;
		this.options = options;
	}

	/** The font, found for the characters of {@code texts}, with the player's font {@code options} on. */
	static ExportFont load(ResourceManager manager, List<String> texts, Set<String> options) {
		Set<Integer> wanted = new HashSet<>();
		for (String text : texts) {
			text.codePoints().forEach(wanted::add);
			// Shaping swaps in other characters (Arabic's joined forms), drawn from the font too.
			shaped(text).codePoints().forEach(wanted::add);
		}
		ExportFont font = new ExportFont(wanted, options);
		font.read(manager, Identifier.withDefaultNamespace("default"), new HashSet<>());
		return font;
	}

	/** Whether a provider's filter lets it in: each option it names on or off as the player has it. */
	private boolean passes(JsonObject provider) {
		if (!provider.has("filter")) {
			return true;
		}
		for (Map.Entry<String, JsonElement> entry : provider.getAsJsonObject("filter").entrySet()) {
			if (this.options.contains(entry.getKey()) != entry.getValue().getAsBoolean()) {
				return false;
			}
		}
		return true;
	}

	private boolean has(int c) {
		return this.glyphs.containsKey(c) || this.spaces.containsKey(c);
	}

	/** A font file from every resource pack, the highest first, each one's providers in order. */
	private void read(ResourceManager manager, Identifier id, Set<Identifier> seen) {
		if (!seen.add(id)) {
			return;
		}
		Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), "font/" + id.getPath() + ".json");
		List<Resource> stack = manager.getResourceStack(file);
		for (int i = stack.size() - 1; i >= 0; i--) {
			try (InputStream in = stack.get(i).open()) {
				JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (JsonElement element : json.getAsJsonArray("providers")) {
					this.provider(manager, element, seen);
				}
			} catch (IOException | RuntimeException e) {
				// As the game does: a pack with a broken font file is passed over, not the export.
				Diorama.LOGGER.warn("Couldn't read font {} for an export", file, e);
			}
		}
	}

	private void provider(ResourceManager manager, JsonElement element, Set<Identifier> seen) {
		try {
			JsonObject provider = element.getAsJsonObject();
			if (!this.passes(provider)) {
				// A filter on a reference covers everything it brings in.
				return;
			}
			switch (provider.get("type").getAsString()) {
				case "reference" -> {
					Identifier ref = Identifier.tryParse(provider.get("id").getAsString());
					if (ref != null) {
						this.read(manager, ref, seen);
					}
				}
				case "space" -> {
					// All or nothing, as the game takes a provider: a bad entry drops the lot.
					Map<Integer, Float> advances = new HashMap<>();
					for (Map.Entry<String, JsonElement> advance : provider.getAsJsonObject("advances").entrySet()) {
						advances.put(advance.getKey().codePointAt(0), advance.getValue().getAsFloat());
					}
					advances.forEach((c, advance) -> {
						if (!this.has(c)) {
							this.spaces.put(c, advance);
						}
					});
				}
				case "bitmap" -> this.bitmap(manager, provider);
				case "unihex" -> this.unihex(manager, provider);
				default -> {
					// TrueType fonts aren't read.
				}
			}
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read a font provider for an export", e);
		}
	}

	private void bitmap(ResourceManager manager, JsonObject provider) throws IOException {
		Identifier sheet = Identifier.tryParse(provider.get("file").getAsString());
		if (sheet == null) {
			return;
		}
		int height = provider.has("height") ? provider.get("height").getAsInt() : 8;
		int ascent = provider.get("ascent").getAsInt();
		com.google.gson.JsonArray rows = provider.getAsJsonArray("chars");
		Optional<Resource> resource = manager.getResource(Identifier.fromNamespaceAndPath(sheet.getNamespace(), "textures/" + sheet.getPath()));
		if (resource.isEmpty() || rows.isEmpty()) {
			return;
		}
		// Kept only once the whole sheet has read: a broken provider adds nothing.
		Map<Integer, Glyph> read = new HashMap<>();
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			String first = rows.get(0).getAsString();
			int columns = first.codePointCount(0, first.length());
			if (columns == 0) {
				return;
			}
			int cellW = image.getWidth() / columns;
			int cellH = image.getHeight() / rows.size();
			float scale = (float) height / cellH;
			for (int row = 0; row < rows.size(); row++) {
				int[] points = rows.get(row).getAsString().codePoints().toArray();
				for (int col = 0; col < points.length; col++) {
					int c = points[col];
					if (c == 0 || this.has(c) || read.containsKey(c)) {
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
					read.put(c, new Glyph(alpha, cellW, cellH, scale, 7 - ascent, (int) (0.5 + width * scale) + 1, 1));
				}
			}
		}
		this.glyphs.putAll(read);
	}

	/** Unifont's glyphs, from the hex files in its zip, for the wanted characters nothing before it has. */
	private void unihex(ResourceManager manager, JsonObject provider) throws IOException {
		Set<Integer> missing = new HashSet<>();
		for (int c : this.wanted) {
			if (!this.has(c)) {
				missing.add(c);
			}
		}
		Identifier hex = Identifier.tryParse(provider.get("hex_file").getAsString());
		Optional<Resource> resource = hex == null ? Optional.empty() : manager.getResource(hex);
		if (missing.isEmpty() || resource.isEmpty()) {
			return;
		}
		Map<Integer, int[]> sizes = new HashMap<>();
		if (provider.has("size_overrides")) {
			for (JsonElement element : provider.getAsJsonArray("size_overrides")) {
				JsonObject range = element.getAsJsonObject();
				int from = range.get("from").getAsString().codePointAt(0);
				int to = range.get("to").getAsString().codePointAt(0);
				for (int c : missing) {
					if (c >= from && c <= to) {
						// The first range a character is in sets its size, as in the game.
						sizes.putIfAbsent(c, new int[] {range.get("left").getAsInt(), range.get("right").getAsInt()});
					}
				}
			}
		}
		// A character listed twice keeps its last bitmap, as in the game, so every file is read.
		Map<Integer, String> found = new HashMap<>();
		try (InputStream in = resource.get().open(); ZipInputStream zip = new ZipInputStream(in)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.getName().endsWith(".hex")) {
					continue;
				}
				// Not closed: that would close the zip.
				BufferedReader lines = new BufferedReader(new InputStreamReader(zip, StandardCharsets.US_ASCII));
				String line;
				while ((line = lines.readLine()) != null) {
					int colon = line.indexOf(':');
					if (colon < 4 || colon > 6) {
						continue;
					}
					int c = Integer.parseInt(line.substring(0, colon), 16);
					if (missing.contains(c)) {
						found.put(c, line.substring(colon + 1).trim());
					}
				}
			}
		}
		for (Map.Entry<Integer, String> glyph : found.entrySet()) {
			this.hexGlyph(glyph.getKey(), glyph.getValue(), sizes.get(glyph.getKey()));
		}
	}

	/** A Unifont glyph: 16 rows of 8 to 32 bits, half a GUI pixel each, cut to its ink or to {@code size}. */
	private void hexGlyph(int c, String digits, int[] size) {
		int perRow = digits.length() / 16;
		if (perRow < 2 || perRow > 8 || digits.length() % 16 != 0) {
			return;
		}
		int bits = perRow * 4;
		int[] rows = new int[16];
		int mask = 0;
		for (int y = 0; y < 16; y++) {
			// Left-aligned in 32 bits, as the game keeps them.
			rows[y] = (int) (Long.parseLong(digits.substring(y * perRow, (y + 1) * perRow), 16) << (32 - bits));
			mask |= rows[y];
		}
		int left;
		int right;
		if (size != null) {
			left = size[0];
			right = size[1];
		} else if (mask == 0) {
			left = 0;
			right = bits;
		} else {
			left = Integer.numberOfLeadingZeros(mask);
			right = 32 - Integer.numberOfTrailingZeros(mask) - 1;
		}
		int width = right - left + 1;
		if (width <= 0) {
			return;
		}
		byte[] alpha = new byte[width * 16];
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < width; x++) {
				int bit = 31 - (left + x);
				if (bit >= 0 && bit < 32 && (rows[y] >> bit & 1) != 0) {
					alpha[y * width + x] = (byte) 0xFF;
				}
			}
		}
		this.glyphs.put(c, new Glyph(alpha, width, 16, 0.5F, 0, width / 2 + 1, 0.5F));
	}

	private static Glyph missing() {
		byte[] alpha = new byte[5 * 8];
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 5; x++) {
				if (x == 0 || x == 4 || y == 0 || y == 7) {
					alpha[y * 5 + x] = (byte) 0xFF;
				}
			}
		}
		return new Glyph(alpha, 5, 8, 1, 0, 6, 1);
	}

	private static int alpha(NativeImage image, int x, int y) {
		//? if >=1.21.2 {
		return image.getPixel(x, y) >>> 24;
		//?} else {
		/*return image.getPixelRGBA(x, y) >>> 24;
		*///?}
	}

	private Glyph glyph(int c) {
		Glyph glyph = this.glyphs.get(c);
		return glyph != null ? glyph : MISSING;
	}

	/**
	 * Text as the game draws it: in a right-to-left language it shapes Arabic and puts the
	 * characters in visual order first, as {@code Font.bidirectionalShaping} does. Measuring uses
	 * the text as written, as the game's does.
	 */
	static String shaped(String text) {
		if (!Language.getInstance().isDefaultRightToLeft()) {
			return text;
		}
		try {
			Bidi bidi = new Bidi(new ArabicShaping(ArabicShaping.LETTERS_SHAPE).shape(text), Bidi.LEVEL_DEFAULT_RTL);
			bidi.setReorderingMode(Bidi.REORDER_DEFAULT);
			return bidi.writeReordered(Bidi.DO_MIRRORING);
		} catch (ArabicShapingException e) {
			return text;
		}
	}

	/** How wide a line of text is, in GUI pixels, as the game's font measures it. */
	float width(String text) {
		float width = 0;
		for (int c : text.codePoints().toArray()) {
			Float space = this.spaces.get(c);
			width += space != null ? space : this.glyph(c).advance();
		}
		return width;
	}

	/**
	 * How far the text's glyphs and shadows reach left and right of where it starts, in GUI
	 * pixels: {left, right}. Negative spaces, which resource packs use, can move it left.
	 */
	float[] span(String text) {
		float pen = 0;
		float left = 0;
		float right = 0;
		for (int c : text.codePoints().toArray()) {
			Float space = this.spaces.get(c);
			if (space != null) {
				pen += space;
			} else {
				Glyph glyph = this.glyph(c);
				left = Math.min(left, pen);
				right = Math.max(right, pen + glyph.width() * glyph.size() + glyph.shadow());
				pen += glyph.advance();
			}
			left = Math.min(left, pen);
			right = Math.max(right, pen);
		}
		return new float[] {left, right};
	}

	/** How far the text's glyphs and shadows reach above and below its top, in GUI pixels: {top, bottom}. */
	float[] extent(String text) {
		float top = 0;
		float bottom = 8;
		for (int c : text.codePoints().toArray()) {
			if (this.spaces.containsKey(c)) {
				continue;
			}
			Glyph glyph = this.glyph(c);
			top = Math.min(top, glyph.top());
			bottom = Math.max(bottom, glyph.top() + glyph.height() * glyph.size() + glyph.shadow());
		}
		return new float[] {top, bottom};
	}

	/**
	 * Draws text with its shadow, as the game does, into ARGB {@code pixels} ({@code stride} wide,
	 * {@code rows} tall), its top left at ({@code x}, {@code y}), {@code scale} pixels to a GUI pixel.
	 */
	void draw(int[] pixels, int stride, int rows, String text, float x, float y, int scale, int rgb) {
		text = shaped(text);
		// The shadow: a quarter of the colour, offset by the glyph's shadow distance right and down.
		this.drawPass(pixels, stride, rows, text, x, y, scale, (rgb & 0xFCFCFC) >> 2, true);
		this.drawPass(pixels, stride, rows, text, x, y, scale, rgb, false);
	}

	private void drawPass(int[] pixels, int stride, int rows, String text, float x, float y, int scale, int rgb, boolean shadow) {
		float pen = x;
		for (int c : text.codePoints().toArray()) {
			Float space = this.spaces.get(c);
			if (space != null) {
				pen += space * scale;
				continue;
			}
			Glyph glyph = this.glyph(c);
			float offset = shadow ? glyph.shadow() * scale : 0;
			float texel = glyph.size() * scale;
			int x0 = Math.round(pen + offset);
			int y0 = Math.round(y + glyph.top() * scale + offset);
			int w = Math.round(glyph.width() * texel);
			int h = Math.round(glyph.height() * texel);
			for (int py = 0; py < h; py++) {
				int oy = y0 + py;
				if (oy < 0 || oy >= rows) {
					continue;
				}
				int ty = Math.min(glyph.height() - 1, (int) (py / texel));
				for (int px = 0; px < w; px++) {
					int ox = x0 + px;
					int a = glyph.alpha()[ty * glyph.width() + Math.min(glyph.width() - 1, (int) (px / texel))] & 0xFF;
					if (a != 0 && ox >= 0 && ox < stride) {
						pixels[oy * stride + ox] = over(pixels[oy * stride + ox], a << 24 | rgb);
					}
				}
			}
			pen += glyph.advance() * scale;
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
