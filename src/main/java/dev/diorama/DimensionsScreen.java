package dev.diorama;

import dev.diorama.waypoint.Waypoints;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Picks the dimensions where the map is off: a ticked one has no minimap, no map and no waypoints
 * in the world, and nothing is mapped there. Lists every dimension of the world the player is in,
 * a mod's included, so nobody has to know an ID; Xaero's takes its lists of dimensions as typed
 * IDs. The ones the server's file turns off are ticked and greyed out.
 */
public final class DimensionsScreen extends Screen {
	private static final int WIDTH = 280;
	private static final int COLUMN_GAP = 8;
	private static final int ROW = 22;
	private static final int TOP = 62;

	private final Screen parent;
	private int page;

	public DimensionsScreen(Screen parent) {
		super(Component.translatable("diorama.settings.map_off"));
		this.parent = parent;
	}

	/**
	 * The dimensions to choose from: the world's own, Minecraft's three first, then any the player
	 * or the server turned off that this world doesn't have, so those can be seen and unticked.
	 */
	public static List<String> dimensions() {
		List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		Set<String> others = new TreeSet<>();
		if (connection != null) {
			connection.levels().forEach(level -> others.add(level.identifier().toString()));
			// A world can lack one of Minecraft's three, and then it isn't offered.
			dimensions.retainAll(others);
		}
		others.addAll(Settings.get().mapOffDimensions);
		others.addAll(serverSet());
		others.removeAll(dimensions);
		dimensions.addAll(others);
		return dimensions;
	}

	/** The dimensions the server's settings turn the map off in, of the ones a player could be in. */
	private static Set<String> serverSet() {
		Set<String> set = new TreeSet<>();
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.levels().forEach(level -> {
				String id = level.identifier().toString();
				if (ServerRules.mapOffByServer(id)) {
					set.add(id);
				}
			});
		}
		return set;
	}

	/** What the settings button says: which dimensions have the map off, the server's included. */
	public static Component summary() {
		Set<String> off = new TreeSet<>(Settings.get().mapOffDimensions);
		off.addAll(serverSet());
		return switch (off.size()) {
			case 0 -> Component.translatable("diorama.settings.map_off.none");
			case 1 -> Waypoints.dimensionName(off.iterator().next());
			default -> Component.literal(Integer.toString(off.size()));
		};
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - TOP - 36) / ROW);
	}

	@Override
	protected void init() {
		int left = this.width / 2 - WIDTH / 2;
		int column = (WIDTH - COLUMN_GAP) / 2;
		MultiLineTextWidget about = new MultiLineTextWidget(Component.translatable("diorama.map_off.about").withStyle(ChatFormatting.GRAY), this.font);
		about.setMaxWidth(WIDTH);
		about.setCentered(true);
		about.setPosition(this.width / 2 - about.getWidth() / 2, 30);
		this.addRenderableWidget(about);

		List<String> dimensions = dimensions();
		Set<String> mine = Settings.get().mapOffDimensions;
		int perPage = this.rowsPerPage() * 2;
		int pages = Math.max(1, (dimensions.size() + perPage - 1) / perPage);
		this.page = Math.min(this.page, pages - 1);
		for (int i = this.page * perPage; i < Math.min(dimensions.size(), (this.page + 1) * perPage); i++) {
			String dimension = dimensions.get(i);
			boolean server = ServerRules.mapOffByServer(dimension);
			Checkbox checkbox = SettingsScreen.checkbox(Waypoints.dimensionName(dimension), this.font, server || mine.contains(dimension), column, off -> {
				if (off) {
					mine.add(dimension);
				} else {
					mine.remove(dimension);
				}
			});
			int slot = i - this.page * perPage;
			checkbox.setPosition(left + (slot % 2) * (column + COLUMN_GAP), TOP + (slot / 2) * ROW);
			Component id = Component.literal(dimension);
			if (server) {
				checkbox.active = false;
				checkbox.setAlpha(0.5F);
				checkbox.setTooltip(Tooltip.create(Component.empty().append(SettingsScreen.serverSetLine(false)).append("\n\n").append(id)));
			} else {
				checkbox.setTooltip(Tooltip.create(id));
			}
			this.addRenderableWidget(checkbox);
		}
		int bottom = this.height - 26;
		if (pages > 1) {
			Button back = Button.builder(Component.literal("<"), b -> this.turn(-1)).bounds(left, bottom, 20, 20).build();
			back.active = this.page > 0;
			this.addRenderableWidget(back);
			Button on = Button.builder(Component.literal(">"), b -> this.turn(1)).bounds(left + WIDTH - 20, bottom, 20, 20).build();
			on.active = this.page < pages - 1;
			this.addRenderableWidget(on);
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
			.bounds(this.width / 2 - 100, bottom, 200, 20).build());
	}

	private void turn(int pages) {
		this.page += pages;
		this.rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// A 1.20.1 screen draws its own background.
		//? if <1.21 {
		/*this.renderBackground(graphics);
		*///?}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
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
