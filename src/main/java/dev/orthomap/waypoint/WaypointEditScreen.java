package dev.orthomap.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orthomap.render.MapIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Creates or edits one waypoint: name, initials, position, colour and whether it shows. */
public final class WaypointEditScreen extends Screen {
	private static final int SWATCH = 16;
	private static final int SWATCH_GAP = 4;

	private final @Nullable Screen parent;
	private final Waypoint waypoint;
	private final boolean isNew;
	private EditBox name;
	private EditBox initials;
	private EditBox x;
	private EditBox y;
	private EditBox z;
	private int color;
	private boolean visible;
	private int swatchX;
	private int swatchY;

	public WaypointEditScreen(@Nullable Screen parent, Waypoint waypoint, boolean isNew) {
		super(Component.translatable(isNew ? "orthomap.waypoint.new" : "orthomap.waypoint.edit"));
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
		this.name = this.field(cx - 100, top + 16, 156, this.waypoint.name, "orthomap.waypoint.name", 32);
		this.initials = this.field(cx + 60, top + 16, 40, this.waypoint.initials, "orthomap.waypoint.initials", 2);
		this.x = this.field(cx - 100, top + 56, 64, Integer.toString(this.waypoint.x), "orthomap.waypoint.x", 9);
		this.y = this.field(cx - 32, top + 56, 64, Integer.toString(this.waypoint.y), "orthomap.waypoint.y", 9);
		this.z = this.field(cx + 36, top + 56, 64, Integer.toString(this.waypoint.z), "orthomap.waypoint.z", 9);
		this.swatchX = cx - (Waypoint.PALETTE.length / 2 * (SWATCH + SWATCH_GAP) - SWATCH_GAP) / 2;
		this.swatchY = top + 92;
		this.addRenderableWidget(Button.builder(this.visibilityLabel(), b -> {
			this.visible = !this.visible;
			b.setMessage(this.visibilityLabel());
		}).bounds(cx - 100, top + 140, 200, 20).build());
		int buttons = top + 168;
		if (this.isNew) {
			this.addRenderableWidget(Button.builder(Component.translatable("orthomap.waypoint.save"), b -> this.save()).bounds(cx - 100, buttons, 98, 20).build());
			this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose()).bounds(cx + 2, buttons, 98, 20).build());
		} else {
			this.addRenderableWidget(Button.builder(Component.translatable("orthomap.waypoint.save"), b -> this.save()).bounds(cx - 100, buttons, 64, 20).build());
			this.addRenderableWidget(Button.builder(Component.translatable("orthomap.waypoint.delete"), b -> this.delete()).bounds(cx - 32, buttons, 64, 20).build());
			this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose()).bounds(cx + 36, buttons, 64, 20).build());
		}
		this.setInitialFocus(this.name);
	}

	private EditBox field(int x, int y, int width, String value, String hint, int maxLength) {
		EditBox box = new EditBox(this.font, x, y, width, 20, Component.translatable(hint));
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setHint(Component.translatable(hint));
		return this.addRenderableWidget(box);
	}

	private Component visibilityLabel() {
		return Component.translatable(this.visible ? "orthomap.waypoint.shown" : "orthomap.waypoint.hidden");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int cx = this.width / 2;
		int top = this.height / 2 - 92;
		graphics.centeredText(this.font, this.title, cx, top - 12, 0xFFFFFFFF);
		graphics.text(this.font, Component.translatable("orthomap.waypoint.name"), cx - 100, top + 5, 0xFFA0A0A0, false);
		graphics.text(this.font, Component.translatable("orthomap.waypoint.initials"), cx + 60, top + 5, 0xFFA0A0A0, false);
		graphics.text(this.font, Component.translatable("orthomap.waypoint.position"), cx - 100, top + 45, 0xFFA0A0A0, false);
		graphics.text(this.font, Component.translatable("orthomap.waypoint.colour"), cx - 100, top + 81, 0xFFA0A0A0, false);
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

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (int i = 0; i < Waypoint.PALETTE.length; i++) {
				int sx = this.swatchX + (i % 8) * (SWATCH + SWATCH_GAP);
				int sy = this.swatchY + (i / 8) * (SWATCH + SWATCH_GAP);
				if (event.x() >= sx && event.x() < sx + SWATCH && event.y() >= sy && event.y() < sy + SWATCH) {
					this.color = Waypoint.PALETTE[i];
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void save() {
		WaypointStore store = WaypointStore.get();
		if (store == null) {
			this.onClose();
			return;
		}
		String name = this.name.getValue().trim();
		this.waypoint.name = name.isEmpty() ? this.waypoint.name : name;
		this.waypoint.initials = this.initials.getValue().trim().toUpperCase(java.util.Locale.ROOT);
		this.waypoint.x = parse(this.x.getValue(), this.waypoint.x);
		this.waypoint.y = parse(this.y.getValue(), this.waypoint.y);
		this.waypoint.z = parse(this.z.getValue(), this.waypoint.z);
		this.waypoint.color = this.color;
		this.waypoint.visible = this.visible;
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

	private static int parse(String text, int fallback) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
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
