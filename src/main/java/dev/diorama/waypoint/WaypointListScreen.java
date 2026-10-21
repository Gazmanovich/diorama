package dev.diorama.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.DioramaClient;
import dev.diorama.render.Pins;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permissions;
//?}
import org.jspecify.annotations.Nullable;

/**
 * Every waypoint in the current dimension, nearest first, in vanilla's in-world list. A search
 * box filters them by name and New waypoint sits beside it. Clicking a row selects it and shows
 * its actions on it: edit, hide or show, teleport (for players allowed to use /tp) and delete,
 * which asks for a second click in place. Double-clicking a row edits it. With no waypoints in
 * this dimension, a pin standing on a piece of the map shows what one is and how to add one.
 * When Xaero's Minimap has waypoints for this world that aren't here yet, a button imports them.
 */
public final class WaypointListScreen extends Screen {
	private static final int LIST_WIDTH = 340;
	private static final int SEARCH_WIDTH = 236;
	private static final int NEW_WIDTH = 100;
	/** Rows are 24 tall with 2 between them, as in vanilla's lists. */
	private static final int ROW = 24;
	private static final int PITCH = 26;
	private static final int LIST_TOP = 48;
	/** Where a row's name and position start, right of its badge. */
	private static final int TEXT_LEFT = 24;
	private static final int ACTION_GAP = 2;
	/** Vanilla's double-click window. */
	private static final long DOUBLE_CLICK_MS = 250;
	private static final int RED = 0xFFFF5555;
	private static final int DETAIL = 0xFF909090;
	private static final int HIDDEN = 0xFF808080;
	private static final int HINT = 0xFFA0A0A0;
	private static final int SEARCH_HINT = 0xFF707070;

	private final @Nullable Screen parent;
	/** Every waypoint in this dimension, nearest first. */
	private final List<Waypoint> all = new ArrayList<>();
	/** The ones the search lets through, still nearest first. */
	private final List<Waypoint> rows = new ArrayList<>();
	/**
	 * The selected row's buttons. They're made with the screen and only moved, shown or hidden, and
	 * they act on whatever is selected when pressed: a button that has gone from view can keep the
	 * keyboard focus, and must not act on a row it no longer sits on.
	 */
	private final List<Button> actions = new ArrayList<>();
	private final MapPiece piece = new MapPiece(88, 56, 12);
	/** The sample pin on the empty list's piece of map. */
	private final Waypoint sample = new Waypoint("Home", 0, 0, 0, "", Waypoint.PALETTE[2]);
	private @Nullable EditBox search;
	private @Nullable Waypoint selected;
	/** The selected row's Delete was clicked once: its buttons are one Click again to delete. */
	private boolean armed;
	private @Nullable Button visibility;
	private @Nullable Button confirm;
	private int scroll;
	private int listBottom;
	private int visibleRows;
	private boolean draggingScrollbar;
	private long lastClick;
	private @Nullable Waypoint lastClicked;
	private boolean canTeleport;
	/** Set by the add-waypoint key; tick() opens the editor. */
	private boolean newWaypointPressed;
	private boolean xaeroRead;
	private XaeroImport.@Nullable Found xaero;
	/** What the last import brought in, shown above the import button. */
	private @Nullable Component imported;
	private int importedY;
	/** Top of the empty list's block: the piece of map, then the words and New waypoint. */
	private int emptyTop;

	public WaypointListScreen(@Nullable Screen parent) {
		super(Component.translatable("diorama.waypoints.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.all.clear();
		WaypointStore store = WaypointStore.get();
		LocalPlayer player = this.minecraft.player;
		if (store != null && player != null && this.minecraft.level != null) {
			this.all.addAll(store.in(this.minecraft.level.dimension().identifier().toString()));
			this.all.sort(Comparator.comparingDouble(w -> w.distanceTo(player.getX(), player.getY(), player.getZ())));
		}
		if (this.selected != null && !this.all.contains(this.selected)) {
			this.selected = null;
		}
		if (!this.xaeroRead) {
			this.xaeroRead = true;
			this.xaero = XaeroImport.find();
		}
		//? if >=1.21.11 {
		this.canTeleport = player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		//?} else {
		/*// Permission level 2 is the game master level /tp needs.
		this.canTeleport = player != null && player.hasPermissions(2);
		*///?}
		int cx = this.width / 2;
		int left = cx - LIST_WIDTH / 2;

		// The footer: Done alone, and the import button above it when Xaero's has something new.
		int footer = this.height - 28;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(cx - 100, footer, 200, 20).build());
		int above = footer;
		Button importButton = this.importButton(store);
		if (importButton != null) {
			above -= 24;
			this.addRenderableWidget(importButton);
			importButton.setRectangle(204, 20, cx - 102, above);
		}
		if (this.imported != null) {
			above -= 12;
			this.importedY = above;
		}
		this.listBottom = above - (above == footer ? 10 : 6);

		if (this.all.isEmpty()) {
			// The empty list: the piece of map, the headline and hints, then New waypoint under them.
			int newY = 186;
			this.emptyTop = Math.min(0, this.listBottom + 6 - (newY + 20 + 4));
			this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.new"), b -> this.newWaypoint())
				.bounds(cx - 75, newY + this.emptyTop, 150, 20).build());
			this.search = null;
			this.actions.clear();
			this.visibility = null;
			this.confirm = null;
			return;
		}

		// The search box: vanilla's text field frame with the search icon in it, the text right of the icon.
		String query = this.search != null ? this.search.getValue() : "";
		EditBox box = new EditBox(this.font, left + 20, 28, SEARCH_WIDTH - 24, 12, Component.translatable("diorama.waypoints.search"));
		box.setBordered(false);
		box.setMaxLength(32);
		box.setValue(query);
		box.setResponder(text -> this.filter());
		this.search = this.addRenderableWidget(box);
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.new"), b -> this.newWaypoint())
			.bounds(left + LIST_WIDTH - NEW_WIDTH, 22, NEW_WIDTH, 20).build());
		this.actions.clear();
		this.actions.add(this.action(Component.translatable("diorama.waypoints.edit"), () -> this.edit(this.selected)));
		this.visibility = this.action(Component.translatable("diorama.waypoints.hide"), () -> this.toggleVisible(this.selected));
		this.actions.add(this.visibility);
		if (this.canTeleport) {
			this.actions.add(this.action(Component.translatable("diorama.waypoint.teleport"), () -> this.teleport(this.selected)));
		}
		this.actions.add(this.action(Component.translatable("diorama.waypoint.delete").withStyle(ChatFormatting.RED), () -> {
			this.armed = true;
			this.placeRowButtons();
		}));
		this.confirm = this.addRenderableWidget(Button.builder(Component.translatable("diorama.menu.delete_confirm").withStyle(ChatFormatting.RED), b -> {
			if (this.armed) {
				this.delete(this.selected);
			}
		}).size(20, 20).build());
		this.visibleRows = Math.max(1, (this.listBottom - LIST_TOP - 2) / PITCH);
		this.filter();
		this.setInitialFocus(box);
	}

	/** Shows the waypoints whose name has the search text in it, any case, and lays the rows out again. */
	private void filter() {
		String query = this.search != null ? this.search.getValue().trim().toLowerCase(Locale.ROOT) : "";
		this.rows.clear();
		for (Waypoint waypoint : this.all) {
			if (query.isEmpty() || waypoint.name.toLowerCase(Locale.ROOT).contains(query)) {
				this.rows.add(waypoint);
			}
		}
		if (this.selected != null && !this.rows.contains(this.selected)) {
			this.select(null);
		}
		this.scrollTo(this.scroll);
	}

	private int maxScroll() {
		return Math.max(0, this.rows.size() - this.visibleRows);
	}

	private void scrollTo(int scroll) {
		this.scroll = Math.max(0, Math.min(scroll, this.maxScroll()));
		this.placeRowButtons();
	}

	private void select(@Nullable Waypoint waypoint) {
		this.selected = waypoint;
		this.armed = false;
		this.placeRowButtons();
	}

	/**
	 * The selected row's buttons, right-aligned inside it: Edit, Hide or Show, Teleport, then
	 * Delete in red. Once Delete is clicked they become one red Click again to delete across them.
	 */
	private void placeRowButtons() {
		int index = this.selected == null ? -1 : this.rows.indexOf(this.selected) - this.scroll;
		boolean shown = index >= 0 && index < this.visibleRows;
		Button visibility = this.visibility;
		if (shown && visibility != null) {
			Component label = Component.translatable(this.selected.visible ? "diorama.waypoints.hide" : "diorama.waypoints.show");
			visibility.setMessage(label);
			visibility.setWidth(width(this.font.width(label)));
		}
		int span = -ACTION_GAP;
		for (Button button : this.actions) {
			span += button.getWidth() + ACTION_GAP;
		}
		int right = this.width / 2 + LIST_WIDTH / 2 - 2;
		int y = shown ? this.rowY(index) + 2 : 0;
		int x = right - span;
		for (Button button : this.actions) {
			show(button, shown && !this.armed);
			button.setPosition(x, y);
			x += button.getWidth() + ACTION_GAP;
		}
		Button confirm = this.confirm;
		if (confirm != null) {
			show(confirm, shown && this.armed);
			confirm.setRectangle(span, 20, right - span, y);
		}
	}

	private static void show(Button button, boolean shown) {
		button.visible = shown;
		button.active = shown;
	}

	/** A row button just wide enough for its label, and never narrower than vanilla's small ones. */
	private Button action(Component label, Runnable onPress) {
		return this.addRenderableWidget(Button.builder(label, b -> onPress.run()).size(width(this.font.width(label)), 20).build());
	}

	private static int width(int label) {
		return Math.max(32, label + 10);
	}

	/** How wide the selected row's buttons are together, with the gap before them. */
	private int actionsWidth() {
		int span = 0;
		for (Button button : this.actions) {
			span += button.getWidth() + ACTION_GAP;
		}
		return span;
	}

	private int rowY(int index) {
		return LIST_TOP + 2 + index * PITCH;
	}

	/** The row under (x, y), or -1. */
	private int rowAt(double x, double y) {
		int left = this.width / 2 - LIST_WIDTH / 2;
		if (x < left || x >= left + LIST_WIDTH || y < LIST_TOP || y >= this.listBottom) {
			return -1;
		}
		int index = (int) ((y - LIST_TOP - 2) / PITCH);
		if (index < 0 || index >= this.visibleRows || this.scroll + index >= this.rows.size() || y >= this.rowY(index) + ROW) {
			return -1;
		}
		return this.scroll + index;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		// Drawn with the background, so the buttons go on top of it: before 1.21.6 a screen's own
		// drawing comes after its background, and the rows have to be under their buttons.
		//? if >=1.21.6 {
		graphics.nextStratum();
		//?}
		if (this.all.isEmpty()) {
			this.drawEmpty(graphics);
		} else {
			this.drawList(graphics, mouseX, mouseY);
		}
	}

	private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int cx = this.width / 2;
		int left = cx - LIST_WIDTH / 2;
		int right = left + LIST_WIDTH;
		graphics.centeredText(this.font, this.title, cx, 8, 0xFFFFFFFF);
		EditBox box = this.search;
		if (box != null) {
			ScreenArt.sprite(graphics, box.isFocused() ? ScreenArt.TEXT_FIELD_FOCUSED : ScreenArt.TEXT_FIELD, left, 22, SEARCH_WIDTH, 20);
			ScreenArt.sprite(graphics, ScreenArt.SEARCH, left + 4, 26, 12, 12);
			if (box.getValue().isEmpty()) {
				graphics.text(this.font, Component.translatable("diorama.waypoints.search"), left + 20, 28, SEARCH_HINT, false);
			}
		}
		ScreenArt.list(graphics, 0, LIST_TOP, this.width, this.listBottom);
		LocalPlayer player = this.minecraft.player;
		if (this.rows.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("diorama.waypoints.no_match"), cx, LIST_TOP + 20, HINT);
		}
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.rows.size(); i++) {
			Waypoint waypoint = this.rows.get(this.scroll + i);
			int y = this.rowY(i);
			boolean selected = waypoint == this.selected;
			if (selected) {
				ScreenArt.selection(graphics, left, y, right, y + ROW);
			}
			Pins.badge(graphics, waypoint, left + 11.5F, y + 12.5F, Pins.LARGE);
			// The name and position stop short of the selected row's buttons.
			int room = right - (selected ? this.actionsWidth() + 4 : 6) - (left + TEXT_LEFT);
			String name = this.fit(waypoint.name, room);
			graphics.text(this.font, name, left + TEXT_LEFT, y + 4, waypoint.visible ? 0xFFFFFFFF : HIDDEN, true);
			String detail = waypoint.x + ", " + waypoint.y + ", " + waypoint.z;
			if (player != null) {
				detail = Math.round(waypoint.distanceTo(player.getX(), player.getY(), player.getZ())) + " m · " + detail;
			}
			if (!waypoint.visible) {
				detail += "  ·  " + Component.translatable("diorama.waypoint.hidden").getString();
			}
			String shownDetail = this.fit(detail, room);
			graphics.text(this.font, shownDetail, left + TEXT_LEFT, y + 14, DETAIL, true);
			boolean overText = mouseX >= left + TEXT_LEFT && mouseX < left + TEXT_LEFT + room && mouseY >= y && mouseY < y + ROW;
			if (overText && (!name.equals(waypoint.name) || !shownDetail.equals(detail))) {
				//? if >=1.21.6 {
				graphics.setComponentTooltipForNextFrame(this.font, List.of(Component.literal(waypoint.name), Component.literal(detail)), mouseX, mouseY);
				//?} else {
				/*// The screen draws this tooltip after everything else.
				this.setTooltipForNextRenderPass(List.of(Component.literal(waypoint.name).getVisualOrderText(), Component.literal(detail).getVisualOrderText()));
				*///?}
			}
		}
		if (this.maxScroll() > 0) {
			int[] bar = this.scrollbar();
			ScreenArt.scrollbar(graphics, bar[0], LIST_TOP, this.listBottom, bar[1], bar[2]);
		}
		if (this.imported != null) {
			graphics.centeredText(this.font, this.imported, cx, this.importedY, 0xFFA0E0A0);
		}
	}

	/** The scrollbar: its x, the handle's height and how far down the track it sits. */
	private int[] scrollbar() {
		int track = this.listBottom - LIST_TOP;
		int handle = Math.max(ScreenArt.SCROLLER_MIN, Math.min(track - 8, track * this.visibleRows / Math.max(1, this.rows.size())));
		int top = this.maxScroll() == 0 ? 0 : (track - handle) * this.scroll / this.maxScroll();
		return new int[] {this.width / 2 + LIST_WIDTH / 2 + 4, handle, top};
	}

	/**
	 * The empty list: a pin standing on a piece of the map around the player, then what's
	 * missing and the two ways to add a waypoint.
	 */
	private void drawEmpty(GuiGraphicsExtractor graphics) {
		int cx = this.width / 2;
		int top = this.emptyTop;
		graphics.centeredText(this.font, this.title, cx, 16 + Math.max(top, -8), 0xFFFFFFFF);
		int pieceX = cx - this.piece.outerWidth() / 2;
		int pieceY = 52 + top;
		this.piece.draw(graphics, pieceX, pieceY);
		// The map's standing pin at twice its size, its tip on the middle of the piece's top.
		graphics.pose().pushMatrix();
		graphics.pose().translate(pieceX + this.piece.tipX(), pieceY + this.piece.tipY());
		graphics.pose().scale(2);
		Pins.standing(graphics, this.sample, 0, 0, Pins.LARGE);
		graphics.pose().popMatrix();
		graphics.centeredText(this.font, Component.translatable("diorama.waypoints.empty_title"), cx, 142 + top, 0xFFFFFFFF);
		Component how = DioramaClient.NEW_WAYPOINT.isUnbound()
			? Component.translatable("diorama.waypoints.empty_unbound")
			: Component.translatable("diorama.waypoints.empty", DioramaClient.NEW_WAYPOINT.getTranslatedKeyMessage());
		graphics.centeredText(this.font, how, cx, 158 + top, HINT);
		graphics.centeredText(this.font, Component.translatable("diorama.waypoints.empty_map", Component.translatable("diorama.menu.add_waypoint")), cx, 168 + top, HINT);
		if (this.imported != null) {
			graphics.centeredText(this.font, this.imported, cx, this.importedY, 0xFFA0E0A0);
		}
	}

	/**
	 * "Import from Xaero's Minimap (N)" when Xaero's has waypoints for this singleplayer world
	 * that aren't here; on a server, or when it's unclear which of Xaero's worlds this is, the
	 * button asks the player.
	 */
	private @Nullable Button importButton(@Nullable WaypointStore store) {
		XaeroImport.Found found = this.xaero;
		if (found == null || store == null) {
			return null;
		}
		XaeroImport.World chosen = found.chosen();
		if (chosen != null) {
			int count = XaeroImport.missing(chosen, store).size();
			return count == 0 ? null : Button.builder(Component.translatable("diorama.xaero.import", count), b -> {
				this.importFrom(chosen);
				this.rebuildWidgets();
			}).build();
		}
		if (found.worlds().stream().allMatch(world -> XaeroImport.missing(world, store).isEmpty())) {
			return null;
		}
		return Button.builder(Component.translatable("diorama.xaero.import_pick"), b ->
			this.minecraft.gui.setScreen(new XaeroWorldScreen(this, found.worlds()))).build();
	}

	/** What the last import brought in, or null before one. */
	@Nullable Component imported() {
		return this.imported;
	}

	/** Imports one of Xaero's worlds and says how it went. */
	void importFrom(XaeroImport.World world) {
		WaypointStore store = WaypointStore.get();
		if (store == null || this.minecraft.level == null) {
			return;
		}
		List<Waypoint> added = XaeroImport.importInto(world, store);
		String here = this.minecraft.level.dimension().identifier().toString();
		long elsewhere = added.stream().filter(w -> !w.dimension.equals(here)).count();
		Component count = added.size() == 1
			? Component.translatable("diorama.xaero.count_one")
			: Component.translatable("diorama.xaero.count", added.size());
		this.imported = elsewhere == 0
			? Component.translatable("diorama.xaero.imported", count)
			: Component.translatable("diorama.xaero.imported_elsewhere", count, elsewhere);
	}

	/** The text, cut to the width with an ellipsis the way vanilla clips a label, if it's wider. */
	private String fit(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		String ellipsis = CommonComponents.ELLIPSIS.getString();
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width(ellipsis))) + ellipsis;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.scrollTo(this.scroll - (int) Math.signum(scrollY));
		return true;
	}

	// Mouse and key events came as separate numbers before 1.21.9. Each override hands them to a
	// method below that both versions share.
	//? if >=1.21.9 {
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (DioramaClient.NEW_WAYPOINT.matches(event) && !(this.getFocused() instanceof EditBox)) {
			this.newWaypointPressed = true;
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		this.disarmUnless(event.x(), event.y());
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (DioramaClient.NEW_WAYPOINT.matchesMouse(event)) {
			this.newWaypointPressed = true;
			return true;
		}
		return this.clicked(event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.y()) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.draggingScrollbar = false;
		return super.mouseReleased(event);
	}
	//?} else {
	/*@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (DioramaClient.NEW_WAYPOINT.matches(key, scancode) && !(this.getFocused() instanceof EditBox)) {
			this.newWaypointPressed = true;
			return true;
		}
		return super.keyPressed(key, scancode, modifiers);
	}

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		this.disarmUnless(x, y);
		if (super.mouseClicked(x, y, button)) {
			return true;
		}
		if (DioramaClient.NEW_WAYPOINT.matchesMouse(button)) {
			this.newWaypointPressed = true;
			return true;
		}
		return this.clicked(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(y) || super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		this.draggingScrollbar = false;
		return super.mouseReleased(x, y, button);
	}
	*///?}

	/** A click anywhere but on Click again to delete puts the row's buttons back. */
	private void disarmUnless(double x, double y) {
		Button button = this.confirm;
		if (this.armed && (button == null || !button.isMouseOver(x, y))) {
			this.armed = false;
			// Delete had the focus from its click and shows again now: Enter mustn't ask again.
			if (this.getFocused() == button || this.actions.contains(this.getFocused())) {
				this.setFocused(null);
			}
			this.placeRowButtons();
		}
	}

	/** A click no widget took: the search frame, the scrollbar, or a row. */
	private boolean clicked(double x, double y, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT || this.all.isEmpty()) {
			return false;
		}
		int left = this.width / 2 - LIST_WIDTH / 2;
		EditBox box = this.search;
		if (box != null && x >= left && x < left + SEARCH_WIDTH && y >= 22 && y < 42) {
			this.setFocused(box);
			return true;
		}
		int[] bar = this.scrollbar();
		if (this.maxScroll() > 0 && x >= bar[0] && x < bar[0] + ScreenArt.SCROLLBAR_WIDTH && y >= LIST_TOP && y < this.listBottom) {
			this.draggingScrollbar = true;
			this.dragged(y);
			return true;
		}
		int row = this.rowAt(x, y);
		if (row < 0) {
			return false;
		}
		Waypoint waypoint = this.rows.get(row);
		long now = System.currentTimeMillis();
		boolean twice = waypoint == this.lastClicked && now - this.lastClick < DOUBLE_CLICK_MS;
		this.lastClicked = waypoint;
		this.lastClick = now;
		this.setFocused(null);
		if (twice) {
			this.edit(waypoint);
		} else if (waypoint != this.selected) {
			this.select(waypoint);
		}
		return true;
	}

	/** Moves the list with the scrollbar handle while it's held. */
	private boolean dragged(double y) {
		if (!this.draggingScrollbar) {
			return false;
		}
		int[] bar = this.scrollbar();
		int track = this.listBottom - LIST_TOP - bar[1];
		double t = track <= 0 ? 0 : (y - LIST_TOP - bar[1] / 2.0) / track;
		this.scrollTo((int) Math.round(t * this.maxScroll()));
		return true;
	}

	@Override
	public void tick() {
		if (this.newWaypointPressed) {
			this.newWaypointPressed = false;
			this.newWaypoint();
		}
	}

	private void newWaypoint() {
		Waypoint fresh = Waypoints.atPlayer();
		if (fresh != null) {
			this.minecraft.gui.setScreen(new WaypointEditScreen(this, fresh, true));
		}
	}

	private void edit(@Nullable Waypoint waypoint) {
		if (waypoint != null) {
			this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, false));
		}
	}

	private void toggleVisible(@Nullable Waypoint waypoint) {
		if (waypoint == null) {
			return;
		}
		waypoint.visible = !waypoint.visible;
		WaypointStore store = WaypointStore.get();
		if (store != null) {
			store.changed();
		}
		this.placeRowButtons();
	}

	private void delete(@Nullable Waypoint waypoint) {
		WaypointStore store = WaypointStore.get();
		if (store != null && waypoint != null) {
			store.remove(waypoint);
		}
		this.selected = null;
		this.armed = false;
		this.rebuildWidgets();
	}

	private void teleport(@Nullable Waypoint waypoint) {
		LocalPlayer player = this.minecraft.player;
		if (player != null && waypoint != null) {
			player.connection.sendCommand("tp @s " + waypoint.x + " " + waypoint.y + " " + waypoint.z);
		}
		this.minecraft.gui.setScreen(null);
	}

	/** The waypoint whose row is selected, or null. For tests. */
	public @Nullable Waypoint selected() {
		return this.selected;
	}

	/** The rows the search lets through, nearest first. For tests. */
	public List<Waypoint> shown() {
		return List.copyOf(this.rows);
	}

	/** The middle of a waypoint's name on screen, or null when its row is scrolled out of view. For tests. */
	public int @Nullable [] rowCentre(Waypoint waypoint) {
		int index = this.rows.indexOf(waypoint) - this.scroll;
		if (index < 0 || index >= this.visibleRows) {
			return null;
		}
		return new int[] {this.width / 2 - LIST_WIDTH / 2 + TEXT_LEFT + 20, this.rowY(index) + ROW / 2};
	}

	@Override
	public void removed() {
		this.piece.close();
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
