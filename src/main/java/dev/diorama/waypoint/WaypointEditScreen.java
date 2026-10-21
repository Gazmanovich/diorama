package dev.diorama.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.render.MapIcons;
import dev.diorama.render.Pins;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Creates or edits one waypoint: name, initials, position, colour and whether it shows. A preview
 * on the right stands its pin on a piece of the map, as the map will draw it, and follows the
 * colour and initials as they change; Share in chat and Teleport sit under it. The footer is only
 * the two ways out, Save and Cancel. Deleting lives on the list's rows and the map's menu.
 */
public final class WaypointEditScreen extends Screen {
	/** The form on the left, the preview on the right, as wide together as today's list. */
	private static final int WIDTH = 292;
	private static final int FORM = 200;
	private static final int PREVIEW_LEFT = 212;
	private static final int SWATCH = 16;
	private static final int SWATCH_GAP = 4;
	/** Vanilla's colour for a number field it can't read, from the game rules screen. */
	private static final int INVALID_TEXT = 0xFFFF0000;
	private static final int LABEL = 0xFFA0A0A0;

	private final @Nullable Screen parent;
	private final Waypoint waypoint;
	private final boolean isNew;
	/** What the preview draws: the waypoint as the form shows it now. */
	private final Waypoint preview = new Waypoint();
	private final MapPiece piece = new MapPiece(64, 40, 12);
	private EditBox name;
	private EditBox initials;
	private EditBox x;
	private EditBox y;
	private EditBox z;
	private @Nullable Button save;
	private @Nullable Button share;
	private @Nullable Button teleport;
	private int color;
	private boolean visible;
	private int swatchX;
	private int swatchY;

	public WaypointEditScreen(@Nullable Screen parent, Waypoint waypoint, boolean isNew) {
		super(Component.translatable(isNew ? "diorama.waypoint.new" : "diorama.waypoint.edit"));
		this.parent = parent;
		this.waypoint = waypoint;
		this.isNew = isNew;
		this.color = waypoint.color;
		this.visible = waypoint.visible;
	}

	private int left() {
		return this.width / 2 - WIDTH / 2;
	}

	private int top() {
		return this.height / 2 - 92;
	}

	@Override
	protected void init() {
		int left = this.left();
		int top = this.top();
		int cx = left + FORM / 2;
		// Resizing the window or changing GUI scale runs init() again: keep what's typed, not the saved waypoint.
		this.name = this.field(left, top + 16, 156, draft(this.name, this.waypoint.name), "diorama.waypoint.name", 32);
		this.initials = this.field(left + 160, top + 16, 40, draft(this.initials, this.waypoint.initials), "diorama.waypoint.initials", 2);
		this.x = this.field(left, top + 56, 64, draft(this.x, Integer.toString(this.waypoint.x)), "diorama.waypoint.x", 9);
		this.y = this.field(left + 68, top + 56, 64, draft(this.y, Integer.toString(this.waypoint.y)), "diorama.waypoint.y", 9);
		this.z = this.field(left + 136, top + 56, 64, draft(this.z, Integer.toString(this.waypoint.z)), "diorama.waypoint.z", 9);
		this.x.setResponder(text -> this.validate());
		this.y.setResponder(text -> this.validate());
		this.z.setResponder(text -> this.validate());
		this.swatchX = cx - (Waypoint.PALETTE.length / 2 * (SWATCH + SWATCH_GAP) - SWATCH_GAP) / 2;
		this.swatchY = top + 92;
		this.addRenderableWidget(Checkbox.builder(Component.translatable("diorama.waypoint.show_on_maps"), this.font)
			.pos(left, top + 141)
			.selected(this.visible)
			.onValueChange((box, value) -> this.visible = value)
			.build());

		// What to do with this place, under its preview. A new waypoint has none until it's saved.
		int previewLeft = left + PREVIEW_LEFT;
		int previewWidth = WIDTH - PREVIEW_LEFT;
		this.share = null;
		this.teleport = null;
		if (!this.isNew) {
			this.share = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.share"), b -> this.share())
				.bounds(previewLeft, this.previewBottom() + 4, previewWidth, 20).build());
			this.teleport = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.teleport"), b -> this.teleport())
				.bounds(previewLeft, this.previewBottom() + 28, previewWidth, 20).build());
		}

		// The footer: only the two ways out, Save then Cancel, as vanilla orders them.
		int half = (WIDTH - 4) / 2;
		this.save = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.save"), b -> this.save())
			.bounds(left, top + 192, half, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(left + WIDTH - half, top + 192, half, 20).build());
		this.validate();
		this.setInitialFocus(this.name);
	}

	private int previewTop() {
		return this.top() + 16;
	}

	private int previewBottom() {
		return this.top() + 132;
	}

	private static String draft(@Nullable EditBox box, String saved) {
		return box != null ? box.getValue() : saved;
	}

	/**
	 * Colours a coordinate that isn't a whole number red, the way vanilla's game rules screen does,
	 * and keeps Save, Share and Teleport off until every coordinate reads.
	 */
	private void validate() {
		boolean valid = true;
		for (EditBox box : new EditBox[] {this.x, this.y, this.z}) {
			boolean reads = parse(box.getValue()) != null;
			box.setTextColor(reads ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT);
			valid &= reads;
		}
		if (this.save != null) {
			this.save.active = valid;
		}
		if (this.share != null) {
			this.share.active = valid;
		}
		if (this.teleport != null) {
			this.teleport.active = valid && Waypoints.canTeleport();
		}
	}

	private EditBox field(int x, int y, int width, String value, String hint, int maxLength) {
		EditBox box = new EditBox(this.font, x, y, width, 20, Component.translatable(hint));
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setHint(Component.translatable(hint));
		return this.addRenderableWidget(box);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.left();
		int top = this.top();
		graphics.centeredText(this.font, this.title, this.width / 2, top - 12, 0xFFFFFFFF);
		graphics.text(this.font, Component.translatable("diorama.waypoint.name"), left, top + 5, LABEL, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.initials"), left + 160, top + 5, LABEL, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.position"), left, top + 45, LABEL, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.colour"), left, top + 81, LABEL, true);
		for (int i = 0; i < Waypoint.PALETTE.length; i++) {
			float sx = this.swatchX + (i % 8) * (SWATCH + SWATCH_GAP) + SWATCH / 2.0F;
			float sy = this.swatchY + (i / 8) * (SWATCH + SWATCH_GAP) + SWATCH / 2.0F;
			if (Waypoint.PALETTE[i] == this.color) {
				MapIcons.dot(graphics, sx, sy, SWATCH + 6, 0xFFFFFFFF);
			}
			MapIcons.dot(graphics, sx, sy, SWATCH + 2, 0xFF101418);
			MapIcons.dot(graphics, sx, sy, SWATCH, Waypoint.PALETTE[i]);
		}
		this.drawPreview(graphics);
	}

	/**
	 * The pin as the map will draw it, standing on the same piece of map as the empty list, in a
	 * list panel of its own. The pin's head rises above the piece, so the two are centred together.
	 */
	private void drawPreview(GuiGraphicsExtractor graphics) {
		int x0 = this.left() + PREVIEW_LEFT;
		int x1 = this.left() + WIDTH;
		int y0 = this.previewTop();
		int y1 = this.previewBottom();
		graphics.text(this.font, Component.translatable("diorama.waypoint.preview"), x0, this.top() + 5, LABEL, true);
		ScreenArt.list(graphics, x0, y0, x1, y1);
		this.preview.name = this.name.getValue().trim().isEmpty() ? this.waypoint.name : this.name.getValue().trim();
		this.preview.initials = this.initials.getValue().trim().toUpperCase(Locale.ROOT);
		this.preview.color = this.color;
		this.preview.kind = this.waypoint.kind;
		// At twice its size, the pin reaches this far above its tip: its head, its rim and its point.
		int rise = Math.round(2 * (Pins.headAbove(Pins.LARGE) + Pins.LARGE / 2.0F + 1)) - this.piece.tipY();
		int total = rise + this.piece.outerHeight();
		int pieceX = (x0 + x1) / 2 - this.piece.outerWidth() / 2;
		int pieceY = y0 + (y1 - y0 - total) / 2 + rise;
		this.piece.draw(graphics, pieceX, pieceY);
		graphics.pose().pushMatrix();
		graphics.pose().translate(pieceX + this.piece.tipX(), pieceY + this.piece.tipY());
		graphics.pose().scale(2);
		Pins.standing(graphics, this.preview, 0, 0, Pins.LARGE);
		graphics.pose().popMatrix();
	}

	// Mouse events came as separate numbers before 1.21.9.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return this.clicked(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return this.clicked(x, y, button) || super.mouseClicked(x, y, button);
	}
	*///?}

	/** Picks the colour swatch under a left click; false if the click wasn't on one. */
	private boolean clicked(double x, double y, int button) {
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			for (int i = 0; i < Waypoint.PALETTE.length; i++) {
				int sx = this.swatchX + (i % 8) * (SWATCH + SWATCH_GAP);
				int sy = this.swatchY + (i / 8) * (SWATCH + SWATCH_GAP);
				if (x >= sx && x < sx + SWATCH && y >= sy && y < sy + SWATCH) {
					this.color = Waypoint.PALETTE[i];
					return true;
				}
			}
		}
		return false;
	}

	/** The waypoint being edited. For tests. */
	public Waypoint waypoint() {
		return this.waypoint;
	}

	/** The waypoint as the fields show it now, edits included, without saving; null while a coordinate isn't a number. */
	private @Nullable Waypoint shown() {
		Integer x = parse(this.x.getValue());
		Integer y = parse(this.y.getValue());
		Integer z = parse(this.z.getValue());
		if (x == null || y == null || z == null) {
			return null;
		}
		String name = this.name.getValue().trim();
		Waypoint shown = new Waypoint(name.isEmpty() ? this.waypoint.name : name, x, y, z, this.waypoint.dimension, this.color);
		String initials = this.initials.getValue().trim();
		shown.initials = initials.isEmpty() ? shown.initials : initials.toUpperCase(Locale.ROOT);
		return shown;
	}

	/** Sends the waypoint to chat for others to add. */
	private void share() {
		Waypoint shown = this.shown();
		if (shown == null) {
			return;
		}
		if (WaypointShare.send(shown)) {
			this.onClose();
		}
	}

	private void teleport() {
		Waypoint shown = this.shown();
		if (shown == null) {
			return;
		}
		Waypoints.teleport(shown.x, shown.y, shown.z);
		this.onClose();
	}

	private void save() {
		Waypoint shown = this.shown();
		if (shown == null) {
			return;
		}
		WaypointStore store = WaypointStore.get();
		if (store == null) {
			this.onClose();
			return;
		}
		this.waypoint.name = shown.name;
		this.waypoint.initials = this.initials.getValue().trim().toUpperCase(Locale.ROOT);
		this.waypoint.x = shown.x;
		this.waypoint.y = shown.y;
		this.waypoint.z = shown.z;
		this.waypoint.color = this.color;
		this.waypoint.visible = this.visible;
		// Saving a temporary waypoint from the editor keeps it; otherwise a renamed one would still
		// vanish on leaving the world.
		if (this.waypoint.kind == Waypoint.Kind.TEMPORARY) {
			this.waypoint.kind = Waypoint.Kind.MANUAL;
		}
		if (this.isNew) {
			store.add(this.waypoint);
		} else {
			store.changed();
		}
		this.onClose();
	}

	private static @Nullable Integer parse(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
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
