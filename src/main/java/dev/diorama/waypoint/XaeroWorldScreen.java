package dev.diorama.waypoint;

import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Asks which of Xaero's Minimap's worlds for this server is the one the player is in, when
 * Xaero's kept several and there's no telling which it would open. Each world shows the names of
 * its first few waypoints, which is how a player recognises it.
 */
final class XaeroWorldScreen extends Screen {
	private static final int ROW = 36;
	private static final int WIDTH = 300;

	private final WaypointListScreen parent;
	private final List<XaeroImport.World> worlds;
	private int scroll;
	private int listTop;
	private int visibleRows;

	XaeroWorldScreen(WaypointListScreen parent, List<XaeroImport.World> worlds) {
		super(Component.translatable("diorama.xaero.pick_title"));
		this.parent = parent;
		this.worlds = worlds;
	}

	@Override
	protected void init() {
		this.listTop = 50;
		this.visibleRows = Math.max(1, (this.height - this.listTop - 40) / ROW);
		this.scroll = Math.max(0, Math.min(this.scroll, this.worlds.size() - this.visibleRows));
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.worlds.size(); i++) {
			XaeroImport.World world = this.worlds.get(this.scroll + i);
			Component label = Component.translatable("diorama.xaero.world", world.label(), world.entries().size() == 1
				? Component.translatable("diorama.xaero.count_one")
				: Component.translatable("diorama.xaero.count", world.entries().size()));
			this.addRenderableWidget(Button.builder(label, b -> {
				this.parent.importFrom(world);
				this.minecraft.gui.setScreen(this.parent);
			}).bounds((this.width - WIDTH) / 2, this.listTop + i * ROW, WIDTH, 20).build());
		}
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose())
			.bounds(this.width / 2 - 50, this.height - 30, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
		graphics.centeredText(this.font, Component.translatable("diorama.xaero.pick_detail"), this.width / 2, 30, 0xFFA0A0A0);
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.worlds.size(); i++) {
			XaeroImport.World world = this.worlds.get(this.scroll + i);
			String names = world.entries().stream().limit(4).map(entry -> entry.waypoint().name).collect(Collectors.joining(", "));
			if (world.entries().size() > 4) {
				names += ", ...";
			}
			graphics.centeredText(this.font, names, this.width / 2, this.listTop + i * ROW + 23, 0xFF909090);
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int next = Math.max(0, Math.min(this.scroll - (int) Math.signum(scrollY), this.worlds.size() - this.visibleRows));
		if (next != this.scroll) {
			this.scroll = next;
			this.rebuildWidgets();
		}
		return true;
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
