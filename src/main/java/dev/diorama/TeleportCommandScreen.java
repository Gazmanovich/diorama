package dev.diorama;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Changes the command Diorama sends to teleport, for servers where players can't use /tp but
 * have a teleport command of their own.
 */
public final class TeleportCommandScreen extends Screen {
	private static final int WIDTH = 260;
	private static final int LABEL = 0xFFA0A0A0;

	private final Screen parent;
	private @Nullable EditBox command;

	public TeleportCommandScreen(Screen parent) {
		super(Component.translatable("diorama.settings.teleport_command"));
		this.parent = parent;
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
		String typed = this.command != null ? this.command.getValue() : "/" + Settings.get().teleportCommand;
		EditBox box = new EditBox(this.font, left, top + 16, WIDTH, 20, Component.translatable("diorama.settings.teleport_command"));
		// A command can be 256 characters, and the box shows it with its slash.
		box.setMaxLength(257);
		box.setValue(typed);
		box.setHint(Component.literal("/" + Settings.DEFAULT_TELEPORT_COMMAND));
		this.command = this.addRenderableWidget(box);
		int third = (WIDTH - 8) / 3;
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.save"), b -> this.save())
			.bounds(left, top + 64, third, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.teleport_command.reset"), b -> box.setValue("/" + Settings.DEFAULT_TELEPORT_COMMAND))
			.bounds(left + third + 4, top + 64, third, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(left + WIDTH - third, top + 64, third, 20).build());
		this.setInitialFocus(box);
	}

	private void save() {
		if (this.command != null) {
			Settings settings = Settings.get();
			settings.teleportCommand = Settings.cleanCommand(this.command.getValue());
			settings.save();
		}
		this.onClose();
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
		graphics.centeredText(this.font, this.title, this.width / 2, top - 12, 0xFFFFFFFF);
		graphics.text(this.font, Component.translatable("diorama.teleport_command.label"), left, top + 5, LABEL, true);
		graphics.text(this.font, Component.translatable("diorama.teleport_command.hint"), left, top + 42, LABEL, true);
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
