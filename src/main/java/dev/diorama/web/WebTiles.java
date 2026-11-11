package dev.diorama.web;

import dev.diorama.Projection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The web map's pictures on disk: one folder per dimension, one per zoom level, a JPEG per tile.
 * Zoom 0 is the map's own tiles, 512 pixels for 128 blocks across; each level out halves that,
 * one tile made from the four under it. A tile with nothing drawn has no file.
 */
final class WebTiles {
	/** JPEG quality: about 59 KB a full tile with colour at full resolution (Hermitcraft's average). */
	static final float QUALITY = 0.85F;
	/** What the page shows where nothing is drawn; tiles blend their edges into it. */
	static final int BACKGROUND = 0x15171C;
	static final int SIZE = Projection.TILE_PIXELS;

	private WebTiles() {
	}

	static Path file(Path dimension, int zoom, int u, int v) {
		return dimension.resolve(Integer.toString(zoom)).resolve(u + "_" + v + ".jpg");
	}

	/**
	 * Writes a tile from the tracer's pixels (0xAABBGGRR, alpha how much of the pixel is known),
	 * or deletes it if none are known. Returns the bytes written, 0 for none.
	 */
	static long write(Path file, int[] abgr) throws IOException {
		BufferedImage image = image(abgr);
		return image != null ? writeJpeg(file, image) : delete(file);
	}

	/** The same tile as a JPEG in memory, for a map that keeps no pictures of its own; null if no pixel is known. */
	static byte @Nullable [] jpeg(int[] abgr) throws IOException {
		BufferedImage image = image(abgr);
		if (image == null) {
			return null;
		}
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(1 << 16);
		encode(bytes, image);
		return bytes.toByteArray();
	}

	/** The tracer's pixels over the background, or null if none are known. */
	private static @Nullable BufferedImage image(int[] abgr) {
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		boolean any = false;
		int[] row = new int[SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int p = abgr[y * SIZE + x];
				int a = p >>> 24;
				any |= a != 0;
				row[x] = over(p & 0xFF, p >> 8 & 0xFF, p >> 16 & 0xFF, a);
			}
			image.setRGB(0, y, SIZE, 1, row, 0, SIZE);
		}
		return any ? image : null;
	}

	/** A pixel's colour over the background, as 0xRRGGBB. */
	private static int over(int r, int g, int b, int a) {
		int br = BACKGROUND >> 16 & 0xFF;
		int bg = BACKGROUND >> 8 & 0xFF;
		int bb = BACKGROUND & 0xFF;
		return (br + (r - br) * a / 255) << 16 | (bg + (g - bg) * a / 255) << 8 | (bb + (b - bb) * a / 255);
	}

	/**
	 * Makes a zoomed-out tile from the four under it, (0, 0), (1, 0), (0, 1), (1, 1), each pixel
	 * the average of four; deletes it when none of them exists. Returns the bytes written.
	 */
	static long shrink(Path out, Path @Nullable [] children) throws IOException {
		BufferedImage image = null;
		int half = SIZE / 2;
		int[] row = new int[SIZE * 2];
		int[] shrunk = new int[half];
		for (int i = 0; i < 4; i++) {
			Path child = children[i];
			if (child == null || !Files.exists(child)) {
				continue;
			}
			BufferedImage read = ImageIO.read(child.toFile());
			if (read == null) {
				continue;
			}
			if (image == null) {
				image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
				int[] fill = new int[SIZE];
				java.util.Arrays.fill(fill, BACKGROUND);
				for (int y = 0; y < SIZE; y++) {
					image.setRGB(0, y, SIZE, 1, fill, 0, SIZE);
				}
			}
			int ox = (i & 1) * half;
			int oy = (i >> 1) * half;
			for (int y = 0; y < half; y++) {
				read.getRGB(0, 2 * y, SIZE, 2, row, 0, SIZE);
				for (int x = 0; x < half; x++) {
					int a = row[2 * x];
					int b = row[2 * x + 1];
					int c = row[SIZE + 2 * x];
					int d = row[SIZE + 2 * x + 1];
					int r = ((a >> 16 & 0xFF) + (b >> 16 & 0xFF) + (c >> 16 & 0xFF) + (d >> 16 & 0xFF) + 2) >> 2;
					int g = ((a >> 8 & 0xFF) + (b >> 8 & 0xFF) + (c >> 8 & 0xFF) + (d >> 8 & 0xFF) + 2) >> 2;
					int bl = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF) + 2) >> 2;
					shrunk[x] = r << 16 | g << 8 | bl;
				}
				image.setRGB(ox, oy + y, half, 1, shrunk, 0, half);
			}
		}
		return image == null ? delete(out) : writeJpeg(out, image);
	}

	private static long delete(Path file) throws IOException {
		Files.deleteIfExists(file);
		return 0;
	}

	/** Writes beside the file, then moves it in, so a browser never gets half a picture. */
	private static long writeJpeg(Path file, BufferedImage image) throws IOException {
		Files.createDirectories(file.getParent());
		Path part = file.resolveSibling(file.getFileName() + ".part");
		try (OutputStream raw = Files.newOutputStream(part)) {
			encode(raw, image);
		}
		long size = Files.size(part);
		Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return size;
	}

	private static void encode(OutputStream raw, BufferedImage image) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IOException("this Java has no JPEG writer");
		}
		ImageWriter writer = writers.next();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(raw)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(QUALITY);
			// Colour at every pixel (4:4:4): JPEG's default keeps it for every other pixel each way,
			// which smears the edges between blocks of different colour.
			IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
			Node tree = metadata.getAsTree("javax_imageio_jpeg_image_1.0");
			NodeList components = ((Element) tree).getElementsByTagName("componentSpec");
			for (int i = 0; i < components.getLength(); i++) {
				Element component = (Element) components.item(i);
				component.setAttribute("HsamplingFactor", "1");
				component.setAttribute("VsamplingFactor", "1");
			}
			metadata.setFromTree("javax_imageio_jpeg_image_1.0", tree);
			writer.write(null, new IIOImage(image, null, metadata), param);
		} finally {
			writer.dispose();
		}
	}
}
