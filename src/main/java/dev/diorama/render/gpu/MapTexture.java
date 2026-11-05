package dev.diorama.render.gpu;

/**
 * A square RGBA texture the mod fills from the CPU: map tiles, the fog mask and noise, icons.
 * Render thread only. How it lives on the GPU depends on the Minecraft version, so callers only
 * see this.
 */
public interface MapTexture extends AutoCloseable {
	/** A texture with {@code levels} mip levels, level 0 being {@code size} x {@code size}. */
	static MapTexture create(String label, int size, int levels) {
		//? if >=1.21.6 {
		return new StateTexture(label, size, levels);
		//?} else if >=1.21.5 {
		/*return new BlazeTexture(label, size, levels);
		*///?} else {
		/*return new GlIdTexture(size, levels);
		*///?}
	}

	/** A texture that is also drawn into, {@code size} x {@code size}, one level: see {@link FogLayer}. */
	static MapTexture createTarget(String label, int size) {
		//? if >=1.21.6 {
		return new StateTexture(label, size, 1, true);
		//?} else if >=1.21.5 {
		/*return new BlazeTexture(label, size, 1);
		*///?} else {
		/*return new GlIdTexture(size, 1);
		*///?}
	}

	/**
	 * Writes {@code edge} x {@code edge} pixels at (x, y) of one mip level, from the start of
	 * {@code abgr}. Each int is one pixel as ABGR, which is RGBA in memory.
	 */
	void write(int level, int x, int y, int edge, int[] abgr);

	@Override
	void close();
}
