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
 * Picks the icon a waypoint's pin shows: a grid of pins as the map draws them, the first one
 * showing initials (no icon), then every icon on offer. Picking one goes back to the editor.
 */
public final class WaypointIconScreen extends Screen {
	private static final int CELL = 30;
	private static final int COLUMNS = 6;
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
		this.icons.addAll(WaypointIcons.OFFERED);
		for (String id : WaypointIcons.shipped()) {
			if (!this.icons.contains(id)) {
				this.icons.add(id);
			}
		}
	}

	private int rows() {
		return (this.icons.size() + COLUMNS - 1) / COLUMNS;
	}

	private int left() {
		return this.width / 2 - COLUMNS * CELL / 2;
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
		int i = row * COLUMNS + col;
		return col < 0 || col >= COLUMNS || row < 0 || i >= this.icons.size() ? -1 : i;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.left();
		int top = this.top();
		graphics.centeredText(this.font, this.title, this.width / 2, top - 14, 0xFFFFFFFF);
		ScreenArt.list(graphics, left - 2, top - 2, left + COLUMNS * CELL + 2, top + this.rows() * CELL + 2);
		int hovered = this.cellAt(mouseX, mouseY);
		for (int i = 0; i < this.icons.size(); i++) {
			int x = left + (i % COLUMNS) * CELL;
			int y = top + (i / COLUMNS) * CELL;
			String id = this.icons.get(i);
			if (id.equals(this.current)) {
				ScreenArt.selection(graphics, x, y, x + CELL, y + CELL);
			} else if (i == hovered) {
				graphics.fill(x, y, x + CELL, y + CELL, 0x30FFFFFF);
			}
			this.sample.icon = id;
			// Centred on the pin's head or icon, its pointer below.
			float cy = y + CELL / 2.0F - 3;
			Pins.standing(graphics, this.sample, x + CELL / 2.0F, cy + Pins.headAbove(this.sample, Pins.LARGE), Pins.LARGE);
		}
		if (hovered >= 0) {
			graphics.setTooltipForNextFrame(this.font, name(this.icons.get(hovered)), mouseX, mouseY);
		}
	}

	/** What an icon is called: "Initials" for none, the mob's name for a face, else the file's name. */
	public static Component name(String id) {
		if (id.isEmpty()) {
			return Component.translatable("diorama.waypoint.icon_none");
		}
		if (id.startsWith(WaypointIcons.MOB)) {
			net.minecraft.resources.Identifier type = net.minecraft.resources.Identifier.tryParse(id.substring(WaypointIcons.MOB.length()));
			if (type != null) {
				var found = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(type);
				if (found.isPresent()) {
					return found.get().getDescription();
				}
			}
		}
		String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
		return Component.literal(path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1));
	}

	private boolean clicked(double mouseX, double mouseY) {
		int i = this.cellAt(mouseX, mouseY);
		if (i < 0) {
			return false;
		}
		this.picked.accept(this.icons.get(i));
		this.onClose();
		return true;
	}

	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		return this.clicked(event.x(), event.y()) || super.mouseClicked(event, doubleClick);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return this.clicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
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
