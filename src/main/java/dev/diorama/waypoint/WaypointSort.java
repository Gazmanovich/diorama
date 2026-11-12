package dev.diorama.waypoint;

import java.util.Comparator;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * The orders the waypoint list can sort a folder in, as Xaero's Minimap's Sort By offers them,
 * each one reversible. Hidden waypoints go after the others either way, as Xaero's puts its
 * disabled ones, and waypoints that tie are in name order. Saved for each world with the folders.
 */
public enum WaypointSort {
	/**
	 * Nearest first. Waypoints in another dimension than the player's go after them, by name,
	 * unless that dimension is shown here too: they have a spot here to be near or far.
	 */
	DISTANCE("distance"),
	NAME("name"),
	/** By the letters on the pin; pins showing an icon go after the lettered ones, grouped by icon. */
	INITIALS("initials"),
	/** Round the colour wheel from red to pink, then the greys from white to black. */
	COLOUR("colour");

	/** Where the hue wheel is cut: just on the pink side of red, so reds lead and pinks end the colours. */
	private static final float HUE_START = 15 / 360.0F;

	private final String id;

	WaypointSort(String id) {
		this.id = id;
	}

	/** The order's name for the list's button. */
	public String key() {
		return "diorama.waypoints.sort." + this.id;
	}

	/** What the order puts first, reversed or not, for the button's tooltip. */
	public String directionKey(boolean reversed) {
		return this.key() + (reversed ? ".reversed" : ".usual");
	}

	public WaypointSort next() {
		return values()[(this.ordinal() + 1) % values().length];
	}

	/** The order saved under this name, or Distance for none or one this version doesn't know. */
	public static WaypointSort named(@org.jspecify.annotations.Nullable String name) {
		for (WaypointSort sort : values()) {
			if (sort.name().equals(name)) {
				return sort;
			}
		}
		return DISTANCE;
	}

	/** The order, with {@code distance} how far the player is from a waypoint, NaN for one with no spot in their dimension. */
	public Comparator<Waypoint> comparator(boolean reversed, ToDoubleFunction<Waypoint> distance) {
		Comparator<Waypoint> byName = Comparator.comparing(WaypointSort::nameKey);
		Comparator<Waypoint> order = switch (this) {
			case DISTANCE -> {
				Comparator<Waypoint> nearest = Comparator.comparingDouble(w -> {
					double d = distance.applyAsDouble(w);
					return Double.isNaN(d) ? 0 : d;
				});
				// Another dimension's waypoints have no distance to go by: they stay after this one's.
				yield Comparator.<Waypoint, Boolean>comparing(w -> Double.isNaN(distance.applyAsDouble(w))).thenComparing(reversed ? nearest.reversed() : nearest);
			}
			case NAME -> reversed ? byName.reversed() : byName;
			case INITIALS -> {
				Comparator<Waypoint> initials = Comparator.<Waypoint, Boolean>comparing(w -> !w.icon.isEmpty())
					.thenComparing(w -> w.icon.isEmpty() ? w.label().toLowerCase(Locale.ROOT) : w.icon);
				yield reversed ? initials.reversed() : initials;
			}
			case COLOUR -> {
				Comparator<Waypoint> colour = Comparator.comparingDouble(w -> colourKey(w.color));
				yield reversed ? colour.reversed() : colour;
			}
		};
		return Comparator.<Waypoint, Boolean>comparing(w -> !w.visible).thenComparing(order).thenComparing(byName);
	}

	private static String nameKey(Waypoint waypoint) {
		return waypoint.shownName().toLowerCase(Locale.ROOT);
	}

	/**
	 * A colour's place in the order: its hue from 0 to 1 for a colour, then 1 to 2 for a grey, white
	 * first. A colour this pale or dark reads as grey on the pin, whatever its hue.
	 */
	static double colourKey(int argb) {
		float[] hsv = WaypointColours.hsv(argb);
		if (hsv[1] < 0.15F || hsv[2] < 0.12F) {
			return 2 - hsv[2];
		}
		float hue = hsv[0] + HUE_START;
		return hue - Math.floor(hue);
	}
}
