package dev.diorama;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Moves the minimap: drag it anywhere over the world, and C puts it midway across the top or
 * bottom, as in Xaero's Minimap's Change Position screen. Where it's let go, it's kept by the
 * nearest corner's edges, so it stays that far in from them when the window or the GUI scale
 * changes. The Corner button puts it back in a corner. Done keeps it; Cancel and Esc put it back.
 */
public final class MinimapPositionScreen extends Screen {
	/** Xaero's Minimap's colours for the box over the minimap: resting, under the mouse, held. */
	private static final int BOX = 0x50BDBDBD;
	private static final int BOX_HOVERED = 0x64FFFFFF;
	private static final int BOX_HELD = 0x82FFFFFF;
	private static final Settings.Corner[] CORNERS = Settings.Corner.values();

	private final @Nullable Screen parent;
	/** The place it had when the screen opened, for Cancel. */
	private final Settings.Corner startCorner;
	private final int startX;
	private final int startY;
	private final boolean startCentered;
	/** Done was pressed; the screen going any other way, by Esc or by dying or leaving, puts the minimap back. */
	private boolean kept;
	private boolean dragging;
	/** Where the minimap's top-left is from the mouse while it's held. */
	private int grabX;
	private int grabY;
	private @Nullable Button corner;

	public MinimapPositionScreen(@Nullable Screen parent) {
		super(Component.translatable("diorama.position.title"));
		this.parent = parent;
		Settings s = Settings.get();
		this.startCorner = s.minimapCorner;
		this.startX = s.minimapOffsetX;
		this.startY = s.minimapOffsetY;
		this.startCentered = s.minimapCentered;
	}

	@Override
	protected void init() {
		this.dragging = false;
		MinimapHud.holdSize(0);
		int y = this.height / 6 + 143;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.done()).bounds(this.width / 2 - 155, y, 150, 20).build());
		this.corner = this.addRenderableWidget(Button.builder(this.cornerLabel(), b -> this.nextCorner())
			.bounds(this.width / 2 + 5, y, 150, 20).tooltip(Tooltip.create(Component.translatable("diorama.settings.corner.tooltip"))).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose()).bounds(this.width / 2 + 5, y + 25, 150, 20).build());
	}

	/** "Corner: Top right", or Custom once it's been moved off one. */
	private Component cornerLabel() {
		return Component.empty().append(Component.translatable("diorama.settings.corner")).append(": ").append(placeName());
	}

	/** The corner the minimap is in, or Custom. */
	static Component placeName() {
		Settings s = Settings.get();
		return inCorner(s) ? Component.translatable("diorama.settings.corner." + s.minimapCorner.name().toLowerCase(java.util.Locale.ROOT))
			: Component.translatable("diorama.settings.position.custom");
	}

	private static boolean inCorner(Settings s) {
		return !s.minimapCentered && s.minimapOffsetX == MinimapHud.MARGIN && s.minimapOffsetY == MinimapHud.MARGIN;
	}

	/** The next corner, or from a place of its own the first. */
	private void nextCorner() {
		Settings s = Settings.get();
		s.minimapCorner = inCorner(s) ? CORNERS[(s.minimapCorner.ordinal() + 1) % CORNERS.length] : CORNERS[0];
		s.minimapOffsetX = MinimapHud.MARGIN;
		s.minimapOffsetY = MinimapHud.MARGIN;
		s.minimapCentered = false;
		this.updateCorner();
	}

	private void updateCorner() {
		if (this.corner != null) {
			this.corner.setMessage(this.cornerLabel());
		}
	}

	private void done() {
		this.kept = true;
		Settings.get().save();
		this.minecraft.gui.setScreen(this.parent);
	}

	/** Esc and Cancel. */
	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	/** Gone without Done, it's back where it was, as Xaero's Minimap drops a place that wasn't confirmed. */
	@Override
	public void removed() {
		MinimapHud.holdSize(0);
		if (!this.kept) {
			Settings s = Settings.get();
			s.minimapCorner = this.startCorner;
			s.minimapOffsetX = this.startX;
			s.minimapOffsetY = this.startY;
			s.minimapCentered = this.startCentered;
		}
	}

	// The world shows behind it, with the minimap on it; without a world there's nothing to move.
	//? if >=1.21 {
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (MinimapHud.shown() == null) {
			super.extractBackground(graphics, mouseX, mouseY, a);
		}
	}
	//?}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int[] box = MinimapHud.shown();
		//? if <1.21 {
		/*if (box == null) {
			this.renderBackground(graphics);
		}
		*///?}
		if (box != null) {
			boolean hovered = this.over(box, mouseX, mouseY);
			graphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3], this.dragging ? BOX_HELD : hovered ? BOX_HOVERED : BOX);
		}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		Component message = Component.translatable(box == null ? "diorama.position.not_in_world" : "diorama.position.help");
		graphics.centeredText(this.font, message, this.width / 2, this.height / 6 + 128, 0xFFFFFFFF);
	}

	private boolean over(int[] box, double x, double y) {
		return x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3];
	}

	/** A press no button took: on the minimap, it's picked up. */
	private boolean pressed(double x, double y, int button) {
		int[] box = MinimapHud.shown();
		if (button != InputConstants.MOUSE_BUTTON_LEFT || box == null || !this.over(box, x, y)) {
			return false;
		}
		this.dragging = true;
		this.grabX = box[0] - (int) x;
		this.grabY = box[1] - (int) y;
		// The size it's at now, so it doesn't change under the mouse beside the hotbar.
		MinimapHud.holdSize(box[2]);
		return true;
	}

	/**
	 * Moves the held minimap's top-left to under the mouse, kept by whichever side edge and
	 * whichever of the top and bottom it's nearer, as Xaero's Minimap keeps it. The HUD keeps it on
	 * screen and out of the effect icons; this keeps it off the edges.
	 */
	private boolean dragged(double mouseX, double mouseY, int button) {
		int[] box = MinimapHud.shown();
		if (!this.dragging || button != InputConstants.MOUSE_BUTTON_LEFT || box == null) {
			return false;
		}
		Settings s = Settings.get();
		int x = (int) mouseX + this.grabX;
		int y = (int) mouseY + this.grabY;
		int size = box[2];
		int fromBottom = this.height - y - box[3];
		boolean bottom = y > fromBottom;
		boolean right;
		if (s.minimapCentered) {
			right = s.minimapCorner == Settings.Corner.TOP_RIGHT || s.minimapCorner == Settings.Corner.BOTTOM_RIGHT;
		} else {
			int fromRight = this.width - x - size;
			right = x > fromRight;
			s.minimapOffsetX = Math.max(MinimapHud.MARGIN, right ? fromRight : x);
		}
		s.minimapOffsetY = Math.max(MinimapHud.MARGIN, bottom ? fromBottom : y);
		s.minimapCorner = bottom ? right ? Settings.Corner.BOTTOM_RIGHT : Settings.Corner.BOTTOM_LEFT
			: right ? Settings.Corner.TOP_RIGHT : Settings.Corner.TOP_LEFT;
		this.updateCorner();
		return true;
	}

	private boolean released(int button) {
		if (!this.dragging || button != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		this.dragging = false;
		MinimapHud.holdSize(0);
		return true;
	}

	/** C: midway across, or back by its side edge. */
	private boolean keyDown(int key) {
		if (key != InputConstants.KEY_C) {
			return false;
		}
		Settings s = Settings.get();
		s.minimapCentered = !s.minimapCentered;
		this.updateCorner();
		return true;
	}

	// Mouse and key events came as separate numbers before 1.21.9.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return super.mouseClicked(event, doubleClick) || this.pressed(event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return this.released(event.button()) || super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.x(), event.y(), event.button()) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return this.keyDown(event.key()) || super.keyPressed(event);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return super.mouseClicked(x, y, button) || this.pressed(x, y, button);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return this.released(button) || super.mouseReleased(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(x, y, button) || super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return this.keyDown(key) || super.keyPressed(key, scancode, modifiers);
	}
	*///?}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
