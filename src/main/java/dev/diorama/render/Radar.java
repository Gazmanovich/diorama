package dev.diorama.render;

import com.google.common.collect.Iterables;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.TrackedPlayers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.1 {
import net.minecraft.client.gui.components.PlayerFaceExtractor;
//?} else {
/*import net.minecraft.client.gui.components.PlayerFaceRenderer;
*///?}
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
		if (kind == Kind.PLAYER) {
			return fullscreen ? (float) Math.max(8, Math.min(16, zoom * 3)) : 7;
		}
		return fullscreen ? (float) Math.max(4, Math.min(9, zoom * 2.2)) : 4;
	}

	/**
	 * Draws either the players or everything else. Maps draw mobs, then waypoints, then players,
	 * so a player standing at a waypoint shows on top of its marker and a crowd of mobs doesn't
	 * hide one.
	 */
	public static void draw(GuiGraphicsExtractor graphics, MapViewport.View view, List<Blip> blips, boolean fullscreen, boolean players) {
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		Font font = Minecraft.getInstance().font;
		for (Blip blip : blips) {
			if ((blip.kind() == Kind.PLAYER) != players) {
				continue;
			}
			int alpha = alphaFor(blip.dy());
			float s = blip.size();
			if (blip.kind() == Kind.PLAYER && blip.entity() instanceof AbstractClientPlayer player) {
				float x0 = blip.x() - s / 2;
				float y0 = blip.y() - s / 2;
				MapIcons.dot(graphics, blip.x(), blip.y(), s * 1.55F, withAlpha(0xFF101418, alpha));
				graphics.pose().pushMatrix();
				graphics.pose().translate(x0, y0);
				graphics.pose().scale(s / 8);
				//? if >=26.1 {
				PlayerFaceExtractor.extractRenderState(graphics, player.getSkin(), 0, 0, 8, withAlpha(0xFFFFFFFF, alpha));
				//?} else {
				/*PlayerFaceRenderer.draw(graphics, player.getSkin(), 0, 0, 8, withAlpha(0xFFFFFFFF, alpha));
				*///?}
				graphics.pose().popMatrix();
				if (fullscreen) {
					String name = player.getName().getString();
					int w = font.width(name);
					int tx = Math.round(blip.x() - w / 2.0F);
					int ty = Math.round(blip.y() + s * 0.75F + 2);
					graphics.fill(tx - 2, ty - 1, tx + w + 2, ty + 9, 0x90000000);
					graphics.text(font, name, tx, ty, withAlpha(0xFFFFFFFF, alpha), false);
				}
			} else if (MobFaces.has(blip.entity())) {
				// The mob's own face in a dark square, framed in its kind's colour.
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
			if (Math.abs(blip.dy()) > CARET_HEIGHT) {
				boolean below = blip.dy() < 0;
				float caret = Math.max(4, s * 0.7F);
				float cx = blip.x() + s * 0.55F + caret * 0.3F;
				float cy = blip.y() - s * 0.45F;
				MapIcons.caret(graphics, cx, cy, caret + 2, withAlpha(0xE0101418, alpha), below);
				MapIcons.caret(graphics, cx, cy, caret, withAlpha(0xFFFFFFFF, alpha), below);
			}
		}
		graphics.disableScissor();
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

	private static int withAlpha(int argb, int alpha) {
		int a = (argb >>> 24) * alpha / 255;
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
