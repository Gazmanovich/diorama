package dev.diorama.drawing;

import dev.diorama.Projection;
import dev.diorama.data.ImageExport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Drawings painted into exported images row by row, after the fog, as {@link DrawingOverlay} draws
 * them on the map: lines and areas in the order they were drawn, then notes on top. Widths are in
 * GUI pixels, as on screen, at the export's {@link #SCALE} pixels to a GUI pixel, so they look as
 * they do on the map at every detail level. Lines are anti-aliased; area fills aren't, their edge
 * line covers that.
 */
public final class ExportDrawings implements ImageExport.Finish {
	/** Export pixels to a GUI pixel: an export pixel is a screen pixel at GUI scale 2, as for the fog. */
	private static final int SCALE = 2;

	/** A line or an area, in export pixels: x0, y0, x1, y1, ... from the area's top left. */
	private record Shape(Drawing.Kind kind, float[] points, int color, float top, float bottom, float left, float right) {
	}

	/** A note, already drawn: ARGB pixels at its top left. */
	private record Plate(int[] argb, int width, int height, int x, int y) {
	}

	private final List<Shape> shapes = new ArrayList<>();
	private final List<Plate> plates = new ArrayList<>();
	private final int width;
	private final int height;
	/** The area's first row, in map pixels: rows come numbered that way. */
	private final long top;
	private final ThreadLocal<float[]> coverage;

	private ExportDrawings(ImageExport.Area area) {
		this.width = area.width();
		this.height = area.height();
		this.top = area.y();
		this.coverage = ThreadLocal.withInitial(() -> new float[this.width]);
	}

	/**
	 * The drawings over {@code area}. The font for notes comes from the resource packs in
	 * {@code resources}, with the font options the player has on ("uniform", "jp").
	 */
	public static ExportDrawings build(List<Drawing> drawings, ImageExport.Area area, ResourceManager resources, Set<String> fontOptions) {
		ExportDrawings out = new ExportDrawings(area);
		double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
		List<String> texts = new ArrayList<>();
		for (Drawing drawing : drawings) {
			if (drawing.kind == Drawing.Kind.NOTE && drawing.complete()) {
				texts.add(drawing.text);
			}
		}
		ExportFont font = texts.isEmpty() ? null : ExportFont.load(resources, texts, fontOptions);
		float reach = (DrawingOverlay.CORE + DrawingOverlay.RIM * 2) * SCALE;
		for (Drawing drawing : drawings) {
			if (!drawing.complete()) {
				continue;
			}
			int n = drawing.pointCount();
			float[] p = new float[n * 2];
			float top = Float.MAX_VALUE;
			float bottom = -Float.MAX_VALUE;
			float left = Float.MAX_VALUE;
			float right = -Float.MAX_VALUE;
			for (int k = 0; k < n; k++) {
				p[k * 2] = (float) (drawing.points[k * 2] * perUnit - area.x());
				p[k * 2 + 1] = (float) (drawing.points[k * 2 + 1] * perUnit - area.y());
				left = Math.min(left, p[k * 2]);
				right = Math.max(right, p[k * 2]);
				top = Math.min(top, p[k * 2 + 1]);
				bottom = Math.max(bottom, p[k * 2 + 1]);
			}
			if (drawing.kind == Drawing.Kind.NOTE) {
				out.plate(font, drawing.text, p[0], p[1], drawing.color);
				continue;
			}
			if (right + reach < 0 || left - reach > area.width() || bottom + reach < 0 || top - reach > area.height()) {
				continue;
			}
			out.shapes.add(new Shape(drawing.kind, p, drawing.color, top - reach, bottom + reach, left - reach, right + reach));
		}
		return out;
	}

	/**
	 * A note's dark plate and its text with a shadow, laid out as {@code DrawingOverlay.plate} does,
	 * in GUI pixels times {@link #SCALE}. Text taller than the plate spills over it, as on the map.
	 */
	private void plate(ExportFont font, String text, float x, float y, int color) {
		// The plate is laid out from the text as written, as the game's is; the text drawn on it may
		// be shaped into other glyphs (see ExportFont.shaped), so the canvas holds both.
		String shaped = ExportFont.shaped(text);
		int textWidth = (int) Math.ceil(font.width(text));
		// The game's plate, in GUI pixels from the text's top left; a negative width (negative
		// spaces) turns it round, as the game's fill does.
		int plateLeft = Math.min(-2, textWidth + 1);
		int plateRight = Math.max(-2, textWidth + 1);
		float[] written = font.span(text);
		float[] drawn = font.span(shaped);
		float[] writtenRows = font.extent(text);
		float[] drawnRows = font.extent(shaped);
		// The canvas: the plate, and whatever the glyphs reach past it.
		int left = (int) Math.floor(Math.min(plateLeft, Math.min(written[0], drawn[0])));
		int right = (int) Math.ceil(Math.max(plateRight, Math.max(written[1], drawn[1])));
		int top = (int) Math.floor(Math.min(-2, Math.min(writtenRows[0], drawnRows[0])));
		int bottom = (int) Math.ceil(Math.max(9, Math.max(writtenRows[1], drawnRows[1])));
		int w = (right - left) * SCALE;
		int h = (bottom - top) * SCALE;
		int textX = Math.round(x - textWidth * SCALE / 2.0F);
		int textY = Math.round(y - 4 * SCALE);
		int canvasLeft = textX + left * SCALE;
		int canvasTop = textY + top * SCALE;
		if (w <= 0 || h <= 0 || canvasLeft + w <= 0 || canvasLeft >= this.width || canvasTop + h <= 0 || canvasTop >= this.height) {
			// Not in this image at all: nothing to draw or keep.
			return;
		}
		int[] argb = new int[w * h];
		int plateTop = (-2 - top) * SCALE;
		for (int row = plateTop; row < plateTop + 11 * SCALE; row++) {
			Arrays.fill(argb, row * w + (plateLeft - left) * SCALE, row * w + (plateRight - left) * SCALE, DrawingOverlay.PLATE);
		}
		font.draw(argb, w, h, text, -left * SCALE, -top * SCALE, SCALE, color & 0xFFFFFF);
		this.plates.add(new Plate(argb, w, h, canvasLeft, canvasTop));
	}

	@Override
	public void row(int[] abgr, long y, long x0) {
		// Shapes are kept from the area's top left; the row's middle is where it's sampled.
		float cy = (float) (y - this.top) + 0.5F;
		for (Shape shape : this.shapes) {
			if (cy < shape.top() || cy > shape.bottom()) {
				continue;
			}
			if (shape.kind() == Drawing.Kind.AREA) {
				this.fill(abgr, shape, cy, DrawingOverlay.FILL_ALPHA << 24 | shape.color() & 0xFFFFFF);
				this.line(abgr, shape, cy, true, DrawingOverlay.EDGE * SCALE, 0xFF000000 | shape.color());
			} else {
				this.line(abgr, shape, cy, false, (DrawingOverlay.CORE + DrawingOverlay.RIM * 2) * SCALE, DrawingOverlay.rim(shape.color()));
				this.line(abgr, shape, cy, false, DrawingOverlay.CORE * SCALE, 0xFF000000 | shape.color());
			}
		}
		int iy = (int) Math.floor(cy);
		for (Plate plate : this.plates) {
			int py = iy - plate.y();
			if (py < 0 || py >= plate.height()) {
				continue;
			}
			for (int px = Math.max(0, -plate.x()); px < plate.width() && plate.x() + px < abgr.length; px++) {
				int i = plate.x() + px;
				abgr[i] = over(abgr[i], plate.argb()[py * plate.width() + px]);
			}
		}
	}

	/** A line through the shape's points, {@code width} wide with round joints, anti-aliased, blended once per pixel. */
	private void line(int[] abgr, Shape shape, float cy, boolean closed, float width, int argb) {
		float half = width / 2;
		float[] p = shape.points();
		int n = p.length / 2;
		float[] cover = this.coverage.get();
		int from = Integer.MAX_VALUE;
		int to = Integer.MIN_VALUE;
		int segments = closed ? n : n - 1;
		for (int k = 0; k < segments; k++) {
			int j = (k + 1) % n;
			float ax = p[k * 2];
			float ay = p[k * 2 + 1];
			float bx = p[j * 2];
			float by = p[j * 2 + 1];
			if (cy < Math.min(ay, by) - half - 1 || cy > Math.max(ay, by) + half + 1) {
				continue;
			}
			// Only where the line's band crosses this row, not the whole of a long diagonal's box.
			float t0 = 0;
			float t1 = 1;
			if (Math.abs(by - ay) > 1e-4F) {
				float ta = (cy - half - 1 - ay) / (by - ay);
				float tb = (cy + half + 1 - ay) / (by - ay);
				t0 = Math.max(0, Math.min(ta, tb));
				t1 = Math.min(1, Math.max(ta, tb));
			}
			float sx0 = ax + (bx - ax) * t0;
			float sx1 = ax + (bx - ax) * t1;
			int x0 = Math.max(0, (int) Math.floor(Math.min(sx0, sx1) - half - 1));
			int x1 = Math.min(this.width - 1, (int) Math.ceil(Math.max(sx0, sx1) + half + 1));
			if (x0 > x1) {
				continue;
			}
			if (from > to) {
				Arrays.fill(cover, x0, x1 + 1, 0);
				from = x0;
				to = x1;
			} else {
				if (x0 < from) {
					Arrays.fill(cover, x0, from, 0);
					from = x0;
				}
				if (x1 > to) {
					Arrays.fill(cover, to + 1, x1 + 1, 0);
					to = x1;
				}
			}
			float dx = bx - ax;
			float dy = by - ay;
			float length = dx * dx + dy * dy;
			for (int x = x0; x <= x1; x++) {
				float px = x + 0.5F;
				float t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (cy - ay) * dy) / length));
				float d = (float) Math.hypot(px - (ax + t * dx), cy - (ay + t * dy));
				float c = Math.max(0, Math.min(1, half + 0.5F - d));
				if (c > cover[x]) {
					cover[x] = c;
				}
			}
		}
		int alpha = argb >>> 24;
		for (int x = from; x <= to; x++) {
			if (cover[x] > 0) {
				abgr[x] = over(abgr[x], Math.round(alpha * cover[x]) << 24 | argb & 0xFFFFFF);
			}
		}
	}

	/** The inside of an area on this row, even-odd, by pixel centres. */
	private void fill(int[] abgr, Shape shape, float cy, int argb) {
		float[] p = shape.points();
		int n = p.length / 2;
		float[] crossings = new float[n];
		int count = 0;
		for (int k = 0, j = n - 1; k < n; j = k++) {
			float ay = p[k * 2 + 1];
			float by = p[j * 2 + 1];
			if ((ay > cy) != (by > cy)) {
				crossings[count++] = p[k * 2] + (cy - ay) * (p[j * 2] - p[k * 2]) / (by - ay);
			}
		}
		Arrays.sort(crossings, 0, count);
		for (int i = 0; i + 1 < count; i += 2) {
			int x0 = Math.max(0, (int) Math.ceil(crossings[i] - 0.5F));
			int x1 = Math.min(this.width - 1, (int) Math.floor(crossings[i + 1] - 0.5F));
			for (int x = x0; x <= x1; x++) {
				abgr[x] = over(abgr[x], argb);
			}
		}
	}

	/** An ARGB colour over an opaque ABGR pixel, as ABGR. */
	private static int over(int abgr, int argb) {
		int a = argb >>> 24;
		if (a == 0) {
			return abgr;
		}
		int r = (((argb >> 16) & 0xFF) * a + (abgr & 0xFF) * (255 - a)) / 255;
		int g = (((argb >> 8) & 0xFF) * a + ((abgr >> 8) & 0xFF) * (255 - a)) / 255;
		int b = ((argb & 0xFF) * a + ((abgr >> 16) & 0xFF) * (255 - a)) / 255;
		return 0xFF000000 | b << 16 | g << 8 | r;
	}
}
