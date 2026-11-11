package dev.diorama.waypoint;

import dev.diorama.Settings;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Beams standing on waypoints in the world, like a beacon's: a pin floating on the screen is hard
 * to place in depth, and a column that narrows with distance shows where to walk. They're drawn on
 * the HUD under the pins, each one the column's outline projected through the game camera, so like
 * the pins they show through terrain, fog and the dark, and past the render distance. All of a
 * frame's beams go to the GPU as one draw.
 */
public final class WaypointBeams {
	/** How far above its waypoint a beam reaches, in blocks. */
	static final int HEIGHT = 256;
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
	/** Nearer the camera's plane than this a point can't be projected; the same as {@link WorldProjection#project}. */
	private static final float NEAR = 0.05F;
	/** The texture's edge: soft sides across, and the fade at the top of the beam down it. */
	private static final int TEXTURE = 8;
	private static final float[] ACROSS = {0, 0.4F, 1, 1, 1, 1, 0.4F, 0};
	/** How much wider than a beam its quad is, for those soft sides. */
	private static final float SOFT = 1.6F;
	private static final float[] DOWN = {0, 0.5F, 1, 1, 1, 1, 1, 1};

	private static @Nullable MapTexture texture;

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

	void begin() {
		this.quads = 0;
	}

	/**
	 * Adds the beam of a waypoint, seen from {@code eye} in a GUI of {@code width} by {@code height}.
	 * Nothing is added when it's off screen or the player is standing at it.
	 */
	void add(Waypoint waypoint, Vec3 eye, float width, float height, float z) {
		double dx = waypoint.x + 0.5 - eye.x;
		double dy = waypoint.y + 1.0 - eye.y;
		double dz = waypoint.z + 0.5 - eye.z;
		double ground = Math.sqrt(dx * dx + dz * dz);
		if (ground <= GONE) {
			return;
		}
		if (!WorldProjection.clip(dx, dy, dz, this.base) || !WorldProjection.clip(dx, dy + HEIGHT, dz, this.top)) {
			return;
		}
		// Cut the beam where it passes behind the camera, as the game cuts anything it draws.
		float baseV = 1;
		float topV = 0;
		if (this.base.w < NEAR && this.top.w < NEAR) {
			return;
		}
		if (this.base.w < NEAR) {
			float t = (NEAR - this.base.w) / (this.top.w - this.base.w);
			this.base.lerp(this.top, t);
			baseV = 1 - t;
		} else if (this.top.w < NEAR) {
			float t = (NEAR - this.top.w) / (this.base.w - this.top.w);
			this.top.lerp(this.base, t);
			topV = t;
		}
		float ax = (this.base.x / this.base.w + 1) / 2 * width;
		float ay = (1 - this.base.y / this.base.w) / 2 * height;
		float bx = (this.top.x / this.top.w + 1) / 2 * width;
		float by = (1 - this.top.y / this.top.w) / 2 * height;
		// One block at the camera's plane distance 1 is this many GUI pixels.
		float perBlock = WorldProjection.focal() * height / 2;
		float gui = (float) Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		float reach = Math.min(width, Math.max(EDGE_MIN / gui, EDGE * perBlock / Math.min(this.base.w, this.top.w))) / 2 * SOFT;
		if (Math.max(ax, bx) < -reach || Math.min(ax, bx) > width + reach || Math.max(ay, by) < -reach || Math.min(ay, by) > height + reach) {
			return;
		}
		float length = (float) Math.hypot(ax - bx, ay - by);
		if (length < 0.5F) {
			// Seen end on, from right under or over it.
			return;
		}
		// Along the beam on screen from its top to its base, and to the left of that.
		float ex = (ax - bx) / length;
		float ey = (ay - by) / length;
		float lx = -ey;
		float ly = ex;

		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		float near = (float) Math.min(1, (ground - GONE) / (FULL - GONE));
		float far = 1 - (1 - FAR_ALPHA) * (float) Math.max(0, Math.min(1, (distance - DIM_FROM) / (DIM_TO - DIM_FROM)));
		// A colour saved with no alpha at all is meant opaque, as the pin draws it.
		int saved = waypoint.color >>> 24 == 0 ? 0xFF : waypoint.color >>> 24;
		float strength = near * (2 - near) * far * saved / 255.0F;
		int rgb = waypoint.color & 0xFFFFFF;
		// A column's width on screen falls off as one over its distance, which is a straight taper.
		this.quad(ax, ay, bx, by, lx, ly, half(EDGE, EDGE_MIN, perBlock, this.base.w, gui, width), half(EDGE, EDGE_MIN, perBlock, this.top.w, gui, width),
			baseV, topV, z, alpha(EDGE_ALPHA * strength) << 24 | edgeColor(rgb));
		this.quad(ax, ay, bx, by, lx, ly, half(CORE, CORE_MIN, perBlock, this.base.w, gui, width), half(CORE, CORE_MIN, perBlock, this.top.w, gui, width),
			baseV, topV, z, alpha(CORE_ALPHA * strength) << 24 | coreColor(rgb));
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

	/** Half of what a column {@code blocks} wide is drawn at, in GUI pixels, at clip distance {@code w}. */
	private static float half(float blocks, float minPixels, float perBlock, float w, float gui, float width) {
		return Math.min(width, Math.max(minPixels / gui, blocks * perBlock / w)) / 2 * SOFT;
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

	private void quad(float ax, float ay, float bx, float by, float lx, float ly, float baseHalf, float topHalf, float baseV, float topV, float z, int argb) {
		if (this.quads == this.colors.length) {
			int n = this.quads * 2;
			this.xyz = Arrays.copyOf(this.xyz, n * 12);
			this.uvs = Arrays.copyOf(this.uvs, n * 8);
			this.colors = Arrays.copyOf(this.colors, n);
		}
		int p = this.quads * 12;
		int t = this.quads * 8;
		// Counter-clockwise from the top left, the order MapCanvas draws: top left, base left, base right, top right.
		this.corner(p, t, bx + lx * topHalf, by + ly * topHalf, z, 0, topV);
		this.corner(p + 3, t + 2, ax + lx * baseHalf, ay + ly * baseHalf, z, 0, baseV);
		this.corner(p + 6, t + 4, ax - lx * baseHalf, ay - ly * baseHalf, z, 1, baseV);
		this.corner(p + 9, t + 6, bx - lx * topHalf, by - ly * topHalf, z, 1, topV);
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
