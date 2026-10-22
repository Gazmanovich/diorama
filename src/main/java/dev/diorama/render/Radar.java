package dev.diorama.render;

import com.google.common.collect.Iterables;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.TrackedPlayers;
import dev.diorama.render.gpu.MapCanvas;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Other players and mobs on the map. Players show as their skin's face, including players
 * beyond the loaded chunks whose position the server reports (see {@link TrackedPlayers}). Mobs
 * show as their own face (see {@link MobFaces}) framed in a colour by how they treat you, or as a
 * dot of that colour when they have no face icon. Anything well above or below you gets a caret
 * and fades a little, since the map is a top view and can't otherwise show height.
 */
public final class Radar {
	/** Vertical distance, in blocks, past which a caret shows. */
	private static final double CARET_HEIGHT = 3;

	public enum Kind {
		PASSIVE(0xFF62D96B),
		NEUTRAL(0xFFF2C14E),
		HOSTILE(0xFFEF4B4B),
		PLAYER(0xFFFFFFFF);

		final int color;

		Kind(int color) {
			this.color = color;
		}
	}

	/** An entity placed on screen. {@code dy} is its height relative to the viewing player. */
	public record Blip(Entity entity, Kind kind, float x, float y, double dy, float size) {
	}

	private Radar() {
	}

	public static @Nullable Kind kind(Entity entity) {
		if (entity instanceof AbstractClientPlayer) {
			return Kind.PLAYER;
		}
		// Neutral first: endermen and zombified piglins are both, and they only attack when provoked.
		if (entity instanceof NeutralMob) {
			return Kind.NEUTRAL;
		}
		if (entity instanceof Enemy) {
			return Kind.HOSTILE;
		}
		if (entity instanceof Mob) {
			return Kind.PASSIVE;
		}
		return null;
	}

	/** Everything the radar can show inside the view, players last. */
	public static List<Blip> collect(MapViewport.View view, float partialTick, boolean fullscreen) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer self = mc.player;
		List<Blip> blips = new ArrayList<>();
		if (level == null || self == null) {
			return blips;
		}
		double selfY = self.getPosition(partialTick).y;
		Settings settings = Settings.get();
		// Entities farther above or below than the range are left off; otherwise cave mobs crowd
		// the surface view.
		double range = settings.radarRange;
		float margin = 12;
		for (Entity entity : Iterables.concat(level.entitiesForRendering(), TrackedPlayers.unloaded(level))) {
			if (entity == self || !entity.isAlive() || entity.isInvisibleTo(self)) {
				continue;
			}
			Kind kind = kind(entity);
			if (kind == null || !shown(kind, settings)) {
				continue;
			}
			Vec3 pos = entity.getPosition(partialTick);
			if (Math.abs(pos.y - selfY) > range) {
				continue;
			}
			float x = view.screenX(pos.x);
			float y = view.screenY(Projection.v(pos.y, pos.z));
			if (x < view.x() - margin || y < view.y() - margin || x > view.x() + view.width() + margin || y > view.y() + view.height() + margin) {
				continue;
			}
			blips.add(new Blip(entity, kind, x, y, pos.y - selfY, size(kind, view.zoom(), fullscreen)));
		}
		blips.sort(Comparator.comparingInt(b -> b.kind().ordinal()));
		return blips;
	}

	private static boolean shown(Kind kind, Settings settings) {
		return switch (kind) {
			case PLAYER -> settings.radarPlayers;
			case HOSTILE -> settings.radarHostile;
			case NEUTRAL -> settings.radarNeutral;
			case PASSIVE -> settings.radarPassive;
		};
	}

	private static float size(Kind kind, double zoom, boolean fullscreen) {
		Settings settings = Settings.get();
		if (kind == Kind.PLAYER) {
			return settings.playerIconScale / 100.0F * (fullscreen ? (float) Math.max(8, Math.min(16, zoom * 3)) : 7);
		}
		return settings.mobIconScale / 100.0F * (fullscreen ? (float) Math.max(4, Math.min(9, zoom * 2.2)) : 4);
	}

	/**
	 * Draws either the players or everything else. Maps draw mobs, then waypoints, then players,
	 * so a player standing at a waypoint shows on top of its marker and a crowd of mobs doesn't
	 * hide one.
	 */
	public static void draw(GuiGraphicsExtractor graphics, MapViewport.View view, List<Blip> blips, boolean fullscreen, boolean players) {
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		Settings settings = Settings.get();
		boolean names = settings.playerNames == Settings.NameTags.ALWAYS || settings.playerNames == Settings.NameTags.FULLSCREEN && fullscreen;
		for (Blip blip : blips) {
			if ((blip.kind() == Kind.PLAYER) != players) {
				continue;
			}
			int alpha = alphaFor(blip.dy());
			switch (settings.radarStyle) {
				case FRAMED -> framed(graphics, blip, alpha);
				case DOTS -> dot(graphics, blip, alpha);
				default -> pixelHead(graphics, blip, alpha, settings.radarTypeOutline);
			}
			if (names && blip.kind() == Kind.PLAYER) {
				nameTag(graphics, blip, alpha);
			}
			if (Math.abs(blip.dy()) > CARET_HEIGHT) {
				boolean below = blip.dy() < 0;
				float s = blip.size();
				if (settings.radarStyle == Settings.RadarStyle.FRAMED) {
					float caret = Math.max(4, s * 0.7F);
					float cx = blip.x() + s * 0.55F + caret * 0.3F;
					float cy = blip.y() - s * 0.45F;
					MapIcons.caret(graphics, cx, cy, caret + 2, withAlpha(0xE0101418, alpha), below);
					MapIcons.caret(graphics, cx, cy, caret, withAlpha(0xFFFFFFFF, alpha), below);
				} else {
					pixelCaret(graphics, Math.round(blip.x() + s * 0.7F + 1), Math.round(blip.y() - s * 0.5F), below, alpha);
				}
			}
		}
		graphics.disableScissor();
	}

	/** The original look: a player's face on a dark disc, a mob's face in a dark box framed in its kind's colour. */
	private static void framed(GuiGraphicsExtractor graphics, Blip blip, int alpha) {
		float s = blip.size();
		if (blip.kind() == Kind.PLAYER && blip.entity() instanceof AbstractClientPlayer player) {
			MapIcons.dot(graphics, blip.x(), blip.y(), s * 1.55F, withAlpha(0xFF101418, alpha));
			graphics.pose().pushMatrix();
			graphics.pose().translate(blip.x() - s / 2, blip.y() - s / 2);
			graphics.pose().scale(s / 8);
			MapCanvas.of(graphics).playerFace(player, 0, 0, 8, withAlpha(0xFFFFFFFF, alpha));
			graphics.pose().popMatrix();
		} else if (MobFaces.has(blip.entity())) {
			int box = Math.round(s * 1.6F);
			int x0 = Math.round(blip.x() - box / 2.0F);
			int y0 = Math.round(blip.y() - box / 2.0F);
			graphics.fill(x0 - 1, y0 - 1, x0 + box + 1, y0 + box + 1, withAlpha(blip.kind().color, alpha));
			graphics.fill(x0, y0, x0 + box, y0 + box, withAlpha(0xFF101418, alpha));
			if (!MobFaces.draw(graphics, blip.entity(), x0 + box / 2.0F, y0 + box / 2.0F, box - 2, withAlpha(0xFFFFFFFF, alpha))) {
				MapIcons.dot(graphics, blip.x(), blip.y(), s, withAlpha(blip.kind().color, alpha));
			}
		} else {
			MapIcons.dot(graphics, blip.x(), blip.y(), s + 2, withAlpha(0xE0101418, alpha));
			MapIcons.dot(graphics, blip.x(), blip.y(), s, withAlpha(blip.kind().color, alpha));
		}
	}

	/**
	 * The head as pixel art, like a player head item: each texel a whole number of screen pixels,
	 * on the screen's pixel grid, with a one-texel black outline and a drop shadow.
	 */
	private static void pixelHead(GuiGraphicsExtractor graphics, Blip blip, int alpha, boolean typeOutline) {
		float s = blip.size();
		int outline = typeOutline && blip.kind() != Kind.PLAYER ? blip.kind().color : 0xFF000000;
		if (blip.kind() == Kind.PLAYER && blip.entity() instanceof AbstractClientPlayer player) {
			float texel = texel(s, 8);
			pixelFrame(graphics, blip.x(), blip.y(), 8, 8, texel, withAlpha(outline, alpha), alpha);
			MapCanvas.of(graphics).playerFace(player, 0, 0, 8, withAlpha(0xFFFFFFFF, alpha));
			graphics.pose().popMatrix();
		} else if (!MobFaces.drawPixel(graphics, blip.entity(), blip.x(), blip.y(), s * 1.6F, withAlpha(0xFFFFFFFF, alpha), withAlpha(outline, alpha), alpha)) {
			dot(graphics, blip, alpha);
		}
	}

	/**
	 * Screen-pixel-aligned size of one texel for an icon {@code texels} across that should be about
	 * {@code target} GUI pixels: a whole number of screen pixels, at least one.
	 */
	static float texel(float target, int texels) {
		double gui = guiScale();
		long pixels = Math.max(1, Math.round(target * gui / texels));
		return (float) (pixels / gui);
	}

	/** Rounds a GUI coordinate to the nearest screen pixel. */
	static float snap(float value) {
		double gui = guiScale();
		return (float) (Math.round(value * gui) / gui);
	}

	private static double guiScale() {
		return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
	}

	/**
	 * Pushes a pose with one unit per texel and its origin at the icon's top left, centred on
	 * (x, y), and draws the shadow and outline of a w x h icon there. The caller draws the icon at
	 * (0, 0) and pops the pose.
	 */
	static void pixelFrame(GuiGraphicsExtractor graphics, float x, float y, int w, int h, float texel, int outline, int alpha) {
		float x0 = snap(x - w * texel / 2);
		float y0 = snap(y - h * texel / 2);
		graphics.pose().pushMatrix();
		graphics.pose().translate(x0, y0);
		graphics.pose().scale(texel);
		graphics.fill(0, 0, w + 2, h + 2, withAlpha(0x60000000, alpha));
		graphics.fill(-1, -1, w + 1, h + 1, outline);
	}

	/** A square dot in the kind's colour with a black outline, on the GUI pixel grid. */
	private static void dot(GuiGraphicsExtractor graphics, Blip blip, int alpha) {
		int d = Math.max(2, Math.round(blip.size() * (blip.kind() == Kind.PLAYER ? 0.6F : 0.8F)));
		int x0 = Math.round(blip.x() - d / 2.0F);
		int y0 = Math.round(blip.y() - d / 2.0F);
		graphics.fill(x0, y0, x0 + d + 2, y0 + d + 2, withAlpha(0x60000000, alpha));
		graphics.fill(x0 - 1, y0 - 1, x0 + d + 1, y0 + d + 1, withAlpha(0xFF000000, alpha));
		graphics.fill(x0, y0, x0 + d, y0 + d, withAlpha(blip.kind().color, alpha));
		// A one-pixel highlight on the top left, the way Minecraft's GUI bevels its buttons.
		if (d >= 3) {
			int light = withAlpha(0x50FFFFFF, alpha);
			graphics.fill(x0, y0, x0 + d - 1, y0 + 1, light);
			graphics.fill(x0, y0 + 1, x0 + 1, y0 + d - 1, light);
		}
	}

	/** A small pixel arrow, up or down, white with a black outline, its centre at (cx, cy). */
	private static void pixelCaret(GuiGraphicsExtractor graphics, int cx, int cy, boolean down, int alpha) {
		int black = withAlpha(0xFF000000, alpha);
		int white = withAlpha(0xFFFFFFFF, alpha);
		// Three rows, 1, 3 and 5 pixels wide, tip first.
		for (int pass = 0; pass < 2; pass++) {
			int grow = pass == 0 ? 1 : 0;
			for (int row = 0; row < 3; row++) {
				int half = row;
				int y = down ? cy + 1 - row : cy - 1 + row;
				graphics.fill(cx - half - grow, y - grow, cx + half + 1 + grow, y + 1 + grow, pass == 0 ? black : white);
			}
		}
	}

	/** The player's name under their head, on the translucent dark plate vanilla puts name tags on. */
	private static void nameTag(GuiGraphicsExtractor graphics, Blip blip, int alpha) {
		Font font = Minecraft.getInstance().font;
		String name = blip.entity().getName().getString();
		int w = font.width(name);
		int tx = Math.round(blip.x() - w / 2.0F);
		int ty = Math.round(blip.y() + blip.size() * 0.75F + 3);
		graphics.fill(tx - 1, ty - 1, tx + w, ty + 8, withAlpha(0x40000000, alpha));
		graphics.text(font, name, tx, ty, withAlpha(0xFFFFFFFF, alpha), false);
	}

	/** The blip under the cursor, if any, preferring players and then the nearest. */
	public static @Nullable Blip under(List<Blip> blips, double mouseX, double mouseY) {
		Blip best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Blip blip : blips) {
			double dx = blip.x() - mouseX;
			double dy = blip.y() - mouseY;
			double d = Math.sqrt(dx * dx + dy * dy);
			if (d > blip.size() / 2 + 3) {
				continue;
			}
			boolean better = best == null
				|| blip.kind().ordinal() > best.kind().ordinal()
				|| blip.kind() == best.kind() && d < bestDistance;
			if (better) {
				best = blip;
				bestDistance = d;
			}
		}
		return best;
	}

	/** "Zombie · 23 m · 6 below" */
	public static Component describe(Blip blip, Entity viewer) {
		int distance = (int) Math.round(blip.entity().distanceTo(viewer));
		int dy = (int) Math.round(blip.dy());
		String height = dy > 0 ? " · " + dy + " above" : dy < 0 ? " · " + (-dy) + " below" : "";
		return Component.empty().append(blip.entity().getDisplayName()).append(" · " + distance + " m" + height);
	}

	/** Things far above or below fade, but never below a readable alpha. */
	private static int alphaFor(double dy) {
		double fade = Math.max(0, Math.abs(dy) - 8) / 40;
		return (int) Math.round(255 * Math.max(0.45, 1 - fade));
	}

	static int withAlpha(int argb, int alpha) {
		int a = (argb >>> 24) * alpha / 255;
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
