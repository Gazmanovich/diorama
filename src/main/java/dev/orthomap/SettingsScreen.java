package dev.orthomap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Every setting as a button that cycles its value, in three columns: Minimap, Radar, and Waypoints with Look. */
public final class SettingsScreen extends Screen {
	private static final int MAX_BUTTON_WIDTH = 158;

	/** Sized in init() to fit the screen: at 427x240 the full-size layout would run off it. */
	private int buttonWidth = MAX_BUTTON_WIDTH;
	private int row = 22;
	private int buttonHeight = 20;

	private final @Nullable Screen parent;
	private final List<Header> headers = new ArrayList<>();

	private record Header(Component text, int x, int y) {
	}

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("orthomap.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.headers.clear();
		Settings s = Settings.get();
		int gap = 6;
		this.buttonWidth = Math.min(MAX_BUTTON_WIDTH, (this.width - 2 * gap - 16) / 3);
		// The tallest columns have two headers and eight rows, which must clear Done.
		boolean compact = 30 + 2 * 16 + 4 + 8 * 22 > this.height - 30;
		this.row = compact ? 19 : 22;
		this.buttonHeight = compact ? 18 : 20;
		int left = this.width / 2 - this.buttonWidth * 3 / 2 - gap;
		int middle = left + this.buttonWidth + gap;
		int right = middle + this.buttonWidth + gap;
		int top = compact ? 22 : 30;

		int y = this.header("orthomap.settings.minimap", left, top);
		y = this.toggle(left, y, "orthomap.settings.minimap_shown", () -> s.minimap, v -> s.minimap = v);
		y = this.cycle(left, y, "orthomap.settings.size", () -> s.minimapSize.ordinal(), Settings.Size.values().length,
			i -> s.minimapSize = Settings.Size.values()[i], i -> Component.translatable("orthomap.settings.size." + Settings.Size.values()[i].name().toLowerCase(java.util.Locale.ROOT)));
		y = this.cycle(left, y, "orthomap.settings.corner", () -> s.minimapCorner.ordinal(), Settings.Corner.values().length,
			i -> s.minimapCorner = Settings.Corner.values()[i], i -> Component.translatable("orthomap.settings.corner." + Settings.Corner.values()[i].name().toLowerCase(java.util.Locale.ROOT)));
		y = this.cycle(left, y, "orthomap.settings.zoom", () -> s.minimapZoom, Settings.ZOOMS.length,
			i -> s.minimapZoom = i, i -> Component.literal(Settings.ZOOMS[i] + "×"));
		y = this.toggle(left, y, "orthomap.settings.compass", () -> s.compass, v -> s.compass = v);
		y = this.toggle(left, y, "orthomap.settings.coordinates", () -> s.coordinates, v -> s.coordinates = v);
		y = this.toggle(left, y, "orthomap.settings.biome", () -> s.biome, v -> s.biome = v);
		this.toggle(left, y, "orthomap.settings.auto_caves", () -> s.autoCaves, v -> s.autoCaves = v);

		y = this.header("orthomap.settings.radar", middle, top);
		y = this.toggle(middle, y, "orthomap.settings.players", () -> s.radarPlayers, v -> s.radarPlayers = v);
		y = this.toggle(middle, y, "orthomap.settings.hostile", () -> s.radarHostile, v -> s.radarHostile = v);
		y = this.toggle(middle, y, "orthomap.settings.neutral", () -> s.radarNeutral, v -> s.radarNeutral = v);
		y = this.toggle(middle, y, "orthomap.settings.passive", () -> s.radarPassive, v -> s.radarPassive = v);
		int[] ranges = Settings.RANGES;
		y = this.cycle(middle, y, "orthomap.settings.range", () -> indexOf(ranges, s.radarRange), ranges.length,
			i -> s.radarRange = ranges[i], i -> Component.literal(ranges[i] + " m"));

		y = this.header("orthomap.settings.overlays", middle, y + 4);
		y = this.toggle(middle, y, "orthomap.settings.chunk_grid", () -> s.chunkGrid, v -> s.chunkGrid = v);
		y = this.toggle(middle, y, "orthomap.settings.slime_chunks", () -> s.slimeChunks, v -> s.slimeChunks = v);
		this.cycle(middle, y, "orthomap.settings.spawn_spots", () -> s.spawnSpots.ordinal(), Settings.SpawnSpots.values().length,
			i -> s.spawnSpots = Settings.SpawnSpots.values()[i],
			i -> Component.translatable("orthomap.settings.spawn_spots." + Settings.SpawnSpots.values()[i].name().toLowerCase(java.util.Locale.ROOT)));

		y = this.header("orthomap.settings.waypoints", right, top);
		y = this.toggle(right, y, "orthomap.settings.waypoints_map", () -> s.waypointsOnMap, v -> s.waypointsOnMap = v);
		y = this.toggle(right, y, "orthomap.settings.waypoints_world", () -> s.waypointsInWorld, v -> s.waypointsInWorld = v);
		y = this.toggle(right, y, "orthomap.settings.deaths", () -> s.deathMarkers, v -> s.deathMarkers = v);

		y = this.header("orthomap.settings.look", right, y + 4);
		y = this.toggle(right, y, "orthomap.settings.outlines", () -> s.outlines, v -> s.outlines = v);
		y = this.toggle(right, y, "orthomap.settings.rounded_fog", () -> s.roundedFog, v -> s.roundedFog = v);
		this.toggle(right, y, "orthomap.settings.fog_of_war", () -> s.alwaysFogOfWar, v -> s.alwaysFogOfWar = v);

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
			.bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
	}

	private int header(String key, int x, int y) {
		this.headers.add(new Header(Component.translatable(key), x, y + 5));
		return y + 16;
	}

	private int toggle(int x, int y, String key, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
		return this.cycle(x, y, key, () -> get.get() ? 1 : 0, 2, i -> set.accept(i == 1),
			i -> Component.translatable(i == 1 ? "options.on" : "options.off"));
	}

	private int cycle(int x, int y, String key, Supplier<Integer> get, int count, java.util.function.IntConsumer set, Function<Integer, Component> name) {
		Component label = Component.translatable(key);
		Button button = Button.builder(label(label, name.apply(get.get())), b -> {
			int next = (get.get() + 1) % count;
			set.accept(next);
			Settings.get().save();
			b.setMessage(label(label, name.apply(next)));
		}).bounds(x, y, this.buttonWidth, this.buttonHeight).build();
		this.addRenderableWidget(button);
		return y + this.row;
	}

	private static Component label(Component name, Component value) {
		return Component.empty().append(name).append(": ").append(value);
	}

	private static int indexOf(int[] values, int value) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == value) {
				return i;
			}
		}
		return 1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		for (Header header : this.headers) {
			graphics.text(this.font, header.text(), header.x(), header.y(), 0xFFFFD870, false);
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
