package dev.diorama;

import dev.diorama.waypoint.Waypoints;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Names a new map for a dimension, which then becomes the one in use, or renames one. Deleting
 * one is on its row in the Maps screen.
 */
public final class MapNameScreen extends Screen {
	private static final int WIDTH = 200;
	private static final int LABEL = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;

	private final MapsScreen parent;
	private final String dimension;
	/** The map being renamed, or null for a new one. */
	private final @Nullable String existing;
	private @Nullable EditBox name;
	private @Nullable Button save;
	private @Nullable Component problem;

	public MapNameScreen(MapsScreen parent, String dimension, @Nullable String existing) {
		super(Component.translatable(existing == null ? "diorama.maps.new" : "diorama.maps.rename_title"));
		this.parent = parent;
		this.dimension = dimension;
		this.existing = existing;
	}

	private int left() {
		return this.width / 2 - WIDTH / 2;
	}

	private int top() {
		return this.height / 2 - 40;
	}

	@Override
	protected void init() {
		int left = this.left();
		int top = this.top();
		Maps maps = Maps.get();
		String typed = this.name != null ? this.name.getValue()
			: maps == null ? "" : this.existing != null ? maps.name(this.dimension, this.existing) : maps.suggestedName(this.dimension);
		EditBox box = new EditBox(this.font, left, top + 16, WIDTH, 20, Component.translatable("diorama.maps.name"));
		box.setMaxLength(Maps.MAX_NAME);
		box.setValue(typed);
		box.setResponder(text -> this.validate());
		this.name = this.addRenderableWidget(box);
		int half = (WIDTH - 4) / 2;
		this.save = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.save"), b -> this.save())
			.bounds(left, top + 64, half, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(left + WIDTH - half, top + 64, half, 20).build());
		this.validate();
		this.setInitialFocus(box);
	}

	/** Save is on only for a name that isn't blank and isn't another of the dimension's maps'. */
	private void validate() {
		Maps maps = Maps.get();
		String typed = this.name == null ? "" : this.name.getValue().trim();
		this.problem = !typed.isEmpty() && maps != null && maps.taken(this.dimension, typed, this.existing)
			? Component.translatable("diorama.maps.taken") : null;
		if (this.save != null) {
			this.save.active = maps != null && !typed.isEmpty() && this.problem == null;
		}
	}

	private void save() {
		Maps maps = Maps.get();
		if (maps == null || this.name == null) {
			return;
		}
		String typed = this.name.getValue().trim();
		if (this.existing == null) {
			String id = maps.create(this.dimension, typed);
			if (id != null) {
				this.parent.show(this.dimension, id);
				this.onClose();
				return;
			}
		} else if (maps.rename(this.dimension, this.existing, typed)) {
			this.parent.show(this.dimension, this.existing);
			this.onClose();
			return;
		}
		// The name was checked already, so the list of maps couldn't be saved.
		this.problem = Component.translatable("diorama.maps.not_saved");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// A 1.20.1 screen draws its own background.
		//? if <1.21 {
		/*this.renderBackground(graphics);
		*///?}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.left();
		int top = this.top();
		graphics.centeredText(this.font, this.title, this.width / 2, top - 24, 0xFFFFFFFF);
		graphics.centeredText(this.font, Waypoints.dimensionName(this.dimension), this.width / 2, top - 12, LABEL);
		graphics.text(this.font, Component.translatable("diorama.maps.name"), left, top + 5, LABEL, true);
		if (this.problem != null) {
			graphics.text(this.font, this.problem, left, top + 42, RED, true);
		} else if (this.existing == null) {
			graphics.text(this.font, Component.translatable("diorama.maps.new_hint"), left, top + 42, LABEL, true);
		}
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
