package dev.diorama.waypoint;

import dev.diorama.Settings;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

	/** Lines of sight a frame may try, about three beams' worth; see {@link #add}. */
	private static final int RAYS = 64;
	/** How long what was seen of a beam is kept, in milliseconds, with the camera moving and still. */
	private static final long AGAIN_MOVING = 50;
	private static final long AGAIN_STILL = 500;
	/** Points up a beam the line of sight is tried to, and how many times each change between them is halved. */
	private static final int LOOKS = 12;
	private static final int NARROWING = 6;

	private final Map<UUID, Sight> sights = new HashMap<>();
	private ClientLevel level;
	private Entity viewer;
	private Vec3 eye = Vec3.ZERO;
	private float width;
	private float height;
	private float z;
	private long frame;
	private long now;
	private int rays;
	private double reach;
	/** This beam's place among the frame's, the place the last frame's rays ran out at, and where this frame's did. */
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

	/** Starts a frame's beams, seen from {@code eye} by {@code viewer} in a GUI of {@code width} by {@code height}. */
	void begin(ClientLevel level, Entity viewer, Vec3 eye, float width, float height, float z) {
		this.quads = 0;
		this.level = level;
		this.viewer = viewer;
		this.eye = eye;
		this.width = width;
		this.height = height;
		this.z = z;
		this.frame++;
		this.now = System.nanoTime() / 1_000_000;
		this.rays = RAYS;
		this.index = 0;
		this.resume = this.next;
		this.next = 0;
		// Past the loaded chunks there's nothing to hide a beam, and nothing for a ray to do.
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
		// Each frame has rays for so many beams; the rest keep what they last saw, and the next frame
		// goes on from where this one stopped, so with hundreds of beams each is looked at in turn.
		if ((sight == null || index >= this.resume && sight.stale(eye, this.now)) && this.rays > 0) {
			if (sight == null) {
				sight = new Sight();
				this.sights.put(waypoint.id, sight);
			}
			this.look(sight, dx, dy, dz, ground);
			sight.at = eye;
			sight.time = this.now;
			if (this.rays <= 0) {
				this.next = index + 1;
			}
		}
		if (sight == null) {
			// Not looked at yet: it shows once a frame has rays for it.
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
	 * shows only where no block stands in front of it. The line is tried to points evenly spread up
	 * the beam by the angle they're seen at, and each change from hidden to seen is then narrowed down.
	 */
	private void look(Sight sight, double dx, double dy, double dz, double ground) {
		double low = Math.atan2(dy, ground);
		double high = Math.atan2(dy + HEIGHT, ground);
		sight.count = 0;
		boolean seen = false;
		float start = 0;
		double before = low;
		for (int i = 0; i <= LOOKS; i++) {
			double angle = low + (high - low) * i / LOOKS;
			boolean clear = this.clear(dx, ground * Math.tan(angle), dz);
			if (clear != seen) {
				// Somewhere between the last point and this one the beam comes into view or leaves it.
				double hidden = clear ? before : angle;
				double shown = clear ? angle : before;
				for (int step = 0; i > 0 && step < NARROWING; step++) {
					double middle = (hidden + shown) / 2;
					if (this.clear(dx, ground * Math.tan(middle), dz)) {
						shown = middle;
					} else {
						hidden = middle;
					}
				}
				float edge = (float) Math.max(0, Math.min(HEIGHT, ground * Math.tan(i > 0 ? shown : low) - dy));
				if (clear) {
					start = edge;
				} else {
					sight.add(start, edge);
				}
				seen = clear;
			}
			before = angle;
		}
		if (seen) {
			sight.add(start, HEIGHT);
		}
	}

	/** Whether nothing stands between the camera and the point at (dx, dy, dz) from it, as far as chunks are loaded. */
	private boolean clear(double dx, double dy, double dz) {
		this.rays--;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double scale = length > this.reach ? this.reach / length : 1;
		Vec3 to = new Vec3(this.eye.x + dx * scale, this.eye.y + dy * scale, this.eye.z + dz * scale);
		// What blocks the view, not what blocks the way: glass and water let a beam through.
		return this.level.clip(new ClipContext(this.eye, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, this.viewer)).getType() == HitResult.Type.MISS;
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
		 * may have been placed or broken. Looking costs a dozen or two lines of sight through the
		 * world, so not on every frame.
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
