package dev.diorama.waypoint;

import dev.diorama.DioramaClient;
import dev.diorama.render.Pins;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
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
 * Every waypoint in the current dimension, nearest first, with buttons to edit, hide, delete and,
 * for players allowed to use /tp, teleport. When Xaero's Minimap has waypoints for this world that
 * aren't here yet, a button imports them.
 */
public final class WaypointListScreen extends Screen {
	private static final int ROW = 24;
	private static final int LIST_WIDTH = 340;
	/** Where a row's name and position start, right of its dot. */
	private static final int TEXT_LEFT = 22;
	/** Edit, Hide and Delete, each 44 wide with a 2 pixel gap. */
	private static final int ROW_BUTTONS = 3 * 46;
	private static final int TP_BUTTON = 30;

	private final @Nullable Screen parent;
	private final List<Waypoint> rows = new ArrayList<>();
	private int scroll;
	private int listTop;
	private int visibleRows;
	private boolean canTeleport;
	/** Set by the add-waypoint key; tick() opens the editor. */
	private boolean newWaypointPressed;
	private boolean xaeroRead;
	private XaeroImport.@Nullable Found xaero;
	/** What the last import brought in, shown above the import button. */
	private @Nullable Component imported;
	private boolean importButtonShown;

	public WaypointListScreen(@Nullable Screen parent) {
		super(Component.translatable("diorama.waypoints.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.rows.clear();
		WaypointStore store = WaypointStore.get();
		LocalPlayer player = this.minecraft.player;
		if (store != null && player != null && this.minecraft.level != null) {
			this.rows.addAll(store.in(this.minecraft.level.dimension().identifier().toString()));
			this.rows.sort(Comparator.comparingDouble(w -> w.distanceTo(player.getX(), player.getY(), player.getZ())));
		}
		if (!this.xaeroRead) {
			this.xaeroRead = true;
			this.xaero = XaeroImport.find();
		}
		Button importButton = this.importButton(store);
		int importRow = (importButton != null ? ROW : 0) + (this.imported != null ? 12 : 0);
		this.listTop = 40;
		this.visibleRows = Math.max(1, (this.height - this.listTop - 40 - importRow) / ROW);
		this.scroll = Math.max(0, Math.min(this.scroll, this.rows.size() - this.visibleRows));
		int left = (this.width - LIST_WIDTH) / 2;
		//? if >=1.21.11 {
		this.canTeleport = player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		//?} else {
		/*// Permission level 2 is the game master level /tp needs.
		this.canTeleport = player != null && player.hasPermissions(2);
		*///?}
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.rows.size(); i++) {
			Waypoint waypoint = this.rows.get(this.scroll + i);
			int y = this.listTop + i * ROW + 2;
			int bx = left + LIST_WIDTH;
			if (this.canTeleport) {
				bx -= TP_BUTTON;
				this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoints.tp"), b -> this.teleport(waypoint)).bounds(bx, y, 28, 20).build());
			}
			bx -= 46;
			this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.delete"), b -> {
				WaypointStore s = WaypointStore.get();
				if (s != null) {
					s.remove(waypoint);
				}
				this.rebuildWidgets();
			}).bounds(bx, y, 44, 20).build());
			bx -= 46;
			this.addRenderableWidget(Button.builder(Component.translatable(waypoint.visible ? "diorama.waypoints.hide" : "diorama.waypoints.show"), b -> {
				waypoint.visible = !waypoint.visible;
				WaypointStore s = WaypointStore.get();
				if (s != null) {
					s.changed();
				}
				this.rebuildWidgets();
			}).bounds(bx, y, 44, 20).build());
			bx -= 46;
			this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoints.edit"), b ->
				this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, false))).bounds(bx, y, 44, 20).build());
		}
		int bottom = this.height - 30;
		this.importButtonShown = importButton != null;
		if (importButton != null) {
			this.addRenderableWidget(importButton);
			importButton.setRectangle(204, 20, this.width / 2 - 102, bottom - ROW);
		}
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.new"), b -> this.newWaypoint())
			.bounds(this.width / 2 - 102, bottom, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose()).bounds(this.width / 2 + 2, bottom, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
		LocalPlayer player = this.minecraft.player;
		int left = (this.width - LIST_WIDTH) / 2;
		// The name and position stop short of the row's first button.
		int textWidth = LIST_WIDTH - ROW_BUTTONS - (this.canTeleport ? TP_BUTTON : 0) - TEXT_LEFT - 4;
		if (this.rows.isEmpty()) {
			Component hint = DioramaClient.NEW_WAYPOINT.isUnbound()
				? Component.translatable("diorama.waypoints.empty_unbound")
				: Component.translatable("diorama.waypoints.empty", DioramaClient.NEW_WAYPOINT.getTranslatedKeyMessage());
			graphics.centeredText(this.font, hint, this.width / 2, this.listTop + 20, 0xFFA0A0A0);
		}
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.rows.size(); i++) {
			Waypoint waypoint = this.rows.get(this.scroll + i);
			int y = this.listTop + i * ROW;
			graphics.fill(left - 4, y, left + LIST_WIDTH + 4, y + ROW - 1, i % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
			float cx = left + 9;
			float cy = y + ROW / 2.0F;
			Pins.badge(graphics, waypoint, cx, cy, Pins.LARGE);
			int nameColor = waypoint.visible ? 0xFFFFFFFF : 0xFF808080;
			String name = this.fit(waypoint.name, textWidth);
			graphics.text(this.font, name, left + TEXT_LEFT, y + 4, nameColor, true);
			String detail = waypoint.x + ", " + waypoint.y + ", " + waypoint.z;
			if (player != null) {
				detail = Math.round(waypoint.distanceTo(player.getX(), player.getY(), player.getZ())) + " m · " + detail;
			}
			String shownDetail = this.fit(detail, textWidth);
			graphics.text(this.font, shownDetail, left + TEXT_LEFT, y + 14, 0xFF909090, true);
			boolean overText = mouseX >= left + TEXT_LEFT && mouseX < left + TEXT_LEFT + textWidth && mouseY >= y && mouseY < y + ROW - 1;
			if (overText && (!name.equals(waypoint.name) || !shownDetail.equals(detail))) {
				//? if >=1.21.6 {
				graphics.setComponentTooltipForNextFrame(this.font, List.of(Component.literal(waypoint.name), Component.literal(detail)), mouseX, mouseY);
				//?} else {
				/*// The screen draws this tooltip after everything else.
				this.setTooltipForNextRenderPass(List.of(Component.literal(waypoint.name).getVisualOrderText(), Component.literal(detail).getVisualOrderText()));
				*///?}
			}
		}
		if (this.imported != null) {
			int below = this.importButtonShown ? ROW : 0;
			graphics.centeredText(this.font, this.imported, this.width / 2, this.height - 30 - below - 12, 0xFFA0E0A0);
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
		return this.font.plainSubstrByWidth(text, width - this.font.width(ellipsis)) + ellipsis;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int next = Math.max(0, Math.min(this.scroll - (int) Math.signum(scrollY), this.rows.size() - this.visibleRows));
		if (next != this.scroll) {
			this.scroll = next;
			this.rebuildWidgets();
		}
		return true;
	}

	private void newWaypoint() {
		Waypoint fresh = Waypoints.atPlayer();
		if (fresh != null) {
			this.minecraft.gui.setScreen(new WaypointEditScreen(this, fresh, true));
		}
	}

	/**
	 * The add-waypoint key (or mouse button) works here too, since the empty list tells the player to
	 * press it. The editor opens on the next tick: the key's typed letter arrives after the key
	 * itself, and would otherwise land in the editor's name field.
	 */
	//? if >=1.21.9 {
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (DioramaClient.NEW_WAYPOINT.matches(event)) {
			this.newWaypointPressed = true;
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (DioramaClient.NEW_WAYPOINT.matchesMouse(event)) {
			this.newWaypointPressed = true;
			return true;
		}
		return false;
	}
	//?} else {
	/*@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (DioramaClient.NEW_WAYPOINT.matches(key, scancode)) {
			this.newWaypointPressed = true;
			return true;
		}
		return super.keyPressed(key, scancode, modifiers);
	}

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		if (super.mouseClicked(x, y, button)) {
			return true;
		}
		if (DioramaClient.NEW_WAYPOINT.matchesMouse(button)) {
			this.newWaypointPressed = true;
			return true;
		}
		return false;
	}
	*///?}

	@Override
	public void tick() {
		if (this.newWaypointPressed) {
			this.newWaypointPressed = false;
			this.newWaypoint();
		}
	}

	private void teleport(Waypoint waypoint) {
		LocalPlayer player = this.minecraft.player;
		if (player != null) {
			player.connection.sendCommand("tp @s " + waypoint.x + " " + waypoint.y + " " + waypoint.z);
		}
		this.minecraft.gui.setScreen(null);
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
