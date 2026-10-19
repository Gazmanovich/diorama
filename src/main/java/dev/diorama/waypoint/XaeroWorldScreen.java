package dev.diorama.waypoint;

import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Asks which of Xaero's Minimap's worlds is the one the player is in, when Xaero's kept several
 * and there's no telling which it would open. Each dimension's file is its own row, since Xaero's
 * World Map picks a world per dimension, and each shows its first few waypoint names, which is
 * how a player recognises it. Stays open, so the player can take one for each dimension.
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
		this.visibleRows = Math.max(1, (this.height - this.listTop - 60) / ROW);
		this.scroll = Math.max(0, Math.min(this.scroll, this.worlds.size() - this.visibleRows));
		WaypointStore store = WaypointStore.get();
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.worlds.size(); i++) {
			XaeroImport.World world = this.worlds.get(this.scroll + i);
			int fresh = store == null ? 0 : XaeroImport.missing(world, store).size();
			Component label = fresh == 0
				? Component.translatable("diorama.xaero.world_done", world.label(), dimensionName(world.dimension()))
				: Component.translatable("diorama.xaero.world", world.label(), dimensionName(world.dimension()), fresh);
			Button button = Button.builder(label, b -> {
				this.parent.importFrom(world);
				this.rebuildWidgets();
			}).bounds((this.width - WIDTH) / 2, this.listTop + i * ROW, WIDTH, 20).build();
			button.active = fresh > 0;
			this.addRenderableWidget(button);
		}
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
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
		if (this.parent.imported() != null) {
			graphics.centeredText(this.font, this.parent.imported(), this.width / 2, this.height - 46, 0xFFA0E0A0);
		}
	}

	/** "Overworld", "Nether", "End", or the id of any other dimension. */
	private static Component dimensionName(String id) {
		return switch (id) {
			case "minecraft:overworld" -> Component.translatable("diorama.xaero.overworld");
			case "minecraft:the_nether" -> Component.translatable("diorama.xaero.nether");
			case "minecraft:the_end" -> Component.translatable("diorama.xaero.end");
			default -> Component.literal(id);
		};
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
