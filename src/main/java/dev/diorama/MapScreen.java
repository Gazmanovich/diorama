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
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner;
import net.minecraft.client.multiplayer.ClientLevel;
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///?}
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The fullscreen map: drag to pan, scroll to zoom, C for caves, PgUp/PgDn to move the slice,
 * click a waypoint to edit it, right-click for a menu of what can be done at a spot or waypoint.
 * Each key also has a control on screen: Surface and Caves tabs, a Waypoints button, zoom and
 * recenter buttons, and on the cave map a depth gauge whose band moves the slice.
 */
public final class MapScreen extends Screen {
	/** How far out the saved surface map zooms; overview tiles make it cheap. */
	private static final double MIN_ZOOM = 0.04;

	private static final double MAX_ZOOM = 16;

	/** Height of the top bar, which holds the tabs and buttons. */
	private static final int BAR = 16;
	private static final int BOTTOM_BAR = 14;
	private static final String HELP = "Drag to pan · Scroll to zoom · Right-click for options";
	/** One press of a zoom button: two notches of the mouse wheel. */
	private static final int ZOOM_BUTTON_STEPS = 2;
	private static final Identifier TAB = Identifier.withDefaultNamespace("widget/tab");
	private static final Identifier TAB_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/tab_highlighted");
	private static final Identifier TAB_SELECTED = Identifier.withDefaultNamespace("widget/tab_selected");
	private static final Identifier TAB_SELECTED_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/tab_selected_highlighted");
	private static final Component[] TAB_NAMES = {Component.translatable("diorama.map.surface"), Component.translatable("diorama.map.caves")};

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
	/** The button of the latest press, until it's released. */
	private int pressButton = -1;
	/** Whether the latest press went to a control (a button, a tab, the gauge), so it can't pan. */
	private boolean pressOnControl;
	private final DepthGauge gauge = new DepthGauge();
	private Button zoomIn;
	private Button zoomOut;
	private Button recenter;
	private Button sliceUp;
	private Button sliceDown;
	private Button waypointsButton;
	private Button settingsButton;
	/** The tabs' left edges and widths as drawn last frame, and where the status text starts. */
	private final int[] tabX = new int[2];
	private final int[] tabWidth = new int[2];
	private int tabsEnd;
	/** The block under the mouse, kept a few frames while the map point under it stays in one block. */
	private int spotU = Integer.MIN_VALUE;
	private int spotV;
	private int spotLayer;
	private int spotAge;
	private @Nullable BlockPos spot;

	public MapScreen() {
		super(Component.translatable("diorama.screen.title"));
		MapSession session = MapSession.get();
		LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		// Underground it opens on the cave the player is in, unless the settings say surface only.
		this.layer = session != null && player != null && Settings.get().autoCaves ? session.autoLayer(player) : TileKey.SURFACE;
	}

	@Override
	protected void init() {
		// Built for the old size, so a resize closes it. A press that opened another screen was
		// released there, so none is held on coming back.
		this.menu = null;
		this.gauge.cancel();
		this.pressButton = -1;
		this.pressOnMenu = false;
		this.pressOnControl = false;
		this.waypointsButton = this.addRenderableWidget(Button.builder(Component.translatable("diorama.map.waypoints"), b ->
			this.minecraft.gui.setScreen(new WaypointListScreen(this))).bounds(this.width - 130, 0, 64, BAR).build());
		this.settingsButton = this.addRenderableWidget(Button.builder(Component.translatable("diorama.settings.button"), b ->
			this.minecraft.gui.setScreen(new SettingsScreen(this))).bounds(this.width - 64, 0, 64, BAR).build());
		this.zoomIn = this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.zoomButton(ZOOM_BUTTON_STEPS))
			.size(20, 20).createNarration(n -> Component.translatable("diorama.map.zoom_in")).build());
		this.zoomOut = this.addRenderableWidget(Button.builder(Component.literal("-"), b -> this.zoomButton(-ZOOM_BUTTON_STEPS))
			.size(20, 20).createNarration(n -> Component.translatable("diorama.map.zoom_out")).build());
		this.recenter = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.following = true;
		}).size(20, 20).createNarration(n -> Component.translatable("diorama.map.recenter")).build());
		this.sliceUp = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.shiftSlice(DepthGauge.STEP, this.minecraft.player);
		}).size(18, 14).createNarration(n -> Component.translatable("diorama.map.slice_up")).build());
		this.sliceDown = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.shiftSlice(-DepthGauge.STEP, this.minecraft.player);
		}).size(18, 14).createNarration(n -> Component.translatable("diorama.map.slice_down")).build());
		this.placeControls();
	}

	/** Puts the zoom buttons beside the gauge on the cave map, in the corner otherwise. */
	private void placeControls() {
		boolean caves = this.layer != TileKey.SURFACE;
		int x = this.width - (caves ? 56 : 26);
		int y = this.height - 90;
		this.zoomIn.setPosition(x, y);
		this.zoomOut.setPosition(x, y + 22);
		this.recenter.setPosition(x, y + 48);
		this.zoomIn.active = this.zoom < MAX_ZOOM;
		this.zoomOut.active = this.zoom > this.minZoom();
		this.sliceUp.visible = caves;
		this.sliceDown.visible = caves;
		ClientLevel level = this.minecraft.level;
		if (caves && level != null) {
			this.sliceUp.active = this.layer < level.getMaxY() + 1;
			this.sliceDown.active = this.layer > level.getMinY() + DepthGauge.STEP;
		}
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
		boolean caves = this.layer != TileKey.SURFACE;
		this.placeControls();
		Radar.Blip hovered = null;
		this.blips = List.of();
		if (ServerRules.radarAllowed()) {
			this.blips = Radar.collect(view, a, true);
			Radar.draw(graphics, view, this.blips, true, false);
			hovered = Radar.under(this.blips, mouseX, mouseY);
		}
		WaypointStore store = WaypointStore.get();
		this.pins = store == null || !Settings.get().waypointsOnMap ? List.of() : this.layoutPins(view, store.in(this.minecraft.level.dimension().identifier().toString()));
		WaypointOverlay.Pin hoveredPin = WaypointOverlay.under(this.pins, mouseX, mouseY);
		WaypointOverlay.draw(graphics, view, this.pins, true, hoveredPin);
		Radar.draw(graphics, view, this.blips, true, true);
		Markers.player(graphics, view.screenX(playerU), view.screenY(playerV), player.getViewYRot(a), 6);
		this.lastView = view;
		if (caves) {
			this.gauge.layout(this.minecraft.level, player.blockPosition(), this.layer, this.width, this.height);
			this.sliceUp.setPosition(this.gauge.upButtonX(), this.gauge.upButtonY());
			this.sliceDown.setPosition(this.gauge.upButtonX(), this.gauge.downButtonY());
			// Its labels slide left of the zoom buttons rather than under them.
			int avoidX = this.zoomIn.getX() - 3;
			int avoidTop = this.zoomIn.getY() - 2;
			int avoidBottom = this.recenter.getY() + this.recenter.getHeight() + 2;
			//? if >=1.21.2 {
			this.gauge.draw(graphics, this.font, pos.y, this.layer, avoidX, avoidTop, avoidBottom);
			//?} else {
			/*// Each fill would be its own draw call otherwise.
			graphics.drawManaged(() -> this.gauge.draw(graphics, this.font, pos.y, this.layer, avoidX, avoidTop, avoidBottom));
			*///?}
		} else {
			this.gauge.cancel();
		}
		// The tooltip is for a control under the mouse, else whatever is drawn on top: a player,
		// then a waypoint, then a mob, then the spot itself. The menu covers them while it's open.
		if (this.menu == null) {
			if (this.overControls(mouseX, mouseY)) {
				Tip tip = this.controlTooltip(mouseX, mouseY);
				if (tip != null) {
					// Placed like a button's own tooltip: below the control, or above where there's no room.
					//? if >=1.21.6 {
					graphics.setTooltipForNextFrame(this.font, tip.lines(), new MenuTooltipPositioner(tip.control()), mouseX, mouseY, false);
					//?} else {
					/*this.setTooltipForNextRenderPass(tip.lines(), new MenuTooltipPositioner(tip.control()), false);
					*///?}
				}
			} else if (hovered != null && hovered.kind() == Radar.Kind.PLAYER) {
				graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
			} else if (hoveredPin != null) {
				graphics.setTooltipForNextFrame(this.font, WaypointOverlay.describe(hoveredPin.waypoint(), player), mouseX, mouseY);
			} else if (hovered != null) {
				graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
			} else if (this.pressButton == -1) {
				List<FormattedCharSequence> lines = this.spotTooltip(mouseX, mouseY);
				if (lines != null) {
					graphics.setTooltipForNextFrame(this.font, lines, mouseX, mouseY);
				}
			}
		}

		// The bars: tabs and status on top with the buttons, the mouse's ways in at the bottom.
		graphics.fill(0, 0, this.width, BAR, 0x90000000);
		this.drawTabs(graphics, mouseX, mouseY);
		String mode = !caves && session.map().roofless() ? "Under the roof   ·   " : "";
		String status = String.format(
			"%s%d, %d, %d   ·   %d chunks%s",
			mode, player.getBlockX(), player.getBlockY(), player.getBlockZ(), session.map().chunkCount(),
			session.map().bakingCount() > 0 ? "   ·   baking" : ""
		);
		graphics.text(this.font, status, this.tabsEnd + 8, 4, 0xFFFFFFFF, false);
		graphics.fill(0, this.height - BOTTOM_BAR, this.width, this.height, 0x90000000);
		graphics.text(this.font, HELP, 6, this.height - 11, 0xFFC8D0DA, false);
		// Widgets (the buttons) draw over the map, then the icons on them.
		super.extractRenderState(graphics, mouseX, mouseY, a);
		Markers.player(graphics, this.recenter.getX() + 10, this.recenter.getY() + 10.5F, 180, 5);
		if (caves) {
			this.caret(graphics, this.sliceUp, false);
			this.caret(graphics, this.sliceDown, true);
		}
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
		return this.menuPressed(event.x(), event.y(), event.button()) || this.clicked(super.mouseClicked(event, doubleClick), event.x(), event.y(), event.button());
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
		return this.menuPressed(x, y, button) || this.clicked(super.mouseClicked(x, y, button), x, y, button);
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

	/**
	 * A press while the menu is open, which the widgets don't get: on the menu it's for the menu,
	 * and anywhere else its release only closes the menu. False when the menu isn't open.
	 */
	private boolean menuPressed(double x, double y, int button) {
		if (this.menu == null) {
			return false;
		}
		this.pressOnMenu = this.menu.contains(x, y);
		this.pressButton = button;
		this.pressX = x;
		this.pressY = y;
		this.dragged = false;
		return true;
	}

	/** A press; {@code widgetTook} if a widget (a button) took it. */
	private boolean clicked(boolean widgetTook, double x, double y, int button) {
		this.pressButton = button;
		this.pressOnControl = false;
		if (!widgetTook && button == InputConstants.MOUSE_BUTTON_LEFT) {
			int tab = this.tabAt(x, y);
			if (tab >= 0) {
				if ((tab == 1) != (this.layer != TileKey.SURFACE)) {
					this.toggleCaves();
				}
				this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
				widgetTook = true;
			} else if (this.layer != TileKey.SURFACE && this.minecraft.level != null && this.gauge.contains(x, y)) {
				this.menu = null;
				this.gauge.grab(this.minecraft.level, y);
				widgetTook = true;
			}
		}
		if (!widgetTook && this.onButton(x, y)) {
			// A button that can't go further (zoom at its limit) still isn't the map.
			widgetTook = true;
		}
		if (widgetTook) {
			this.pressOnControl = true;
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
		if (button == InputConstants.MOUSE_BUTTON_LEFT && this.gauge.dragging()) {
			// The slice moves only now, so the map prepares just the one it was let go at.
			int slice = this.gauge.release();
			if (this.layer != TileKey.SURFACE) {
				this.layer = slice;
			}
		}
		// Only the release of the latest press counts: letting go of a button held since before
		// the menu opened mustn't close it.
		if (button != this.pressButton) {
			return true;
		}
		this.pressButton = -1;
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
		if (this.pressButton == -1) {
			// No press of this screen's: it was reset (a resize rebuilt the screen mid-drag).
			return false;
		}
		if (Math.hypot(x - this.pressX, y - this.pressY) > 3) {
			this.dragged = true;
		}
		if (this.pressOnMenu) {
			return true;
		}
		if (this.gauge.dragging()) {
			if (this.minecraft.level != null) {
				this.gauge.drag(this.minecraft.level, y);
			}
			return true;
		}
		if (this.pressOnControl) {
			return false;
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

	/** A zoom button: zooms about the middle of the screen, so the map keeps following the player. */
	private void zoomButton(int steps) {
		this.menu = null;
		this.zoom = Math.max(this.minZoom(), Math.min(MAX_ZOOM, this.zoom * Math.pow(1.25, steps)));
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
		// Each of these changes the view, which an open menu belongs to, so they close it.
		switch (key) {
			case InputConstants.KEY_C -> {
				this.toggleCaves();
				return true;
			}
			case InputConstants.KEY_PAGEUP -> {
				this.menu = null;
				this.shiftSlice(4, player);
				return true;
			}
			case InputConstants.KEY_PAGEDOWN -> {
				this.menu = null;
				this.shiftSlice(-4, player);
				return true;
			}
			case InputConstants.KEY_SPACE -> {
				this.menu = null;
				this.following = true;
				return true;
			}
			default -> {
			}
		}
		return false;
	}

	/** Between the surface and the slice at the player's feet, as C and the tabs do. */
	private void toggleCaves() {
		this.menu = null;
		this.gauge.cancel();
		LocalPlayer player = this.minecraft.player;
		this.layer = this.layer == TileKey.SURFACE && player != null ? MapSession.caveLayerFor(player.getY()) : TileKey.SURFACE;
	}

	private void shiftSlice(int delta, @Nullable LocalPlayer player) {
		if (this.layer == TileKey.SURFACE) {
			if (player == null) {
				return;
			}
			this.layer = MapSession.caveLayerFor(player.getY());
		}
		this.layer = Math.max(this.minecraft.level.getMinY() + 4, Math.min(this.minecraft.level.getMaxY() + 1, this.layer + delta));
	}

	/**
	 * Lays out the waypoint pins with the edge ones kept clear of the bars, the depth gauge and the
	 * zoom buttons: pins inside the view stay where they are.
	 */
	private List<WaypointOverlay.Pin> layoutPins(MapViewport.View view, List<Waypoint> waypoints) {
		int top = BAR + 1;
		int bottom = this.height - BOTTOM_BAR - 1;
		int right = this.width - (this.layer != TileKey.SURFACE ? 60 : 0);
		// The same map under a smaller view: points land where they do in the full one.
		MapViewport.View clear = new MapViewport.View(0, top, right, bottom - top,
			view.centerU() + (right / 2.0 - view.x() - view.width() / 2.0) / view.zoom(),
			view.centerV() + ((top + bottom) / 2.0 - view.y() - view.height() / 2.0) / view.zoom(),
			view.zoom(), view.layer());
		List<WaypointOverlay.Pin> pins = WaypointOverlay.layout(clear, waypoints, true);
		int bx0 = this.zoomIn.getX() - 2;
		int by0 = this.zoomIn.getY() - 2;
		int bx1 = this.zoomIn.getX() + this.zoomIn.getWidth() + 2;
		int by1 = this.recenter.getY() + this.recenter.getHeight() + 2;
		List<WaypointOverlay.Pin> out = new ArrayList<>(pins.size());
		for (WaypointOverlay.Pin pin : pins) {
			float r = pin.size() / 2 + 2;
			if (pin.clamped() && pin.x() + r > bx0 && pin.x() - r < bx1 && pin.y() + r > by0 && pin.y() - r < by1) {
				// An edge pin behind the zoom buttons moves the shorter way out: above them or left.
				float up = pin.y() + r - by0;
				float left = pin.x() + r - bx0;
				pin = up <= left
					? new WaypointOverlay.Pin(pin.waypoint(), pin.x(), by0 - r, pin.size(), true)
					: new WaypointOverlay.Pin(pin.waypoint(), bx0 - r, pin.y(), pin.size(), true);
			}
			out.add(pin);
		}
		return out;
	}

	/** Surface and Caves as the game's tabs, the open one taller and underlined. */
	private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = 2;
		for (int i = 0; i < 2; i++) {
			boolean selected = (i == 1) == (this.layer != TileKey.SURFACE);
			String name = TAB_NAMES[i].getString();
			int nameWidth = this.font.width(name);
			int w = nameWidth + 13;
			int h = selected ? BAR + 2 : BAR;
			boolean hovered = this.menu == null && mouseX >= x && mouseX < x + w && mouseY >= 0 && mouseY < h;
			sprite(graphics, selected ? (hovered ? TAB_SELECTED_HIGHLIGHTED : TAB_SELECTED) : (hovered ? TAB_HIGHLIGHTED : TAB), x, 0, w, h);
			if (selected) {
				// The selected tab's sprite is open in the middle; the game puts its menu backdrop there.
				graphics.fill(x + 2, 2, x + w - 2, h, 0x40000000);
				int underline = nameWidth - 1;
				graphics.fill(x + (w - underline) / 2, h - 3, x + (w - underline) / 2 + underline, h - 2, 0xFFFFFFFF);
			}
			graphics.text(this.font, name, x + w / 2 - nameWidth / 2, selected ? 4 : 6, selected ? 0xFFFFFFFF : 0xFFE0E0E0, true);
			this.tabX[i] = x;
			this.tabWidth[i] = w;
			x += w;
		}
		this.tabsEnd = x;
	}

	/** The tab under a point: 0 Surface, 1 Caves, -1 neither. */
	private int tabAt(double x, double y) {
		for (int i = 0; i < 2; i++) {
			if (x >= this.tabX[i] && x < this.tabX[i] + this.tabWidth[i] && y >= 0 && y < BAR) {
				return i;
			}
		}
		return -1;
	}

	private static void sprite(GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int w, int h) {
		//? if >=1.21.6 {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);
		//?} else if >=1.21.2 {
		/*graphics.blitSprite(RenderType::guiTextured, sprite, x, y, w, h);
		*///?} else {
		/*graphics.blitSprite(sprite, x, y, w, h);
		*///?}
	}

	/** A small up or down arrow on a slice button, grey when it can't go further. */
	private void caret(GuiGraphicsExtractor graphics, Button button, boolean down) {
		int colour = button.active ? 0xFFFFFFFF : 0xFFA0A0A0;
		int cx = button.getX() + button.getWidth() / 2;
		int y = button.getY() + (button.getHeight() - 3) / 2;
		for (int row = 0; row < 3; row++) {
			int half = down ? 2 - row : row;
			graphics.fill(cx - half, y + row, cx + half + 1, y + row + 1, colour);
		}
	}

	/** Whether a point is on the bars or a control, where the map's own tooltips don't show. */
	private boolean overControls(double x, double y) {
		if (y < BAR || y >= this.height - BOTTOM_BAR) {
			return true;
		}
		return this.onButton(x, y) || this.layer != TileKey.SURFACE && this.gauge.contains(x, y);
	}

	/** Whether a point is on a shown button, whether or not it can be pressed right now. */
	private boolean onButton(double x, double y) {
		for (AbstractWidget widget : new AbstractWidget[] {this.waypointsButton, this.settingsButton, this.zoomIn, this.zoomOut, this.recenter, this.sliceUp, this.sliceDown}) {
			if (on(widget, x, y)) {
				return true;
			}
		}
		return false;
	}

	/** Whether a point is on a widget that's shown; unlike isMouseOver, also when it's inactive. */
	private static boolean on(AbstractWidget widget, double x, double y) {
		return widget.visible && x >= widget.getX() && x < widget.getX() + widget.getWidth() && y >= widget.getY() && y < widget.getY() + widget.getHeight();
	}

	/** A control's tooltip and where the control is. */
	private record Tip(List<FormattedCharSequence> lines, ScreenRectangle control) {
	}

	/** What a control does and its key: the player's binding where there is one. */
	private @Nullable Tip controlTooltip(double x, double y) {
		int tab = this.tabAt(x, y);
		if (tab >= 0) {
			return new Tip(lines(TAB_NAMES[tab], key(keyName(InputConstants.KEY_C))), new ScreenRectangle(this.tabX[tab], 0, this.tabWidth[tab], BAR));
		}
		if (on(this.waypointsButton, x, y) && !DioramaClient.WAYPOINT_LIST.isUnbound()) {
			return tip(this.waypointsButton, this.waypointsButton.getMessage(), key(DioramaClient.WAYPOINT_LIST.getTranslatedKeyMessage()));
		}
		if (on(this.zoomIn, x, y)) {
			return tip(this.zoomIn, Component.translatable("diorama.map.zoom_in"), Component.translatable("diorama.map.scroll_up"));
		}
		if (on(this.zoomOut, x, y)) {
			return tip(this.zoomOut, Component.translatable("diorama.map.zoom_out"), Component.translatable("diorama.map.scroll_down"));
		}
		if (on(this.recenter, x, y)) {
			return tip(this.recenter, Component.translatable("diorama.map.recenter"), key(keyName(InputConstants.KEY_SPACE)));
		}
		if (on(this.sliceUp, x, y)) {
			return tip(this.sliceUp, Component.translatable("diorama.map.slice_up"), key(keyName(InputConstants.KEY_PAGEUP)));
		}
		if (on(this.sliceDown, x, y)) {
			return tip(this.sliceDown, Component.translatable("diorama.map.slice_down"), key(keyName(InputConstants.KEY_PAGEDOWN)));
		}
		return null;
	}

	private static Tip tip(AbstractWidget control, Component name, Component second) {
		return new Tip(lines(name, second), new ScreenRectangle(control.getX(), control.getY(), control.getWidth(), control.getHeight()));
	}

	/** "-382, 62, 825" over "River · 146 m" for the block under an empty spot of the map. */
	private @Nullable List<FormattedCharSequence> spotTooltip(int mouseX, int mouseY) {
		MapViewport.View view = this.lastView;
		LocalPlayer player = this.minecraft.player;
		if (view == null || player == null) {
			return null;
		}
		// The pick walks a ray through the map's blocks; do it again only for another block.
		double u = view.centerU() + (mouseX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (mouseY - view.y() - view.height() / 2.0) / view.zoom();
		int blockU = (int) Math.floor(u);
		int blockV = (int) Math.floor(v);
		if (blockU != this.spotU || blockV != this.spotV || this.layer != this.spotLayer || ++this.spotAge > 10) {
			this.spotU = blockU;
			this.spotV = blockV;
			this.spotLayer = this.layer;
			this.spotAge = 0;
			this.spot = this.spotAt(mouseX, mouseY);
		}
		BlockPos spot = this.spot;
		if (spot == null) {
			return null;
		}
		long distance = Math.round(Math.sqrt(player.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5)));
		Component biome = this.biomeAt(spot);
		Component detail = biome != null
			? Component.translatable("diorama.map.spot_detail", biome, distance)
			: Component.translatable("diorama.map.distance", distance);
		return lines(Component.translatable("diorama.map.spot", spot.getX(), spot.getY(), spot.getZ()), detail);
	}

	/** The biome at a spot, where the game has its chunk loaded; null elsewhere. */
	private @Nullable Component biomeAt(BlockPos pos) {
		ClientLevel level = this.minecraft.level;
		if (level == null || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
			return null;
		}
		return level.getBiome(pos).unwrapKey()
			.map(k -> (Component) Component.translatableWithFallback(k.identifier().toLanguageKey("biome"), k.identifier().getPath()))
			.orElse(null);
	}

	/** A tooltip: a white first line and a grey second one. */
	private static List<FormattedCharSequence> lines(Component first, Component second) {
		return List.of(first.getVisualOrderText(), second.copy().withStyle(ChatFormatting.GRAY).getVisualOrderText());
	}

	private static Component key(Component name) {
		return Component.translatable("diorama.map.key", name);
	}

	/** A fixed key's name in the player's keyboard layout and language. */
	private static Component keyName(int key) {
		//? if >=26.3 {
		return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName();
		//?} else {
		/*return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName();
		*///?}
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

	/** For tests: the middle of the depth gauge's slice band last frame, in GUI coordinates; null on the surface. */
	public float @Nullable [] sliceBand() {
		return this.layer == TileKey.SURFACE ? null : this.gauge.band();
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
