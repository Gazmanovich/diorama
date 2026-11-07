package dev.diorama.drawing;

import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.render.MapViewport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Shift with the line and area tools: the next point goes due north, south, east or west of the
 * last, or diagonal, on the ground, as on a top-down map (#285).
 *
 * <p>The map is tilted, so a place higher up shows higher on the picture, by 0.64 of a block for
 * every block of height. Kept level on the picture instead, a line from low ground to a hilltop
 * would end 0.84 of a block south for every block it climbs. So both ends are taken where the map
 * shows ground: the last point's from the block under it, the next one's by finding how high the
 * ground is at the place Shift keeps it to. Everything here is in turned space ({@link Facing}),
 * where the map's u is x; turning keeps north, south, east and west among themselves. Where the
 * map shows no ground under the last point, Shift keeps the line level on the picture instead.
 */
public final class GroundSnap {
	/** How far a guess at the ground's height goes from the first, each way. */
	private static final int REACH = 512;
	/** Guesses at most, for one place: halving {@link #REACH} twice over takes 10. */
	private static final int MAX_GUESSES = 16;
	/**
	 * How long a click waits, all told, for the answers from saved tiles; each usually takes a few
	 * milliseconds. Past it, the point takes the height guessed so far.
	 */
	private static final long CLICK_WAIT_NS = TimeUnit.MILLISECONDS.toNanos(200);

	/** The block the map shows at a map point, in the world; null in the answer where it shows none. */
	public interface Picker {
		CompletableFuture<@Nullable BlockPos> at(double u, double v);
	}

	private final Picker picker;
	/** The view's layer the answers below are for: the surface, or a cave slice. */
	private int layer = Integer.MIN_VALUE;
	/** The ground under the last point clicked; null before the first. */
	private @Nullable Ground anchor;
	/** The ground about the cursor: how high it is there. */
	private @Nullable Ground under;
	/** How high the ground is where the point Shift keeps to is. */
	private @Nullable Landing landing;

	GroundSnap(Picker picker) {
		this.picker = picker;
	}

	/** A point was clicked without Shift: the next one keeps to the ground under it. */
	void clicked(MapViewport.View view, double[] point) {
		this.forgetOtherLayers(view);
		this.anchor = this.ask(point[0], point[1]);
	}

	/** Answers from the surface say nothing about a cave slice, or the other way round. */
	private void forgetOtherLayers(MapViewport.View view) {
		if (view.layer() != this.layer) {
			this.layer = view.layer();
			this.anchor = null;
			this.under = null;
			this.landing = null;
		}
	}

	/**
	 * The point Shift keeps the cursor's to, from {@code last}, in map space; null where the map
	 * shows no ground under {@code last}, or hasn't said yet. With {@code click} it waits a little
	 * for the map's answers, and the point becomes the one the next keeps to. Where the map shows
	 * no ground at the place it keeps to (hidden behind a hill, or never mapped), the point is
	 * there at the height about the cursor.
	 */
	double @Nullable [] snap(MapViewport.View view, double[] last, double[] cursor, boolean click) {
		this.forgetOtherLayers(view);
		long deadline = click ? System.nanoTime() + CLICK_WAIT_NS : 0;
		Ground anchor = this.anchor;
		if (anchor == null || anchor.u != last[0] || anchor.v != last[1] || click && anchor.answered() && anchor.block() == null) {
			// A point from before a resize built this anew, or from another layer: ask again. So does
			// a click where the map showed nothing under the last point: it may have loaded since.
			anchor = this.anchor = this.ask(last[0], last[1]);
		}
		anchor.await(deadline);
		double[] from = anchor.point();
		if (from == null) {
			return null;
		}
		if (this.under == null || this.under.answered() && (this.under.u != cursor[0] || this.under.v != cursor[1]) || click) {
			Ground fresh = this.ask(cursor[0], cursor[1]);
			// Until it answers, the last answer still says about how high the ground is here.
			if (this.under != null) {
				BlockPos before = this.under.block();
				fresh.previous = before != null ? before.getY() : this.under.previous;
			}
			this.under = fresh;
		}
		this.under.await(deadline);
		BlockPos about = this.under.block();
		int cursorY = about != null ? about.getY() : this.under.previous != Integer.MIN_VALUE ? this.under.previous : (int) from[1];
		double cursorZ = Projection.zAt(cursor[1], cursorY);

		// Due north, south, east or west, or diagonal: the cursor's place brought onto that line.
		double dx = cursor[0] - from[0];
		double dz = cursorZ - from[2];
		double angle = Math.round(Math.atan2(dz, dx) / (Math.PI / 4)) * (Math.PI / 4);
		double ax = Math.abs(Math.cos(angle)) < 1e-9 ? 0 : Math.signum(Math.cos(angle));
		double az = Math.abs(Math.sin(angle)) < 1e-9 ? 0 : Math.signum(Math.sin(angle));
		double along = (dx * ax + dz * az) / (ax * ax + az * az);
		double x = from[0] + ax * along;
		double z = from[2] + az * along;

		int blockX = (int) Math.floor(x);
		int blockZ = (int) Math.floor(z);
		Landing landing = this.landing;
		if (landing == null || landing.blockX != blockX || landing.blockZ != blockZ) {
			// The ground beside the last place is about as high, and the cursor's place is near.
			int guess = landing != null && landing.found ? landing.guess : cursorY;
			landing = this.landing = new Landing(blockX, blockZ, guess);
		}
		landing.advance(x, z, deadline);
		double[] point = {x, Projection.v(landing.guess, z)};
		if (click) {
			this.anchor = landing.found
				? new Ground(point[0], point[1], CompletableFuture.completedFuture(null), new BlockPos(blockX, landing.guess, blockZ))
				: this.ask(point[0], point[1]);
			this.landing = null;
		}
		return point;
	}

	private Ground ask(double u, double v) {
		return new Ground(u, v, this.picker.at(u, v), null);
	}

	/**
	 * The ground the map shows under a map point, in turned space, as the picker answers: at once
	 * over the map's chunks, a moment later over saved tiles.
	 */
	private static final class Ground {
		final double u;
		final double v;
		private final CompletableFuture<@Nullable BlockPos> pick;
		/** The block, in turned space, once answered; null for ground the map doesn't show. */
		private @Nullable BlockPos block;
		private boolean answered;
		/** The height an earlier answer about here gave, or {@link Integer#MIN_VALUE}. */
		int previous = Integer.MIN_VALUE;

		Ground(double u, double v, CompletableFuture<@Nullable BlockPos> pick, @Nullable BlockPos known) {
			this.u = u;
			this.v = v;
			this.pick = pick;
			if (known != null) {
				this.block = known;
				this.answered = true;
			}
		}

		boolean answered() {
			if (!this.answered && this.pick.isDone()) {
				BlockPos world = this.pick.isCompletedExceptionally() || this.pick.isCancelled() ? null : this.pick.join();
				if (world != null) {
					Facing facing = Projection.facing();
					this.block = new BlockPos(facing.blockX(world.getX(), world.getZ()), world.getY(), facing.blockZ(world.getX(), world.getZ()));
				}
				this.answered = true;
			}
			return this.answered;
		}

		/** The block where the map shows ground here, in turned space; null until answered, or where there's none. */
		@Nullable BlockPos block() {
			return this.answered() ? this.block : null;
		}

		/**
		 * The point on that ground: x, height, z, in turned space. The ray through the map point
		 * meets the block's top there, or its side, at the block's south edge.
		 */
		double @Nullable [] point() {
			BlockPos block = this.block();
			if (block == null) {
				return null;
			}
			double z = Math.max(block.getZ(), Math.min(block.getZ() + 0.999, Projection.zAt(this.v, block.getY())));
			return new double[] {this.u, block.getY(), z};
		}

		/** Waits for the answer until {@code deadline} ({@link System#nanoTime}); 0 doesn't wait. */
		void await(long deadline) {
			long left = deadline - System.nanoTime();
			if (this.answered || deadline == 0 || left <= 0) {
				return;
			}
			try {
				this.pick.get(left, TimeUnit.NANOSECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException | TimeoutException e) {
				// No answer: what the map knows so far stands.
			}
		}
	}

	/**
	 * How high the ground the map shows at one block is: what the map shows where the block would
	 * be at a guessed height says which way to guess again. Found north of the block, the ray went
	 * past it above the ground, so the ground is lower; found south of it, something higher stood
	 * in front. Found in the block itself but at another height, the ray met its south wall or its
	 * top further along: the top is only where a guess and the answer agree. The height of what it
	 * found is the next guess, which on gentle ground is right at once; else the guesses halve the
	 * gap between the last too high and too low.
	 */
	private final class Landing {
		final int blockX;
		final int blockZ;
		/** A standing height: the top of the ground block. */
		int guess;
		private int low;
		private int high;
		private int guesses;
		/** The highest the map has shown this block at, for when the guesses run out; or MIN_VALUE. */
		private int inBlock = Integer.MIN_VALUE;
		boolean found;
		private boolean done;
		private @Nullable Ground asked;

		Landing(int blockX, int blockZ, int guess) {
			this.blockX = blockX;
			this.blockZ = blockZ;
			this.guess = guess;
			this.low = guess - REACH;
			this.high = guess + REACH;
		}

		/**
		 * Takes in what the map has answered, and asks again, at the place's point {@code (x, z)};
		 * with a {@code deadline}, waiting for the answers until then.
		 */
		void advance(double x, double z, long deadline) {
			while (!this.done) {
				if (this.asked == null) {
					this.asked = GroundSnap.this.ask(x, Projection.v(this.guess, z));
				}
				this.asked.await(deadline);
				if (!this.asked.answered()) {
					return;
				}
				BlockPos seen = this.asked.block();
				this.asked = null;
				if (seen == null) {
					// Nothing shown there: the guess stands.
					this.done = true;
				} else if (seen.getZ() == this.blockZ && seen.getY() == this.guess) {
					this.found = true;
					this.done = true;
				} else {
					if (seen.getZ() == this.blockZ) {
						// The block, but up its wall or further along its top: what's highest so far.
						this.inBlock = Math.max(this.inBlock, seen.getY());
					}
					if (seen.getZ() < this.blockZ || seen.getZ() == this.blockZ && seen.getY() < this.guess) {
						this.high = this.guess;
					} else {
						this.low = this.guess;
					}
					int next = seen.getY() > this.low && seen.getY() < this.high ? seen.getY() : Math.floorDiv(this.low + this.high, 2);
					if (next <= this.low || next >= this.high || ++this.guesses >= MAX_GUESSES) {
						this.done = true;
						if (this.inBlock != Integer.MIN_VALUE) {
							this.guess = this.inBlock;
							this.found = true;
						}
					} else {
						this.guess = next;
					}
				}
			}
		}
	}
}
