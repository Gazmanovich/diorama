package dev.diorama.drawing;

import dev.diorama.render.MapViewport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The measuring tool: a drag from one place to another reads how far apart they are, in blocks
 * across the ground, and which way, with the climb. Both ends are the blocks the map shows there,
 * found the way the right-click menu finds them. Nothing is saved; the reading stays until the
 * next drag, another tool, or Esc.
 */
public final class Measure {
	/** The block a screen point shows; null in the future's answer where the map shows none. */
	public interface Picker {
		CompletableFuture<@Nullable BlockPos> at(double screenX, double screenY);
	}

	/**
	 * One end's block. Over saved map tiles the answer comes a moment later, off the render thread:
	 * meanwhile the end keeps its last answer, so a drag reads on rather than flickering to unknown.
	 */
	private static final class End {
		private @Nullable CompletableFuture<@Nullable BlockPos> pick;
		/** The latest answer: a block, or null with {@link #answered} for ground the map hasn't seen. */
		private @Nullable BlockPos block;
		private boolean answered;

		void ask(CompletableFuture<@Nullable BlockPos> pick) {
			this.pick = pick;
			this.refresh();
		}

		void refresh() {
			CompletableFuture<@Nullable BlockPos> pending = this.pick;
			if (pending == null || !pending.isDone()) {
				return;
			}
			this.block = pending.isCompletedExceptionally() || pending.isCancelled() ? null : pending.join();
			this.answered = true;
			this.pick = null;
		}
	}

	private double @Nullable [] from;
	private double @Nullable [] to;
	private End fromEnd = new End();
	private End toEnd = new End();
	private boolean dragging;

	void start(double[] mapPoint, CompletableFuture<@Nullable BlockPos> pick) {
		this.from = mapPoint;
		this.to = mapPoint;
		this.fromEnd = new End();
		this.toEnd = new End();
		this.fromEnd.ask(pick);
		this.toEnd.ask(pick);
		this.dragging = true;
	}

	void move(double[] mapPoint, CompletableFuture<@Nullable BlockPos> pick) {
		this.to = mapPoint;
		this.toEnd.ask(pick);
	}

	void end() {
		this.dragging = false;
	}

	boolean dragging() {
		return this.dragging;
	}

	boolean shown() {
		return this.from != null;
	}

	void clear() {
		this.from = null;
		this.to = null;
		this.fromEnd = new End();
		this.toEnd = new End();
		this.dragging = false;
	}

	/** Blocks across the ground between the ends, or -1 while either end isn't known. */
	int blocks() {
		this.fromEnd.refresh();
		this.toEnd.refresh();
		BlockPos a = this.fromEnd.block;
		BlockPos b = this.toEnd.block;
		return a == null || b == null ? -1 : (int) Math.round(Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()));
	}

	/** "143 blocks · 120 east, 78 north · 12 up". */
	Component reading() {
		this.fromEnd.refresh();
		this.toEnd.refresh();
		BlockPos a = this.fromEnd.block;
		BlockPos b = this.toEnd.block;
		if (a == null || b == null) {
			// Still looking, or looked and found nothing.
			boolean looking = a == null && !this.fromEnd.answered || b == null && !this.toEnd.answered;
			return Component.translatable(looking ? "diorama.draw.measure.looking" : "diorama.draw.measure.unknown");
		}
		int dx = b.getX() - a.getX();
		int dy = b.getY() - a.getY();
		int dz = b.getZ() - a.getZ();
		List<Component> ways = new ArrayList<>();
		if (dx != 0) {
			ways.add(Component.translatable(dx > 0 ? "diorama.draw.measure.east" : "diorama.draw.measure.west", Math.abs(dx)));
		}
		if (dz != 0) {
			ways.add(Component.translatable(dz > 0 ? "diorama.draw.measure.south" : "diorama.draw.measure.north", Math.abs(dz)));
		}
		Component out = Component.translatable("diorama.draw.measure.blocks", this.blocks());
		if (!ways.isEmpty()) {
			Component joined = ways.get(0);
			for (int i = 1; i < ways.size(); i++) {
				joined = joined.copy().append(", ").append(ways.get(i));
			}
			out = out.copy().append(" · ").append(joined);
		}
		if (dy != 0) {
			out = out.copy().append(" · ").append(Component.translatable(dy > 0 ? "diorama.draw.measure.up" : "diorama.draw.measure.down", Math.abs(dy)));
		}
		return out;
	}

	/** The line between the ends, as a drawing the overlay can show, or null. */
	@Nullable Drawing line() {
		if (this.from == null || this.to == null) {
			return null;
		}
		return new Drawing(Drawing.Kind.STROKE, "", 0xFFFFFFFF, new double[] {this.from[0], this.from[1], this.to[0], this.to[1]}, "");
	}

	/** The reading on a dark plate just above the far end, kept on screen. */
	void drawReading(GuiGraphicsExtractor graphics, Font font, MapViewport.View view) {
		if (this.to == null) {
			return;
		}
		String text = this.reading().getString();
		int width = font.width(text);
		int x = Math.round(view.screenX(this.to[0])) - width / 2;
		int y = Math.round(view.screenY(this.to[1])) - 16;
		x = Math.max(view.x() + 2, Math.min(view.x() + view.width() - width - 2, x));
		// Clear of the map screen's top and bottom bars.
		y = Math.max(view.y() + 18, Math.min(view.y() + view.height() - 26, y));
		graphics.fill(x - 2, y - 2, x + width + 1, y + 9, 0xC0000000);
		graphics.text(font, text, x, y, 0xFFFFFFFF, true);
	}
}
