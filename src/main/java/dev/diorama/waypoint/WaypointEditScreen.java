package dev.diorama.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.render.MapIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Creates or edits one waypoint: name, initials, position, colour and whether it shows. */
public final class WaypointEditScreen extends Screen {
	private static final int SWATCH = 16;
	private static final int SWATCH_GAP = 4;
	/** Vanilla's colour for a number field it can't read, from the game rules screen. */
	private static final int INVALID_TEXT = 0xFFFF0000;

	private final @Nullable Screen parent;
	private final Waypoint waypoint;
	private final boolean isNew;
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

	@Override
	protected void init() {
		int cx = this.width / 2;
		int top = this.height / 2 - 92;
		// Resizing the window or changing GUI scale runs init() again: keep what's typed, not the saved waypoint.
		this.name = this.field(cx - 100, top + 16, 156, draft(this.name, this.waypoint.name), "diorama.waypoint.name", 32);
		this.initials = this.field(cx + 60, top + 16, 40, draft(this.initials, this.waypoint.initials), "diorama.waypoint.initials", 2);
		this.x = this.field(cx - 100, top + 56, 64, draft(this.x, Integer.toString(this.waypoint.x)), "diorama.waypoint.x", 9);
		this.y = this.field(cx - 32, top + 56, 64, draft(this.y, Integer.toString(this.waypoint.y)), "diorama.waypoint.y", 9);
		this.z = this.field(cx + 36, top + 56, 64, draft(this.z, Integer.toString(this.waypoint.z)), "diorama.waypoint.z", 9);
		this.x.setResponder(text -> this.validate());
		this.y.setResponder(text -> this.validate());
		this.z.setResponder(text -> this.validate());
		this.swatchX = cx - (Waypoint.PALETTE.length / 2 * (SWATCH + SWATCH_GAP) - SWATCH_GAP) / 2;
		this.swatchY = top + 92;
		this.addRenderableWidget(Button.builder(this.visibilityLabel(), b -> {
			this.visible = !this.visible;
			b.setMessage(this.visibilityLabel());
		}).bounds(cx - 100, top + 140, 200, 20).build());
		int buttons = top + 168;
		if (this.isNew) {
			this.save = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.save"), b -> this.save()).bounds(cx - 100, buttons, 98, 20).build());
			this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose()).bounds(cx + 2, buttons, 98, 20).build());
		} else {
			this.save = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.save"), b -> this.save()).bounds(cx - 100, buttons, 64, 20).build());
			this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.delete"), b -> this.delete()).bounds(cx - 32, buttons, 64, 20).build());
			this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose()).bounds(cx + 36, buttons, 64, 20).build());
			this.share = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.share"), b -> this.share()).bounds(cx - 100, buttons + 24, 98, 20).build());
			this.teleport = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.teleport"), b -> this.teleport()).bounds(cx + 2, buttons + 24, 98, 20).build());
		}
		this.validate();
		this.setInitialFocus(this.name);
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

	private Component visibilityLabel() {
		return Component.translatable(this.visible ? "diorama.waypoint.shown" : "diorama.waypoint.hidden");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int cx = this.width / 2;
		int top = this.height / 2 - 92;
		graphics.centeredText(this.font, this.title, cx, top - 12, 0xFFFFFFFF);
		graphics.text(this.font, Component.translatable("diorama.waypoint.name"), cx - 100, top + 5, 0xFFA0A0A0, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.initials"), cx + 60, top + 5, 0xFFA0A0A0, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.position"), cx - 100, top + 45, 0xFFA0A0A0, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.colour"), cx - 100, top + 81, 0xFFA0A0A0, true);
		for (int i = 0; i < Waypoint.PALETTE.length; i++) {
			float sx = this.swatchX + (i % 8) * (SWATCH + SWATCH_GAP) + SWATCH / 2.0F;
			float sy = this.swatchY + (i / 8) * (SWATCH + SWATCH_GAP) + SWATCH / 2.0F;
			if (Waypoint.PALETTE[i] == this.color) {
				MapIcons.dot(graphics, sx, sy, SWATCH + 6, 0xFFFFFFFF);
			}
			MapIcons.dot(graphics, sx, sy, SWATCH + 2, 0xFF101418);
			MapIcons.dot(graphics, sx, sy, SWATCH, Waypoint.PALETTE[i]);
		}
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
		shown.initials = initials.isEmpty() ? shown.initials : initials.toUpperCase(java.util.Locale.ROOT);
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
		this.waypoint.initials = this.initials.getValue().trim().toUpperCase(java.util.Locale.ROOT);
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

	private void delete() {
		WaypointStore store = WaypointStore.get();
		if (store != null) {
			store.remove(this.waypoint);
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
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
