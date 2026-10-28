package dev.diorama.waypoint;

import dev.diorama.render.Pins;
import dev.diorama.render.WaypointIcons;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Picks the icon a waypoint's pin shows: a grid of badges as the waypoint list draws them, the
 * first one showing initials (no icon), then Diorama's icons and any a resource pack adds. Picking one goes
 * back to the editor.
 */
public final class WaypointIconScreen extends Screen {
	/** Wide enough for the initials badge with four letters. */
	private static final int CELL = 34;
	/** Columns when the icons fit in the window's height; a resource pack's many icons get more. */
	private static final int MIN_COLUMNS = 6;
	private static final int LABEL = 0xFFA0A0A0;

	private final Screen parent;
	private final Waypoint sample = new Waypoint();
	private final String current;
	private final Consumer<String> picked;
	private final List<String> icons = new ArrayList<>();

	public WaypointIconScreen(Screen parent, Waypoint shown, String current, Consumer<String> picked) {
		super(Component.translatable("diorama.waypoint.icon_title"));
		this.parent = parent;
		this.current = current;
		this.picked = picked;
		this.sample.name = shown.name;
		this.sample.initials = shown.initials;
		this.sample.color = shown.color;
		this.icons.add("");
		this.icons.addAll(WaypointIcons.all());
	}

	private int columns() {
		// Room for the title above and Cancel below.
		int rows = Math.max(1, (this.height - 64) / CELL);
		return Math.max(MIN_COLUMNS, (this.icons.size() + rows - 1) / rows);
	}

	private int rows() {
		return (this.icons.size() + this.columns() - 1) / this.columns();
	}

	private int left() {
		return this.width / 2 - this.columns() * CELL / 2;
	}

	private int top() {
		return this.height / 2 - (this.rows() * CELL + 28) / 2;
	}

	@Override
	protected void init() {
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(this.width / 2 - 50, this.top() + this.rows() * CELL + 8, 100, 20).build());
	}

	private int cellAt(double mouseX, double mouseY) {
		int col = (int) Math.floor((mouseX - this.left()) / CELL);
		int row = (int) Math.floor((mouseY - this.top()) / CELL);
		int i = row * this.columns() + col;
		return col < 0 || col >= this.columns() || row < 0 || i >= this.icons.size() ? -1 : i;
	}

	/** The centre of an icon's cell, in GUI pixels. For tests. */
	public float[] cellCentre(String id) {
		int i = this.icons.indexOf(id);
		return new float[] {this.left() + (i % this.columns()) * CELL + CELL / 2.0F, this.top() + (i / this.columns()) * CELL + CELL / 2.0F};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.left();
		int top = this.top();
		graphics.centeredText(this.font, this.title, this.width / 2, top - 14, 0xFFFFFFFF);
		ScreenArt.list(graphics, left - 2, top - 2, left + this.columns() * CELL + 2, top + this.rows() * CELL + 2);
		int hovered = this.cellAt(mouseX, mouseY);
		for (int i = 0; i < this.icons.size(); i++) {
			int x = left + (i % this.columns()) * CELL;
			int y = top + (i / this.columns()) * CELL;
			String id = this.icons.get(i);
			if (id.equals(this.current)) {
				ScreenArt.selection(graphics, x, y, x + CELL, y + CELL);
			} else if (i == hovered) {
				graphics.fill(x, y, x + CELL, y + CELL, 0x30FFFFFF);
			}
			this.sample.icon = id;
			Pins.badge(graphics, this.sample, x + CELL / 2.0F, y + CELL / 2.0F, Pins.LARGE);
		}
		if (hovered >= 0) {
			graphics.setTooltipForNextFrame(this.font, name(this.icons.get(hovered)), mouseX, mouseY);
		}
	}

	/**
	 * What an icon is called: "Initials" for none, Diorama's own by translation, and one a resource
	 * pack adds by its file's name, or by {@code diorama.waypoint.icon.<namespace>.<name>} if the pack
	 * translates it.
	 */
	public static Component name(String id) {
		if (id.isEmpty()) {
			return Component.translatable("diorama.waypoint.icon_none");
		}
		String path = id.substring(id.indexOf(':') + 1);
		String fallback = path.replace('_', ' ');
		fallback = fallback.isEmpty() ? id : Character.toUpperCase(fallback.charAt(0)) + fallback.substring(1);
		return Component.translatableWithFallback("diorama.waypoint.icon." + id.replace(':', '.'), fallback);
	}

	private boolean clicked(double mouseX, double mouseY, int button) {
		int i = this.cellAt(mouseX, mouseY);
		if (i < 0 || button != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		this.picked.accept(this.icons.get(i));
		this.onClose();
		return true;
	}

	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		return this.clicked(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return this.clicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
	}
	*///?}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
