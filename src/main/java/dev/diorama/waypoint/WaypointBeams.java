package dev.diorama.waypoint;

import dev.diorama.Settings;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Beams standing on waypoints in the world, like a beacon's: a pin floating on the screen is hard
 * to place in depth, and a column that narrows with distance shows where to walk. They're drawn on
 * the HUD under the pins, each one the column's outline projected through the game camera, so like
 * the pins they show through fog and the dark, and past the render distance. Unlike the pins they
 * are hidden by blocks in front of them, as a beacon's beam is: only the stretches the camera has
 * a clear line to are drawn ({@link #look}). All of a frame's beams go to the GPU as one draw.
 */
public final class WaypointBeams {
	/** How far above its waypoint a beam reaches, in blocks. */
	static final int HEIGHT = 256;
	/** Its top this many blocks fade out. */
	private static final int TIP = 64;
	/**
	 * Widths in blocks: the beam, and the dark edge around it that keeps a pale beam readable against
	 * clouds and snow, as the pins' outlines do. A beacon's beam is 0.4 wide.
	 */
	private static final float CORE = 0.4F;
	private static final float EDGE = 0.8F;
	/** The least each is drawn at, in screen pixels, so a beam a thousand blocks off is still a line. */
	private static final float CORE_MIN = 2;
	private static final float EDGE_MIN = 5;
	private static final float CORE_ALPHA = 0.9F;
	private static final float EDGE_ALPHA = 0.45F;
	/** A beam fades out as the player walks up to it, between these distances over the ground: it has done its job. */
	private static final double GONE = 3;
	private static final double FULL = 12;
	/** Beams dim with distance, down to {@link #FAR_ALPHA} of their strength, so a horizon full of them stays calm. */
	private static final double DIM_FROM = 256;
	private static final double DIM_TO = 2048;
	private static final float FAR_ALPHA = 0.7F;
	/** How far past the screen's edges a beam is still drawn, in GUI pixels: room for its width. */
	private static final float MARGIN = 96;
	/** Nearer the camera's plane than this a point can't be projected; the same as {@link WorldProjection#project}. */
	private static final float NEAR = 0.05F;
	/** The texture's edge: soft sides across, and clear to solid down it for the beam's tip. */
	private static final int TEXTURE = 8;
	private static final float[] ACROSS = {0, 0.4F, 1, 1, 1, 1, 0.4F, 0};
	/** How much wider than a beam its quad is, for those soft sides. */
	private static final float SOFT = 1.6F;
	private static final float[] DOWN = {0, 1, 1, 1, 1, 1, 1, 1};
	/** Where down the texture a beam is clear, the middle of its first row, and where it's solid, the middle of its second. */
	private static final float CLEAR_V = 0.5F / TEXTURE;
	private static final float SOLID_V = 1.5F / TEXTURE;

	private static @Nullable MapTexture texture;

	/** Blocks a frame may look at for what hides its beams, a few beams' worth; see {@link #add}. */
	private static final int BLOCKS = 4000;
	/** A beam's visible stretches are worked out in parts this many to a block of its height. */
	private static final int PER_BLOCK = 4;
	private static final int PARTS = HEIGHT * PER_BLOCK;
	private static final float[] CLEAR = new float[0];
	/** Where {@link #blocking} keeps how much of a block's face its shape covers, seen along x and along z, and how much hides a beam. */
	private static final int ALONG_X = 2;
	private static final int ALONG_Z = 3;
	private static final float COVERS = 0.9F;
	/** The most blocks one beam may take to look at. */
	private static final int LOOK_MOST = 12000;
	/** How long what was seen of a beam is kept, in milliseconds, with the camera moving and still. */
	private static final long AGAIN_MOVING = 50;
	private static final long AGAIN_STILL = 500;

	private final Map<UUID, Sight> sights = new HashMap<>();
	private final boolean[] hidden = new boolean[PARTS];
	private final Map<BlockState, float[]> blocking = new IdentityHashMap<>();
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	/** The world of the frame being drawn; null between frames, so a world left isn't kept. */
	private @Nullable ClientLevel level;
	private WeakReference<ClientLevel> seenIn = new WeakReference<>(null);
	private Vec3 eye = Vec3.ZERO;
	private float width;
	private float height;
	private float z;
	private long frame;
	private long now;
	private int blocks;
	private double reach;
	/** This beam's place among the frame's, the place the last frame stopped looking at, and where this frame did. */
	private int index;
	private int resume;
	private int next;
	private final Vector4f base = new Vector4f();
	private final Vector4f top = new Vector4f();
	private float[] xyz = new float[12 * 32];
	private float[] uvs = new float[8 * 32];
	private int[] colors = new int[32];
	private int quads;

	/** Whether a waypoint gets a beam under the setting. */
	static boolean has(Waypoint waypoint, Settings.Beams beams) {
		return switch (beams) {
			case OFF -> false;
			case TEMPORARY -> waypoint.kind == Waypoint.Kind.TEMPORARY || waypoint.kind == Waypoint.Kind.DEATH;
			case ALL -> true;
		};
	}

	/** Starts a frame's beams, seen from {@code eye} in a GUI of {@code width} by {@code height}. */
	void begin(ClientLevel level, Vec3 eye, float width, float height, float z) {
		this.quads = 0;
		this.level = level;
		if (this.seenIn.get() != level) {
			// Another world or dimension: what was seen in the last one says nothing here.
			this.seenIn = new WeakReference<>(level);
			this.sights.clear();
		}
		this.eye = eye;
		this.width = width;
		this.height = height;
		this.z = z;
		this.frame++;
		this.now = System.nanoTime() / 1_000_000;
		this.blocks = BLOCKS;
		this.index = 0;
		this.resume = this.next;
		this.next = 0;
		this.reach = Minecraft.getInstance().options.renderDistance().get() * 16 + 16;
		if (this.frame % 1200 == 0) {
			this.sights.values().removeIf(sight -> this.frame - sight.used > 1200);
		}
	}

	/**
	 * Adds the beam of a waypoint: the stretches of it the camera has a clear line to. Nothing is
	 * added when it's off screen or the player is standing at it.
	 */
	void add(Waypoint waypoint) {
		int index = this.index++;
		Vec3 eye = this.eye;
		double dx = waypoint.x + 0.5 - eye.x;
		double dy = waypoint.y + 1.0 - eye.y;
		double dz = waypoint.z + 0.5 - eye.z;
		double ground = Math.sqrt(dx * dx + dz * dz);
		if (ground <= GONE) {
			return;
		}
		Sight sight = this.sights.get(waypoint.id);
		// Each frame looks at so many blocks, and starts no new beam once it has (so it goes over by
		// at most one beam's worth); the rest keep what they last saw, and the next frame goes on from
		// where this one stopped, so with hundreds of beams each is looked at in turn.
		if ((sight == null || index >= this.resume && sight.stale(eye, this.now)) && this.blocks > 0) {
			if (sight == null) {
				sight = new Sight();
				this.sights.put(waypoint.id, sight);
			}
			this.look(sight, dx, dy, dz, ground);
			sight.at = eye;
			sight.time = this.now;
			if (this.blocks <= 0) {
				this.next = index + 1;
			}
		}
		if (sight == null) {
			// Not looked at yet: it shows once a frame has time for it.
			return;
		}
		sight.used = this.frame;
		if (sight.count == 0) {
			return;
		}
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		float near = (float) Math.min(1, (ground - GONE) / (FULL - GONE));
		float far = 1 - (1 - FAR_ALPHA) * (float) Math.max(0, Math.min(1, (distance - DIM_FROM) / (DIM_TO - DIM_FROM)));
		// A colour saved with no alpha at all is meant opaque, as the pin draws it.
		int saved = waypoint.color >>> 24 == 0 ? 0xFF : waypoint.color >>> 24;
		float strength = near * (2 - near) * far * saved / 255.0F;
		int rgb = waypoint.color & 0xFFFFFF;
		int edge = alpha(EDGE_ALPHA * strength) << 24 | edgeColor(rgb);
		int core = alpha(CORE_ALPHA * strength) << 24 | coreColor(rgb);
		int body = HEIGHT - TIP;
		for (int i = 0; i < sight.count; i++) {
			float from = sight.spans[i * 2];
			float to = sight.spans[i * 2 + 1];
			// The fading tip is its own piece. A quad's texture runs evenly along it on screen, and seen
			// from below nearly all of a beam's length on screen is its lowest few blocks: one piece would
			// spread the fade down over what the player sees of it.
			if (from < body) {
				this.piece(dx, dy, dz, from, Math.min(to, body), SOLID_V, SOLID_V, this.width, this.height, this.z, edge, core);
			}
			if (to > body) {
				float start = Math.max(from, body);
				this.piece(dx, dy, dz, start, to, tipV(start), tipV(to), this.width, this.height, this.z, edge, core);
			}
		}
	}

	/** The texture row for a height in the beam's tip. */
	private static float tipV(float height) {
		return SOLID_V + (CLEAR_V - SOLID_V) * (height - (HEIGHT - TIP)) / TIP;
	}

	/**
	 * Works out which stretches of a beam the camera has a clear line to, as a vanilla beacon's beam
	 * shows only where no block stands in front of it. Every block between the camera and the beam
	 * that could be in the way is looked at: the columns of blocks the line over the ground from one
	 * to the other crosses, and in each the blocks between the lines of sight to the beam's foot and
	 * to its top. Each block that blocks the view hides the stretch of beam behind it.
	 */
	private void look(Sight sight, double dx, double dy, double dz, double ground) {
		Arrays.fill(this.hidden, false);
		int hiddenParts = 0;
		// Past the loaded chunks there's nothing to hide a beam.
		double limit = Math.min(ground, this.reach);
		double ux = dx / ground;
		double uz = dz / ground;
		// How steeply the lines of sight to the beam's foot and top rise.
		double low = dy / ground;
		double high = (dy + HEIGHT) / ground;
		int x = (int) Math.floor(this.eye.x);
		int z = (int) Math.floor(this.eye.z);
		int stepX = ux > 0 ? 1 : -1;
		int stepZ = uz > 0 ? 1 : -1;
		double perX = ux == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / ux);
		double perZ = uz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / uz);
		double nextX = ux == 0 ? Double.POSITIVE_INFINITY : (ux > 0 ? x + 1 - this.eye.x : this.eye.x - x) * perX;
		double nextZ = uz == 0 ? Double.POSITIVE_INFINITY : (uz > 0 ? z + 1 - this.eye.z : this.eye.z - z) * perZ;
		// Which way the line runs over the ground decides which face of a block it meets.
		int face = Math.abs(ux) >= Math.abs(uz) ? ALONG_X : ALONG_Z;
		int floor = this.level.getMinY();
		int budget = this.blocks - LOOK_MOST;
		double entered = 0;
		// Past its budget a look stops, and the beam shows for what wasn't looked at: a camera high over
		// a waypoint a long way down, with tall glass towers between.
		while (entered < limit && hiddenParts < PARTS && this.blocks > budget) {
			// The line crosses this column between these distances over the ground.
			double near = Math.max(entered, 0.001);
			double far = Math.max(Math.min(Math.min(nextX, nextZ), limit), near);
			// Up to the block over the highest that stops movement: snow lies on top of that one.
			int top = Math.min(this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), (int) Math.floor(this.eye.y + Math.max(near * high, far * high)));
			int bottom = Math.max(floor, (int) Math.floor(this.eye.y + Math.min(near * low, far * low)));
			for (int y = top; y >= bottom && hiddenParts < PARTS; y--) {
				this.blocks--;
				BlockState state = this.level.getBlockState(this.pos.set(x, y, z));
				if (state.isAir()) {
					continue;
				}
				float[] blocking = this.blocking(state);
				if (blocking == CLEAR || blocking[face] < COVERS) {
					continue;
				}
				// Seen from the camera, the block covers the lines of sight between these two slopes,
				// and so this much of the beam.
				double under = y + blocking[0] - this.eye.y;
				double over = y + blocking[1] - this.eye.y;
				int from = Math.max(0, (int) Math.floor((Math.min(under / near, under / far) * ground - dy) * PER_BLOCK));
				int to = Math.min(PARTS, (int) Math.ceil((Math.max(over / near, over / far) * ground - dy) * PER_BLOCK));
				for (int i = from; i < to; i++) {
					if (!this.hidden[i]) {
						this.hidden[i] = true;
						hiddenParts++;
					}
				}
			}
			if (nextX < nextZ) {
				x += stepX;
				entered = nextX;
				nextX += perX;
			} else {
				z += stepZ;
				entered = nextZ;
				nextZ += perZ;
			}
		}
		sight.count = 0;
		int start = -1;
		for (int i = 0; i <= PARTS; i++) {
			boolean shown = i < PARTS && !this.hidden[i];
			if (shown && start < 0) {
				start = i;
			} else if (!shown && start >= 0) {
				sight.add((float) start / PER_BLOCK, (float) i / PER_BLOCK);
				start = -1;
			}
		}
	}

	/**
	 * What of a block hides what's behind it: from where to where up its height (0 to 1), and how much
	 * of that its shape covers seen along x and along z; or {@link #CLEAR}. By the shape that blocks
	 * the view, so glass, water and plants are clear. A door covers its whole face seen from the front
	 * and a sliver from the side; a fence, a quarter. Only what covers nearly all of it hides a beam,
	 * so a block is all or nothing each way: stairs seen from the side are let through.
	 */
	private float[] blocking(BlockState state) {
		boolean fixed = !state.getBlock().hasDynamicShape();
		float[] blocking = fixed ? this.blocking.get(state) : null;
		if (blocking == null) {
			blocking = CLEAR;
			try {
				VoxelShape shape = state.getVisualShape(this.level, this.pos, CollisionContext.empty());
				// A fence's shape is a solid panel where it joins its neighbours, though it's rails to look at.
				if (!shape.isEmpty() && !state.is(BlockTags.FENCES) && !state.is(BlockTags.FENCE_GATES)) {
					double from = Math.max(0, shape.min(Direction.Axis.Y));
					double to = Math.min(1, shape.max(Direction.Axis.Y));
					double[] covered = new double[2];
					shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> {
						double tall = Math.min(to, y2) - Math.max(from, y1);
						if (tall > 0) {
							covered[0] += (z2 - z1) * tall;
							covered[1] += (x2 - x1) * tall;
						}
					});
					if (to > from && Math.max(covered[0], covered[1]) >= COVERS * (to - from)) {
						blocking = new float[] {(float) from, (float) to, (float) Math.min(1, covered[0] / (to - from)), (float) Math.min(1, covered[1] / (to - from))};
					}
				}
			} catch (RuntimeException e) {
				// Another mod's block that can't say its shape here: let the beam through.
			}
			if (fixed) {
				// A piston's moving block or a shulker box changes shape without changing state: those are asked each time.
				this.blocking.put(state, blocking);
			}
		}
		return blocking;
	}

	/** What was last seen of one waypoint's beam: the heights above its foot between which it shows, in pairs. */
	private static final class Sight {
		float[] spans = new float[8];
		int count;
		/** Where the camera was when it was last looked at, and when, in milliseconds. */
		Vec3 at = Vec3.ZERO;
		long time;
		/** The frame it was last drawn in. */
		long used;

		/**
		 * Whether to look again: the camera has moved since, or a while has passed in which a block
		 * may have been placed or broken. Looking reads hundreds of blocks, so not on every frame.
		 */
		boolean stale(Vec3 eye, long now) {
			long age = now - this.time;
			return age >= AGAIN_STILL || age >= AGAIN_MOVING && eye.distanceToSqr(this.at) > 0.01;
		}

		void add(float from, float to) {
			if (to - from < 0.05F) {
				return;
			}
			if (this.count * 2 == this.spans.length) {
				this.spans = Arrays.copyOf(this.spans, this.count * 4);
			}
			this.spans[this.count * 2] = from;
			this.spans[this.count * 2 + 1] = to;
			this.count++;
		}
	}

	/** The beam between two heights above its foot, with the texture row at each end. */
	private void piece(double dx, double dy, double dz, double from, double to, float baseV, float topV, float width, float height, float z, int edge, int core) {
		if (!WorldProjection.clip(dx, dy + from, dz, this.base) || !WorldProjection.clip(dx, dy + to, dz, this.top)) {
			return;
		}
		// Cut the beam where it passes behind the camera, as the game cuts anything it draws.
		if (this.base.w < NEAR && this.top.w < NEAR) {
			return;
		}
		if (this.base.w < NEAR) {
			float t = (NEAR - this.base.w) / (this.top.w - this.base.w);
			this.base.lerp(this.top, t);
			baseV += (topV - baseV) * t;
		} else if (this.top.w < NEAR) {
			float t = (NEAR - this.top.w) / (this.base.w - this.top.w);
			this.top.lerp(this.base, t);
			topV += (baseV - topV) * t;
		}
		float ax = (this.base.x / this.base.w + 1) / 2 * width;
		float ay = (1 - this.base.y / this.base.w) / 2 * height;
		float bx = (this.top.x / this.top.w + 1) / 2 * width;
		float by = (1 - this.top.y / this.top.w) / 2 * height;
		// The part of it on screen, as how far along from the top (0) to the base (1): an end cut at
		// the camera's plane lies thousands of pixels off, and is as many wide.
		float s0 = 0;
		float s1 = 1;
		for (int side = 0; side < 4; side++) {
			// Inside is where p + d * s is at least zero: past the left edge, the right, the top, the bottom.
			float p = side == 0 ? bx + MARGIN : side == 1 ? width + MARGIN - bx : side == 2 ? by + MARGIN : height + MARGIN - by;
			float d = side == 0 ? ax - bx : side == 1 ? bx - ax : side == 2 ? ay - by : by - ay;
			if (d == 0) {
				if (p < 0) {
					return;
				}
			} else if (d > 0) {
				s0 = Math.max(s0, -p / d);
			} else {
				s1 = Math.min(s1, -p / d);
			}
		}
		float length = (float) Math.hypot(ax - bx, ay - by);
		if (s0 >= s1 || length * (s1 - s0) < 0.5F) {
			// Off the screen, or seen end on from right under or over it.
			return;
		}
		// Along the beam on screen from its top to its base, and to the left of that.
		float lx = -(ay - by) / length;
		float ly = (ax - bx) / length;
		// GUI pixels a block covers at each end: one block at the camera's plane distance 1 is perBlock.
		// A column's width on screen falls off as one over its distance, which is a straight taper along it.
		float perBlock = WorldProjection.focal() * height / 2;
		float topScale = perBlock / this.top.w;
		float baseScale = perBlock / this.base.w;
		float gui = (float) Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		float first = topScale + (baseScale - topScale) * s0;
		float last = topScale + (baseScale - topScale) * s1;
		float taper = Math.max(first, last) / Math.min(first, last);
		// A quad much wider at one end than the other bends its texture, so a beam seen from below
		// goes in several, each drawn as two halves that meet on the beam's middle line.
		int steps = taper < 1.02F ? 1 : taper < 2 ? 2 : taper < 6 ? 4 : 8;
		for (int layer = 0; layer < 2; layer++) {
			float blocks = layer == 0 ? EDGE : CORE;
			float least = (layer == 0 ? EDGE_MIN : CORE_MIN) / gui;
			int argb = layer == 0 ? edge : core;
			for (int i = 0; i < steps; i++) {
				float sa = s0 + (s1 - s0) * i / steps;
				float sb = s0 + (s1 - s0) * (i + 1) / steps;
				float tx = bx + (ax - bx) * sa;
				float ty = by + (ay - by) * sa;
				float ux = bx + (ax - bx) * sb;
				float uy = by + (ay - by) * sb;
				float th = Math.max(least, blocks * (topScale + (baseScale - topScale) * sa)) / 2 * SOFT;
				float uh = Math.max(least, blocks * (topScale + (baseScale - topScale) * sb)) / 2 * SOFT;
				float tv = topV + (baseV - topV) * sa;
				float uv = topV + (baseV - topV) * sb;
				// Counter-clockwise from the top left, the order MapCanvas draws.
				if (steps == 1) {
					this.quad(tx + lx * th, ty + ly * th, ux + lx * uh, uy + ly * uh, ux - lx * uh, uy - ly * uh, tx - lx * th, ty - ly * th, 0, 1, tv, uv, z, argb);
				} else {
					this.quad(tx + lx * th, ty + ly * th, ux + lx * uh, uy + ly * uh, ux, uy, tx, ty, 0, 0.5F, tv, uv, z, argb);
					this.quad(tx, ty, ux, uy, ux - lx * uh, uy - ly * uh, tx - lx * th, ty - ly * th, 0.5F, 1, tv, uv, z, argb);
				}
			}
		}
	}

	/** Ends a frame that {@link #begin} started, drawn or not. */
	void end() {
		this.level = null;
	}

	/** Draws what was added since {@link #begin}. */
	void draw(GuiGraphicsExtractor graphics) {
		if (this.quads == 0) {
			return;
		}
		if (texture == null) {
			texture = MapTexture.create("diorama waypoint beam", TEXTURE, 1);
			int[] abgr = new int[TEXTURE * TEXTURE];
			for (int y = 0; y < TEXTURE; y++) {
				for (int x = 0; x < TEXTURE; x++) {
					abgr[y * TEXTURE + x] = Math.round(ACROSS[x] * DOWN[y] * 255) << 24 | 0xFFFFFF;
				}
			}
			texture.write(0, 0, 0, TEXTURE, abgr);
		}
		MapCanvas.of(graphics).rects(texture, Sampling.CLAMP_LINEAR, this.xyz, this.uvs, this.colors, this.quads);
	}

	private static int alpha(float alpha) {
		return Math.max(0, Math.min(255, Math.round(alpha * 255)));
	}

	/** The colour a beam is drawn in: the waypoint's, a quarter of the way to white, so it reads as light. */
	public static int coreColor(int rgb) {
		int r = rgb >> 16 & 0xFF;
		int g = rgb >> 8 & 0xFF;
		int b = rgb & 0xFF;
		return r + (255 - r) / 4 << 16 | g + (255 - g) / 4 << 8 | b + (255 - b) / 4;
	}

	/** The dark shade of the colour at a beam's edges. */
	private static int edgeColor(int rgb) {
		return (rgb >> 16 & 0xFF) / 4 << 16 | (rgb >> 8 & 0xFF) / 4 << 8 | (rgb & 0xFF) / 4;
	}

	/** A quad by its corners, top left, base left, base right, top right, with the texture's columns at its sides and rows at its ends. */
	private void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float leftU, float rightU, float topV, float baseV, float z, int argb) {
		if (this.quads == this.colors.length) {
			int n = this.quads * 2;
			this.xyz = Arrays.copyOf(this.xyz, n * 12);
			this.uvs = Arrays.copyOf(this.uvs, n * 8);
			this.colors = Arrays.copyOf(this.colors, n);
		}
		int p = this.quads * 12;
		int t = this.quads * 8;
		this.corner(p, t, x0, y0, z, leftU, topV);
		this.corner(p + 3, t + 2, x1, y1, z, leftU, baseV);
		this.corner(p + 6, t + 4, x2, y2, z, rightU, baseV);
		this.corner(p + 9, t + 6, x3, y3, z, rightU, topV);
		this.colors[this.quads++] = argb;
	}

	private void corner(int p, int t, float x, float y, float z, float u, float v) {
		this.xyz[p] = x;
		this.xyz[p + 1] = y;
		this.xyz[p + 2] = z;
		this.uvs[t] = u;
		this.uvs[t + 1] = v;
	}
}
