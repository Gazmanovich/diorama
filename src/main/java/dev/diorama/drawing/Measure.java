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

	private double @Nullable [] from;
	private double @Nullable [] to;
	private @Nullable CompletableFuture<@Nullable BlockPos> fromPick;
	private @Nullable CompletableFuture<@Nullable BlockPos> toPick;
	private boolean dragging;

	void start(double[] mapPoint, CompletableFuture<@Nullable BlockPos> pick) {
		this.from = mapPoint;
		this.to = mapPoint;
		this.fromPick = pick;
		this.toPick = pick;
		this.dragging = true;
	}

	void move(double[] mapPoint, CompletableFuture<@Nullable BlockPos> pick) {
		this.to = mapPoint;
		this.toPick = pick;
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
		this.fromPick = null;
		this.toPick = null;
		this.dragging = false;
	}

	private static @Nullable BlockPos answer(@Nullable CompletableFuture<@Nullable BlockPos> pick) {
		return pick == null || !pick.isDone() || pick.isCompletedExceptionally() ? null : pick.join();
	}

	/** Blocks across the ground between the ends, or -1 while either end isn't known. */
	int blocks() {
		BlockPos a = answer(this.fromPick);
		BlockPos b = answer(this.toPick);
		return a == null || b == null ? -1 : (int) Math.round(Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()));
	}

	/** "143 blocks · 120 east, 78 north · 12 up". */
	Component reading() {
		BlockPos a = answer(this.fromPick);
		BlockPos b = answer(this.toPick);
		if (a == null || b == null) {
			return Component.translatable("diorama.draw.measure.unknown");
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
		// Clear of the map screen's top bar.
		y = Math.max(view.y() + 18, y);
		graphics.fill(x - 2, y - 2, x + width + 1, y + 9, 0xC0000000);
		graphics.text(font, text, x, y, 0xFFFFFFFF, true);
	}
}
