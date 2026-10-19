package dev.diorama;

import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.MapPick;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Markers;
import dev.diorama.render.Radar;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waypoints;
import net.minecraft.core.BlockPos;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The fullscreen map: drag to pan, scroll to zoom, C for caves, PgUp/PgDn to move the slice,
 * right-click to add a waypoint, click a waypoint to edit it.
 */
public final class MapScreen extends Screen {
	/** How far out the saved surface map zooms; overview tiles make it cheap. */
	private static final double MIN_ZOOM = 0.04;

	private static final double MAX_ZOOM = 16;

	private final MapViewport viewport = new MapViewport();
	private double centerU;
	private double centerV;
	private double zoom = 2.0;
	private int layer;
	private boolean following = true;
	private List<Radar.Blip> blips = List.of();
	private List<WaypointOverlay.Pin> pins = List.of();
	private MapViewport.@Nullable View lastView;
	/** Where the current mouse press started, to tell a click from a drag. */
	private double pressX;
	private double pressY;
	private boolean dragged;

	public MapScreen() {
		super(Component.translatable("diorama.screen.title"));
		MapSession session = MapSession.get();
		LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		this.layer = session != null && player != null ? session.autoLayer(player) : TileKey.SURFACE;
	}

	@Override
	protected void init() {
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.settings.button"), b ->
			this.minecraft.gui.setScreen(new SettingsScreen(this))).bounds(this.width - 64, 0, 64, 14).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (!ServerRules.minimapAllowed()) {
			// The server can switch maps off while this screen is open.
			this.onClose();
			return;
		}
		MapSession session = MapSession.get();
		LocalPlayer player = this.minecraft.player;
		if (session == null || player == null) {
			return;
		}
		Vec3 pos = player.getPosition(a);
		double playerU = pos.x;
		double playerV = Projection.v(pos.y, pos.z);
		if (this.following) {
			this.centerU = playerU;
			this.centerV = playerV;
		}
		// Switching to a cave slice while zoomed far out comes back to the closest zoom it can show.
		this.zoom = Math.max(this.minZoom(), this.zoom);
		MapViewport.View view = new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, this.zoom, this.layer);
		this.viewport.draw(graphics, session.map(), view, overlayBits(session));
		Radar.Blip hovered = null;
		this.blips = List.of();
		if (ServerRules.radarAllowed()) {
			this.blips = Radar.collect(view, a, true);
			Radar.draw(graphics, view, this.blips, true, false);
			hovered = Radar.under(this.blips, mouseX, mouseY);
		}
		WaypointStore store = WaypointStore.get();
		this.pins = store == null || !Settings.get().waypointsOnMap ? List.of() : WaypointOverlay.layout(view, store.in(this.minecraft.level.dimension().identifier().toString()), true);
		WaypointOverlay.Pin hoveredPin = WaypointOverlay.under(this.pins, mouseX, mouseY);
		WaypointOverlay.draw(graphics, view, this.pins, true, hoveredPin);
		Radar.draw(graphics, view, this.blips, true, true);
		Markers.player(graphics, view.screenX(playerU), view.screenY(playerV), player.getViewYRot(a), 6);
		this.lastView = view;
		// The tooltip is for whatever is drawn on top: a player, then a waypoint, then a mob.
		if (hovered != null && hovered.kind() == Radar.Kind.PLAYER) {
			graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
		} else if (hoveredPin != null) {
			graphics.setTooltipForNextFrame(this.font, WaypointOverlay.describe(hoveredPin.waypoint(), player), mouseX, mouseY);
		} else if (hovered != null) {
			graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
		}

		String mode = this.layer != TileKey.SURFACE
			? "Cave slice at y=" + this.layer
			: session.map().roofless() ? "Under the roof" : "Surface";
		String status = String.format(
			"%s   ·   %d, %d, %d   ·   %d chunks%s",
			mode, player.getBlockX(), player.getBlockY(), player.getBlockZ(), session.map().chunkCount(),
			session.map().bakingCount() > 0 ? "   ·   baking" : ""
		);
		graphics.fill(0, 0, this.width, 14, 0x90000000);
		graphics.text(this.font, status, 6, 3, 0xFFFFFFFF, false);
		String help = "Drag pan · Scroll zoom · Right-click add waypoint · U waypoints · C caves · PgUp/PgDn slice · Space recenter";
		graphics.fill(0, this.height - 14, this.width, this.height, 0x90000000);
		// Shrink the help line rather than cut it off on narrow screens.
		float helpScale = Math.min(1, (this.width - 12) / (float) this.font.width(help));
		graphics.pose().pushMatrix();
		graphics.pose().translate(6, this.height - 7 - 4 * helpScale);
		graphics.pose().scale(helpScale);
		graphics.text(this.font, help, 0, 0, 0xFFC8D0DA, false);
		graphics.pose().popMatrix();
		// Widgets (the settings button) draw over the map.
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			// A widget took the click.
			this.dragged = true;
			return true;
		}
		this.pressX = event.x();
		this.pressY = event.y();
		this.dragged = false;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.dragged || Math.hypot(event.x() - this.pressX, event.y() - this.pressY) > 3) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			WaypointOverlay.Pin pin = WaypointOverlay.under(this.pins, event.x(), event.y());
			if (pin != null) {
				this.minecraft.gui.setScreen(new WaypointEditScreen(this, pin.waypoint(), false));
			}
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			this.addWaypointAt(event.x(), event.y());
			return true;
		}
		return super.mouseReleased(event);
	}

	/** Opens a new waypoint on the block shown under a screen point. */
	public void addWaypointAt(double screenX, double screenY) {
		MapSession session = MapSession.get();
		BlockLooks looks = BlockLooks.get();
		MapViewport.View view = this.lastView;
		if (session == null || looks == null || view == null) {
			return;
		}
		double u = view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom();
		BlockPos spot = MapPick.standingSpot(session.map(), looks, u, v, this.layer, session.map().fogOfWar());
		if (spot == null) {
			return;
		}
		Waypoint waypoint = Waypoints.at(spot);
		if (waypoint != null) {
			this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, true));
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (Math.hypot(event.x() - this.pressX, event.y() - this.pressY) > 3) {
			this.dragged = true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.dragged) {
			this.centerU -= dx / this.zoom;
			this.centerV -= dy / this.zoom;
			this.following = false;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		double before = this.zoom;
		double after = Math.max(this.minZoom(), Math.min(MAX_ZOOM, before * Math.pow(1.25, scrollY)));
		if (after != before) {
			// Keep the map point under the cursor where it is.
			double mapU = this.centerU + (x - this.width / 2.0) / before;
			double mapV = this.centerV + (y - this.height / 2.0) / before;
			this.centerU = mapU - (x - this.width / 2.0) / after;
			this.centerV = mapV - (y - this.height / 2.0) / after;
			this.zoom = after;
			if (this.following) {
				this.following = false;
			}
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// The player's own bindings come before the map's fixed keys, so Open map rebound to C still closes it.
		if (DioramaClient.WAYPOINT_LIST.matches(event)) {
			this.minecraft.gui.setScreen(new WaypointListScreen(this));
			return true;
		}
		if (DioramaClient.OPEN_MAP.matches(event)) {
			this.onClose();
			return true;
		}
		LocalPlayer player = this.minecraft.player;
		switch (event.key()) {
			case InputConstants.KEY_C -> {
				this.layer = this.layer == TileKey.SURFACE && player != null ? MapSession.caveLayerFor(player.getY()) : TileKey.SURFACE;
				return true;
			}
			case InputConstants.KEY_PAGEUP -> {
				this.shiftSlice(4, player);
				return true;
			}
			case InputConstants.KEY_PAGEDOWN -> {
				this.shiftSlice(-4, player);
				return true;
			}
			case InputConstants.KEY_SPACE -> {
				this.following = true;
				return true;
			}
			default -> {
			}
		}
		return super.keyPressed(event);
	}

	private void shiftSlice(int delta, LocalPlayer player) {
		if (this.layer == TileKey.SURFACE) {
			if (player == null) {
				return;
			}
			this.layer = MapSession.caveLayerFor(player.getY());
		}
		this.layer = Math.max(this.minecraft.level.getMinY() + 4, Math.min(this.minecraft.level.getMaxY() + 1, this.layer + delta));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		this.viewport.close();
	}

	/** For tests: the view drawn last frame. */
	public MapViewport.@Nullable View view() {
		return this.lastView;
	}

	/** For tests: the waypoint pins drawn last frame, in GUI coordinates. */
	public List<WaypointOverlay.Pin> pins() {
		return this.pins;
	}

	/** For tests: the radar markers drawn last frame, in GUI coordinates. */
	public List<Radar.Blip> blips() {
		return this.blips;
	}

	/** The {@link Overlays} bits to draw, from the settings. */
	private static int overlayBits(MapSession session) {
		Settings s = Settings.get();
		int bits = 0;
		if (s.chunkGrid) {
			bits |= Overlays.CHUNK_WEST | Overlays.CHUNK_NORTH;
		}
		if (s.slimeChunks && session.slimeSeed() != null) {
			bits |= Overlays.SLIME;
		}
		if (s.spawnSpots != Settings.SpawnSpots.OFF) {
			bits |= Overlays.SPAWN_ANY_TIME;
		}
		if (s.spawnSpots == Settings.SpawnSpots.AT_NIGHT_TOO) {
			bits |= Overlays.SPAWN_AT_NIGHT;
		}
		return bits;
	}

	/** Cave slices and unsaved maps have no overview tiles, so they stop at the detailed zoom. */
	private double minZoom() {
		MapSession session = MapSession.get();
		boolean overviews = this.layer == TileKey.SURFACE && session != null && session.map().hasOverviews();
		return overviews ? MIN_ZOOM : MapViewport.DETAILED_ZOOM;
	}

	/** For tests: put the view at a spot without following the player. */
	public void lookAt(double u, double v, double zoom, int layer) {
		this.centerU = u;
		this.centerV = v;
		this.zoom = zoom;
		this.layer = layer;
		this.following = false;
	}
}
