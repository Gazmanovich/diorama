package dev.diorama.data;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes an RGBA PNG one row at a time, so an image far bigger than memory can be saved. Each row
 * gets the filter that suits it best, the way image editors pick, which roughly halves the size of
 * a map next to no filtering.
 */
public final class PngWriter implements AutoCloseable {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	/** Compressed data is cut into IDAT chunks of this size. */
	private static final int CHUNK = 1 << 18;

	private final DataOutputStream out;
	private final int width;
	private final Deflater deflater = new Deflater(6);
	private final IdatStream idat = new IdatStream();
	private final DeflaterOutputStream deflate;
	private byte[] previous;
	private byte[] current;
	private final byte[][] filtered = new byte[5][];
	private int rows;
	private final int height;

	public PngWriter(OutputStream file, int width, int height) throws IOException {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Empty image: " + width + "x" + height);
		}
		this.width = width;
		this.height = height;
		this.out = new DataOutputStream(new BufferedOutputStream(file, 1 << 16));
		this.out.write(SIGNATURE);
		ByteArrayOutputStream header = new ByteArrayOutputStream();
		DataOutputStream h = new DataOutputStream(header);
		h.writeInt(width);
		h.writeInt(height);
		h.writeByte(8); // bits per channel
		h.writeByte(6); // RGBA
		h.writeByte(0);
		h.writeByte(0);
		h.writeByte(0);
		this.chunk("IHDR", header.toByteArray(), header.size());
		this.deflate = new DeflaterOutputStream(this.idat, this.deflater, 1 << 16);
		this.previous = new byte[width * 4];
		this.current = new byte[width * 4];
		for (int i = 0; i < this.filtered.length; i++) {
			this.filtered[i] = new byte[width * 4];
		}
	}

	/** Adds a text chunk, like the program that made the image. Only before the first row. */
	public void text(String key, String value) throws IOException {
		if (this.rows > 0) {
			throw new IllegalStateException("Text goes before the pixels");
		}
		byte[] k = key.getBytes(StandardCharsets.ISO_8859_1);
		byte[] v = value.getBytes(StandardCharsets.ISO_8859_1);
		byte[] data = new byte[k.length + 1 + v.length];
		System.arraycopy(k, 0, data, 0, k.length);
		System.arraycopy(v, 0, data, k.length + 1, v.length);
		this.chunk("tEXt", data, data.length);
	}

	/** The next row: {@code width} ABGR pixels from {@code offset}, the order tiles keep them in. */
	public void row(int[] abgr, int offset) throws IOException {
		if (this.rows >= this.height) {
			throw new IllegalStateException("Image is already full");
		}
		byte[] row = this.current;
		for (int x = 0; x < this.width; x++) {
			int p = abgr[offset + x];
			int i = x * 4;
			row[i] = (byte) p;
			row[i + 1] = (byte) (p >> 8);
			row[i + 2] = (byte) (p >> 16);
			row[i + 3] = (byte) (p >>> 24);
		}
		int best = this.filter();
		this.deflate.write(best);
		this.deflate.write(this.filtered[best]);
		this.current = this.previous;
		this.previous = row;
		this.rows++;
	}

	/**
	 * Fills {@link #filtered} with the row under each filter and returns the one whose bytes, read
	 * as signed, sum smallest: the usual guess at which compresses best.
	 */
	private int filter() {
		byte[] row = this.current;
		byte[] up = this.previous;
		long[] scores = new long[5];
		for (int i = 0; i < row.length; i++) {
			int x = row[i] & 0xFF;
			int a = i >= 4 ? row[i - 4] & 0xFF : 0;
			int b = up[i] & 0xFF;
			int c = i >= 4 ? up[i - 4] & 0xFF : 0;
			byte none = (byte) x;
			byte sub = (byte) (x - a);
			byte upF = (byte) (x - b);
			byte avg = (byte) (x - ((a + b) >> 1));
			byte paeth = (byte) (x - paeth(a, b, c));
			this.filtered[0][i] = none;
			this.filtered[1][i] = sub;
			this.filtered[2][i] = upF;
			this.filtered[3][i] = avg;
			this.filtered[4][i] = paeth;
			scores[0] += Math.abs(none);
			scores[1] += Math.abs(sub);
			scores[2] += Math.abs(upF);
			scores[3] += Math.abs(avg);
			scores[4] += Math.abs(paeth);
		}
		int best = 0;
		for (int f = 1; f < 5; f++) {
			if (scores[f] < scores[best]) {
				best = f;
			}
		}
		return best;
	}

	private static int paeth(int a, int b, int c) {
		int p = a + b - c;
		int pa = Math.abs(p - a);
		int pb = Math.abs(p - b);
		int pc = Math.abs(p - c);
		if (pa <= pb && pa <= pc) {
			return a;
		}
		return pb <= pc ? b : c;
	}

	@Override
	public void close() throws IOException {
		try {
			if (this.rows != this.height) {
				throw new IOException("Image ended after " + this.rows + " of " + this.height + " rows");
			}
			this.deflate.finish();
			this.idat.flushChunk();
			this.chunk("IEND", new byte[0], 0);
			this.out.flush();
		} finally {
			this.deflater.end();
			this.out.close();
		}
	}

	private void chunk(String type, byte[] data, int length) throws IOException {
		byte[] name = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(name);
		crc.update(data, 0, length);
		this.out.writeInt(length);
		this.out.write(name);
		this.out.write(data, 0, length);
		this.out.writeInt((int) crc.getValue());
	}

	/** Collects compressed bytes and writes them out as IDAT chunks. */
	private final class IdatStream extends OutputStream {
		private final byte[] buffer = new byte[CHUNK];
		private int used;

		@Override
		public void write(int b) throws IOException {
			if (this.used == this.buffer.length) {
				this.flushChunk();
			}
			this.buffer[this.used++] = (byte) b;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			while (len > 0) {
				if (this.used == this.buffer.length) {
					this.flushChunk();
				}
				int n = Math.min(len, this.buffer.length - this.used);
				System.arraycopy(b, off, this.buffer, this.used, n);
				this.used += n;
				off += n;
				len -= n;
			}
		}

		void flushChunk() throws IOException {
			if (this.used > 0) {
				PngWriter.this.chunk("IDAT", this.buffer, this.used);
				this.used = 0;
			}
		}
	}
}
