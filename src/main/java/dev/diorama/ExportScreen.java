package dev.diorama;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ImageExport;
import dev.diorama.data.TileStore;
import dev.diorama.render.MapViewport;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingStore;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

/**
 * Saving the map as an image: a sidebar with the choices, the map beside it with the box that
 * will be saved, and Save image and Cancel along the bottom. In Area mode the box starts inside
 * the view the map had; drag inside it to move it, its edges or corners to resize it, outside it
 * to pan. Whole map boxes everything explored. Cancel and Save go back to the map as it was.
 */
public final class ExportScreen extends Screen {
	private static final int SIDEBAR = 112;
	private static final int FOOTER = 28;
	/** Over the map, but see-through enough that it still reads as the map. */
	private static final int PANEL = 0xC0000000;
	private static final int EDGE = 0xFF3A3A42;
	private static final int MUTED = 0xFFB4BCC8;
	private static final int DIM = 0x90000000;
	/** How close to an edge or corner, in GUI pixels, a press takes hold of it. */
	private static final int GRAB = 4;
	/** The smallest box, in map units. */
	private static final double MIN_BOX = 16;
	private static final String[] DETAIL_LABELS = {"4", "2", "1", "½"};
	private static final int LEFT = 1;
	private static final int RIGHT = 2;
	private static final int TOP = 4;
	private static final int BOTTOM = 8;
	private static final int MOVE = 16;
	private static final int PAN = 32;

	private final MapScreen parent;
	private final MapViewport viewport = new MapViewport();
	/** The middle of the preview, in map units, and its zoom in GUI pixels per unit. */
	private double centerU;
	private double centerV;
	private double zoom;
	private boolean whole;
	/** The Area box, in map units; kept while Whole map is shown. */
	private double u0;
	private double v0;
	private double u1;
	private double v1;
	/** Everything explored with a margin of clouds, once found; null until then or when nothing is. */
	private double @Nullable [] explored;
	private @Nullable CompletableFuture<double @Nullable []> finding;
	/** The saved tiles {@link #explored} was found in. */
	private @Nullable TileStore boundsStore;
	/** The level chosen with the detail buttons, or -1 to follow the default. */
	private int chosenLod = -1;
	private final Button[] detail = new Button[4];
	private @Nullable Button save;
	/** Whether the player's drawings go in the image; only asked when this map has some. */
	private boolean withDrawings = true;
	/** What the current press is doing: the {@link #LEFT}... bits, {@link #MOVE}, {@link #PAN}, or 0. */
	private int grip;
	private int pressButton = -1;
	/** Which detail buttons have the "too big" tooltip, so it's only set again when that changes. */
	private final boolean[] tooBig = new boolean[4];

	public ExportScreen(MapScreen parent, double centerU, double centerV, double zoom, int width, int height) {
		super(Component.translatable("diorama.export.title"));
		this.parent = parent;
		this.zoom = zoom;
		// The parent's view filled the screen; the preview is the part beside the sidebar and above the footer.
		this.centerU = centerU + (SIDEBAR / 2.0) / zoom;
		this.centerV = centerV - (FOOTER / 2.0) / zoom;
		double halfW = (width - SIDEBAR) / 2.0 / zoom;
		double halfH = (height - FOOTER) / 2.0 / zoom;
		this.u0 = this.centerU - halfW * 0.75;
		this.u1 = this.centerU + halfW * 0.75;
		this.v0 = this.centerV - halfH * 0.75;
		this.v1 = this.centerV + halfH * 0.75;
	}

	@Override
	protected void init() {
		this.pressButton = -1;
		this.grip = 0;
		java.util.Arrays.fill(this.tooBig, false);
		for (int i = 0; i < this.detail.length; i++) {
			int lod = i;
			this.detail[i] = this.addRenderableWidget(Button.builder(Component.literal(DETAIL_LABELS[i]), b -> {
				this.chosenLod = lod;
			}).bounds(6 + i * 25, 46, 24, 20).build());
		}
		if (!this.drawings().isEmpty()) {
			//? if >=1.21 {
			this.addRenderableWidget(Checkbox.builder(Component.translatable("diorama.export.drawings"), this.font)
				.pos(6, 104)
				.selected(this.withDrawings)
				.onValueChange((box, value) -> this.withDrawings = value)
				.build());
			//?} else {
			/*// 1.20.1's checkbox has no builder and tells no one it changed.
			Component label = Component.translatable("diorama.export.drawings");
			this.addRenderableWidget(new Checkbox(6, 104, 24 + this.font.width(label), 20, label, this.withDrawings) {
				@Override
				public void onPress() {
					super.onPress();
					ExportScreen.this.withDrawings = this.selected();
				}
			});
			*///?}
		}
		this.save = this.addRenderableWidget(Button.builder(Component.translatable("diorama.export.save"), b -> this.save())
			.bounds(6, this.height - FOOTER + 4, 80, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(90, this.height - FOOTER + 4, 60, 20).build());
	}

	private int previewWidth() {
		return this.width - SIDEBAR;
	}

	private int previewHeight() {
		return this.height - FOOTER;
	}

	/** The box being saved, in map units: the Area box, or everything explored. Null while Whole map is still looking. */
	private double @Nullable [] box() {
		return this.whole ? this.explored : new double[] {this.u0, this.v0, this.u1, this.v1};
	}

	private List<Integer> levels(double[] box) {
		return ImageExport.levels(box[0], box[1], box[2], box[3]);
	}

	/** The level the export will be saved at for this box. */
	private int lod(double[] box) {
		List<Integer> levels = this.levels(box);
		if (this.chosenLod >= 0 && levels.contains(this.chosenLod)) {
			return this.chosenLod;
		}
		if (this.chosenLod >= 0) {
			// The chosen level is too big for this box: the next one down that isn't.
			for (int lod : levels) {
				if (lod > this.chosenLod) {
					return lod;
				}
			}
		}
		return ImageExport.defaultLevel(box[0], box[1], box[2], box[3]);
	}

	private double screenX(double u) {
		return SIDEBAR + this.previewWidth() / 2.0 + (u - this.centerU) * this.zoom;
	}

	private double screenY(double v) {
		return this.previewHeight() / 2.0 + (v - this.centerV) * this.zoom;
	}

	// No blurred, dimmed world behind the map. On 1.20.1 a screen draws no background unless it asks for one.
	//? if >=1.21 {
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}
	//?}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		MapSession session = MapSession.get();
		if (session == null || session.map().savedMap() == null || !ServerRules.minimapAllowed()) {
			this.onClose();
			return;
		}
		if (this.whole && session.map().savedMap() != this.boundsStore) {
			// The map moved to other saved tiles (another dimension, or the view under the Nether's
			// roof changed kind): what's explored has to be found again there.
			this.whole = false;
			this.showWhole(true);
		}
		if (this.whole && this.explored != null && this.finding != null) {
			// Found just now: fit it into the preview.
			this.finding = null;
			this.fit(this.explored);
		}
		// One view over the whole screen, placed so the preview's middle is (centerU, centerV).
		double fullU = this.centerU - (SIDEBAR / 2.0) / this.zoom;
		double fullV = this.centerV + (FOOTER / 2.0) / this.zoom;
		this.viewport.draw(graphics, session.map(), new MapViewport.View(0, 0, this.width, this.height, fullU, fullV, this.zoom, TileKey.SURFACE));

		double[] box = this.box();
		int pw = this.previewWidth();
		int ph = this.previewHeight();
		if (box != null) {
			int x0 = (int) Math.round(Math.max(SIDEBAR, Math.min(this.width, this.screenX(box[0]))));
			int x1 = (int) Math.round(Math.max(SIDEBAR, Math.min(this.width, this.screenX(box[2]))));
			int y0 = (int) Math.round(Math.max(0, Math.min(ph, this.screenY(box[1]))));
			int y1 = (int) Math.round(Math.max(0, Math.min(ph, this.screenY(box[3]))));
			graphics.fill(SIDEBAR, 0, this.width, y0, DIM);
			graphics.fill(SIDEBAR, y1, this.width, ph, DIM);
			graphics.fill(SIDEBAR, y0, x0, y1, DIM);
			graphics.fill(x1, y0, this.width, y1, DIM);
			this.outline(graphics, box);
		} else if (this.whole) {
			graphics.fill(SIDEBAR, 0, this.width, ph, DIM);
			Component what = Component.translatable(this.finding != null ? "diorama.export.finding" : "diorama.export.nothing");
			graphics.centeredText(this.font, what, SIDEBAR + pw / 2, ph / 2 - 4, 0xFFFFFFFF);
		}

		// The sidebar and footer, over the map.
		graphics.fill(0, 0, SIDEBAR, ph, PANEL);
		graphics.fill(SIDEBAR - 1, 0, SIDEBAR, ph, EDGE);
		graphics.fill(0, ph, this.width, this.height, PANEL);
		graphics.fill(0, ph, this.width, ph + 1, EDGE);
		this.drawTabs(graphics, mouseX, mouseY);
		graphics.text(this.font, Component.translatable("diorama.export.detail"), 6, 34, MUTED, false);

		int lod = box != null ? this.lod(box) : -1;
		List<Integer> levels = box != null ? this.levels(box) : List.of();
		for (int i = 0; i < this.detail.length; i++) {
			this.detail[i].active = box != null && levels.contains(i) && i != lod;
			boolean tooBig = box != null && !levels.contains(i);
			if (tooBig != this.tooBig[i]) {
				this.tooBig[i] = tooBig;
				this.detail[i].setTooltip(tooBig ? Tooltip.create(Component.translatable("diorama.export.too_big", number(ImageExport.MAX_EDGE))) : null);
			}
		}
		if (this.save != null) {
			this.save.active = box != null && !levels.isEmpty() && MapExport.running() == null;
		}
		if (box != null && !levels.isEmpty()) {
			ImageExport.Area area = ImageExport.Area.ofUnits(lod, box[0], box[1], box[2], box[3]);
			graphics.text(this.font, Component.translatable("diorama.export.pixels", number(area.width()), number(area.height())), 6, 76, 0xFFFFFFFF, false);
			graphics.text(this.font, size(ImageExport.estimateBytes(area)), 6, 88, MUTED, false);
			// In map units, a block each, so it reads against pixels per block.
			Component blocks = Component.translatable("diorama.export.blocks", number(Math.round(box[2] - box[0])), number(Math.round(box[3] - box[1])));
			graphics.fill(SIDEBAR + 4, 4, SIDEBAR + 10 + this.font.width(blocks), 16, DIM);
			graphics.text(this.font, blocks, SIDEBAR + 7, 6, 0xFFFFFFFF, false);
		}
		if (!this.whole) {
			// Beside the buttons, not over what's being saved.
			String help = this.font.plainSubstrByWidth(Component.translatable("diorama.export.help").getString(), Math.max(0, this.width - 164));
			graphics.text(this.font, help, 158, ph + 10, MUTED, false);
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
		// The chosen level: a dark face with a white rim and underline, so it doesn't read as hovered or greyed out.
		if (lod >= 0) {
			Button chosen = this.detail[lod];
			int x = chosen.getX();
			int y = chosen.getY();
			graphics.fill(x, y, x + chosen.getWidth(), y + chosen.getHeight(), 0xFFFFFFFF);
			graphics.fill(x + 1, y + 1, x + chosen.getWidth() - 1, y + chosen.getHeight() - 1, 0xFF101014);
			String label = DETAIL_LABELS[lod];
			int lx = x + (chosen.getWidth() - this.font.width(label)) / 2;
			graphics.text(this.font, label, lx, y + 6, 0xFFFFFFFF, true);
			graphics.fill(lx - 1, y + 15, lx + this.font.width(label), y + 16, 0xFFFFFFFF);
		}
	}

	/** The box's edge and, in Area mode, its eight handles. */
	private void outline(GuiGraphicsExtractor graphics, double[] box) {
		int x0 = (int) Math.round(this.screenX(box[0]));
		int x1 = (int) Math.round(this.screenX(box[2]));
		int y0 = (int) Math.round(this.screenY(box[1]));
		int y1 = (int) Math.round(this.screenY(box[3]));
		graphics.enableScissor(SIDEBAR, 0, this.width, this.previewHeight());
		graphics.fill(x0 - 1, y0 - 1, x1 + 1, y0, 0xFFFFFFFF);
		graphics.fill(x0 - 1, y1, x1 + 1, y1 + 1, 0xFFFFFFFF);
		graphics.fill(x0 - 1, y0, x0, y1, 0xFFFFFFFF);
		graphics.fill(x1, y0, x1 + 1, y1, 0xFFFFFFFF);
		if (!this.whole) {
			int mx = (x0 + x1) / 2;
			int my = (y0 + y1) / 2;
			for (int[] p : new int[][] {{x0, y0}, {mx, y0}, {x1, y0}, {x0, my}, {x1, my}, {x0, y1}, {mx, y1}, {x1, y1}}) {
				graphics.fill(p[0] - 3, p[1] - 3, p[0] + 3, p[1] + 3, 0xFF000000);
				graphics.fill(p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2, 0xFFFFFFFF);
			}
		}
		graphics.disableScissor();
	}

	/** Area and Whole map, drawn as the map's own Surface and Caves tabs are. */
	private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = 6;
		for (int i = 0; i < 2; i++) {
			boolean selected = (i == 1) == this.whole;
			Component name = Component.translatable(i == 0 ? "diorama.export.area" : "diorama.export.whole");
			int nameWidth = this.font.width(name);
			int w = this.tabWidth(i);
			int h = selected ? 20 : 18;
			int y = 6;
			boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 18;
			MapScreen.tab(graphics, selected, hovered, x, y, w, h);
			if (selected) {
				//? if >=1.21 {
				graphics.fill(x + 2, y + 2, x + w - 2, y + h, 0x40000000);
				//?}
				int underline = nameWidth - 1;
				graphics.fill(x + (w - underline) / 2, y + h - 3, x + (w - underline) / 2 + underline, y + h - 2, 0xFFFFFFFF);
			}
			graphics.text(this.font, name, x + w / 2 - nameWidth / 2, y + (selected ? 5 : 6), selected ? 0xFFFFFFFF : 0xFFE0E0E0, true);
			x += w;
		}
	}

	/** The tabs share the sidebar's width, Whole map getting what its name needs. */
	private int tabWidth(int i) {
		int whole = Math.max(this.font.width(Component.translatable("diorama.export.whole")) + 12, 50);
		return i == 1 ? Math.min(whole, SIDEBAR - 12 - 30) : SIDEBAR - 12 - Math.min(whole, SIDEBAR - 12 - 30);
	}

	/** The tab under a point: 0 Area, 1 Whole map, -1 neither. */
	private int tabAt(double x, double y) {
		if (y < 6 || y >= 24) {
			return -1;
		}
		if (x >= 6 && x < 6 + this.tabWidth(0)) {
			return 0;
		}
		if (x >= 6 + this.tabWidth(0) && x < 6 + this.tabWidth(0) + this.tabWidth(1)) {
			return 1;
		}
		return -1;
	}

	private void showWhole(boolean whole) {
		if (whole == this.whole) {
			return;
		}
		this.whole = whole;
		if (!whole) {
			// Back to the box that was drawn, in view.
			this.fit(new double[] {this.u0, this.v0, this.u1, this.v1});
			return;
		}
		MapSession session = MapSession.get();
		TileStore store = session == null ? null : session.map().savedMap();
		if (store == null) {
			return;
		}
		this.explored = null;
		this.boundsStore = store;
		CompletableFuture<double @Nullable []> found = CompletableFuture.supplyAsync(() -> {
			try {
				store.catchUp();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			ImageExport.Area area = ImageExport.explored(store, 0);
			if (area == null) {
				return null;
			}
			double[] units = area.units();
			double m = ImageExport.WHOLE_MAP_MARGIN;
			return new double[] {units[0] - m, units[1] - m, units[2] + m, units[3] + m};
		});
		this.finding = found;
		found.whenCompleteAsync((units, failure) -> {
			if (failure != null) {
				Diorama.LOGGER.warn("Couldn't find what's explored for an export", failure);
			}
			if (this.finding == found) {
				this.explored = failure != null ? null : units;
				if (this.explored == null) {
					// Nothing found, or the search failed: stop saying it's looking.
					this.finding = null;
				}
			}
		}, this.minecraft);
	}

	/** The zoom that shows a box with a little room around it in the preview. */
	private double fitZoom(double[] box) {
		double zoom = Math.min(this.previewWidth() / (box[2] - box[0]), this.previewHeight() / (box[3] - box[1])) * 0.9;
		return Math.max(0.04, Math.min(16, zoom));
	}

	private void fit(double[] box) {
		this.centerU = (box[0] + box[2]) / 2;
		this.centerV = (box[1] + box[3]) / 2;
		this.zoom = this.fitZoom(box);
	}

	/** The drawings on this map of this dimension, as the map shows them. */
	private List<Drawing> drawings() {
		DrawingStore store = DrawingStore.get();
		if (store == null || this.minecraft == null || this.minecraft.level == null) {
			return List.of();
		}
		return store.in(this.minecraft.level.dimension().identifier().toString());
	}

	private void save() {
		double[] box = this.box();
		MapSession session = MapSession.get();
		TileStore store = session == null ? null : session.map().savedMap();
		if (box == null || store == null || this.levels(box).isEmpty()) {
			return;
		}
		ImageExport.Area area = ImageExport.Area.ofUnits(this.lod(box), box[0], box[1], box[2], box[3]);
		if (MapExport.start(store, area, session.map().roofless(), this.withDrawings ? this.drawings() : List.of())) {
			this.onClose();
		}
	}

	// Mouse events came as separate numbers before 1.21.9.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return super.mouseClicked(event, doubleClick) || this.pressed(event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.released(event.button());
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.button(), dx, dy) || super.mouseDragged(event, dx, dy);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return super.mouseClicked(x, y, button) || this.pressed(x, y, button);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		this.released(button);
		return super.mouseReleased(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
	}
	*///?}

	/** A press no widget took: a tab, or on the map a hold of the box or the map. */
	private boolean pressed(double x, double y, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int tab = this.tabAt(x, y);
		if (tab >= 0) {
			this.showWhole(tab == 1);
			this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
			return true;
		}
		if (x < SIDEBAR || y >= this.previewHeight()) {
			return false;
		}
		this.pressButton = button;
		this.grip = this.whole ? PAN : this.gripAt(x, y);
		return true;
	}

	/** What a press at a point takes hold of: edges and corners, the inside, or else the map. */
	private int gripAt(double x, double y) {
		double x0 = this.screenX(this.u0);
		double x1 = this.screenX(this.u1);
		double y0 = this.screenY(this.v0);
		double y1 = this.screenY(this.v1);
		boolean inX = x > x0 - GRAB && x < x1 + GRAB;
		boolean inY = y > y0 - GRAB && y < y1 + GRAB;
		int grip = 0;
		if (inY && Math.abs(x - x0) <= GRAB) {
			grip |= LEFT;
		} else if (inY && Math.abs(x - x1) <= GRAB) {
			grip |= RIGHT;
		}
		if (inX && Math.abs(y - y0) <= GRAB) {
			grip |= TOP;
		} else if (inX && Math.abs(y - y1) <= GRAB) {
			grip |= BOTTOM;
		}
		if (grip != 0) {
			return grip;
		}
		return x > x0 && x < x1 && y > y0 && y < y1 ? MOVE : PAN;
	}

	private void released(int button) {
		if (button == this.pressButton) {
			this.pressButton = -1;
			this.grip = 0;
		}
	}

	private boolean dragged(int button, double dx, double dy) {
		if (button != this.pressButton || this.grip == 0) {
			return false;
		}
		double du = dx / this.zoom;
		double dv = dy / this.zoom;
		if (this.grip == PAN) {
			this.centerU -= du;
			this.centerV -= dv;
		} else if (this.grip == MOVE) {
			this.u0 += du;
			this.u1 += du;
			this.v0 += dv;
			this.v1 += dv;
		} else {
			if ((this.grip & LEFT) != 0) {
				this.u0 = Math.min(this.u0 + du, this.u1 - MIN_BOX);
			}
			if ((this.grip & RIGHT) != 0) {
				this.u1 = Math.max(this.u1 + du, this.u0 + MIN_BOX);
			}
			if ((this.grip & TOP) != 0) {
				this.v0 = Math.min(this.v0 + dv, this.v1 - MIN_BOX);
			}
			if ((this.grip & BOTTOM) != 0) {
				this.v1 = Math.max(this.v1 + dv, this.v0 + MIN_BOX);
			}
		}
		return true;
	}

	// The mouse wheel had only the one axis on 1.20.1.
	@Override
	//? if >=1.21 {
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
	//?} else {
	/*public boolean mouseScrolled(double x, double y, double scrollY) {
	*///?}
		if (x < SIDEBAR || y >= this.previewHeight()) {
			return false;
		}
		double before = this.zoom;
		double after = Math.max(0.04, Math.min(16, before * Math.pow(1.25, scrollY)));
		// Keep the map point under the cursor where it is.
		double px = x - SIDEBAR - this.previewWidth() / 2.0;
		double py = y - this.previewHeight() / 2.0;
		double mapU = this.centerU + px / before;
		double mapV = this.centerV + py / before;
		this.centerU = mapU - px / after;
		this.centerV = mapV - py / after;
		this.zoom = after;
		return true;
	}

	private static String number(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	/** "about 6 MB", or kilobytes under a megabyte. */
	private static Component size(long bytes) {
		if (bytes < 1_000_000) {
			return Component.translatable("diorama.export.size_kb", number(Math.max(1, Math.round(bytes / 1000.0))));
		}
		return Component.translatable("diorama.export.size_mb", number(Math.round(bytes / 1_000_000.0)));
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void removed() {
		this.viewport.close();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** For tests: the image Save image would write now, or null while there's none to write. */
	public ImageExport.@Nullable Area plannedArea() {
		double[] box = this.box();
		if (box == null || this.levels(box).isEmpty()) {
			return null;
		}
		return ImageExport.Area.ofUnits(this.lod(box), box[0], box[1], box[2], box[3]);
	}

	/** For tests: the box on screen in GUI pixels, {x0, y0, x1, y1}, or null while there's none. */
	public double @Nullable [] boxOnScreen() {
		double[] box = this.box();
		return box == null ? null : new double[] {this.screenX(box[0]), this.screenY(box[1]), this.screenX(box[2]), this.screenY(box[3])};
	}

	/** For tests: the middle of a tab in GUI pixels, 0 Area, 1 Whole map. */
	public int[] tabCenter(int tab) {
		int x = 6 + (tab == 1 ? this.tabWidth(0) : 0);
		return new int[] {x + this.tabWidth(tab) / 2, 14};
	}

	/** For tests: a detail button, 0 (4 px per block) to 3, and Save image. */
	public Button detailButton(int lod) {
		return this.detail[lod];
	}

	public @Nullable Button saveButton() {
		return this.save;
	}
}
