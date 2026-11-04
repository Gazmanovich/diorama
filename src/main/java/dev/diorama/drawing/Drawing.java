package dev.diorama.drawing;

import java.util.UUID;

/**
 * Something the player drew on the map: a stroke (from the pen or the line tool), an area, or a
 * note.
 *
 * <p>Points are in map space, the map picture's own coordinates in blocks ({@code u} right, {@code v}
 * down the picture; see {@link dev.diorama.Projection}), not world positions, on the map as it
 * faced when drawn. The map's camera has no perspective, so a drawing kept there stays exactly where
 * it was drawn over the ground, at every zoom and on the minimap, and shows in every view of its
 * dimension, caves included, as drawings do on flat maps. On a map turned since, {@link #shown} moves
 * it to the same place on the ground, taking the ground at {@link #TURN_Y}, as a point on a tilted
 * picture doesn't say its height.
 */
public final class Drawing {
	public enum Kind {
		/** A line through its points. */
		STROKE,
		/** A closed shape through its points, filled see-through. */
		AREA,
		/** {@link #text} at its one point. */
		NOTE
	}

	public UUID id = UUID.randomUUID();
	public Kind kind = Kind.STROKE;
	public String dimension = "";
	/** ARGB, opaque. */
	public int color;
	/** u0, v0, u1, v1, ...; one point for a note. */
	public double[] points = new double[0];
	/** Which way the map faced when this was drawn; see {@link dev.diorama.Facing}. */
	public int turns;
	private static final int TURN_Y = 64;
	/** {@link #points} on the map as it faces now, and which way that was. */
	private transient double @org.jspecify.annotations.Nullable [] shown;
	private transient int shownTurns = -1;
	/** A note's text; empty for the others. */
	public String text = "";
	/** The dimension's map it was drawn on ({@link dev.diorama.Maps}); null for the main map. */
	public @org.jspecify.annotations.Nullable String map;

	public Drawing() {
	}

	/** {@link #points} on the map as it faces now. */
	public double[] shown() {
		dev.diorama.Facing now = dev.diorama.Projection.facing();
		if (now.turns == this.turns) {
			return this.points;
		}
		if (this.shown == null || this.shownTurns != now.turns) {
			dev.diorama.Facing drawn = dev.diorama.Facing.values()[Math.floorMod(this.turns, 4)];
			double[] out = new double[this.points.length];
			for (int i = 0; i + 1 < out.length; i += 2) {
				double u = this.points[i];
				double z = dev.diorama.Projection.zAt(this.points[i + 1], TURN_Y);
				double x = drawn.worldX(u, z);
				double worldZ = drawn.worldZ(u, z);
				out[i] = dev.diorama.Projection.u(x, worldZ);
				out[i + 1] = dev.diorama.Projection.v(x, TURN_Y, worldZ);
			}
			this.shown = out;
			this.shownTurns = now.turns;
		}
		return this.shown;
	}

	/** A copy fixed on the map as it faces now, for work that goes on after the map could turn. */
	public Drawing fixed() {
		Drawing copy = new Drawing(this.kind, this.dimension, this.color, this.shown().clone(), this.text);
		copy.turns = dev.diorama.Projection.facing().turns;
		return copy;
	}

	public Drawing(Kind kind, String dimension, int color, double[] points, String text) {
		this.kind = kind;
		this.dimension = dimension;
		this.color = color;
		this.points = points;
		this.turns = dev.diorama.Projection.facing().turns;
		this.text = text;
	}

	public int pointCount() {
		return this.points.length / 2;
	}

	/** Whether it has what its kind needs: two points for a stroke, three for an area, text for a note. */
	public boolean complete() {
		return switch (this.kind) {
			case STROKE -> this.pointCount() >= 2;
			case AREA -> this.pointCount() >= 3;
			case NOTE -> this.pointCount() == 1 && !this.text.isBlank();
		};
	}
}
