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
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waypoints;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The fullscreen map: drag to pan, scroll to zoom, C for caves, PgUp/PgDn to move the slice,
 * click a waypoint to edit it, right-click for a menu of what can be done at a spot or waypoint.
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
	/** The right-click menu, while it's open. */
	private @Nullable MapMenu menu;
	/** Whether the current mouse press started on the menu. */
	private boolean pressOnMenu;

	public MapScreen() {
		super(Component.translatable("diorama.screen.title"));
		MapSession session = MapSession.get();
		LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		this.layer = session != null && player != null ? session.autoLayer(player) : TileKey.SURFACE;
	}

	@Override
	protected void init() {
		// Built for the old size, so a resize closes it.
		this.menu = null;
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
		// The tooltip is for whatever is drawn on top: a player, then a waypoint, then a mob. The
		// menu covers them while it's open.
		if (this.menu == null) {
			if (hovered != null && hovered.kind() == Radar.Kind.PLAYER) {
				graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
			} else if (hoveredPin != null) {
				graphics.setTooltipForNextFrame(this.font, WaypointOverlay.describe(hoveredPin.waypoint(), player), mouseX, mouseY);
			} else if (hovered != null) {
				graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
			}
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
		String help = "Drag pan · Scroll zoom · Right-click options · U waypoints · C caves · PgUp/PgDn slice · Space recenter";
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
		if (this.menu != null) {
			// Over everything drawn so far, text included.
			//? if >=1.21.6 {
			graphics.nextStratum();
			this.menu.draw(graphics, mouseX, mouseY);
			//?} else {
			/*graphics.flush();
			var pose = graphics.pose();
			pose.pushPose();
			pose.translate(0, 0, 400);
			this.menu.draw(graphics, mouseX, mouseY);
			pose.popPose();
			*///?}
		}
	}

	// Mouse and key events came as separate numbers before 1.21.9. Each override hands them to a
	// method below that both versions share.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return this.menuPressed(event.x(), event.y()) || this.clicked(super.mouseClicked(event, doubleClick), event.x(), event.y());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return this.released(event.x(), event.y(), event.button()) || super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.x(), event.y(), event.button(), dx, dy) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return this.keyDown(event.key(), DioramaClient.WAYPOINT_LIST.matches(event), DioramaClient.OPEN_MAP.matches(event))
			|| super.keyPressed(event);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return this.menuPressed(x, y) || this.clicked(super.mouseClicked(x, y, button), x, y);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return this.released(x, y, button) || super.mouseReleased(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(x, y, button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return this.keyDown(key, DioramaClient.WAYPOINT_LIST.matches(key, scancode), DioramaClient.OPEN_MAP.matches(key, scancode))
			|| super.keyPressed(key, scancode, modifiers);
	}
	*///?}

	/** A press on the open menu, which comes before the widgets it covers; false if it's elsewhere. */
	private boolean menuPressed(double x, double y) {
		if (this.menu == null || !this.menu.contains(x, y)) {
			return false;
		}
		this.pressOnMenu = true;
		this.pressX = x;
		this.pressY = y;
		this.dragged = false;
		return true;
	}

	/** A press; {@code widgetTook} if a widget (the settings button) took it. */
	private boolean clicked(boolean widgetTook, double x, double y) {
		if (widgetTook) {
			this.dragged = true;
			return true;
		}
		this.pressX = x;
		this.pressY = y;
		this.dragged = false;
		return true;
	}

	/** A release; false leaves it to the screen's widgets. */
	private boolean released(double x, double y, int button) {
		boolean moved = this.dragged || Math.hypot(x - this.pressX, y - this.pressY) > 3;
		if (this.pressOnMenu) {
			this.pressOnMenu = false;
			if (!moved && button == InputConstants.MOUSE_BUTTON_LEFT && this.menu != null && this.menu.click(x, y)) {
				this.menu = null;
			}
			return true;
		}
		if (moved) {
			return true;
		}
		if (this.menu != null) {
			// A click off the menu only closes it; a right-click opens the next one.
			this.menu = null;
			if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				this.openMenu(x, y);
			}
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			WaypointOverlay.Pin pin = WaypointOverlay.under(this.pins, x, y);
			if (pin != null) {
				this.minecraft.gui.setScreen(new WaypointEditScreen(this, pin.waypoint(), false));
			}
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			this.openMenu(x, y);
			return true;
		}
		return false;
	}

	/** Opens the menu for what's under a screen point: a waypoint's, or the spot's. */
	private void openMenu(double screenX, double screenY) {
		WaypointOverlay.Pin pin = WaypointOverlay.under(this.pins, screenX, screenY);
		List<MapMenu.Item> items = pin != null ? this.waypointItems(pin.waypoint()) : this.spotItems(this.spotAt(screenX, screenY));
		this.menu = new MapMenu(items, this.font, screenX, screenY, this.width, this.height);
	}

	/** Where a player would stand on the block shown under a screen point; null where the map has nothing. */
	private @Nullable BlockPos spotAt(double screenX, double screenY) {
		MapSession session = MapSession.get();
		BlockLooks looks = BlockLooks.get();
		MapViewport.View view = this.lastView;
		if (session == null || looks == null || view == null) {
			return null;
		}
		double u = view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom();
		return MapPick.standingSpot(session.map(), looks, u, v, this.layer, session.map().fogOfWar());
	}

	private List<MapMenu.Item> spotItems(@Nullable BlockPos spot) {
		if (spot == null) {
			return List.of(MapMenu.Item.info(Component.translatable("diorama.menu.not_mapped")));
		}
		int x = spot.getX();
		int y = spot.getY();
		int z = spot.getZ();
		return List.of(
			MapMenu.Item.info(this.where(x, y, z)),
			MapMenu.Item.action(Component.translatable("diorama.menu.teleport_here"), Waypoints.canTeleport(), () -> {
				Waypoints.teleport(x, y, z);
				this.minecraft.gui.setScreen(null);
			}),
			MapMenu.Item.action(Component.translatable("diorama.menu.add_waypoint"), true, () -> {
				Waypoint waypoint = Waypoints.at(spot);
				if (waypoint != null) {
					this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, true));
				}
			}),
			MapMenu.Item.action(Component.translatable("diorama.menu.add_temporary"), true, () -> Waypoints.addTemporary(spot)),
			// Chat can't take it back, so sharing asks twice.
			MapMenu.Item.confirmed(Component.translatable("diorama.menu.share_location"), Component.translatable("diorama.menu.share_confirm"), () -> {
				String dimension = Waypoints.dimension();
				Waypoint location = new Waypoint(Component.translatable("diorama.menu.shared_location").getString(), x, y, z,
					dimension != null ? dimension : "minecraft:overworld", Waypoint.PALETTE[0]);
				if (WaypointShare.send(location)) {
					this.minecraft.gui.setScreen(null);
				}
			})
		);
	}

	private List<MapMenu.Item> waypointItems(Waypoint waypoint) {
		List<MapMenu.Item> items = new ArrayList<>();
		items.add(MapMenu.Item.title(Component.literal(waypoint.name)));
		items.add(MapMenu.Item.info(this.where(waypoint.x, waypoint.y, waypoint.z)));
		items.add(MapMenu.Item.action(Component.translatable("diorama.waypoints.edit"), true, () ->
			this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, false))));
		items.add(MapMenu.Item.action(Component.translatable("diorama.waypoint.teleport"), Waypoints.canTeleport(), () -> {
			Waypoints.teleport(waypoint.x, waypoint.y, waypoint.z);
			this.minecraft.gui.setScreen(null);
		}));
		items.add(MapMenu.Item.confirmed(Component.translatable("diorama.waypoint.share"), Component.translatable("diorama.menu.share_confirm"), () -> {
			if (WaypointShare.send(waypoint)) {
				this.minecraft.gui.setScreen(null);
			}
		}));
		if (waypoint.kind == Waypoint.Kind.TEMPORARY) {
			items.add(MapMenu.Item.action(Component.translatable("diorama.menu.keep"), true, () -> {
				waypoint.kind = Waypoint.Kind.MANUAL;
				this.changed();
			}));
		}
		items.add(MapMenu.Item.action(Component.translatable("diorama.waypoints.hide"), true, () -> {
			waypoint.visible = false;
			this.changed();
		}));
		items.add(MapMenu.Item.confirmed(Component.translatable("diorama.waypoint.delete"), Component.translatable("diorama.menu.delete_confirm"), () -> {
			WaypointStore store = WaypointStore.get();
			if (store != null) {
				store.remove(waypoint);
			}
		}));
		return items;
	}

	private void changed() {
		WaypointStore store = WaypointStore.get();
		if (store != null) {
			store.changed();
		}
	}

	/** "120, 64, -340 · 212 m": a block position and how far the player is from it. */
	private Component where(int x, int y, int z) {
		LocalPlayer player = this.minecraft.player;
		long distance = player == null ? 0 : Math.round(Math.sqrt(player.distanceToSqr(x + 0.5, y, z + 0.5)));
		return Component.translatable("diorama.menu.where", x, y, z, distance);
	}

	/** A drag; false leaves it to the screen's widgets. */
	private boolean dragged(double x, double y, int button, double dx, double dy) {
		if (Math.hypot(x - this.pressX, y - this.pressY) > 3) {
			this.dragged = true;
		}
		if (this.pressOnMenu) {
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT && this.dragged) {
			// The menu stays where it opened, so it can't follow the map.
			this.menu = null;
			this.centerU -= dx / this.zoom;
			this.centerV -= dy / this.zoom;
			this.following = false;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.menu = null;
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

	/** A key press, and whether it is bound to the waypoint list or the map; false leaves it to the screen. */
	private boolean keyDown(int key, boolean waypointListKey, boolean mapKey) {
		// The player's own bindings come before the map's fixed keys, so Open map rebound to C still closes it.
		if (waypointListKey) {
			this.minecraft.gui.setScreen(new WaypointListScreen(this));
			return true;
		}
		if (mapKey) {
			this.onClose();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && this.menu != null) {
			this.menu = null;
			return true;
		}
		LocalPlayer player = this.minecraft.player;
		switch (key) {
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
		return false;
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

	/** For tests: the right-click menu, if it's open. */
	public @Nullable MapMenu menu() {
		return this.menu;
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
