package dev.diorama;

import dev.diorama.bake.BlockLooks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The cave map's depth gauge on the right edge: the real column of blocks under the player, cut
 * open from the ground down to the bottom of the world, with a band for the slice the map shows and
 * a pointer at the player's height. Dragging the band only previews a height; {@link MapScreen}
 * moves the slice when the mouse is let go, so the map doesn't prepare every slice on the way.
 */
final class DepthGauge {
	/** The column's width, and its left edge's distance from the screen's right edge, in GUI px. */
	static final int WIDTH = 12;
	private static final int FROM_RIGHT = 28;
	/** Top of the gauge (the slice up button sits right above it) and its gap to the screen's bottom. */
	private static final int TOP = 44;
	private static final int FROM_BOTTOM = 34;
	/** Room between the gauge's top and the highest thing on it: the grass's top face and plate. */
	private static final int HEAD = 10;
	/** The column is drawn in strips across, each block a little lighter or darker in each. */
	private static final int STRIPS = 4;
	/** How many blocks deep the slice band is: one PgUp or PgDn. */
	static final int STEP = 4;
	private static final int PLATE = 0xC0101418;
	private static final int OPEN = 0xFF15181E;
	private static final int BAND = 0xFF3FA9F5;
	/** Re-read the column this often even if the player stays put, in case blocks there change. */
	private static final long REREAD_TICKS = 20;

	/** The column as last read: where, when, its top block, and its colours per block and strip. */
	private @Nullable BlockPos readAt;
	private long readTick;
	private int columnTop;
	private int columnBottom;
	private int topFace;
	private int[] colours = new int[0];

	/** This frame's layout, in GUI px and world heights. */
	private int x0;
	private int top;
	private int bottom;
	private double lo;
	private double hi;

	/** The middle of the slice band as drawn last, for tests. */
	private float bandY;

	/** While the band is dragged: the slice under the mouse, and the height range frozen. */
	private boolean dragging;
	private int preview;

	boolean dragging() {
		return this.dragging;
	}

	/** Lays the gauge out for this frame; call before drawing or hit tests. */
	void layout(ClientLevel level, BlockPos feet, int layer, int screenWidth, int screenHeight) {
		this.read(level, feet);
		this.x0 = screenWidth - FROM_RIGHT;
		this.top = TOP;
		this.bottom = Math.max(TOP + HEAD + 40, screenHeight - FROM_BOTTOM);
		if (!this.dragging) {
			this.lo = level.getMinY();
			this.hi = Math.max(Math.max(this.columnTop + 1, feet.getY()), layer);
		}
	}

	/** Where the slice up button goes: right above the gauge. */
	int upButtonX() {
		return this.x0 - 2;
	}

	int upButtonY() {
		return this.top - 14;
	}

	int downButtonY() {
		return this.bottom + 5;
	}

	/** Whether a point is on the gauge's column (where a press grabs the band). */
	boolean contains(double x, double y) {
		return x >= this.x0 - 3 && x < this.x0 + WIDTH + 6 && y >= this.top + HEAD - 6 && y < this.bottom + 2;
	}

	/** Starts dragging the band from a press at screen height {@code y}. */
	void grab(ClientLevel level, double y) {
		this.dragging = true;
		this.preview = this.layerAt(level, y);
	}

	void drag(ClientLevel level, double y) {
		if (this.dragging) {
			this.preview = this.layerAt(level, y);
		}
	}

	/** Lets go of the band: the slice it was left at. */
	int release() {
		this.dragging = false;
		return this.preview;
	}

	void cancel() {
		this.dragging = false;
	}

	/** The middle of the slice band as drawn last, {x, y} in GUI coordinates. */
	float[] band() {
		return new float[] {this.x0 + WIDTH / 2.0F, this.bandY};
	}

	/** Screen height of world height {@code wy}. */
	private double screenY(double wy) {
		double span = this.bottom - (this.top + HEAD);
		return this.top + HEAD + (this.hi - wy) / Math.max(1, this.hi - this.lo) * span;
	}

	/** The slice whose band is centred nearest screen height {@code y}. */
	private int layerAt(ClientLevel level, double y) {
		double span = this.bottom - (this.top + HEAD);
		double wy = this.hi - (y - this.top - HEAD) / span * (this.hi - this.lo);
		int layer = (int) Math.round((wy + STEP / 2.0) / STEP) * STEP;
		int max = Math.min(level.getMaxY() + 1, (int) Math.ceil(this.hi / STEP) * STEP);
		return Math.max(level.getMinY() + STEP, Math.min(max, layer));
	}

	/**
	 * Draws the gauge. Labels whose height falls between {@code avoidTop} and {@code avoidBottom}
	 * end at {@code avoidX} instead, to stay clear of the buttons beside the gauge.
	 */
	void draw(GuiGraphicsExtractor graphics, Font font, double feetY, int layer, int avoidX, int avoidTop, int avoidBottom) {
		int x1 = this.x0 + WIDTH;
		int ground = (int) Math.round(this.screenY(this.columnTop + 1));
		// A backing plate, then the column as a block: its top face, the cut front, a side in shade.
		graphics.fill(this.x0 - 2, this.top + HEAD - 6, x1 + 6, this.bottom + 2, PLATE);
		graphics.fill(this.x0, ground - 4, x1, ground, this.topFace);
		int stripWidth = WIDTH / STRIPS;
		int runStart = ground;
		int runBlock = this.blockAt(ground);
		for (int row = ground + 1; row <= this.bottom; row++) {
			int block = row < this.bottom ? this.blockAt(row) : Integer.MIN_VALUE;
			if (block == runBlock) {
				continue;
			}
			int index = runBlock - this.columnBottom;
			if (index >= 0 && index < this.colours.length / STRIPS) {
				for (int s = 0; s < STRIPS; s++) {
					graphics.fill(this.x0 + s * stripWidth, runStart, this.x0 + (s + 1) * stripWidth, row, this.colours[index * STRIPS + s]);
				}
			}
			runStart = row;
			runBlock = block;
		}
		graphics.fill(x1, ground - 2, x1 + 4, this.bottom, 0x90000000);

		// The slice: a band as deep as one step, from the cut down, outlined, its height beside it.
		int shown = this.dragging ? this.preview : layer;
		int sy0 = (int) Math.round(this.screenY(shown));
		int sy1 = Math.max(sy0 + 3, (int) Math.round(this.screenY(shown - STEP)));
		graphics.fill(this.x0 - 3, sy0 - 1, x1 + 3, sy1 + 1, 0xFFFFFFFF);
		graphics.fill(this.x0 - 2, sy0, x1 + 2, sy1, BAND);
		int mid = (sy0 + sy1) / 2;
		this.bandY = (sy0 + sy1) / 2.0F;
		label(graphics, font, "y=" + shown, mid, this.x0 - 5, 0xFFFFFFFF, avoidX, avoidTop, avoidBottom);

		// Where the player stands: a pointer at their height, its label moved off the slice's.
		int py = (int) Math.round(this.screenY(feetY));
		graphics.fill(this.x0 - 5, py - 2, this.x0 - 4, py + 3, 0xFFFFFFFF);
		graphics.fill(this.x0 - 4, py - 1, this.x0 - 3, py + 2, 0xFFFFFFFF);
		graphics.fill(this.x0 - 3, py, this.x0 - 2, py + 1, 0xFFFFFFFF);
		int ly = Math.abs(py - mid) >= 11 ? py : py <= mid ? mid - 11 : mid + 11;
		label(graphics, font, Component.translatable("diorama.map.you").getString(), ly, this.x0 - 6, 0xFFE0E0E0, avoidX, avoidTop, avoidBottom);
	}

	/** A label on a dark plate, centred on height {@code y}, its plate ending at {@code right}. */
	private static void label(GuiGraphicsExtractor graphics, Font font, String text, int y, int right, int colour, int avoidX, int avoidTop, int avoidBottom) {
		if (y + 5 > avoidTop && y - 5 < avoidBottom) {
			right = Math.min(right, avoidX);
		}
		int w = font.width(text);
		graphics.fill(right - 4 - w, y - 5, right, y + 5, PLATE);
		graphics.text(font, text, right - 2 - w, y - 4, colour, false);
	}

	/** The block height a screen row shows. */
	private int blockAt(int row) {
		double span = this.bottom - (this.top + HEAD);
		return (int) Math.floor(this.hi - (row + 0.5 - this.top - HEAD) / span * (this.hi - this.lo));
	}

	/** Reads the column under the player, again only when they move to another block or it's old. */
	private void read(ClientLevel level, BlockPos feet) {
		long tick = level.getGameTime();
		if (feet.equals(this.readAt) && tick - this.readTick < REREAD_TICKS && tick >= this.readTick) {
			return;
		}
		this.readAt = feet.immutable();
		this.readTick = tick;
		BlockLooks looks = BlockLooks.get();
		int minY = level.getMinY();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// The ground: the first block from the top that isn't air, a plant or leaves.
		int groundY = minY;
		for (int y = level.getMaxY(); y >= minY; y--) {
			BlockState state = level.getBlockState(pos.set(feet.getX(), y, feet.getZ()));
			if (!open(looks, state) && !state.is(BlockTags.LEAVES)) {
				groundY = y;
				break;
			}
		}
		this.columnTop = groundY;
		this.columnBottom = minY;
		int blocks = groundY - minY + 1;
		this.colours = new int[blocks * STRIPS];
		for (int y = minY; y <= groundY; y++) {
			BlockState state = level.getBlockState(pos.set(feet.getX(), y, feet.getZ()));
			int base = open(looks, state) ? OPEN : colour(looks, level, pos, state, false);
			for (int s = 0; s < STRIPS; s++) {
				this.colours[(y - minY) * STRIPS + s] = base == OPEN ? OPEN : vary(base, y, s);
			}
		}
		BlockState topState = level.getBlockState(pos.set(feet.getX(), groundY, feet.getZ()));
		this.topFace = colour(looks, level, pos, topState, true);
	}

	private static boolean open(@Nullable BlockLooks looks, BlockState state) {
		if (state.isAir()) {
			return true;
		}
		if (looks == null) {
			return false;
		}
		byte kind = looks.kind[BlockLooks.id(state)];
		return kind == BlockLooks.AIR || kind == BlockLooks.PLANT;
	}

	/** A block's colour as the map would show its top or a side, tinted for its biome. */
	private static int colour(@Nullable BlockLooks looks, ClientLevel level, BlockPos pos, BlockState state, boolean top) {
		if (looks == null) {
			return 0xFF7F7F7F;
		}
		int id = BlockLooks.id(state);
		int base = top ? looks.top[id] : looks.side[id];
		int tinted = top ? looks.topTinted[id] : looks.sideTinted[id];
		float w = top ? looks.topTintWeight[id] : looks.sideTintWeight[id];
		int tint = switch (looks.tint[id]) {
			case BlockLooks.TINT_GRASS -> BiomeColors.getAverageGrassColor(level, pos);
			case BlockLooks.TINT_FOLIAGE, BlockLooks.TINT_DRY_FOLIAGE -> BiomeColors.getAverageFoliageColor(level, pos);
			case BlockLooks.TINT_WATER -> BiomeColors.getAverageWaterColor(level, pos);
			default -> looks.constantTint[id] < 0 ? 0xFFFFFF : looks.constantTint[id];
		};
		int r = mix(base >> 16, tinted >> 16, tint >> 16, w);
		int g = mix(base >> 8, tinted >> 8, tint >> 8, w);
		int b = mix(base, tinted, tint, w);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int mix(int base, int tinted, int tint, float w) {
		float c = (base & 0xFF) * (1 - w) + (tinted & 0xFF) * (tint & 0xFF) / 255.0F * w;
		return Math.max(0, Math.min(255, Math.round(c)));
	}

	/** A cut face in a little shade, each block and strip a fixed touch lighter or darker. */
	private static int vary(int argb, int y, int strip) {
		int h = y * 0x9E3779B1 + strip * 0x85EBCA6B;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		float f = 0.84F * (1 + ((h & 0xFF) / 255.0F - 0.5F) * 0.12F);
		int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
		int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
		int b = Math.min(255, Math.round((argb & 0xFF) * f));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
