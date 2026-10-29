package dev.diorama.drawing;

import java.util.UUID;

/**
 * Something the player drew on the map: a stroke (from the pen or the line tool), an area, or a
 * note.
 *
 * <p>Points are in map space, the map picture's own coordinates in blocks ({@code u} east, {@code v}
 * down the picture; see {@link dev.diorama.Projection}), not world positions. The map's camera
 * never changes angle and has no perspective, so a drawing kept there stays exactly where it was
 * drawn over the ground, at every zoom and on the minimap, and shows in every view of its
 * dimension, caves included, as drawings do on flat maps.
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
	/** A note's text; empty for the others. */
	public String text = "";
	/** The dimension's map it was drawn on ({@link dev.diorama.Maps}); null for the main map. */
	public @org.jspecify.annotations.Nullable String map;

	public Drawing() {
	}

	public Drawing(Kind kind, String dimension, int color, double[] points, String text) {
		this.kind = kind;
		this.dimension = dimension;
		this.color = color;
		this.points = points;
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
