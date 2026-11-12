package dev.diorama.waypoint;

import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Maps;
import dev.diorama.Projection;
import dev.diorama.ServerRules;
import dev.diorama.bake.BiomeTints;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * A small piece of the real map around the player, cut out like a diorama: the map's own view on
 * top, and under its south edge the cut face of the ground there, block by block (grass, dirt,
 * stone, whatever is really there), lit the way the map lights a south face. A dark outline and a
 * shadow to the east set it off from the blurred world behind. The empty waypoint list stands a pin
 * on it to show what a waypoint is.
 *
 * <p>Where the map has nothing around the player yet (a world just made), the top is plain grass
 * lit like the map's, until the map's tiles there have baked.
 *
 * <p>The waypoint editor shows a {@link Place} instead: the waypoint's own spot, from the map of
 * its dimension, even one the player isn't in. Ground the map has never seen is fog or a roof's
 * smoke, as on the map. The cut face shows blocks only where the game has them loaded; elsewhere
 * it is {@link #UNKNOWN}, a plain face that claims no blocks.
 */
final class MapPiece {
	/** GUI pixels per block across, close to what the full map shows by default. */
	private static final double ZOOM = 1.5;
	/** The cut face's texture: one texel per block across, {@link #TEXELS_PER_ROW} per block down. */
	private static final int FACE_TEXTURE = 64;
	/** A block on the face is one GUI pixel tall; four texels let a grass edge be thinner than that. */
	private static final int TEXELS_PER_ROW = 4;
	private static final int OUTLINE = 0xD2101418;
	private static final int SHADOW = 0x40000000;
	/** Air behind the cut: a cave, seen into. */
	private static final int CAVE = 0xFF1C1E24;
	/** The cut face of ground the game hasn't loaded: no blocks to show, so none are made up. */
	private static final int UNKNOWN = 0xFF3B3A39;
	/** The sun's direction across the ground, as the map's baker has it (west, a little south). */
	private static final double SUN_AZIMUTH_Z = 0.479;
	/** How much of the sky an open south face sees. */
	private static final float FACE_SKY = 0.5F;
	/**
	 * Brightness spread between neighbouring blocks on the face. The top shows each block's
	 * texture through the map's own detail; the face is one colour per block, so without this a
	 * layer of stone reads as a flat band.
	 */
	private static final float FACE_VARIATION = 0.12F;
	/** How often the face is read from the world again, for chunks arriving and blocks changing. */
	private static final long FACE_REFRESH_MS = 1000;

	private static final float[] SRGB_TO_LINEAR = new float[256];

	static {
		for (int i = 0; i < 256; i++) {
			double v = i / 255.0;
			SRGB_TO_LINEAR[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
		}
	}

	private final int width;
	private final int depth;
	private final int side;
	private final MapViewport viewport = new MapViewport();
	private final int[] facePixels = new int[FACE_TEXTURE * FACE_TEXTURE];
	private @Nullable MapTexture face;
	/** What the face texture was last built from, and when. */
	private @Nullable FaceKey faceKey;
	private long faceBuilt;

	/** Another dimension's saved map, opened for a place shown there. */
	private @Nullable SavedMap saved;

	private record FaceKey(ClientLevel level, int x0, int columns, long edge, MapStyle style, @Nullable BlockLooks looks, boolean madeUp, boolean fogged) {
	}

	/** A dimension's saved map, and what it was opened for: a change to either opens it again. */
	private record SavedMap(Path folder, MapStyle style, MapLevel map) {
	}

	/** A waypoint's spot, in its own dimension's coordinates, for the piece to stand under it. */
	record Place(String dimension, int x, int y, int z) {
	}

	/**
	 * @param width the top's width in GUI pixels
	 * @param depth the top's height in GUI pixels
	 * @param side the cut face's height in GUI pixels, one block each (at most 16)
	 */
	MapPiece(int width, int depth, int side) {
		this.width = width;
		this.depth = depth;
		this.side = Math.min(side, FACE_TEXTURE / TEXELS_PER_ROW);
	}

	/** Width with the outline. */
	int outerWidth() {
		return this.width + 2;
	}

	/** Height with the outline. */
	int outerHeight() {
		return this.depth + this.side + 2;
	}

	/** Where a pin standing on the player's spot has its tip, from the piece's top left: the middle of the top. */
	int tipX() {
		return 1 + this.width / 2;
	}

	int tipY() {
		return 1 + this.depth / 2;
	}

	/** Draws the piece around the player, with its outline's top left at (x, y); the shadow reaches 3 further east. */
	void draw(GuiGraphicsExtractor graphics, int x, int y) {
		this.draw(graphics, x, y, null);
	}

	/** Same, around {@code place} instead if there is one, which can be in another dimension. */
	void draw(GuiGraphicsExtractor graphics, int x, int y, @Nullable Place place) {
		graphics.fill(x + 3, y - 1, x + this.outerWidth() + 3, y + this.outerHeight() - 1, SHADOW);
		graphics.fill(x, y, x + this.outerWidth(), y + this.outerHeight(), OUTLINE);
		int left = x + 1;
		int top = y + 1;
		Minecraft mc = Minecraft.getInstance();
		Entity viewer = MapSession.viewer();
		ClientLevel level = mc.level;
		MapStyle style = MapStyle.get();
		BlockLooks looks = BlockLooks.get();
		if (viewer == null || level == null || looks == null) {
			graphics.fill(left, top, left + this.width, top + this.depth + this.side, 0xFF6E8B4A);
			return;
		}
		MapSession session = MapSession.get();
		// In the player's dimension the live map has the place, and the loaded world its blocks.
		boolean here = place == null || place.dimension().equals(level.dimension().identifier().toString());
		MapLevel map = !here ? this.savedMap(place.dimension()) : session != null ? session.map() : null;
		MapViewport.View view = place == null
			? new MapViewport.View(left, top, this.width, this.depth, Projection.u(viewer.getX(), viewer.getZ()), Projection.v(viewer.getX(), viewer.getY(), viewer.getZ()), ZOOM, TileKey.SURFACE)
			// Where the map stands a waypoint's pin: the middle of its block's top.
			: new MapViewport.View(left, top, this.width, this.depth, Projection.u(place.x() + 0.5, place.z() + 0.5), Projection.v(place.x() + 0.5, place.y(), place.z() + 0.5), ZOOM, TileKey.SURFACE);
		BlockPos ground = place == null ? viewer.blockPosition() : new BlockPos(place.x(), place.y(), place.z());
		if (map != null && (place != null || MapViewport.showsAnything(map, view))) {
			// Under a waypoint, ground the map has never seen is fog, as on the map.
			this.viewport.draw(graphics, map, view);
		} else {
			if (map != null) {
				// Asks for the tiles, so the real map takes over once they've baked.
				MapViewport.ready(map, view);
			}
			graphics.fill(left, top, left + this.width, top + this.depth, argb(grassTop(looks, level, ground, style)));
		}

		int faceTop = top + this.depth;
		if (!here) {
			// Nothing of another dimension is loaded.
			graphics.fill(left, faceTop, left + this.width, faceTop + this.side - 1, UNKNOWN);
			graphics.fill(left, faceTop + this.side - 1, left + this.width, faceTop + this.side, closed(UNKNOWN));
			return;
		}
		int x0 = (int) Math.floor(view.minU());
		int columns = Math.min(FACE_TEXTURE, (int) Math.floor(view.minU() + this.width / ZOOM) - x0 + 1);
		double edge = view.minV() + this.depth / ZOOM;
		// Under a roof with fog of war, the map hides ground the player hasn't seen, and so does the face.
		MapLevel fogged = place != null && map != null && map.roofless() && map.fogOfWar() ? map : null;
		MapTexture texture = this.face(level, looks, style, x0, columns, edge, ground, place == null, fogged);
		graphics.enableScissor(left, faceTop, left + this.width, faceTop + this.side);
		MapCanvas.of(graphics).rect(texture, Sampling.CLAMP_CRISP, view.screenX(x0), faceTop, view.screenX(x0 + columns), faceTop + this.side,
			0, 0, columns / (float) FACE_TEXTURE, this.side * TEXELS_PER_ROW / (float) FACE_TEXTURE, 0xFFFFFFFF);
		graphics.disableScissor();
	}

	/**
	 * The cut face's texture, built again when the piece has moved or the map's look changed.
	 * Where nothing is loaded a column is grass, dirt and stone if {@code madeUp}, standing in for
	 * the player's own ground until it arrives, and {@link #UNKNOWN} otherwise. So is a column whose
	 * ground the player hasn't seen on a {@code fogged} map.
	 */
	private MapTexture face(ClientLevel level, BlockLooks looks, MapStyle style, int x0, int columns, double edge, BlockPos player, boolean madeUp,
		@Nullable MapLevel fogged) {
		if (this.face == null) {
			this.face = MapTexture.create("diorama map piece", FACE_TEXTURE, 1);
		}
		FaceKey key = new FaceKey(level, x0, columns, Math.round(edge * 8), style, looks, madeUp, fogged != null);
		long now = System.currentTimeMillis();
		if (key.equals(this.faceKey) && now - this.faceBuilt < FACE_REFRESH_MS) {
			return this.face;
		}
		this.faceKey = key;
		this.faceBuilt = now;
		java.util.Arrays.fill(this.facePixels, 0);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int c = 0; c < columns; c++) {
			int bx = x0 + c;
			int[] hit = surface(level, looks, bx, edge);
			if (hit != null && fogged != null && !fogged.isSeen(bx, hit[0], hit[1])) {
				hit = null;
			}
			if (hit == null && !madeUp) {
				for (int row = 0; row < this.side; row++) {
					int plain = abgr(row == this.side - 1 ? closed(UNKNOWN) : UNKNOWN);
					for (int t = 0; t < TEXELS_PER_ROW; t++) {
						this.facePixels[(row * TEXELS_PER_ROW + t) * FACE_TEXTURE + c] = plain;
					}
				}
				continue;
			}
			for (int row = 0; row < this.side; row++) {
				// Where the map shows nothing, an ordinary column: grass, a few blocks of dirt, then stone.
				BlockState state;
				if (hit != null) {
					pos.set(facing(bx, hit[1], true), hit[0] - row, facing(bx, hit[1], false));
					// As the map sees it: the side facing the camera is the one shown.
					state = Projection.facing().turned(level.getBlockState(pos));
				} else {
					int pz = Projection.facing().blockZ(player.getX(), player.getZ());
					pos.set(facing(bx, pz, true), player.getY() - 1 - row, facing(bx, pz, false));
					state = (row == 0 ? Blocks.GRASS_BLOCK : row <= 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState();
				}
				for (int t = 0; t < TEXELS_PER_ROW; t++) {
					int abgr = this.texel(level, looks, style, state, pos, row, t);
					this.facePixels[(row * TEXELS_PER_ROW + t) * FACE_TEXTURE + c] = abgr;
				}
			}
		}
		this.face.write(0, 0, 0, FACE_TEXTURE, this.facePixels);
		return this.face;
	}

	/** One texel of the face, as ABGR. */
	private int texel(ClientLevel level, BlockLooks looks, MapStyle style, BlockState state, BlockPos pos, int row, int t) {
		int id = looks.ensure(Block.BLOCK_STATE_REGISTRY.getId(state));
		if (id < 0 || id >= looks.kind.length || looks.kind[id] == BlockLooks.AIR || looks.kind[id] == BlockLooks.PLANT) {
			return abgr(CAVE);
		}
		// The top row shows the edge of the block's top above its side; a grass block's green
		// fringe reaches further down, over dirt.
		boolean grass = looks.tint[id] == BlockLooks.TINT_GRASS;
		boolean topEdge = row == 0 && t < (grass ? 2 : 1);
		float[] rgb = albedo(looks, level, id, pos, topEdge, row == 0 && grass && !topEdge);
		float nudge = 1 + Math.max(style.blockVariation(), FACE_VARIATION) * blockNoise(pos.getX(), pos.getY(), pos.getZ());
		float[] light = faceLight(style, pos.getY());
		// The last GUI row closes the cut, like the map's outlines.
		float close = row == this.side - 1 ? 0.75F : 1;
		return toAbgr(grade(style, rgb[0] * light[0] * nudge * close, rgb[1] * light[1] * nudge * close, rgb[2] * light[2] * nudge * close));
	}

	/**
	 * The block the map shows at the piece's bottom edge in column {@code x}: the first one the
	 * camera's ray through that row meets, skipping trees and plants. Returns {y, z}, or null where
	 * there's nothing loaded.
	 */
	private static int @Nullable [] surface(ClientLevel level, BlockLooks looks, int x, double v) {
		double sin = Projection.sin();
		double cos = Projection.cos();
		int minY = level.getMinY();
		boolean ceiling = level.dimensionType().hasCeiling();
		int start;
		if (ceiling) {
			start = Math.min(level.getMaxY(), minY + level.dimensionType().logicalHeight() - 1);
		} else {
			// Start a little above the ground the heightmap finds along the ray, to save walking
			// down from the sky; a few rounds settle where the ray meets it.
			int z = (int) Math.floor(v / sin);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, facing(x, z, true), facing(x, z, false));
			for (int i = 0; i < 4; i++) {
				z = (int) Math.floor((v + y * cos) / sin);
				y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, facing(x, z, true), facing(x, z, false));
			}
			if (y <= minY) {
				return null;
			}
			start = Math.min(level.getMaxY(), y + 16);
		}
		// Under a roof the map starts below it, so walk out of the roof first.
		boolean underRoof = !ceiling;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = start; y >= minY; y--) {
			// Through this layer the ray runs north, from the height of its top to its bottom.
			int zIn = (int) Math.floor((v + (y + 1) * cos) / sin);
			int zOut = (int) Math.floor((v + y * cos) / sin);
			for (int z = zIn; z >= zOut; z--) {
				BlockState state = level.getBlockState(pos.set(facing(x, z, true), y, facing(x, z, false)));
				if (!underRoof) {
					if (state.isAir()) {
						underRoof = true;
					}
					continue;
				}
				if (ground(looks, state)) {
					return new int[] {y, z};
				}
			}
		}
		return null;
	}

	/** The world's x, or z, of a column of the map. */
	private static int facing(int x, int z, boolean worldX) {
		return worldX ? Projection.facing().worldBlockX(x, z) : Projection.facing().worldBlockZ(x, z);
	}

	private static boolean ground(BlockLooks looks, BlockState state) {
		int id = looks.ensure(Block.BLOCK_STATE_REGISTRY.getId(state));
		if (id < 0 || id >= looks.kind.length) {
			return false;
		}
		byte kind = looks.kind[id];
		return kind != BlockLooks.AIR && kind != BlockLooks.PLANT && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS);
	}

	/**
	 * A block's colour in linear RGB, as the map's baker mixes it: the untinted part and the part
	 * the biome tints. {@code top} takes the top's colour; {@code bare} only the untinted side
	 * (a grass block's dirt under its green fringe).
	 */
	private static float[] albedo(BlockLooks looks, ClientLevel level, int id, BlockPos pos, boolean top, boolean bare) {
		int base = top ? looks.top[id] : looks.side[id];
		int tinted = top ? looks.topTinted[id] : looks.sideTinted[id];
		float w = bare ? 0 : top ? looks.topTintWeight[id] : looks.sideTintWeight[id];
		int tint = switch (looks.tint[id]) {
			case BlockLooks.TINT_GRASS -> BiomeColors.getAverageGrassColor(level, pos);
			case BlockLooks.TINT_FOLIAGE, BlockLooks.TINT_DRY_FOLIAGE -> BiomeColors.getAverageFoliageColor(level, pos);
			case BlockLooks.TINT_WATER -> level.getBlockTint(pos, BiomeTints.water());
			default -> looks.constantTint[id] < 0 ? 0xFFFFFF : looks.constantTint[id];
		};
		float bw = 1 - w;
		return new float[] {
			SRGB_TO_LINEAR[(base >> 16) & 0xFF] * bw + SRGB_TO_LINEAR[(tinted >> 16) & 0xFF] * SRGB_TO_LINEAR[(tint >> 16) & 0xFF] * w,
			SRGB_TO_LINEAR[(base >> 8) & 0xFF] * bw + SRGB_TO_LINEAR[(tinted >> 8) & 0xFF] * SRGB_TO_LINEAR[(tint >> 8) & 0xFF] * w,
			SRGB_TO_LINEAR[base & 0xFF] * bw + SRGB_TO_LINEAR[tinted & 0xFF] * SRGB_TO_LINEAR[tint & 0xFF] * w
		};
	}

	/**
	 * The light on an open south face at height y, as the baker works it out: the sky's share,
	 * the sun at its slant, the height shade, and the darkening Minecraft gives block sides.
	 */
	private static float[] faceLight(MapStyle style, int y) {
		double elevation = Math.toRadians(style.sunElevation());
		double lambert = Math.max(0, Math.cos(elevation) * SUN_AZIMUTH_Z) / Math.sin(elevation);
		float direct = (float) (style.sunStrength() * lambert);
		float ambient = style.ambient() * (1 - style.skyOcclusion() * (1 - (0.15F + 0.85F * FACE_SKY)));
		float shade = (1 + style.heightShade() * (float) Math.max(-1, Math.min(1, (y + 0.5 - 64) / 64))) * 0.82F;
		float[] sky = style.shadowTint();
		float[] sun = style.sunTint();
		return new float[] {(ambient * sky[0] + direct * sun[0]) * shade, (ambient * sky[1] + direct * sun[1]) * shade, (ambient * sky[2] + direct * sun[2]) * shade};
	}

	/** Grass in the open, lit as the map lights a top face in the sun: the top where there's no map yet. */
	private static int grassTop(BlockLooks looks, ClientLevel level, BlockPos pos, MapStyle style) {
		int id = looks.lookId(Blocks.GRASS_BLOCK.defaultBlockState());
		float[] rgb = albedo(looks, level, id, pos, true, false);
		float ambient = style.ambient();
		float shade = 1 + style.heightShade() * (float) Math.max(-1, Math.min(1, (pos.getY() - 64) / 64.0));
		float[] sky = style.shadowTint();
		float[] sun = style.sunTint();
		float[] light = new float[3];
		for (int i = 0; i < 3; i++) {
			light[i] = (ambient * sky[i] + style.sunStrength() * sun[i]) * shade;
		}
		return toAbgr(grade(style, rgb[0] * light[0], rgb[1] * light[1], rgb[2] * light[2]));
	}

	/** The style's saturation and exposure, then back to sRGB: what the baker does last. */
	private static float[] grade(MapStyle style, float r, float g, float b) {
		float l = 0.2126F * r + 0.7152F * g + 0.0722F * b;
		float s = style.saturation();
		float e = style.exposure();
		return new float[] {srgb((l + (r - l) * s) * e), srgb((l + (g - l) * s) * e), srgb((l + (b - l) * s) * e)};
	}

	private static float srgb(float linear) {
		double v = Math.max(0, Math.min(1, linear));
		return (float) (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055);
	}

	private static int toAbgr(float[] rgb) {
		return 0xFF000000 | Math.round(rgb[2] * 255) << 16 | Math.round(rgb[1] * 255) << 8 | Math.round(rgb[0] * 255);
	}

	/** A colour as the face's last row has it, darker, closing the cut. */
	private static int closed(int argb) {
		int r = Math.round(((argb >> 16) & 0xFF) * 0.75F);
		int g = Math.round(((argb >> 8) & 0xFF) * 0.75F);
		int b = Math.round((argb & 0xFF) * 0.75F);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/**
	 * The saved map in use in another dimension, opened again when the player switches it or the
	 * look changes, as the full map's dimension button does.
	 */
	private @Nullable MapLevel savedMap(String dimension) {
		Maps maps = Maps.get();
		// Nor where the map is off: what was mapped there before stays out of sight.
		if (maps == null || ServerRules.mapOff(dimension)) {
			this.closeSaved();
			return null;
		}
		Path folder = maps.tileFolder(dimension, maps.active(dimension));
		if (this.saved == null || !this.saved.folder().equals(folder) || this.saved.style() != MapStyle.get()) {
			this.closeSaved();
			this.saved = new SavedMap(folder, MapStyle.get(), MapLevel.saved(folder));
		}
		this.saved.map().setFogOfWar(ServerRules.fogOfWar(dimension.equals("minecraft:the_nether"), this.saved.map().roofless()));
		return this.saved.map();
	}

	/** Takes in the saved map's loaded tiles; the session does this for the live map. Call every tick. */
	void tick() {
		if (this.saved != null) {
			this.saved.map().update();
		}
	}

	private void closeSaved() {
		if (this.saved != null) {
			this.saved.map().close();
			this.saved = null;
		}
	}

	/** ARGB to ABGR and back: the same swap. */
	private static int abgr(int argb) {
		return (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
	}

	private static int argb(int abgr) {
		return abgr(abgr);
	}

	/** -1..1, fixed per block position: the noise the baker nudges block brightness by. */
	private static float blockNoise(int x, int y, int z) {
		int h = x * 73_856_093 ^ y * 19_349_663 ^ z * 83_492_791;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		h ^= h >>> 15;
		return ((h & 0xFFFF) / 32767.5F) - 1;
	}

	/** Frees the GPU textures. The piece can still draw afterwards; it makes them again. */
	void close() {
		this.viewport.close();
		this.closeSaved();
		if (this.face != null) {
			this.face.close();
			this.face = null;
		}
		this.faceKey = null;
	}
}
