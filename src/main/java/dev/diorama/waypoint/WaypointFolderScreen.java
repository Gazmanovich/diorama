package dev.diorama.waypoint;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Makes one of the player's waypoint folders, or renames one. Deleting one is on the waypoint
 * list's folder bar, beside the folder.
 */
public final class WaypointFolderScreen extends Screen {
	private static final int WIDTH = 200;
	private static final int LABEL = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;

	private final WaypointListScreen parent;
	/** The folder being edited, or null for a new one. */
	private final @Nullable String existing;
	private @Nullable EditBox name;
	private @Nullable Button save;
	private @Nullable Component problem;

	public WaypointFolderScreen(WaypointListScreen parent, @Nullable String existing) {
		super(Component.translatable(existing == null ? "diorama.folder.new" : "diorama.folder.rename_title"));
		this.parent = parent;
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
		String typed = this.name != null ? this.name.getValue() : this.existing != null ? this.existing : "";
		EditBox box = new EditBox(this.font, left, top + 16, WIDTH, 20, Component.translatable("diorama.folder.name"));
		box.setMaxLength(32);
		box.setValue(typed);
		box.setHint(Component.translatable("diorama.folder.name_hint"));
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

	/** Save is on only for a name that isn't blank and isn't another folder's; the problem shows under the field. */
	private void validate() {
		WaypointStore store = WaypointStore.get();
		String typed = this.name == null ? "" : this.name.getValue().trim();
		String clash = store == null ? null : store.folderName(typed);
		if (typed.isEmpty()) {
			this.problem = null;
		} else if (clash != null && !clash.equals(this.existing)) {
			this.problem = Component.translatable("diorama.folder.taken");
		} else {
			this.problem = null;
		}
		if (this.save != null) {
			this.save.active = store != null && !typed.isEmpty() && this.problem == null;
		}
	}

	private void save() {
		WaypointStore store = WaypointStore.get();
		if (store == null || this.name == null) {
			return;
		}
		String typed = this.name.getValue().trim();
		boolean done = this.existing == null ? store.addFolder(typed) : store.renameFolder(this.existing, typed);
		if (done) {
			this.parent.showFolder(WaypointStore.customKey(store.folderName(typed)));
			this.onClose();
		}
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
		graphics.text(this.font, Component.translatable("diorama.folder.name"), left, top + 5, LABEL, true);
		if (this.problem != null) {
			graphics.text(this.font, this.problem, left, top + 42, RED, true);
		} else {
			graphics.text(this.font, Component.translatable("diorama.folder.hint"), left, top + 42, LABEL, true);
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
